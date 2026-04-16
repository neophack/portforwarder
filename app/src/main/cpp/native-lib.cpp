#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <future>
#include <chrono>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <netdb.h>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "PortForwarder"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper: send all bytes, handling partial writes and EAGAIN/EWOULDBLOCK
static ssize_t send_all(int sockfd, const char* buf, size_t len) {
    size_t total_sent = 0;
    while (total_sent < len) {
        ssize_t n = send(sockfd, buf + total_sent, len - total_sent, 0);
        if (n > 0) {
            total_sent += n;
        } else if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Wait for socket to become writable
                struct pollfd pfd;
                pfd.fd = sockfd;
                pfd.events = POLLOUT;
                int ret = poll(&pfd, 1, 5000); // 5 second timeout
                if (ret <= 0) {
                    LOGE("send_all: poll timeout or error");
                    return -1;
                }
                if (pfd.revents & (POLLERR | POLLHUP)) {
                    LOGE("send_all: socket error during poll");
                    return -1;
                }
                continue; // Retry send
            } else {
                LOGE("send_all: send error: %s", strerror(errno));
                return -1;
            }
        } else {
            // n == 0, shouldn't happen for stream sockets but handle it
            return -1;
        }
    }
    return (ssize_t)total_sent;
}

// Helper: resolve hostname or IP to a sockaddr_in using getaddrinfo
// Returns 0 on success, -1 on failure. Fills out_addr.
static int resolve_host(const std::string& host, int port, int socktype, struct sockaddr_in* out_addr) {
    struct addrinfo hints{};
    hints.ai_family = AF_INET;       // IPv4
    hints.ai_socktype = socktype;    // SOCK_STREAM or SOCK_DGRAM

    std::string port_str = std::to_string(port);
    struct addrinfo* result = nullptr;

    int ret = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
    if (ret != 0) {
        LOGE("resolve_host: getaddrinfo failed for %s:%d: %s", host.c_str(), port, gai_strerror(ret));
        return -1;
    }

    if (result == nullptr) {
        LOGE("resolve_host: no addresses found for %s:%d", host.c_str(), port);
        return -1;
    }

    // Use the first result
    memcpy(out_addr, result->ai_addr, sizeof(struct sockaddr_in));
    freeaddrinfo(result);
    return 0;
}

// Client thread entry: pairs a thread with a finished flag for cleanup
struct ClientThreadEntry {
    std::thread thread;
    std::shared_ptr<std::atomic<bool>> finished;

    ClientThreadEntry(std::thread t, std::shared_ptr<std::atomic<bool>> f)
        : thread(std::move(t)), finished(std::move(f)) {}
};

// 转发会话结构
struct ForwardSession {
    int id;
    int protocol; // 0: TCP, 1: UDP
    int listenPort;
    std::string targetHost;
    int targetPort;
    int listenSocket;
    std::atomic<bool> running;
    std::thread workerThread;
    std::vector<ClientThreadEntry> clientThreads;
    std::mutex clientThreadsMutex;

    ForwardSession() : running(false), listenSocket(-1) {}
};

// 全局会话管理
static std::unordered_map<int, std::unique_ptr<ForwardSession>> g_sessions;
static std::mutex g_sessionMutex;
static int g_nextSessionId = 1;

