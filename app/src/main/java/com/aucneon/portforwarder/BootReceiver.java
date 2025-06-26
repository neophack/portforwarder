package com.aucneon.portforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import java.util.List;

/**
 * 开机启动接收器
 * 用于在设备启动时自动启动服务和转发配置
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            
            try {
                ConfigManager configManager = ConfigManager.getInstance(context);
                
                // 检查是否启用了自动启动服务
                if (configManager.isAutoStartService()) {
                    Log.d(TAG, "Auto start service is enabled, starting service...");
                    
                    // 启动前台服务
                    Intent serviceIntent = new Intent(context, PortForwarderService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    
                    // 延迟启动自动转发配置
                    new Thread(() -> {
                        try {
                            Thread.sleep(3000); // 等待3秒让服务完全启动
                            
                            List<ForwardConfig> autoStartConfigs = configManager.getAutoStartConfigs();
                            Log.d(TAG, "Found " + autoStartConfigs.size() + " auto-start configs");
                            
                            for (ForwardConfig config : autoStartConfigs) {
                                try {
                                    int sessionId = PortForwarder.createForward(
                                            config.protocol,
                                            config.listenPort,
                                            config.targetHost,
                                            config.targetPort
                                    );
                                    
                                    if (sessionId > 0) {
                                        Log.i(TAG, "Auto-started forward: " + config.toString());
                                    } else {
                                        Log.w(TAG, "Failed to auto-start forward: " + config.toString());
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error starting forward: " + config.toString(), e);
                                }
                            }
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Thread interrupted", e);
                        }
                    }).start();
                } else {
                    Log.d(TAG, "Auto start service is disabled");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in boot receiver", e);
            }
        }
    }
} 