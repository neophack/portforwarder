package com.aucneon.portforwarder;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.espressif.iot.esptouch.EsptouchTask;
import com.espressif.iot.esptouch.IEsptouchListener;
import com.espressif.iot.esptouch.IEsptouchResult;
import com.espressif.iot.esptouch.util.ByteUtil;
import com.espressif.iot.esptouch.util.TouchNetUtil;

public class MainActivity extends AppCompatActivity implements ForwardAdapter.OnConfigActionListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;

    // 新的UI组件
    private TextView tvServiceStatus;
    private TextView tvConfigCount;
    private TextView tvActiveCount;
    private TextView tvLastUpdate;
    private TextView tvDeviceIp;
    private Button btnStartAll;
    private Button btnStopAllNew;
    private Button btnAddConfig;
    private Button btnSettings;
    private Button btnSmartConfig;
    private Button btnRefresh;
    private Button btnRefreshIp;
    private Switch swAutoStartService;
    private RecyclerView rvForwardConfigs;
    
    // SmartConfig相关
    private EsptouchTask esptouchTask;
    private boolean isSmartConfigRunning = false;

    // 数据和适配器
    private ConfigManager configManager;
    private ForwardAdapter forwardAdapter;
    private List<ForwardConfig> forwardConfigs;
    private Handler updateHandler;
    private Runnable updateRunnable;

    // 服务连接
    private PortForwarderService portForwarderService;
    private boolean isServiceBound = false;

    // 旧的UI组件（保留向后兼容）
    private EditText etListenPort;
    private EditText etTargetHost;
    private EditText etTargetPort;
    private RadioGroup rgProtocol;
    private Button btnStart;
    private Button btnStop;
    private Button btnStopAll;
    private TextView tvStatus;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PortForwarderService.PortForwarderBinder binder = (PortForwarderService.PortForwarderBinder) service;
            portForwarderService = binder.getService();
            isServiceBound = true;
            updateServiceStatus();
            Log.d(TAG, "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            portForwarderService = null;
            isServiceBound = false;
            updateServiceStatus();
            Log.d(TAG, "Service disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_new);

        initComponents();
        checkPermissions();
        setupListeners();
        setupAutoUpdate();
        loadConfigs();
        startAndBindService();
    }

    private void initComponents() {
        // 初始化配置管理器
        configManager = ConfigManager.getInstance(this);
        
        // 初始化UI组件
        tvServiceStatus = findViewById(R.id.tv_service_status);
        tvConfigCount = findViewById(R.id.tv_config_count);
        tvActiveCount = findViewById(R.id.tv_active_count);
        tvLastUpdate = findViewById(R.id.tv_last_update);
        tvDeviceIp = findViewById(R.id.tv_device_ip);
        btnStartAll = findViewById(R.id.btn_start_all);
        btnStopAllNew = findViewById(R.id.btn_stop_all_new);
        btnAddConfig = findViewById(R.id.btn_add_config);
        btnSettings = findViewById(R.id.btn_settings);
        btnSmartConfig = findViewById(R.id.btn_smart_config);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnRefreshIp = findViewById(R.id.btn_refresh_ip);
        swAutoStartService = findViewById(R.id.sw_auto_start_service);
        rvForwardConfigs = findViewById(R.id.rv_forward_configs);

        // 初始化RecyclerView
        forwardConfigs = new ArrayList<>();
        forwardAdapter = new ForwardAdapter(this, forwardConfigs);
        forwardAdapter.setOnConfigActionListener(this);
        
        rvForwardConfigs.setLayoutManager(new LinearLayoutManager(this));
        rvForwardConfigs.setAdapter(forwardAdapter);

        // 设置自动启动开关状态
        swAutoStartService.setChecked(configManager.isAutoStartService());
        
        // 初始化设备IP显示
        updateDeviceIp();
    }

    private void checkPermissions() {
        String[] permissions;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.INTERNET,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        boolean needRequest = false;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                showToast("应用需要网络权限才能正常工作");
            }
        }
    }

    private void setupListeners() {
        // 一键启动所有启用的配置
        btnStartAll.setOnClickListener(v -> startAllEnabledConfigs());

        // 停止所有转发
        btnStopAllNew.setOnClickListener(v -> stopAllForwards());

        // 添加新配置
        btnAddConfig.setOnClickListener(v -> showConfigEditDialog(null));

        // 设置按钮
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // SmartConfig按钮
        btnSmartConfig.setOnClickListener(v -> showSmartConfigDialog());

        // 刷新按钮
        btnRefresh.setOnClickListener(v -> refreshStatus());

        // 刷新IP按钮
        btnRefreshIp.setOnClickListener(v -> updateDeviceIp());

        // 自动启动服务开关
        swAutoStartService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            configManager.setAutoStartService(isChecked);
            showToast(isChecked ? "已启用开机启动服务" : "已禁用开机启动服务");
        });
    }

    private void startForward() {
        try {
            String listenPortStr = etListenPort.getText().toString().trim();
            String targetHost = etTargetHost.getText().toString().trim();
            String targetPortStr = etTargetPort.getText().toString().trim();

            // 输入验证
            if (listenPortStr.isEmpty()) {
                showToast("请输入监听端口");
                etListenPort.requestFocus();
                return;
            }

            if (targetHost.isEmpty()) {
                showToast("请输入目标主机地址");
                etTargetHost.requestFocus();
                return;
            }

            if (targetPortStr.isEmpty()) {
                showToast("请输入目标端口");
                etTargetPort.requestFocus();
                return;
            }

            int listenPort = Integer.parseInt(listenPortStr);
            int targetPort = Integer.parseInt(targetPortStr);

            // 端口范围验证
            if (listenPort < 1024 || listenPort > 65535) {
                showToast("监听端口范围应在1024-65535之间");
                etListenPort.requestFocus();
                return;
            }

            if (targetPort < 1 || targetPort > 65535) {
                showToast("目标端口范围应在1-65535之间");
                etTargetPort.requestFocus();
                return;
            }

            // 主机名验证（简单检查）
            if (!isValidHost(targetHost)) {
                showToast("请输入有效的主机地址或IP");
                etTargetHost.requestFocus();
                return;
            }

            // 检查端口是否可用
            if (!PortForwarder.isPortAvailable(listenPort)) {
                showToast("端口 " + listenPort + " 不可用或已被使用");
                etListenPort.requestFocus();
                return;
            }

            // 获取选择的协议
            int protocol = (rgProtocol.getCheckedRadioButtonId() == R.id.rb_tcp) ?
                    PortForwarder.PROTOCOL_TCP : PortForwarder.PROTOCOL_UDP;

            // 禁用开始按钮，防止重复点击
            btnStart.setEnabled(false);

            int sessionId = PortForwarder.createForward(protocol, listenPort, targetHost, targetPort);

            if (sessionId > 0) {
                String protocolName = (protocol == PortForwarder.PROTOCOL_TCP) ? "TCP" : "UDP";
                showToast(String.format("成功创建%s转发 [%d]: %d -> %s:%d",
                        protocolName, sessionId, listenPort, targetHost, targetPort));
                updateStatus();
                
                // 清空输入框
                etListenPort.setText("");
                etTargetHost.setText("");
                etTargetPort.setText("");
            } else {
                showToast("创建端口转发失败: " + getErrorMessage(sessionId));
            }

        } catch (NumberFormatException e) {
            showToast("请输入有效的端口号");
            Log.e(TAG, "Invalid port number", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start forward", e);
            showToast("启动转发失败: " + e.getMessage());
        } finally {
            // 重新启用开始按钮
            btnStart.setEnabled(true);
        }
    }

    private boolean isValidHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        
        // 简单的IP地址格式检查
        if (host.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            String[] parts = host.split("\\.");
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        }
        
        // 简单的域名格式检查
        return host.matches("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$") || 
               host.equals("localhost") ||
               host.matches("^[a-zA-Z0-9.-]+$");
    }

    private void stopLatestForward() {
        Map<Integer, PortForwarder.ForwardInfo> forwards = PortForwarder.getAllForwards();
        if (forwards.isEmpty()) {
            showToast("没有活动的转发会话");
            return;
        }

        // 找到最新创建的会话
        int latestSessionId = -1;
        long latestTime = 0;
        for (PortForwarder.ForwardInfo info : forwards.values()) {
            if (info.createTime > latestTime) {
                latestTime = info.createTime;
                latestSessionId = info.sessionId;
            }
        }

        if (latestSessionId > 0) {
            PortForwarder.ForwardInfo info = forwards.get(latestSessionId);
            boolean success = PortForwarder.stopForward(latestSessionId);
            if (success && info != null) {
                showToast(String.format("已停止转发 [%d]: %d -> %s:%d",
                        latestSessionId, info.listenPort, info.targetHost, info.targetPort));
                updateStatus();
            } else {
                showToast("停止转发失败");
            }
        }
    }

    private void stopAllForwards() {
        int count = PortForwarder.getActiveSessionCount();
        if (count == 0) {
            showToast("没有活动的转发会话");
            return;
        }

        // 异步停止所有转发，避免阻塞UI线程
        new Thread(() -> {
            try {
                PortForwarder.stopAllForwards();
                
                // 回到主线程更新UI前检查Activity状态
                runOnUiThread(() -> {
                    // 检查Activity是否仍然有效
                    if (!isDestroyed() && !isFinishing()) {
                        showToast("已停止所有转发会话 (" + count + " 个)");
                        updateStatus();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error stopping all forwards", e);
                runOnUiThread(() -> {
                    // 检查Activity是否仍然有效
                    if (!isDestroyed() && !isFinishing()) {
                        showToast("停止转发时出现错误");
                    }
                });
            }
        }).start();
    }

    private void updateStatus() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvStatus == null) {
            return;
        }
        
        Map<Integer, PortForwarder.ForwardInfo> forwards = PortForwarder.getAllForwards();
        StringBuilder status = new StringBuilder();

        status.append("活动转发会话: ").append(forwards.size()).append("\n\n");

        if (forwards.isEmpty()) {
            status.append("无活动转发");
        } else {
            for (PortForwarder.ForwardInfo info : forwards.values()) {
                status.append(info.toString()).append("\n");
                status.append("创建时间: ").append(formatTime(info.createTime)).append("\n");
                status.append("状态: ").append(PortForwarder.isRunning(info.sessionId) ? "运行中" : "已停止").append("\n\n");
            }
        }

        tvStatus.setText(status.toString());
    }

    private String formatTime(long timestamp) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date(timestamp));
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, message);
    }

    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case PortForwarder.ERROR_SOCKET_CREATE:
                return "创建套接字失败";
            case PortForwarder.ERROR_BIND_FAILED:
                return "绑定端口失败(端口可能被占用)";
            case PortForwarder.ERROR_LISTEN_FAILED:
                return "监听端口失败";
            default:
                return "未知错误 (" + errorCode + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 停止自动更新
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
            updateHandler = null;
            updateRunnable = null;
        }
        
        // 异步停止所有转发，避免阻塞主线程
        try {
            new Thread(() -> {
                try {
                    PortForwarder.stopAllForwards();
                    Log.d(TAG, "All forwards stopped on destroy");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping forwards on destroy", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error creating stop thread", e);
        }
        
        // 停止SmartConfig任务
        if (esptouchTask != null && isSmartConfigRunning) {
            esptouchTask.interrupt();
            esptouchTask = null;
        }
        
        // 解绑服务
        if (isServiceBound) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service", e);
            }
        }
        
        Log.d(TAG, "MainActivity destroyed");
    }

    // 新增方法：启动并绑定服务
    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, PortForwarderService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);
    }

    // 新增方法：设置自动更新
    private void setupAutoUpdate() {
        updateHandler = new Handler();
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                refreshStatus();
                // 降低更新频率到5秒，减少CPU占用
                updateHandler.postDelayed(this, 5000);
            }
        };
        updateHandler.post(updateRunnable);
    }

    // 新增方法：加载配置
    private void loadConfigs() {
        forwardConfigs.clear();
        forwardConfigs.addAll(configManager.loadForwardConfigs());
        forwardAdapter.updateConfigs(forwardConfigs);
        updateConfigCount();
    }

    // 新增方法：刷新状态
    private void refreshStatus() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        try {
            updateServiceStatus();
            updateActiveCount();
            updateLastUpdateTime();
            updateDeviceIp();
            
            // 检查forwardAdapter是否仍然有效
            if (forwardAdapter != null) {
                forwardAdapter.notifyDataSetChanged();
            }
            
            // 更新服务通知
            if (isServiceBound && portForwarderService != null) {
                portForwarderService.updateNotification();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing status", e);
        }
    }

    // 新增方法：更新服务状态
    private void updateServiceStatus() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvServiceStatus == null) {
            return;
        }
        
        if (isServiceBound && portForwarderService != null) {
            tvServiceStatus.setText("服务运行中");
            tvServiceStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            tvServiceStatus.setText("服务未启动");
            tvServiceStatus.setBackgroundColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    // 新增方法：更新配置数量
    private void updateConfigCount() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvConfigCount == null) {
            return;
        }
        
        int total = forwardConfigs.size();
        int enabled = 0;
        for (ForwardConfig config : forwardConfigs) {
            if (config.enabled) enabled++;
        }
        tvConfigCount.setText(String.format("%d 个配置 (%d 启用)", total, enabled));
    }

    // 新增方法：更新活动转发数量
    private void updateActiveCount() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvActiveCount == null) {
            return;
        }
        
        int activeCount = PortForwarder.getActiveSessionCount();
        tvActiveCount.setText(String.valueOf(activeCount));
    }

    // 新增方法：更新最后更新时间
    private void updateLastUpdateTime() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvLastUpdate == null) {
            return;
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        tvLastUpdate.setText(sdf.format(new Date()));
    }

    /**
     * 更新设备IP显示
     */
    private void updateDeviceIp() {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing() || tvDeviceIp == null) {
            return;
        }
        
        // 在后台线程获取IP地址
        new Thread(() -> {
            String deviceIp = configManager.getWifiIpAddress(this);
            
            // 在主线程更新UI
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || tvDeviceIp == null) {
                    return;
                }
                
                if (deviceIp != null && !deviceIp.equals("未知IP")) {
                    tvDeviceIp.setText(deviceIp);
                    tvDeviceIp.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
                } else {
                    tvDeviceIp.setText("获取失败");
                    tvDeviceIp.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                }
                Log.d(TAG, "Device IP updated: " + deviceIp);
            });
        }).start();
    }

    // 新增方法：一键启动所有启用的配置
    private void startAllEnabledConfigs() {
        int startedCount = 0;
        int failedCount = 0;

        for (ForwardConfig config : forwardConfigs) {
            if (config.enabled) {
                // 检查是否已经在运行
                boolean isAlreadyRunning = false;
                Map<Integer, PortForwarder.ForwardInfo> activeForwards = PortForwarder.getAllForwards();
                for (PortForwarder.ForwardInfo info : activeForwards.values()) {
                    if (info.listenPort == config.listenPort && info.protocol == config.protocol) {
                        isAlreadyRunning = true;
                        break;
                    }
                }

                if (!isAlreadyRunning) {
                    int sessionId = PortForwarder.createForward(
                            config.protocol,
                            config.listenPort,
                            config.targetHost,
                            config.targetPort
                    );

                    if (sessionId > 0) {
                        startedCount++;
                        Log.i(TAG, "Started config: " + config.toString());
                    } else {
                        failedCount++;
                        Log.w(TAG, "Failed to start config: " + config.toString());
                    }
                }
            }
        }

        showToast(String.format("启动完成: %d 成功, %d 失败", startedCount, failedCount));
        refreshStatus();
    }

    // 新增方法：显示配置编辑对话框
    private void showConfigEditDialog(ForwardConfig config) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_config_edit, null);
        
        EditText etConfigName = dialogView.findViewById(R.id.et_config_name);
        RadioGroup rgProtocolEdit = dialogView.findViewById(R.id.rg_protocol_edit);
        EditText etListenPortEdit = dialogView.findViewById(R.id.et_listen_port_edit);
        EditText etTargetHostEdit = dialogView.findViewById(R.id.et_target_host_edit);
        EditText etTargetPortEdit = dialogView.findViewById(R.id.et_target_port_edit);
        Switch swConfigEnabled = dialogView.findViewById(R.id.sw_config_enabled);
        Button btnScanLan = dialogView.findViewById(R.id.btn_scan_lan); // 可能为null