// TCP转发处理
void handleTcpConnection(int clientSocket, const std::string& targetHost, int targetPort) {
    int targetSocket = socket(AF_INET, SOCK_STREAM, 0);
    if (targetSocket < 0) {
        LOGE("Failed to create target socket");
        close(clientSocket);
        return;
    }

    struct sockaddr_in targetAddr;
    if (resolve_host(targetHost, targetPort, SOCK_STREAM, &targetAddr) < 0) {
        LOGE("Failed to resolve target host %s", targetHost.c_str());
        close(clientSocket);
        close(targetSocket);
        return;
    }

    if (connect(targetSocket, (struct sockaddr*)&targetAddr, sizeof(targetAddr)) < 0) {
        LOGE("Failed to connect to target %s:%d", targetHost.c_str(), targetPort);
        close(clientSocket);
        close(targetSocket);
        return;
    }

    // 设置非阻塞模式
    fcntl(clientSocket, F_SETFL, O_NONBLOCK);
    fcntl(targetSocket, F_SETFL, O_NONBLOCK);

    struct pollfd fds[2];
    fds[0].fd = clientSocket;
    fds[0].events = POLLIN;
    fds[1].fd = targetSocket;
    fds[1].events = POLLIN;

    char buffer[8192];
    bool running = true;

    while (running) {
        int ret = poll(fds, 2, 1000); // 1秒超时
        if (ret < 0) break;

        // 客户端到目标服务器
        if (fds[0].revents & POLLIN) {
            ssize_t bytes = recv(clientSocket, buffer, sizeof(buffer), 0);
            if (bytes <= 0) {
                running = false;
            } else {
                if (send_all(targetSocket, buffer, bytes) < 0) {
                    running = false;
                }
            }
        }

        // 目标服务器到客户端
        if (fds[1].revents & POLLIN) {
            ssize_t bytes = recv(targetSocket, buffer, sizeof(buffer), 0);
            if (bytes <= 0) {
                running = false;
            } else {
                if (send_all(clientSocket, buffer, bytes) < 0) {
                    running = false;
                }
            }
        }

        // 检查连接状态
        if ((fds[0].revents & (POLLHUP | POLLERR)) ||
            (fds[1].revents & (POLLHUP | POLLERR))) {
            running = false;
        }
    }

    close(clientSocket);
    close(targetSocket);
    LOGD("TCP connection closed");
}

// TCP转发工作线程
void tcpForwardWorker(ForwardSession* session) {
    LOGD("TCP forwarder started on port %d -> %s:%d",
         session->listenPort, session->targetHost.c_str(), session->targetPort);

    // 设置监听socket为非阻塞模式
    fcntl(session->listenSocket, F_SETFL, O_NONBLOCK);

    while (session->running) {
        struct sockaddr_in clientAddr;
        socklen_t clientLen = sizeof(clientAddr);

        int clientSocket = accept(session->listenSocket,
                                  (struct sockaddr*)&clientAddr, &clientLen);
        if (clientSocket < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // 没有新连接，休眠避免CPU占用过高
                usleep(100000); // 100ms
            } else if (session->running) {
                // 其他错误，但继续运行
                usleep(100000); // 100ms
            }
            continue;
        }

        LOGD("New TCP connection from %s:%d",
             inet_ntoa(clientAddr.sin_addr), ntohs(clientAddr.sin_port));

        // 在新线程中处理连接，存储到session中
        {
            std::lock_guard<std::mutex> lock(session->clientThreadsMutex);

            // Periodic cleanup: join and remove finished client threads
            for (auto it = session->clientThreads.begin(); it != session->clientThreads.end(); ) {
                if (it->finished->load()) {
                    if (it->thread.joinable()) {
                        it->thread.join();
                    }
                    it = session->clientThreads.erase(it);
                } else {
                    ++it;
                }
            }

            auto finished = std::make_shared<std::atomic<bool>>(false);
            std::thread t([clientSocket, session, finished]() {
                handleTcpConnection(clientSocket, session->targetHost, session->targetPort);
                finished->store(true);
            });
            session->clientThreads.emplace_back(std::move(t), finished);
        }
    }

    // Join all client handler threads before exiting the worker
    {
        std::lock_guard<std::mutex> lock(session->clientThreadsMutex);
        for (auto& entry : session->clientThreads) {
            if (entry.thread.joinable()) {
                entry.thread.join();
            }
        }
        session->clientThreads.clear();
    }

    LOGD("TCP forwarder stopped");
}

