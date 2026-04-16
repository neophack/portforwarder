package com.aucneon.portforwarder;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 局域网扫描器
 * 用于扫描局域网内的设备
 */
public class LanScanner {
    private static final String TAG = "LanScanner";
    private static final int PING_TIMEOUT_MS = 1000; // ping超时1秒
    private static final int SOCKET_TIMEOUT_MS = 800; // Socket连接超时800ms
    private static final int MAX_THREADS = 100;
    private static final int TOTAL_TIMEOUT_SECONDS = 90;

    private Context context;
    private ExecutorService executorService;
    private ExecutorService timeoutExecutor;
    private java.util.concurrent.Future<?> timeoutFuture;
    private ScanCallback callback;
    private volatile boolean isScanning = false;
    private volatile boolean forceStop = false;

    public interface ScanCallback {
        void onScanStarted();
        void onDeviceFound(LanDevice device);
        void onScanProgress(int current, int total);
        void onScanFinished(List<LanDevice> devices);
        void onScanError(String error);
    }

    public LanScanner(Context context) {
        this.context = context;
        this.executorService = Executors.newFixedThreadPool(MAX_THREADS);
        this.timeoutExecutor = Executors.newSingleThreadExecutor();
    }

    public void setScanCallback(ScanCallback callback) {
        this.callback = callback;
    }

    public void startScan() {
        if (isScanning) {
            Log.w(TAG, "Scan is already in progress");
            return;
        }

        isScanning = true;
        forceStop = false;

        if (executorService == null || executorService.isTerminated()) {
            executorService = Executors.newFixedThreadPool(MAX_THREADS);
        }

        if (callback != null) {
            callback.onScanStarted();
        }

        new Thread(() -> {
            try {
                scanNetworkReliable();
            } catch (Exception e) {
                Log.e(TAG, "Scan error", e);
                if (callback != null) {
                    callback.onScanError(context.getString(R.string.scan_failed, e.getMessage()));
                }
            } finally {
                isScanning = false;
                forceStop = false;
            }
        }).start();

        timeoutFuture = timeoutExecutor.submit(() -> {
            try {
                Thread.sleep(TOTAL_TIMEOUT_SECONDS * 1000);
                if (isScanning) {
                    Log.w(TAG, "Total scan timeout reached, forcing stop");
                    forceStop = true;
                    isScanning = false;

                    if (executorService != null) {
                        executorService.shutdownNow();
                    }

                    if (callback != null) {
                        callback.onScanError(context.getString(R.string.scan_timeout));
                    }
                }
            } catch (InterruptedException e) {
                // 正常中断
            }
        });
    }

    public void stopScan() {
        isScanning = false;
        forceStop = true;

        if (timeoutFuture != null) {
            timeoutFuture.cancel(true);
            timeoutFuture = null;
        }

        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }

