package com.aucneon.portforwarder;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
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
    private static final int SCAN_TIMEOUT = 200; // 减少单个测试超时时间
    private static final int SOCKET_TIMEOUT = 150; // Socket连接超时
    private static final int MAX_THREADS = 50; // 增加线程数，每个任务超时更短
    private static final int TOTAL_TIMEOUT_SECONDS = 45; // 总扫描超时45秒
    
    private Context context;
    private ExecutorService executorService;
    private ExecutorService timeoutExecutor; // 专门用于超时控制的线程池
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
        this.timeoutExecutor = Executors.newCachedThreadPool(); // 用于超时监控
    }
    
    public void setScanCallback(ScanCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 开始扫描局域网 - 重新设计的可靠版本
     */
    public void startScan() {
        if (isScanning) {
            Log.w(TAG, "Scan is already in progress");
            return;
        }
        
        isScanning = true;
        forceStop = false;
        if (callback != null) {
            callback.onScanStarted();
        }
        
        // 启动主扫描线程
        new Thread(() -> {
            try {
                scanNetworkReliable();
            } catch (Exception e) {
                Log.e(TAG, "Scan error", e);
                if (callback != null) {
                    callback.onScanError("扫描失败: " + e.getMessage());
                }
            } finally {
                isScanning = false;
                forceStop = false;
            }
        }).start();
        
        // 启动总超时监控线程 - 确保绝对不会卡住
        timeoutExecutor.submit(() -> {
            try {
                Thread.sleep(TOTAL_TIMEOUT_SECONDS * 1000);
                if (isScanning) {
                    Log.w(TAG, "Total scan timeout reached, forcing stop");
                    forceStop = true;
                    isScanning = false;
                    
                    // 强制关闭所有线程池
                    if (executorService != null) {
                        executorService.shutdownNow();
                        executorService = Executors.newFixedThreadPool(MAX_THREADS);
                    }
                    
                    if (callback != null) {
                        callback.onScanError("扫描超时，已发现的设备仍然有效");
                    }
                }
            } catch (InterruptedException e) {
                // 正常中断
            }
        });
    }
    
    /**
     * 停止扫描
     */
    public void stopScan() {
        isScanning = false;
        forceStop = true;
        
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
            executorService = Executors.newFixedThreadPool(MAX_THREADS);
        }
    }
    
    /**
     * 新的可靠扫描方法 - 使用CountDownLatch确保完成
     */
    private void scanNetworkReliable() {
        String subnet = getLocalSubnet();
        if (subnet == null) {
            if (callback != null) {
                callback.onScanError("无法获取本地网络信息");
            }
            return;
        }
        
        Log.d(TAG, "Starting reliable scan of subnet: " + subnet);
        
        List<LanDevice> devices = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedCount = new AtomicInteger(0);
        List<Integer> scanIps = getScanIpRange();
        int totalHosts = scanIps.size();
        
        // 使用CountDownLatch确保所有任务完成或超时
        CountDownLatch latch = new CountDownLatch(totalHosts);
        
        Log.i(TAG, "Scanning " + totalHosts + " IP addresses with " + MAX_THREADS + " threads");
        
        // 提交所有扫描任务
        for (Integer ip : scanIps) {
            if (forceStop) break;
            
            final String host = subnet + "." + ip;
            
            executorService.submit(() -> {
                try {
                    if (forceStop) return;
                    
                    // 简单快速的ping测试
                    LanDevice device = quickPingTest(host);
                    
                    if (device != null && device.isReachable) {
                        // 安全获取主机名
                        enrichDeviceInfoSafe(device);
                        
                        devices.add(device);
                        
                        if (callback != null && !forceStop) {
                            callback.onDeviceFound(device);
                        }
                        
                        Log.i(TAG, "Found device: " + host + " (" + device.responseTime + "ms)");
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error scanning " + host + ": " + e.getMessage());
                } finally {
                    // 确保进度始终递增
                    int completed = completedCount.incrementAndGet();
                    latch.countDown();
                    
                    if (callback != null && !forceStop) {
                        callback.onScanProgress(completed, totalHosts);
                    }
                    
                    Log.d(TAG, "Progress: " + completed + "/" + totalHosts);
                }
            });
        }
        
        // 等待所有任务完成或超时
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
     * 获取扫描IP范围 - 扫描整个局域网
     */
    private List<Integer> getScanIpRange() {
        List<Integer> ips = new ArrayList<>();
        
        // 扫描整个局域网：1-254
        for (int i = 1; i <= 254; i++) {
            ips.add(i);
        }
        
        Log.d(TAG, "Will scan entire LAN: " + ips.size() + " IP addresses");
        return ips;
    }

    /**
     * 简化的快速ping测试 - 避免复杂的嵌套Future
     */
    private LanDevice quickPingTest(String host) {
        long startTime = System.currentTimeMillis();
        
        // 方法1：简单的InetAddress测试
        try {
            InetAddress addr = InetAddress.getByName(host);
            
            // 使用超时的线程来测试连通性
            final boolean[] result = {false};
            Thread testThread = new Thread(() -> {
                try {
                    result[0] = addr.isReachable(SCAN_TIMEOUT);
                } catch (Exception e) {
                    result[0] = false;
                }
            });
            
            testThread.start();
            testThread.join(SCAN_TIMEOUT + 50); // 稍微多给一点时间
            
            if (testThread.isAlive()) {
                testThread.interrupt(); // 强制中断
            }
            
            if (result[0]) {
                long responseTime = System.currentTimeMillis() - startTime;
                LanDevice device = new LanDevice(host);
                device.isReachable = true;
                device.responseTime = responseTime;
                return device;
            }
        } catch (Exception e) {
            // 继续尝试下一种方法
        }
        
        // 方法2：Socket测试常用端口
        int[] testPorts = {80, 22, 443}; // 减少测试端口数量
        
        for (int port : testPorts) {
            if (forceStop) break;
            
            try {
                Socket socket = new Socket();
                socket.setSoTimeout(SOCKET_TIMEOUT);
                SocketAddress address = new InetSocketAddress(host, port);
                
                long connectStart = System.currentTimeMillis();
                socket.connect(address, SOCKET_TIMEOUT);
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
        
        return null; // 未找到设备
    }
    

    
    /**
     * 安全的设备信息获取（不依赖系统文件）
     */
    private void enrichDeviceInfoSafe(LanDevice device) {
        try {
            // 获取主机名
            InetAddress address = InetAddress.getByName(device.ipAddress);
            String hostname = address.getCanonicalHostName();
            if (!hostname.equals(device.ipAddress)) {
                device.hostname = hostname;
            }
            
            // MAC地址设为未知，因为无法安全获取
            device.macAddress = "未知";
            
        } catch (Exception e) {
            // 静默处理错误
            device.hostname = "未知";
            device.macAddress = "未知";
        }
    }
    
    /**
     * 获取本地子网
     */
    private String getLocalSubnet() {
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
                    
                    return getSubnetFromIp(ip);
                }
            }
            
            // 回退到网络接口方法
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (!intf.isLoopback() && intf.isUp()) {
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                            String ip = inetAddress.getHostAddress();
                            if (ip != null && ip.indexOf(':') < 0) { // 排除IPv6
                                return getSubnetFromIp(ip);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting local subnet", e);
        }
        
        return null;
    }
    
    /**
     * 从IP地址获取子网
     */
    private String getSubnetFromIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length >= 3) {
            return parts[0] + "." + parts[1] + "." + parts[2];
        }
        return null;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stopScan();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdown();
        }
    }
} 