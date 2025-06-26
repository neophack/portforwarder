package com.aucneon.portforwarder;

import android.util.Log;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Android JNI端口转发器
 * 支持TCP和UDP协议的端口转发
 */
public class PortForwarder {
    private static final String TAG = "PortForwarder";

    // 协议类型常量
    public static final int PROTOCOL_TCP = 0;
    public static final int PROTOCOL_UDP = 1;

    // 错误码
    public static final int ERROR_SOCKET_CREATE = -1;
    public static final int ERROR_BIND_FAILED = -2;
    public static final int ERROR_LISTEN_FAILED = -3;

    // 会话信息缓存
    private static final Map<Integer, ForwardInfo> sessionInfo = new ConcurrentHashMap<>();

    static {
        try {
            System.loadLibrary("portforwarder");
            Log.d(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    /**
     * 转发信息类
     */
    public static class ForwardInfo {
        public final int sessionId;
        public final int protocol;
        public final int listenPort;
        public final String targetHost;
        public final int targetPort;
        public final long createTime;

        public ForwardInfo(int sessionId, int protocol, int listenPort,
                           String targetHost, int targetPort) {
            this.sessionId = sessionId;
            this.protocol = protocol;
            this.listenPort = listenPort;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.createTime = System.currentTimeMillis();
        }

        public String getProtocolName() {
            return protocol == PROTOCOL_TCP ? "TCP" : "UDP";
        }

        @Override
        public String toString() {
            return String.format("%s Forward [%d]: %d -> %s:%d",
                    getProtocolName(), sessionId, listenPort, targetHost, targetPort);
        }
    }

    /**
     * 创建TCP端口转发
     * @param listenPort 监听端口
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     * @return 会话ID，失败返回负数错误码
     */
    public static int createTcpForward(int listenPort, String targetHost, int targetPort) {
        return createForward(PROTOCOL_TCP, listenPort, targetHost, targetPort);
    }

    /**
     * 创建UDP端口转发
     * @param listenPort 监听端口
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     * @return 会话ID，失败返回负数错误码
     */
    public static int createUdpForward(int listenPort, String targetHost, int targetPort) {
        return createForward(PROTOCOL_UDP, listenPort, targetHost, targetPort);
    }

    /**
     * 创建端口转发
     * @param protocol 协议类型 (PROTOCOL_TCP 或 PROTOCOL_UDP)
     * @param listenPort 监听端口
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     * @return 会话ID，失败返回负数错误码
     */
    public static int createForward(int protocol, int listenPort,
                                    String targetHost, int targetPort) {
        if (targetHost == null || targetHost.trim().isEmpty()) {
            Log.e(TAG, "Target host cannot be null or empty");
            return -1;
        }

        if (listenPort <= 0 || listenPort > 65535 || targetPort <= 0 || targetPort > 65535) {
            Log.e(TAG, "Invalid port number");
            return -1;
        }

        int sessionId = nativeCreateForward(protocol, listenPort, targetHost, targetPort);

        if (sessionId > 0) {
            ForwardInfo info = new ForwardInfo(sessionId, protocol, listenPort,
                    targetHost, targetPort);
            sessionInfo.put(sessionId, info);
            Log.i(TAG, "Created: " + info.toString());
        } else {
            String protocolName = (protocol == PROTOCOL_TCP) ? "TCP" : "UDP";
            String errorMsg = getErrorMessage(sessionId);
            Log.e(TAG, String.format("Failed to create %s forward %d -> %s:%d: %s",
                    protocolName, listenPort, targetHost, targetPort, errorMsg));
        }

        return sessionId;
    }

    /**
     * 停止端口转发
     * @param sessionId 会话ID
     * @return 是否成功停止
     */
    public static boolean stopForward(int sessionId) {
        ForwardInfo info = sessionInfo.remove(sessionId);
        boolean result = nativeStopForward(sessionId);

        if (result && info != null) {
            Log.i(TAG, "Stopped: " + info.toString());
        } else {
            Log.w(TAG, "Failed to stop forward session: " + sessionId);
        }

        return result;
    }

    /**
     * 检查转发是否正在运行
     * @param sessionId 会话ID
     * @return 是否正在运行
     */
    public static boolean isRunning(int sessionId) {
        return isForwardRunning(sessionId);
    }

    /**
     * 停止所有端口转发
     */
    public static void stopAllForwards() {
        int count = sessionInfo.size();
        sessionInfo.clear();
        nativeStopAllForwards();
        Log.i(TAG, "Stopped all forwards (" + count + " sessions)");
    }

    /**
     * 获取所有活动的转发会话信息
     * @return 转发信息映射
     */
    public static Map<Integer, ForwardInfo> getAllForwards() {
        return new ConcurrentHashMap<>(sessionInfo);
    }

    /**
     * 获取指定会话的转发信息
     * @param sessionId 会话ID
     * @return 转发信息，不存在返回null
     */
    public static ForwardInfo getForwardInfo(int sessionId) {
        return sessionInfo.get(sessionId);
    }

    /**
     * 获取活动转发会话数量
     * @return 会话数量
     */
    public static int getActiveSessionCount() {
        return sessionInfo.size();
    }

    /**
     * 检查端口是否可用
     * @param port 要检查的端口
     * @return 端口是否可用
     */
    public static boolean isPortAvailable(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }

        // 检查端口是否已被现有转发会话使用
        for (ForwardInfo info : sessionInfo.values()) {
            if (info.listenPort == port) {
                return false;
            }
        }

        // 检查系统端口是否可用
        try (java.net.ServerSocket serverSocket = new java.net.ServerSocket(port)) {
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Port " + port + " is not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * 批量创建多个端口转发
     * @param forwards 转发配置数组，每个元素包含 [protocol, listenPort, targetHost, targetPort]
     * @return 会话ID数组，失败的返回负数错误码
     */
    public static int[] createMultipleForwards(Object[][] forwards) {
        if (forwards == null || forwards.length == 0) {
            return new int[0];
        }

        int[] sessionIds = new int[forwards.length];
        
        for (int i = 0; i < forwards.length; i++) {
            Object[] config = forwards[i];
            if (config == null || config.length != 4) {
                sessionIds[i] = -1;
                continue;
            }

            try {
                int protocol = (Integer) config[0];
                int listenPort = (Integer) config[1];
                String targetHost = (String) config[2];
                int targetPort = (Integer) config[3];

                sessionIds[i] = createForward(protocol, listenPort, targetHost, targetPort);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create forward from config " + i, e);
                sessionIds[i] = -1;
            }
        }

        return sessionIds;
    }

    /**
     * 根据错误码获取错误信息
     * @param errorCode 错误码
     * @return 错误信息
     */
    private static String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case ERROR_SOCKET_CREATE:
                return "Failed to create socket";
            case ERROR_BIND_FAILED:
                return "Failed to bind port (port may be in use)";
            case ERROR_LISTEN_FAILED:
                return "Failed to listen on port";
            default:
                return "Unknown error (" + errorCode + ")";
        }
    }

    // Native方法声明
    private static native int nativeCreateForward(int protocol, int listenPort,
                                                    String targetHost, int targetPort);
    private static native boolean nativeStopForward(int sessionId);
    private static native boolean isForwardRunning(int sessionId);
    private static native void nativeStopAllForwards();
}