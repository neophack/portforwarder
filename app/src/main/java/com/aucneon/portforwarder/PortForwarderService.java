package com.aucneon.portforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.util.Map;

/**
 * 端口转发后台服务
 * 用于保持转发在后台运行，防止被系统杀死
 */
public class PortForwarderService extends Service {
    private static final String TAG = "PortForwarderService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "port_forwarder_channel";

    private PowerManager.WakeLock wakeLock;
    private NotificationManagerCompat notificationManager;

    // 绑定器类
    public class PortForwarderBinder extends Binder {
        PortForwarderService getService() {
            return PortForwarderService.this;
        }
    }

    private final IBinder binder = new PortForwarderBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        // 获取电源管理器
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "PortForwarder::WakeLock");
        }

        // 初始化通知管理器
        notificationManager = NotificationManagerCompat.from(this);

        // 创建通知渠道
        createNotificationChannel();

        // 启动前台服务
        try {
            startForeground(NOTIFICATION_ID, createNotification());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
        }

        // 获取WakeLock防止CPU休眠
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire(10 * 60 * 1000L);
            } catch (Exception e) {
                Log.e(TAG, "Failed to acquire wake lock", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");
        updateNotification();
        return START_STICKY; // 服务被杀死后自动重启
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");

        // 停止所有转发
        PortForwarder.stopAllForwards();

        // 释放WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 停止前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.notification_channel_desc));
            channel.setShowBadge(false);
            channel.setSound(null, null);

            notificationManager = NotificationManagerCompat.from(this);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_channel_name))
                    .setContentText(getString(R.string.notification_running))
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setAutoCancel(false)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create notification", e);
            // 创建一个简单的后备通知
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_channel_name))
                    .setContentText(getString(R.string.notification_running))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }
    }

    /**
     * 更新通知内容
     */
    public void updateNotification() {
        // 检查通知权限
        if (!hasNotificationPermission()) {
            Log.w(TAG, "No notification permission, skipping notification update");
            return;
        }

        Map<Integer, PortForwarder.ForwardInfo> forwards = PortForwarder.getAllForwards();
        int activeCount = forwards.size();

        String contentText;
        if (activeCount == 0) {
            contentText = getString(R.string.notification_no_active);
        } else {
            contentText = getString(R.string.notification_active_count, activeCount);
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_channel_name))
                    .setContentText(contentText)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build();

            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update notification", e);
        }
    }

    /**
     * 检查是否有通知权限
     */
    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, 
                android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return notificationManager != null && notificationManager.areNotificationsEnabled();
    }

    /**
     * 检查服务是否正在运行
     */
    public boolean isRunning() {
        return true; // 如果服务对象存在，说明正在运行
    }

    /**
     * 获取转发统计信息
     */
    public String getStatusInfo() {
        Map<Integer, PortForwarder.ForwardInfo> forwards = PortForwarder.getAllForwards();
        StringBuilder info = new StringBuilder();

        info.append(getString(R.string.status_info_running));
        info.append(getString(R.string.status_info_active, forwards.size()));
        info.append("WakeLock: ").append((wakeLock != null && wakeLock.isHeld()) ? getString(R.string.wakelock_acquired) : getString(R.string.wakelock_not_acquired)).append("\n");

        return info.toString();
    }
}