package com.aucneon.portforwarder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Protocol tester for verifying port forwarding rules.
 * Tests TCP connectivity and UDP reachability through the local forwarding port.
 */
public class ProtocolTester {
    private static final String TAG = "ProtocolTester";
    private static final int TCP_TIMEOUT_MS = 5000;
    private static final int UDP_TIMEOUT_MS = 3000;

    public interface TestCallback {
        void onTestResult(ForwardConfig config, TestResult result);
    }

    public static class TestResult {
        public final boolean success;
        public final long latencyMs;
        public final String protocol;
        public final String message;
        public final String detectedService;

        public TestResult(boolean success, long latencyMs, String protocol, String message, String detectedService) {
            this.success = success;
            this.latencyMs = latencyMs;
            this.protocol = protocol;
            this.message = message;
            this.detectedService = detectedService;
        }
    }

    /**
     * Test a single forwarding config asynchronously.
     * Tests by connecting to localhost:listenPort through the forward.
     */
    public static void testConfig(ForwardConfig config, TestCallback callback) {
        new Thread(() -> {
            TestResult result;
            if (config.protocol == PortForwarder.PROTOCOL_TCP) {
                result = testTcp(config);
            } else {
                result = testUdp(config);
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.onTestResult(config, result));
        }).start();
    }

    /**
     * Test TCP forwarding by attempting a socket connection to localhost:listenPort.
     */
    private static TestResult testTcp(ForwardConfig config) {
        long startTime = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", config.listenPort), TCP_TIMEOUT_MS);
            long latency = System.currentTimeMillis() - startTime;

            // Try to detect the service by reading a banner
            String detectedService = detectTcpService(socket, config.targetPort);

            socket.close();
            Log.i(TAG, "TCP test success for " + config.name + ": " + latency + "ms");
            return new TestResult(true, latency, "TCP",
                    "连接成功 (" + latency + "ms)", detectedService);
        } catch (IOException e) {
            long latency = System.currentTimeMillis() - startTime;
            Log.w(TAG, "TCP test failed for " + config.name + ": " + e.getMessage());
            return new TestResult(false, latency, "TCP",
                    "连接失败: " + e.getMessage(), null);
        }
    }

    /**
     * Test UDP forwarding by sending a probe packet.
     */
    private static TestResult testUdp(ForwardConfig config) {
        long startTime = System.currentTimeMillis();
        try {
            DatagramSocket socket = new DatagramSocket();
            socket.setSoTimeout(UDP_TIMEOUT_MS);

            // Send a small probe packet
            byte[] probeData = new byte[]{0x00};
            InetAddress address = InetAddress.getByName("127.0.0.1");
            DatagramPacket sendPacket = new DatagramPacket(probeData, probeData.length, address, config.listenPort);
            socket.send(sendPacket);

            // Try to receive a response
            byte[] buffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(receivePacket);
                long latency = System.currentTimeMillis() - startTime;
                socket.close();
                Log.i(TAG, "UDP test success for " + config.name + ": " + latency + "ms (got response)");
                return new TestResult(true, latency, "UDP",
                        "端口可达 (" + latency + "ms)", null);
            } catch (IOException e) {
                // Timeout is expected for UDP - the send succeeded which means the port is open
                long latency = System.currentTimeMillis() - startTime;
                socket.close();
                Log.i(TAG, "UDP test partial for " + config.name + ": send succeeded, no response");
                return new TestResult(true, latency, "UDP",
                        "数据已发送，无回复（正常）", null);
            }
        } catch (IOException e) {
            long latency = System.currentTimeMillis() - startTime;
            Log.w(TAG, "UDP test failed for " + config.name + ": " + e.getMessage());
            return new TestResult(false, latency, "UDP",
                    "发送失败: " + e.getMessage(), null);
        }
    }

    /**
     * Try to detect the service type based on the target port and banner.
     */
    private static String detectTcpService(Socket socket, int targetPort) {
        // Known port mappings
        switch (targetPort) {
            case 22: return "SSH";
            case 23: return "Telnet";
            case 80: case 8080: case 8081: case 8443: return "HTTP";
            case 443: return "HTTPS";
            case 21: return "FTP";
            case 25: return "SMTP";
            case 3306: return "MySQL";
            case 5432: return "PostgreSQL";
            case 6379: return "Redis";
            case 27017: return "MongoDB";
        }

        // Try reading a banner (non-blocking, short timeout)
        try {
            socket.setSoTimeout(1000);
            byte[] buffer = new byte[256];
            int bytesRead = socket.getInputStream().read(buffer);
            if (bytesRead > 0) {
                String banner = new String(buffer, 0, bytesRead).trim();
                if (banner.startsWith("SSH")) return "SSH";
                if (banner.startsWith("HTTP")) return "HTTP";
                if (banner.contains("FTP")) return "FTP";
                if (banner.contains("SMTP") || banner.startsWith("220")) return "SMTP";
                return "未知 (有响应)";
            }
        } catch (IOException e) {
            // No banner available, that's fine
        }

        return null;
    }

    /**
     * Check if a config's target port suggests an HTTP service.
     */
    public static boolean isHttpService(ForwardConfig config) {
        if (config.protocol != PortForwarder.PROTOCOL_TCP) return false;
        int port = config.targetPort;
        if (port == 80 || port == 443 || port == 8080 || port == 8081 || port == 8443 || port == 3000 || port == 5000) {
            return true;
        }
        String name = config.name != null ? config.name.toUpperCase() : "";
        return name.contains("HTTP") || name.contains("WEB") || name.contains("WEBUI");
    }

    /**
     * Check if a config's target port suggests a Telnet service.
     */
    public static boolean isTelnetService(ForwardConfig config) {
        if (config.protocol != PortForwarder.PROTOCOL_TCP) return false;
        int port = config.targetPort;
        if (port == 23 || port == 8023 || port == 2323) return true;
        String name = config.name != null ? config.name.toUpperCase() : "";
        return name.contains("TELNET");
    }

    /**
     * Check if a config's target port suggests an SSH service.
     */
    public static boolean isSshService(ForwardConfig config) {
        if (config.protocol != PortForwarder.PROTOCOL_TCP) return false;
        int port = config.targetPort;
        if (port == 22 || port == 2222 || port == 5022) return true;
        String name = config.name != null ? config.name.toUpperCase() : "";
        return name.contains("SSH");
    }
}