// UDP转发工作线程
void udpForwardWorker(ForwardSession* session) {
    LOGD("UDP forwarder started on port %d -> %s:%d",
         session->listenPort, session->targetHost.c_str(), session->targetPort);

    // 客户端到目标服务器socket的映射
    std::unordered_map<std::string, int> clientToTargetMap;
    std::mutex clientMapMutex;
    
    // 用于管理回包线程的容器
    std::vector<std::thread> replyThreads;
    std::mutex replyThreadsMutex;

    char buffer[65536];
    struct sockaddr_in clientAddr;
    socklen_t clientLen = sizeof(clientAddr);

    // 设置监听socket超时
    struct timeval timeout;
    timeout.tv_sec = 1;
    timeout.tv_usec = 0;
    setsockopt(session->listenSocket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    while (session->running) {
        // 从客户端接收数据
        ssize_t bytes = recvfrom(session->listenSocket, buffer, sizeof(buffer), 0,
                                 (struct sockaddr*)&clientAddr, &clientLen);
        if (bytes > 0) {
            // 生成客户端唯一标识
            std::string clientKey = std::string(inet_ntoa(clientAddr.sin_addr)) +
                                    ":" + std::to_string(ntohs(clientAddr.sin_port));

            int targetSocket = -1;
            bool isNewConnection = false;
            {
                std::lock_guard<std::mutex> lock(clientMapMutex);
                auto it = clientToTargetMap.find(clientKey);
                if (it != clientToTargetMap.end()) {
                    targetSocket = it->second;
                } else {
                    // 为新客户端创建目标socket
                    targetSocket = socket(AF_INET, SOCK_DGRAM, 0);
                    if (targetSocket >= 0) {
                        struct sockaddr_in targetAddr;
                        if (resolve_host(session->targetHost, session->targetPort, SOCK_DGRAM, &targetAddr) < 0) {
                            LOGE("Failed to resolve target host %s for UDP", session->targetHost.c_str());
                            close(targetSocket);
                            targetSocket = -1;
                        } else {
                            // 设置socket超时，避免阻塞
                            struct timeval recvTimeout;
                            recvTimeout.tv_sec = 1;
                            recvTimeout.tv_usec = 0;
                            setsockopt(targetSocket, SOL_SOCKET, SO_RCVTIMEO, &recvTimeout, sizeof(recvTimeout));

                            // 连接到目标服务器
                            if (connect(targetSocket, (struct sockaddr*)&targetAddr, sizeof(targetAddr)) == 0) {
                                clientToTargetMap[clientKey] = targetSocket;
                                isNewConnection = true;
                                LOGD("Created new UDP connection for client %s", clientKey.c_str());
                            } else {
                                close(targetSocket);
                                targetSocket = -1;
                            }
                        }
                    }
                }
            }

            // 转发数据到目标服务器
            if (targetSocket >= 0) {
                send(targetSocket, buffer, bytes, 0);
                
                // 只为新连接启动回包线程
                if (isNewConnection) {
                    std::lock_guard<std::mutex> threadLock(replyThreadsMutex);
                    replyThreads.emplace_back([=, &session, &clientMapMutex, &clientToTargetMap]() {
                        char recvBuffer[65536];
                        struct sockaddr_in localClientAddr = clientAddr;
                        
                        while (session->running) {
                            ssize_t recvBytes = recv(targetSocket, recvBuffer, sizeof(recvBuffer), 0);
                            if (recvBytes > 0) {
                                // 转发回客户端
                                sendto(session->listenSocket, recvBuffer, recvBytes, 0,
                                       (struct sockaddr*)&localClientAddr, sizeof(localClientAddr));
                            } else {
                                // 连接断开或出错，清理映射
                                std::lock_guard<std::mutex> mapLock(clientMapMutex);
                                for (auto it = clientToTargetMap.begin(); it != clientToTargetMap.end(); ++it) {
                                    if (it->second == targetSocket) {
                                        close(targetSocket);
                                        clientToTargetMap.erase(it);
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    });
                }
            }
        } else if (bytes < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            // 真正的错误，但继续运行
            if (session->running) {
                usleep(100000); // 100ms延迟避免CPU占用过高
            }
        }
    }

    // 清理所有目标socket
    {
        std::lock_guard<std::mutex> lock(clientMapMutex);
        for (const auto& pair : clientToTargetMap) {
            close(pair.second);
        }
        clientToTargetMap.clear();
    }
    
    // 等待所有回包线程结束
    {
        std::lock_guard<std::mutex> threadLock(replyThreadsMutex);
        for (auto& thread : replyThreads) {
            if (thread.joinable()) {
                thread.join();
            }
        }
        replyThreads.clear();
    }

    LOGD("UDP forwarder stopped");
}

extern "C" {

// 创建端口转发
JNIEXPORT jint JNICALL
Java_com_aucneon_portforwarder_PortForwarder_nativeCreateForward(
        JNIEnv *env, jobject thiz, jint protocol, jint listenPort,
        jstring targetHost, jint targetPort) {

    const char* host = env->GetStringUTFChars(targetHost, nullptr);

    auto session = std::make_unique<ForwardSession>();
    session->protocol = protocol;
    session->listenPort = listenPort;
    session->targetHost = std::string(host);
    session->targetPort = targetPort;
    session->running = false;

    // Validate that the target host can be resolved
    {
        struct sockaddr_in testAddr;
        int socktype = (protocol == 0) ? SOCK_STREAM : SOCK_DGRAM;
        if (resolve_host(session->targetHost, targetPort, socktype, &testAddr) < 0) {
            env->ReleaseStringUTFChars(targetHost, host);
            LOGE("Failed to resolve target host: %s", session->targetHost.c_str());
            return -4;
        }
    }

    // 创建监听socket
    int socketType = (protocol == 0) ? SOCK_STREAM : SOCK_DGRAM;
    session->listenSocket = socket(AF_INET, socketType, 0);
    if (session->listenSocket < 0) {
        env->ReleaseStringUTFChars(targetHost, host);
        LOGE("Failed to create listen socket");
        return -1;
    }

    // 设置socket选项
    int opt = 1;
    setsockopt(session->listenSocket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    // 绑定端口
    struct sockaddr_in listenAddr;
    listenAddr.sin_family = AF_INET;
    listenAddr.sin_addr.s_addr = INADDR_ANY;
    listenAddr.sin_port = htons(listenPort);

    if (bind(session->listenSocket, (struct sockaddr*)&listenAddr, sizeof(listenAddr)) < 0) {
        close(session->listenSocket);
        env->ReleaseStringUTFChars(targetHost, host);
        LOGE("Failed to bind to port %d", listenPort);
        return -2;
    }

    // TCP需要监听
    if (protocol == 0 && listen(session->listenSocket, 128) < 0) {
        close(session->listenSocket);
        env->ReleaseStringUTFChars(targetHost, host);
        LOGE("Failed to listen on port %d", listenPort);
        return -3;
    }

    session->running = true;

    // 启动工作线程
    if (protocol == 0) {
        session->workerThread = std::thread(tcpForwardWorker, session.get());
    } else {
        session->workerThread = std::thread(udpForwardWorker, session.get());
    }

    // 分配会话ID
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    int sessionId = g_nextSessionId++;
    session->id = sessionId;
    g_sessions[sessionId] = std::move(session);

    env->ReleaseStringUTFChars(targetHost, host);

    LOGD("Created %s forward %d: %d -> %s:%d",
         (protocol == 0) ? "TCP" : "UDP", sessionId, listenPort, host, targetPort);

    return sessionId;
}

// 停止端口转发
JNIEXPORT jboolean JNICALL
Java_com_aucneon_portforwarder_PortForwarder_nativeStopForward(
        JNIEnv *env, jobject thiz, jint sessionId) {

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    auto it = g_sessions.find(sessionId);
    if (it == g_sessions.end()) {
        return JNI_FALSE;
    }

    auto& session = it->second;
    
    // 停止运行标志
    session->running = false;

    // 关闭socket以中断阻塞操作
    if (session->listenSocket >= 0) {
        close(session->listenSocket);
        session->listenSocket = -1;
    }

    // 等待工作线程结束
    if (session->workerThread.joinable()) {
        session->workerThread.join();
    }

    LOGD("Stopped forward session %d", sessionId);
    g_sessions.erase(it);

    return JNI_TRUE;
}

// 获取转发状态
JNIEXPORT jboolean JNICALL
Java_com_aucneon_portforwarder_PortForwarder_isForwardRunning(
        JNIEnv *env, jobject thiz, jint sessionId) {

    std::lock_guard<std::mutex> lock(g_sessionMutex);
    auto it = g_sessions.find(sessionId);
    if (it == g_sessions.end()) {
        return JNI_FALSE;
    }

    return it->second->running ? JNI_TRUE : JNI_FALSE;
}

// 停止所有转发
JNIEXPORT void JNICALL
Java_com_aucneon_portforwarder_PortForwarder_nativeStopAllForwards(
        JNIEnv *env, jobject thiz) {

    std::lock_guard<std::mutex> lock(g_sessionMutex);

    for (auto& pair : g_sessions) {
        auto& session = pair.second;
        session->running = false;

        if (session->listenSocket >= 0) {
            close(session->listenSocket);
            session->listenSocket = -1;
        }

        if (session->workerThread.joinable()) {
            session->workerThread.join();
        }
    }

    LOGD("Stopped all forward sessions (%zu)", g_sessions.size());
    g_sessions.clear();
}

}