    /**
     * 可靠扫描方法：先读ARP表快速发现已知设备，再扫描全子网
     */
    private void scanNetworkReliable() {
        List<String> subnets = getAllLocalSubnets();
        if (subnets.isEmpty()) {
            if (callback != null) {
                callback.onScanError(context.getString(R.string.scan_error_no_network));
            }
            return;
        }

        Log.d(TAG, "Starting scan of " + subnets.size() + " subnets: " + subnets);

        List<LanDevice> devices = Collections.synchronizedList(new ArrayList<>());
        Set<String> foundIps = Collections.synchronizedSet(new HashSet<>());

        // 阶段1：读取ARP表，快速发现已知设备
        List<LanDevice> arpDevices = readArpTable(subnets);
        for (LanDevice arpDevice : arpDevices) {
            if (forceStop) break;
            arpDevice.isReachable = true;
            enrichDeviceInfoSafe(arpDevice);
            arpDevice = markDeviceNetwork(arpDevice, subnets);
            devices.add(arpDevice);
            foundIps.add(arpDevice.ipAddress);
            if (callback != null && !forceStop) {
                callback.onDeviceFound(arpDevice);
            }
            Log.i(TAG, "ARP device: " + arpDevice.ipAddress + " MAC: " + arpDevice.macAddress);
        }

        // 阶段2：先用ping -b广播来填充ARP表（触发设备响应）
        triggerArpPopulation(subnets);

        // 阶段3：全子网扫描
        AtomicInteger completedCount = new AtomicInteger(0);
        List<String> allHosts = getAllScanHosts(subnets);
        int totalHosts = allHosts.size();

        CountDownLatch latch = new CountDownLatch(totalHosts);

        Log.i(TAG, "Scanning " + totalHosts + " IPs across " + subnets.size() + " subnets");

        for (String host : allHosts) {
            if (forceStop) {
                latch.countDown();
                continue;
            }

            executorService.submit(() -> {
                try {
                    if (forceStop) return;
                    if (foundIps.contains(host)) return; // 已从ARP表发现

                    LanDevice device = probeHost(host);

                    if (device != null && device.isReachable && foundIps.add(host)) {
                        enrichDeviceInfoSafe(device);
                        device = markDeviceNetwork(device, subnets);
                        devices.add(device);

                        if (callback != null && !forceStop) {
                            callback.onDeviceFound(device);
                        }

                        Log.i(TAG, "Found device: " + host + " (" + device.responseTime + "ms)");
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error scanning " + host + ": " + e.getMessage());
                } finally {
                    int completed = completedCount.incrementAndGet();
                    latch.countDown();

                    if (callback != null && !forceStop) {
                        callback.onScanProgress(completed, totalHosts);
                    }
                }
            });
        }

        try {
            boolean finished = latch.await(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                Log.w(TAG, "Not all scan tasks completed within timeout");
            }

            if (!forceStop && callback != null) {
                Log.i(TAG, "Scan completed. Found " + devices.size() + " devices");
                callback.onScanFinished(new ArrayList<>(devices));
            }

        } catch (InterruptedException e) {
            Log.i(TAG, "Scan was interrupted");
        }
    }

    /**
     * 读取ARP表 /proc/net/arp，快速获取已知的局域网设备
     */
    private List<LanDevice> readArpTable(List<String> subnets) {
        List<LanDevice> devices = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            reader.readLine(); // 跳过标题行
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String flags = parts[2];
                    String mac = parts[3];

                    // flags 0x0 表示无效条目，跳过；mac 00:00:00:00:00:00 也跳过
                    if ("0x0".equals(flags) || "00:00:00:00:00:00".equals(mac)) {
                        continue;
                    }

                    // 只保留目标子网内的设备
                    String subnet = getSubnetFromIp(ip);
                    if (subnet != null && subnets.contains(subnet)) {
                        LanDevice device = new LanDevice(ip);
                        device.macAddress = mac;
                        device.responseTime = 0;
                        devices.add(device);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.d(TAG, "Cannot read ARP table: " + e.getMessage());
        }
        return devices;
    }

    /**
     * 发送广播ping来触发ARP填充，让更多设备出现在ARP表中
     */
    private void triggerArpPopulation(List<String> subnets) {
        for (String subnet : subnets) {
            if (forceStop) break;
            try {
                // ping广播地址，触发ARP响应
                String broadcastAddr = subnet + ".255";
                Process process = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/ping", "-c", "1", "-W", "1", "-b", broadcastAddr});
                process.waitFor(2, TimeUnit.SECONDS);
                process.destroy();
            } catch (Exception e) {
                Log.d(TAG, "Broadcast ping failed for " + subnet + ": " + e.getMessage());
            }
        }
        // 等待ARP表更新
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    /**
     * 探测单个主机是否在线，使用多种方法：
     * 1. 系统ping命令（最可靠，不需要root）
     * 2. TCP端口探测（对禁ping设备有效）
     */
    private LanDevice probeHost(String host) {
        long startTime = System.currentTimeMillis();

        // 方法1：使用系统ping命令（比InetAddress.isReachable可靠得多）
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"/system/bin/ping", "-c", "1", "-W", "1", host});
            boolean finished = process.waitFor(PING_TIMEOUT_MS + 200, TimeUnit.MILLISECONDS);
            int exitCode = -1;
            if (finished) {
                exitCode = process.exitValue();
            }
            process.destroy();

            if (exitCode == 0) {
                long responseTime = System.currentTimeMillis() - startTime;
                LanDevice device = new LanDevice(host);
                device.isReachable = true;
                device.responseTime = responseTime;
                return device;
            }
        } catch (Exception e) {
            // 继续尝试其他方法
        }