//        Switch swConfigAutoStart = dialogView.findViewById(R.id.sw_config_auto_start);

        // 如果是编辑模式，填充现有数据
        if (config != null) {
            etConfigName.setText(config.name);
            if (config.protocol == PortForwarder.PROTOCOL_UDP) {
                rgProtocolEdit.check(R.id.rb_udp_edit);
            }
            etListenPortEdit.setText(String.valueOf(config.listenPort));
            etTargetHostEdit.setText(config.targetHost);
            etTargetPortEdit.setText(String.valueOf(config.targetPort));
            swConfigEnabled.setChecked(config.enabled);
//            swConfigAutoStart.setChecked(config.autoStart);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(config != null ? "编辑配置" : "添加配置")
                .setView(dialogView)
                .create();

        // 局域网扫描按钮（如果存在的话）
        if (btnScanLan != null) {
            btnScanLan.setOnClickListener(v -> showLanScanDialog(etTargetHostEdit));
        } else {
            // 如果没有扫描按钮，给目标主机输入框添加长按事件来触发扫描
            etTargetHostEdit.setOnLongClickListener(v -> {
                showLanScanDialog(etTargetHostEdit);
                return true;
            });
        }

        // 保存按钮
        dialogView.findViewById(R.id.btn_save).setOnClickListener(v -> {
            try {
                String name = etConfigName.getText().toString().trim();
                if (name.isEmpty()) name = "未命名配置";

                int protocol = (rgProtocolEdit.getCheckedRadioButtonId() == R.id.rb_tcp_edit) ?
                        PortForwarder.PROTOCOL_TCP : PortForwarder.PROTOCOL_UDP;
                int listenPort = Integer.parseInt(etListenPortEdit.getText().toString().trim());
                String targetHost = etTargetHostEdit.getText().toString().trim();
                int targetPort = Integer.parseInt(etTargetPortEdit.getText().toString().trim());

                // 验证输入
                if (targetHost.isEmpty()) {
                    showToast("请输入目标主机地址");
                    return;
                }

                if (listenPort < 1 || listenPort > 65535 || targetPort < 1 || targetPort > 65535) {
                    showToast("端口范围应在1-65535之间");
                    return;
                }

                ForwardConfig newConfig;
                if (config != null) {
                    // 编辑模式
                    newConfig = config;
                    newConfig.name = name;
                    newConfig.protocol = protocol;
                    newConfig.listenPort = listenPort;
                    newConfig.targetHost = targetHost;
                    newConfig.targetPort = targetPort;
                    newConfig.enabled = swConfigEnabled.isChecked();
//                    newConfig.autoStart = swConfigAutoStart.isChecked();
                    configManager.updateForwardConfig(newConfig);
                } else {
                    // 添加模式
                    newConfig = new ForwardConfig(name, protocol, listenPort, targetHost, targetPort);
                    newConfig.enabled = swConfigEnabled.isChecked();
//                    newConfig.autoStart = swConfigAutoStart.isChecked();
                    configManager.addForwardConfig(newConfig);
                }

                loadConfigs();
                dialog.dismiss();
                showToast(config != null ? "配置已更新" : "配置已添加");

            } catch (NumberFormatException e) {
                showToast("请输入有效的端口号");
            } catch (Exception e) {
                showToast("保存失败: " + e.getMessage());
                Log.e(TAG, "Failed to save config", e);
            }
        });

        // 取消按钮
        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // 新增方法：显示设置对话框
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置");
        
        String[] options = {"清除所有配置", "导出配置", "导入配置", "关于应用"};
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    showClearConfigsDialog();
                    break;
                case 1:
                    showToast("导出功能待实现");
                    break;
                case 2:
                    showToast("导入功能待实现");
                    break;
                case 3:
                    showAboutDialog();
                    break;
            }
        });
        
        builder.show();
    }

    // 新增方法：显示清除配置确认对话框
    private void showClearConfigsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("清除所有配置")
                .setMessage("确定要清除所有转发配置吗？此操作不可撤销。")
                .setPositiveButton("确定", (dialog, which) -> {
                    PortForwarder.stopAllForwards();
                    configManager.clearAllConfigs();
                    loadConfigs();
                    showToast("所有配置已清除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 新增方法：显示关于对话框
    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("关于端口转发器")
                .setMessage("版本: 2.0\n\n功能特性:\n• TCP/UDP端口转发\n• 配置保存和管理\n• 开机自动启动\n• 后台服务运行\n• 转发状态监控")
                .setPositiveButton("确定", null)
                .show();
    }

    // 辅助方法：查找对应配置的运行中转发
    private PortForwarder.ForwardInfo findRunningForward(ForwardConfig config) {
        Map<Integer, PortForwarder.ForwardInfo> activeForwards = PortForwarder.getAllForwards();
        for (PortForwarder.ForwardInfo info : activeForwards.values()) {
            if (info.listenPort == config.listenPort && info.protocol == config.protocol) {
                return info;
            }
        }
        return null;
    }

    // 实现ForwardAdapter.OnConfigActionListener接口
    @Override
    public void onStartConfig(ForwardConfig config) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        try {
            int sessionId = PortForwarder.createForward(
                    config.protocol,
                    config.listenPort,
                    config.targetHost,
                    config.targetPort
            );

            if (sessionId > 0) {
                showToast("启动成功: " + config.name);
                refreshStatus();
            } else {
                showToast("启动失败: " + getErrorMessage(sessionId));
            }
        } catch (Exception e) {
            showToast("启动失败: " + e.getMessage());
            Log.e(TAG, "Failed to start config", e);
        }
    }

    @Override
    public void onStopConfig(ForwardConfig config) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        PortForwarder.ForwardInfo info = findRunningForward(config);
        if (info != null) {
            boolean success = PortForwarder.stopForward(info.sessionId);
            if (success) {
                showToast("已停止: " + config.name);
                refreshStatus();
            } else {
                showToast("停止失败");
            }
        } else {
            showToast("未找到运行中的转发");
        }
    }

    @Override
    public void onEditConfig(ForwardConfig config) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        showConfigEditDialog(config);
    }

    @Override
    public void onDeleteConfig(ForwardConfig config) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定要删除配置 \"" + config.name + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    // 先停止相关转发
                    onStopConfig(config);
                    
                    // 删除配置
                    configManager.deleteForwardConfig(config.id);
                    loadConfigs();
                    showToast("已删除配置: " + config.name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onToggleAutoStart(ForwardConfig config, boolean autoStart) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        config.autoStart = autoStart;
        configManager.updateForwardConfig(config);
    }

    @Override
    public void onToggleEnabled(ForwardConfig config, boolean enabled) {
        // 检查Activity是否仍然有效
        if (isDestroyed() || isFinishing()) {
            return;
        }
        
        config.enabled = enabled;
        configManager.updateForwardConfig(config);
        
        if (!enabled) {
            // 如果禁用，停止相关转发
            onStopConfig(config);
        }
        
        updateConfigCount();
    }

    // 演示批量创建转发的方法
    private void createBatchForwards() {
        Object[][] forwardConfigs = {
                {PortForwarder.PROTOCOL_TCP, 8081, "161.0.0.2", 8081},
                {PortForwarder.PROTOCOL_TCP, 8023, "161.0.0.2", 23},
                {PortForwarder.PROTOCOL_TCP, 5022, "161.0.0.2", 22},
                {PortForwarder.PROTOCOL_TCP, 3306, "database.example.com", 3306}
        };

        int[] sessionIds = PortForwarder.createMultipleForwards(forwardConfigs);

        int successCount = 0;
        for (int sessionId : sessionIds) {
            if (sessionId > 0) {
                successCount++;
            }
        }

        showToast("批量创建完成: " + successCount + "/" + forwardConfigs.length + " 个成功");
        refreshStatus();
    }

    // 新增方法：显示局域网扫描对话框
    private void showLanScanDialog(EditText targetHostEdit) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_lan_scanner, null);
        
        RecyclerView rvLanDevices = dialogView.findViewById(R.id.rv_lan_devices);
        TextView tvScanStatus = dialogView.findViewById(R.id.tv_scan_status);
        ProgressBar pbScanning = dialogView.findViewById(R.id.pb_scanning);
        Button btnRescan = dialogView.findViewById(R.id.btn_rescan);
        Button btnCancelScan = dialogView.findViewById(R.id.btn_cancel_scan);

        // 设置设备列表
        List<LanDevice> devices = new ArrayList<>();
        LanDeviceAdapter deviceAdapter = new LanDeviceAdapter(this, devices);
        rvLanDevices.setLayoutManager(new LinearLayoutManager(this));
        rvLanDevices.setAdapter(deviceAdapter);

        AlertDialog scanDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        // 创建扫描器
        LanScanner lanScanner = new LanScanner(this);
        
        // 设置扫描回调
        lanScanner.setScanCallback(new LanScanner.ScanCallback() {
            @Override
            public void onScanStarted() {
                runOnUiThread(() -> {
                    tvScanStatus.setText("正在扫描整个局域网 (254个IP地址)...");
                    pbScanning.setVisibility(View.VISIBLE);
                    btnRescan.setEnabled(false);
                    devices.clear();
                    deviceAdapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onDeviceFound(LanDevice device) {
                runOnUiThread(() -> {
                    deviceAdapter.addDevice(device);
                    tvScanStatus.setText(String.format("已发现 %d 个设备，继续扫描中...", devices.size()));
                });
            }

            @Override
            public void onScanProgress(int current, int total) {
                runOnUiThread(() -> {
                    tvScanStatus.setText(String.format("扫描进度: %d/%d (已发现 %d 个设备)", current, total, devices.size()));
                });
            }

            @Override
            public void onScanFinished(List<LanDevice> allDevices) {
                runOnUiThread(() -> {
                    pbScanning.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    
                    if (allDevices.size() > 0) {
                        tvScanStatus.setText(String.format("全网扫描完成！找到 %d 个设备，点击选择", allDevices.size()));
                    } else {
                        tvScanStatus.setText("全网扫描完成，未发现设备。请确保设备在同一局域网");
                    }
                });
            }

            @Override
            public void onScanError(String error) {
                runOnUiThread(() -> {
                    pbScanning.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    tvScanStatus.setText("扫描失败: " + error);
                    showToast("扫描失败: " + error);
                });
            }
        });

        // 设备点击事件
        deviceAdapter.setOnDeviceClickListener(device -> {
            targetHostEdit.setText(device.ipAddress);
            lanScanner.cleanup();
            scanDialog.dismiss();
            showToast("已选择IP: " + device.ipAddress);
        });

        // 重新扫描按钮
        btnRescan.setOnClickListener(v -> {
            lanScanner.stopScan();
            lanScanner.startScan();
        });

        // 取消按钮
        btnCancelScan.setOnClickListener(v -> {
            lanScanner.cleanup();
            scanDialog.dismiss();
        });

        // 对话框关闭时清理资源
        scanDialog.setOnDismissListener(dialog -> lanScanner.cleanup());

        scanDialog.show();
        
        // 开始扫描
        lanScanner.startScan();
    }

    // SmartConfig相关方法
    private void showSmartConfigDialog() {
        if (isSmartConfigRunning) {
            // 如果正在配网，显示停止对话框
            showStopSmartConfigDialog();
            return;
        }

        // 检查WiFi连接状态
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            showToast("请先开启WiFi");
            return;
        }

        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
            showToast("请先连接到WiFi网络");
            return;
        }

        String ssid = wifiInfo.getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>") || ssid.isEmpty()) {
            showToast("无法获取WiFi名称，请检查位置权限");
            return;
        }
        
        // 移除SSID两边的引号
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        
        // 检查BSSID是否可用（用于提前警告）
        String bssid = wifiInfo.getBSSID();
        if (bssid == null || bssid.equals("<unknown ssid>") || bssid.isEmpty()) {
            Log.w(TAG, "BSSID不可用: " + bssid + ", 配网可能不稳定");
        }

        final String finalSsid = ssid;

        // 显示配网对话框
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_smart_config, null);
        
        // 获取控件引用
        View contentView = dialogView.findViewById(R.id.content_view);
        View progressView = dialogView.findViewById(R.id.progress_view);
        TextView tvCurrentSsid = dialogView.findViewById(R.id.tv_current_ssid);
        TextView tvCurrentBssid = dialogView.findViewById(R.id.tv_current_bssid);
        EditText etWifiPassword = dialogView.findViewById(R.id.et_wifi_password);
        EditText etDeviceCount = dialogView.findViewById(R.id.et_device_count);
        Button btnStartConfig = dialogView.findViewById(R.id.btn_start_config);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel);
        Button btnCancelConfig = dialogView.findViewById(R.id.btn_cancel_config);
        TextView tvProgressMessage = dialogView.findViewById(R.id.tv_progress_message);
        TextView tvConfigResult = dialogView.findViewById(R.id.tv_config_result);

        tvCurrentSsid.setText("当前WiFi: " + finalSsid);
        
        // 显示MAC地址
        String currentBssid = wifiInfo.getBSSID();
        if (currentBssid != null && !currentBssid.equals("<unknown ssid>") && !currentBssid.isEmpty()) {
            tvCurrentBssid.setText("MAC地址: " + currentBssid.toUpperCase());
            tvCurrentBssid.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        } else {
            tvCurrentBssid.setText("MAC地址: 无法获取");
            tvCurrentBssid.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }

        AlertDialog configDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        // 设置密码输入框的点击事件
        etWifiPassword.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && etWifiPassword.getText().toString().equals("12345678")) {
                etWifiPassword.setText("");
                etWifiPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
        });

        etWifiPassword.setOnClickListener(v -> {
            if (etWifiPassword.getText().toString().equals("12345678")) {
                etWifiPassword.setText("");
                etWifiPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                etWifiPassword.requestFocus();
            }
        });

        // 进度显示控制方法
        Runnable showProgress = () -> {
            contentView.setVisibility(View.GONE);
            progressView.setVisibility(View.VISIBLE);
            tvProgressMessage.setText("正在配网，请稍候...");
            tvConfigResult.setVisibility(View.GONE);
        };

        Runnable hideProgress = () -> {
            contentView.setVisibility(View.VISIBLE);
            progressView.setVisibility(View.GONE);
        };

        btnStartConfig.setOnClickListener(v -> {
            String password = etWifiPassword.getText().toString();
            if (password.trim().isEmpty()) {
                showToast("请输入WiFi密码");
                return;
            }
            
            String deviceCountStr = etDeviceCount.getText().toString().trim();
            int deviceCount;
            try {
                deviceCount = deviceCountStr.isEmpty() ? 1 : Integer.parseInt(deviceCountStr);
                if (deviceCount < 1 || deviceCount > 10) {
                    showToast("设备数量应在1-10之间");
                    return;
                }
            } catch (NumberFormatException e) {
                showToast("请输入有效的设备数量");
                return;
            }
            
            showProgress.run();
            startSmartConfigWithProgress(finalSsid, password, deviceCount, configDialog, 
                    tvProgressMessage, tvConfigResult, progressView, contentView);
        });

        btnCancel.setOnClickListener(v -> configDialog.dismiss());
        
        btnCancelConfig.setOnClickListener(v -> {
            stopSmartConfig();
            configDialog.dismiss();
        });

        configDialog.show();
    }

    private void startSmartConfigWithProgress(String ssid, String password, int deviceCount, 
                                            AlertDialog dialog, TextView progressMessage, 
                                            TextView resultView, View progressView, View contentView) {
        if (isSmartConfigRunning) {
            return;
        }

        try {
            // 获取WiFi信息
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            
            // 检查WiFi连接状态
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                progressView.setVisibility(View.GONE);
                progressMessage.setText("WiFi连接异常");
                resultView.setText("请确保WiFi已连接并重试");
                resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                resultView.setVisibility(View.VISIBLE);
                return;
            }
            
            // 使用ByteUtil正确转换SSID和密码
            byte[] apSsid = ByteUtil.getBytesByString(ssid);
            byte[] apPassword = password.trim().isEmpty() ? null : ByteUtil.getBytesByString(password);
            byte[] apBssid = null;
            
            // 正确处理BSSID
            String bssid = wifiInfo.getBSSID();
            Log.d(TAG, "获取到的BSSID: " + bssid);
            
            if (bssid != null && !bssid.equals("<unknown ssid>") && !bssid.isEmpty()) {
                try {
                    apBssid = TouchNetUtil.parseBssid2bytes(bssid);
                    Log.d(TAG, "BSSID解析成功，字节长度: " + (apBssid != null ? apBssid.length : "null"));
                } catch (Exception e) {
                    Log.w(TAG, "无法解析BSSID: " + bssid + ", 将使用null", e);
                    apBssid = null;
                }
            } else {
                Log.w(TAG, "BSSID不可用: " + bssid + ", 将使用null");
                apBssid = null;
            }

            // 创建EspTouch任务
            esptouchTask = new EsptouchTask(apSsid, apBssid, apPassword, this);
            esptouchTask.setPackageBroadcast(true);

            // 设置结果监听器
            esptouchTask.setEsptouchListener(new IEsptouchListener() {
                @Override
                public void onEsptouchResultAdded(IEsptouchResult result) {
                    runOnUiThread(() -> {
                        if (result.isCancelled()) {
                            progressMessage.setText("配网已取消");
                            resultView.setText("");
                            resultView.setVisibility(View.GONE);
                            isSmartConfigRunning = false;
                            updateSmartConfigButtonText();
                            
                            // 延迟关闭对话框
                            new Handler().postDelayed(() -> {
                                if (dialog.isShowing()) {
                                    dialog.dismiss();
                                }
                            }, 1000);
                        } else if (result.isSuc()) {
                            String deviceIp = result.getInetAddress().getHostAddress();
                            String bssid = result.getBssid();
                            progressMessage.setText("配网成功！");
                            resultView.setText(String.format("设备IP: %s\nMAC: %s", deviceIp, bssid));
                            resultView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                            resultView.setVisibility(View.VISIBLE);
                            
                            isSmartConfigRunning = false;
                            updateSmartConfigButtonText();
                            
                            // 延迟关闭对话框并显示成功对话框
                            new Handler().postDelayed(() -> {
                                if (dialog.isShowing()) {
                                    dialog.dismiss();
                                    showSmartConfigSuccessDialog(deviceIp);
                                }
                            }, 2000);
                        } else {
                            // 继续等待其他设备
                            Log.d(TAG, "继续等待设备连接...");
                        }
                    });
                }
            });

            isSmartConfigRunning = true;
            updateSmartConfigButtonText();
            progressMessage.setText("正在广播配网信息...");

            // 在后台线程执行配网
            new Thread(() -> {
                try {
                    // 使用-1表示不限制设备数量，参考原始实现
                    int taskResultCount = deviceCount <= 0 ? -1 : deviceCount;
                    List<IEsptouchResult> results = esptouchTask.executeForResults(taskResultCount);
                    
                    runOnUiThread(() -> {
                        isSmartConfigRunning = false;
                        updateSmartConfigButtonText();
                        
                        if (results == null) {
                            // 端口连接失败
                            progressMessage.setText("配网失败");
                            resultView.setText("无法连接到ESP32设备，请检查：\n1. 设备是否处于SmartConfig配网模式\n2. WiFi密码是否正确\n3. 设备是否在WiFi信号范围内\n4. 手机是否连接到2.4GHz WiFi");
                            resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                            resultView.setVisibility(View.VISIBLE);
                            return;
                        }
                        
                        if (results.isEmpty()) {
                            // 没有收到任何响应
                            progressMessage.setText("配网超时");
                            resultView.setText("配网超时，未收到设备响应：\n1. 确认设备已进入SmartConfig模式\n2. 检查WiFi密码是否正确\n3. 确保设备支持当前WiFi频段");
                            resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                            resultView.setVisibility(View.VISIBLE);
                            return;
                        }

                        IEsptouchResult firstResult = results.get(0);
                        if (firstResult.isCancelled()) {
                            // 已在监听器中处理取消情况
                            return;
                        }

                        if (!firstResult.isSuc()) {
                            // 配网过程失败
                            progressMessage.setText("配网失败");
                            resultView.setText("配网过程失败，请重试：\n1. 确认WiFi密码正确\n2. 确保设备处于SmartConfig配网模式\n3. 尝试重启ESP32设备后重新配网");
                            resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                            resultView.setVisibility(View.VISIBLE);
                            return;
                        }

                        // 统计并显示所有成功的设备
                        StringBuilder successDevices = new StringBuilder();
                        int successCount = 0;
                        for (IEsptouchResult result : results) {
                            if (result.isSuc()) {
                                successCount++;
                                successDevices.append(String.format("设备%d: %s\nMAC: %s\n\n", 
                                    successCount, 
                                    result.getInetAddress().getHostAddress(),
                                    result.getBssid()));
                            }
                        }
                        
                        if (successCount > 0) {
                            progressMessage.setText(String.format("配网成功！(%d个设备)", successCount));
                            resultView.setText(successDevices.toString().trim());
                            resultView.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                            resultView.setVisibility(View.VISIBLE);
                            
                            // 延迟关闭对话框并显示成功对话框
                            new Handler().postDelayed(() -> {
                                if (dialog.isShowing()) {
                                    dialog.dismiss();
                                    // 只为第一个成功的设备显示配置对话框
                                    String firstDeviceIp = results.get(0).getInetAddress().getHostAddress();
                                    showSmartConfigSuccessDialog(firstDeviceIp);
                                }
                            }, 2000);
                        }
                    });
                    
                } catch (Exception e) {
                    Log.e(TAG, "SmartConfig执行异常", e);
                    runOnUiThread(() -> {
                        progressMessage.setText("配网异常");
                        resultView.setText("配网过程出现异常: " + e.getMessage() + 
                                          "\n\n请尝试：\n1. 重新启动应用\n2. 检查网络权限\n3. 确认WiFi连接正常");
                        resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                        resultView.setVisibility(View.VISIBLE);
                        isSmartConfigRunning = false;
                        updateSmartConfigButtonText();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "启动SmartConfig失败", e);
            progressMessage.setText("启动失败");
            resultView.setText("启动配网失败: " + e.getMessage());
            resultView.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            resultView.setVisibility(View.VISIBLE);
            isSmartConfigRunning = false;
            updateSmartConfigButtonText();
        }
    }

    private void showStopSmartConfigDialog() {
        new AlertDialog.Builder(this)
                .setTitle("停止配网")
                .setMessage("SmartConfig配网正在进行中，是否要停止？")
                .setPositiveButton("停止", (dialog, which) -> stopSmartConfig())
                .setNegativeButton("取消", null)
                .show();
    }

    private void stopSmartConfig() {
        if (esptouchTask != null && isSmartConfigRunning) {
            esptouchTask.interrupt();
            esptouchTask = null;
            isSmartConfigRunning = false;
            updateSmartConfigButtonText();
            showToast("SmartConfig已停止");
        }
    }

    private void updateSmartConfigButtonText() {
        if (btnSmartConfig != null) {
            btnSmartConfig.setText(isSmartConfigRunning ? "停止" : "配网");
        }
    }

    private void showSmartConfigSuccessDialog(String deviceIp) {
        new AlertDialog.Builder(this)
                .setTitle("配网成功")
                .setMessage("ESP32设备已成功连接到WiFi网络！\n\n设备IP地址: " + deviceIp + 
                           "\n\n是否要为此设备创建端口转发配置？")
                .setPositiveButton("创建配置", (dialog, which) -> {
                    // 预填充设备IP创建配置
                    ForwardConfig newConfig = new ForwardConfig("ESP32设备", PortForwarder.PROTOCOL_TCP, 8080, deviceIp, 80);
                    showConfigEditDialog(newConfig);
                })
                .setNegativeButton("稍后再说", null)
                .show();
    }

}