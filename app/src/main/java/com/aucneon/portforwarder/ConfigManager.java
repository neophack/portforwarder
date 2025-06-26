package com.aucneon.portforwarder;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.NetworkInterface;
import java.net.InetAddress;
import java.util.Enumeration;

/**
 * 配置管理器
 * 负责保存和加载转发配置、应用设置等
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREF_NAME = "port_forwarder_config";
    private static final String KEY_FORWARD_CONFIGS = "forward_configs";
    private static final String KEY_AUTO_START_SERVICE = "auto_start_service";
    private static final String KEY_LAST_CONFIG_ID = "last_config_id";
    
    private static ConfigManager instance;
    private SharedPreferences preferences;
    private Gson gson;
    private AtomicInteger configIdGenerator;
    
    private ConfigManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
        
        // 初始化ID生成器
        int lastId = preferences.getInt(KEY_LAST_CONFIG_ID, 0);
        configIdGenerator = new AtomicInteger(lastId);
    }
    
    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 生成新的配置ID
     */
    public int generateConfigId() {
        int newId = configIdGenerator.incrementAndGet();
        preferences.edit().putInt(KEY_LAST_CONFIG_ID, newId).apply();
        return newId;
    }
    
    /**
     * 保存转发配置列表
     */
    public void saveForwardConfigs(List<ForwardConfig> configs) {
        try {
            String json = gson.toJson(configs);
            preferences.edit().putString(KEY_FORWARD_CONFIGS, json).apply();
            Log.d(TAG, "Saved " + configs.size() + " forward configs");
        } catch (Exception e) {
            Log.e(TAG, "Failed to save forward configs", e);
        }
    }
    
    /**
     * 加载转发配置列表
     */
    public List<ForwardConfig> loadForwardConfigs() {
        try {
            String json = preferences.getString(KEY_FORWARD_CONFIGS, null);
            if (json != null) {
                Type listType = new TypeToken<List<ForwardConfig>>(){}.getType();
                List<ForwardConfig> configs = gson.fromJson(json, listType);
                Log.d(TAG, "Loaded " + configs.size() + " forward configs");
                return configs;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load forward configs", e);
        }
        
        // 返回默认配置
        return getDefaultConfigs();
    }
    
    /**
     * 获取默认配置
     */
    private List<ForwardConfig> getDefaultConfigs() {
        List<ForwardConfig> configs = new ArrayList<>();
        
        // 添加一些示例配置
        ForwardConfig config1 = new ForwardConfig("WEBUI", PortForwarder.PROTOCOL_TCP, 8081, "161.0.0.2", 8081);
        config1.id = generateConfigId();
        configs.add(config1);
        
        ForwardConfig config2 = new ForwardConfig("TELNET", PortForwarder.PROTOCOL_TCP, 8023, "161.0.0.2", 23);
        config2.id = generateConfigId();
        configs.add(config2);
        
        ForwardConfig config3 = new ForwardConfig("ROS-ESP32", PortForwarder.PROTOCOL_UDP, 8099, "161.0.0.2", 8099);
        config3.id = generateConfigId();
        configs.add(config3);
        // 保存到preferences
        saveForwardConfigs(configs);
        Log.d(TAG, "Created and saved " + configs.size() + " default configs");
        Log.d(TAG, "configs: " + configs);
        Log.d(TAG, "configs: " + configs.get(0).name);
        Log.d(TAG, "configs: " + configs.get(0).protocol);
        Log.d(TAG, "configs: " + configs.get(0).listenPort);
        Log.d(TAG, "configs: " + configs.get(0).targetHost);
        Log.d(TAG, "configs: " + configs.get(0).targetPort);

        
        return configs;
    }
    
    /**
     * 添加新的转发配置
     */
    public void addForwardConfig(ForwardConfig config) {
        List<ForwardConfig> configs = loadForwardConfigs();
        config.id = generateConfigId();
        configs.add(config);
        saveForwardConfigs(configs);
    }
    
    /**
     * 更新转发配置
     */
    public void updateForwardConfig(ForwardConfig config) {
        List<ForwardConfig> configs = loadForwardConfigs();
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id == config.id) {
                config.updateModifyTime();
                configs.set(i, config);
                saveForwardConfigs(configs);
                return;
            }
        }
    }
    
    /**
     * 删除转发配置
     */
    public void deleteForwardConfig(int configId) {
        List<ForwardConfig> configs = loadForwardConfigs();
        configs.removeIf(config -> config.id == configId);
        saveForwardConfigs(configs);
    }
    
    /**
     * 根据ID获取转发配置
     */
    public ForwardConfig getForwardConfig(int configId) {
        List<ForwardConfig> configs = loadForwardConfigs();
        for (ForwardConfig config : configs) {
            if (config.id == configId) {
                return config;
            }
        }
        return null;
    }
    
    /**
     * 设置服务自动启动
     */
    public void setAutoStartService(boolean autoStart) {
        preferences.edit().putBoolean(KEY_AUTO_START_SERVICE, autoStart).apply();
    }
    
    /**
     * 获取服务自动启动设置
     */
    public boolean isAutoStartService() {
        return preferences.getBoolean(KEY_AUTO_START_SERVICE, false);
    }
    
    /**
     * 获取所有启用自动启动的配置
     */
    public List<ForwardConfig> getAutoStartConfigs() {
        List<ForwardConfig> allConfigs = loadForwardConfigs();
        List<ForwardConfig> autoStartConfigs = new ArrayList<>();
        
        for (ForwardConfig config : allConfigs) {
            if (config.autoStart && config.enabled) {
                autoStartConfigs.add(config);
            }
        }
        
        return autoStartConfigs;
    }
    
    /**
     * 清除所有配置
     */
    public void clearAllConfigs() {
        preferences.edit().clear().apply();
        configIdGenerator.set(0);
    }
    
    /**
     * 获取设备的本地IP地址
     */
    public String getDeviceIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                        String ip = inetAddress.getHostAddress();
                        // 过滤IPv6地址
                        if (ip != null && ip.indexOf(':') < 0) {
                            Log.d(TAG, "Found device IP: " + ip);
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device IP address", e);
        }
        return "未知IP";
    }

    /**
     * 获取设备的所有本地IP地址（除回环地址）
     */
    public List<String> getAllDeviceIpAddresses() {
        List<String> ipList = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                // 跳过回环和未启用的接口
                if (intf.isLoopback() || !intf.isUp()) {
                    continue;
                }
                
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    // 排除回环地址和链路本地地址
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                        String ip = inetAddress.getHostAddress();
                        // 过滤IPv6地址和无效IP
                        if (ip != null && ip.indexOf(':') < 0 && !ip.equals("0.0.0.0")) {
                            String interfaceName = intf.getDisplayName();
                            String ipWithInterface = ip + " (" + interfaceName + ")";
                            ipList.add(ipWithInterface);
                            Log.d(TAG, "Found device IP: " + ipWithInterface);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device IP addresses", e);
        }
        
        if (ipList.isEmpty()) {
            ipList.add("未找到可用IP");
        }
        
        return ipList;
    }

    /**
     * 获取设备的所有本地IP地址的字符串表示
     */
    public String getAllDeviceIpAddressesString() {
        List<String> ipList = getAllDeviceIpAddresses();
        if (ipList.size() == 1 && ipList.get(0).equals("未找到可用IP")) {
            return "未找到可用IP";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ipList.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(ipList.get(i));
        }
        return sb.toString();
    }

    /**
     * 获取WiFi IP地址（备用方法）
     */
    public String getWifiIpAddress(Context context) {
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                android.net.wifi.WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipAddress = wifiInfo.getIpAddress();
                if (ipAddress != 0) {
                    String ip = String.format("%d.%d.%d.%d",
                            (ipAddress & 0xff),
                            (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff),
                            (ipAddress >> 24 & 0xff));
                    Log.d(TAG, "Found WiFi IP: " + ip);
                    return ip;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get WiFi IP address", e);
        }
        return getDeviceIpAddress(); // 回退到通用方法
    }

    /**
     * 获取所有网络接口的IP地址（包含WiFi优先的方式）
     */
    public String getAllIpAddressesString(Context context) {
        List<String> ipList = new ArrayList<>();
        String wifiIp = null;
        
        try {
            // 首先尝试获取WiFi IP
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                android.net.wifi.WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ipAddress = wifiInfo.getIpAddress();
                if (ipAddress != 0) {
                    wifiIp = String.format("%d.%d.%d.%d",
                            (ipAddress & 0xff),
                            (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff),
                            (ipAddress >> 24 & 0xff));
                    Log.d(TAG, "Found WiFi IP: " + wifiIp);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get WiFi IP address", e);
        }
        
        // 获取所有网络接口IP
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                // 跳过回环和未启用的接口
                if (intf.isLoopback() || !intf.isUp()) {
                    continue;
                }
                
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    // 排除回环地址和链路本地地址
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                        String ip = inetAddress.getHostAddress();
                        // 过滤IPv6地址和无效IP
                        if (ip != null && ip.indexOf(':') < 0 && !ip.equals("0.0.0.0")) {
                            String interfaceName = intf.getDisplayName();
                            // 标记WiFi IP
                            if (ip.equals(wifiIp)) {
                                ipList.add(ip + " (WiFi)");
                            } else {
                                ipList.add(ip + " (" + interfaceName + ")");
                            }
                            Log.d(TAG, "Found device IP: " + ip + " on " + interfaceName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get device IP addresses", e);
        }
        
        if (ipList.isEmpty()) {
            return "未找到可用IP";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ipList.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(ipList.get(i));
        }
        return sb.toString();
    }
} 