        // 方法2：InetAddress.isReachable 作为备用
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isReachable(PING_TIMEOUT_MS)) {
                long responseTime = System.currentTimeMillis() - startTime;
                LanDevice device = new LanDevice(host);
                device.isReachable = true;
                device.responseTime = responseTime;
                return device;
            }
        } catch (Exception e) {
            // 继续
        }

        // 方法3：TCP端口探测（对禁止ICMP的设备有效）
        int[] testPorts = {80, 443, 22, 8080, 554, 5000, 9100, 62078};
        // 80=HTTP, 443=HTTPS, 22=SSH, 8080=WebUI, 554=RTSP(摄像头),
        // 5000=UPnP/NAS, 9100=打印机, 62078=iPhone

        for (int port : testPorts) {
            if (forceStop) break;

            try {
                Socket socket = new Socket();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                SocketAddress address = new InetSocketAddress(host, port);

                long connectStart = System.currentTimeMillis();
                socket.connect(address, SOCKET_TIMEOUT_MS);
                long responseTime = System.currentTimeMillis() - connectStart;

                socket.close();

                LanDevice device = new LanDevice(host);
                device.isReachable = true;
                device.responseTime = responseTime;
                return device;

            } catch (Exception e) {
                // 尝试下一个端口
            }
        }

        return null;
    }

    /**
     * 获取所有本地子网
     */
    private List<String> getAllLocalSubnets() {
        List<String> subnets = new ArrayList<>();

        try {
            // 首先尝试从WiFi获取
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                if (ipAddress != 0) {
                    String ip = String.format("%d.%d.%d.%d",
                            (ipAddress & 0xff),
                            (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff),
                            (ipAddress >> 24 & 0xff));
                    String subnet = getSubnetFromIp(ip);
                    if (subnet != null) {
                        subnets.add(subnet);
                        Log.d(TAG, "Found WiFi subnet: " + subnet);
                    }
                }
            }

            // 获取所有网络接口的子网
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (!intf.isLoopback() && intf.isUp()) {
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                            String ip = inetAddress.getHostAddress();
                            if (ip != null && ip.indexOf(':') < 0) {
                                String subnet = getSubnetFromIp(ip);
                                if (subnet != null && !subnets.contains(subnet)) {
                                    subnets.add(subnet);
                                    Log.d(TAG, "Found network subnet: " + subnet + " on " + intf.getDisplayName());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local subnets", e);
        }

        subnets = new ArrayList<>(new java.util.LinkedHashSet<>(subnets));

        if (subnets.isEmpty()) {
            Log.w(TAG, "No subnets found, trying defaults");
            subnets.add("192.168.1");
            subnets.add("192.168.0");
            subnets.add("10.0.0");
        }

        if (subnets.size() > 5) {
            subnets = subnets.subList(0, 5);
        }

        return subnets;
    }

    private List<String> getAllScanHosts(List<String> subnets) {
        List<String> allHosts = new ArrayList<>();
        for (String subnet : subnets) {
            for (int i = 1; i <= 254; i++) {
                allHosts.add(subnet + "." + i);
            }
        }
        return allHosts;
    }

    private LanDevice markDeviceNetwork(LanDevice device, List<String> subnets) {
        String deviceSubnet = getSubnetFromIp(device.ipAddress);
        if (deviceSubnet != null) {
            try {
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                    if (ipAddress != 0) {
                        String wifiIp = String.format("%d.%d.%d.%d",
                                (ipAddress & 0xff),
                                (ipAddress >> 8 & 0xff),
                                (ipAddress >> 16 & 0xff),
                                (ipAddress >> 24 & 0xff));
                        String wifiSubnet = getSubnetFromIp(wifiIp);
                        if (deviceSubnet.equals(wifiSubnet)) {
                            device.hostname = device.hostname + " [" + context.getString(R.string.network_wifi) + "]";
                            return device;
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }

            if (deviceSubnet.startsWith("192.168.")) {
                device.hostname = device.hostname + " [" + context.getString(R.string.network_home) + "]";
            } else if (deviceSubnet.startsWith("10.")) {
                device.hostname = device.hostname + " [" + context.getString(R.string.network_enterprise) + "]";
            } else if (deviceSubnet.startsWith("172.")) {
                device.hostname = device.hostname + " [" + context.getString(R.string.network_private) + "]";
            } else {
                device.hostname = device.hostname + " [" + context.getString(R.string.network_other) + "]";
            }
        }

        return device;
    }

    private void enrichDeviceInfoSafe(LanDevice device) {
        try {
            InetAddress address = InetAddress.getByName(device.ipAddress);
            String hostname = address.getCanonicalHostName();
            if (!hostname.equals(device.ipAddress)) {
                device.hostname = hostname;
            }

            // 尝试从ARP表补充MAC地址
            if (device.macAddress == null || context.getString(R.string.unknown).equals(device.macAddress)) {
                String mac = getMacFromArp(device.ipAddress);
                device.macAddress = mac != null ? mac : context.getString(R.string.unknown);
            }

        } catch (Exception e) {
            if (device.hostname == null) {
                device.hostname = context.getString(R.string.unknown);
            }
            if (device.macAddress == null) {
                device.macAddress = context.getString(R.string.unknown);
            }
        }
    }

    /**
     * 从ARP表获取指定IP的MAC地址
     */
    private String getMacFromArp(String ip) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            reader.readLine(); // 跳过标题
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4 && parts[0].equals(ip)) {
                    String mac = parts[3];
                    if (!"00:00:00:00:00:00".equals(mac)) {
                        reader.close();
                        return mac;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String getSubnetFromIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return null;
    }

    public void cleanup() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(true);
            timeoutFuture = null;
        }
        stopScan();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdown();
        }
    }
}
