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
    private static final String KEY_HELLO_WEB_ENABLED = "hello_web_enabled";
    private static final String KEY_HELLO_WEB_PORT = "hello_web_port";
    private static final int DEFAULT_HELLO_WEB_PORT = 18080;
    
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

    public void setHelloWebServerEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_HELLO_WEB_ENABLED, enabled).apply();
    }

    public boolean isHelloWebServerEnabled() {
        return preferences.getBoolean(KEY_HELLO_WEB_ENABLED, false);
    }

    public void setHelloWebServerPort(int port) {
        if (port >= 1024 && port <= 65535) {
            preferences.edit().putInt(KEY_HELLO_WEB_PORT, port).apply();
        }
    }

    public int getHelloWebServerPort() {
        return preferences.getInt(KEY_HELLO_WEB_PORT, DEFAULT_HELLO_WEB_PORT);
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
        boolean autoStart = isAutoStartService();
        preferences.edit()
            .remove(KEY_FORWARD_CONFIGS)
            .remove(KEY_LAST_CONFIG_ID)
            .apply();
        configIdGenerator.set(0);
        setAutoStartService(autoStart);
    }
}