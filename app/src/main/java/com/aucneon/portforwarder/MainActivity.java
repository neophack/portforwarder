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
import android.net.Uri;
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
import com.aucneon.portforwarder.DeviceUtils;

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


    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PortForwarderService.PortForwarderBinder binder = (PortForwarderService.PortForwarderBinder) service;
            portForwarderService = binder.getService();
            isServiceBound = true;
            updateServiceStatus();
            Log.d(TAG, "Service connected");

            // 如果已启用"开机启动服务"，则在应用启动并成功绑定服务后自动执行"一键启动"
            try {
                if (configManager != null && configManager.isAutoStartService()) {
                    Log.d(TAG, "AutoStartService is enabled, running startAllEnabledConfigs()");
                    // 由于此回调在主线程执行，直接调用即可
                    startAllEnabledConfigs();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to auto start enabled configs", e);
            }
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
        
        // 设置屏幕方向：手机竖屏禁止旋转，平板可以旋转
        DeviceUtils.setScreenOrientation(this);
        
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
        forwardAdapter.setOnItemLongClickListener(config -> connectConfig(config));
        
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
                showToast(getString(R.string.permission_required));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        if (requestCode == ConfigExporter.REQUEST_EXPORT) {
            List<ForwardConfig> configs = configManager.loadForwardConfigs();
            boolean success = ConfigExporter.exportToUri(this, uri, configs);
            showToast(success ? getString(R.string.export_success_format, configs.size()) : getString(R.string.export_failed));
        } else if (requestCode == ConfigExporter.REQUEST_IMPORT) {
            List<ForwardConfig> imported = ConfigExporter.importFromUri(this, uri);
            if (imported != null) {
                List<ForwardConfig> valid = ConfigExporter.validateConfigs(imported);
                if (valid.isEmpty()) {
                    showToast(getString(R.string.import_no_valid));
                    return;
                }
                // Show import confirmation dialog
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.import_config))
                        .setMessage(getString(R.string.import_found_format, valid.size()))
                        .setPositiveButton(getString(R.string.import_merge), (d, w) -> {
                            for (ForwardConfig config : valid) {
                                config.id = 0; // Reset ID for new generation
                                configManager.addForwardConfig(config);
                            }
                            loadConfigs();
                            showToast(getString(R.string.imported_count_format, valid.size()));
                        })
                        .setNegativeButton(getString(R.string.import_replace), (d, w) -> {
                            PortForwarder.stopAllForwards();
                            configManager.clearAllConfigs();
                            for (ForwardConfig config : valid) {
                                config.id = 0;
                                configManager.addForwardConfig(config);
                            }
                            loadConfigs();
                            showToast(getString(R.string.replaced_count_format, valid.size()));
                        })
                        .setNeutralButton(getString(R.string.cancel), null)
                        .show();
            } else {
                showToast(getString(R.string.import_failed_format));
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
            showToast(isChecked ? getString(R.string.auto_start_enabled) : getString(R.string.auto_start_disabled));
        });
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

    private void stopAllForwards() {
        int count = PortForwarder.getActiveSessionCount();
        if (count == 0) {
            showToast(getString(R.string.no_active_sessions));
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
                        showToast(getString(R.string.stopped_all_format, count));
                        refreshStatus();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error stopping all forwards", e);
                runOnUiThread(() -> {
                    // 检查Activity是否仍然有效
                    if (!isDestroyed() && !isFinishing()) {
                        showToast(getString(R.string.stop_error));
                    }
                });
            }
        }).start();
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
                return getString(R.string.error_socket_create);
            case PortForwarder.ERROR_BIND_FAILED:
                return getString(R.string.error_bind_failed);
            case PortForwarder.ERROR_LISTEN_FAILED:
                return getString(R.string.error_listen_failed);
            case PortForwarder.ERROR_DNS_FAILED:
                return getString(R.string.error_dns_failed);
            default:
                return getString(R.string.error_unknown, errorCode);
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
                forwardAdapter.notifyItemRangeChanged(0, forwardAdapter.getItemCount(), "status");
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
            tvServiceStatus.setText(getString(R.string.service_running));
            tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            tvServiceStatus.setText(getString(R.string.service_not_started));
            tvServiceStatus.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
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
        tvConfigCount.setText(getString(R.string.config_count_format, total, enabled));
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
            String allIps = NetworkUtils.getAllIpAddressesString(this);
            
            // 在主线程更新UI
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || tvDeviceIp == null) {
                    return;
                }
                
                if (allIps != null && !allIps.equals(getString(R.string.no_ip_found))) {
                    tvDeviceIp.setText(allIps);
                    tvDeviceIp.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
                } else {
                    tvDeviceIp.setText(getString(R.string.ip_fetch_failed));
                    tvDeviceIp.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                }
                Log.d(TAG, "Device IPs updated: " + allIps);
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

        showToast(getString(R.string.start_complete_format, startedCount, failedCount));
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
                .setTitle(config != null ? getString(R.string.edit_config) : getString(R.string.add_config))
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
                if (name.isEmpty()) name = getString(R.string.unnamed_config);

                int protocol = (rgProtocolEdit.getCheckedRadioButtonId() == R.id.rb_tcp_edit) ?
                        PortForwarder.PROTOCOL_TCP : PortForwarder.PROTOCOL_UDP;
                int listenPort = Integer.parseInt(etListenPortEdit.getText().toString().trim());
                String targetHost = etTargetHostEdit.getText().toString().trim();
                int targetPort = Integer.parseInt(etTargetPortEdit.getText().toString().trim());

                // 验证输入
                if (targetHost.isEmpty()) {
                    showToast(getString(R.string.input_target_host));
                    return;
                }

                if (listenPort < 1 || listenPort > 65535 || targetPort < 1 || targetPort > 65535) {
                    showToast(getString(R.string.port_range_error));
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
                showToast(config != null ? getString(R.string.config_updated) : getString(R.string.config_added));

            } catch (NumberFormatException e) {
                showToast(getString(R.string.invalid_port));
            } catch (Exception e) {
                showToast(getString(R.string.save_failed, e.getMessage()));
                Log.e(TAG, "Failed to save config", e);
            }
        });

        // 取消按钮
        dialogView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // 新增方法：测试配置连通性
    private void testConfig(ForwardConfig config) {
        showToast(getString(R.string.testing_config, config.name));
        ProtocolTester.testConfig(config, (cfg, result) -> {
            String message = cfg.name + ": " + result.message;
            if (result.detectedService != null) {
                message += "\n" + getString(R.string.detected_service, result.detectedService);
            }
            new AlertDialog.Builder(this)
                    .setTitle(result.success ? getString(R.string.test_success) : getString(R.string.test_failed))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show();
        });
    }

    // 新增方法：连接配置（提供多种连接方式）
    private void connectConfig(ForwardConfig config) {
        // Determine available connection types
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (ProtocolTester.isHttpService(config)) {
            options.add(getString(R.string.http_preview));
            actions.add(() -> WebPreviewActivity.launch(this, config));
            options.add(getString(R.string.open_in_browser));
            actions.add(() -> WebPreviewActivity.openInBrowser(this, config));
        }

        if (ProtocolTester.isTelnetService(config)) {
            options.add(getString(R.string.telnet_connect));
            actions.add(() -> TelnetActivity.launch(this, config));
        }

        if (ProtocolTester.isSshService(config)) {
            options.add(getString(R.string.ssh_connect));
            actions.add(() -> {
                if (SshLauncher.isSshClientAvailable(this)) {
                    SshLauncher.launchSsh(this, config, null);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.no_ssh_client))
                            .setMessage(getString(R.string.ssh_client_required))
                            .setPositiveButton(getString(R.string.go_install), (d, w) -> SshLauncher.openPlayStoreForSshClient(this))
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show();
                }
            });
        }

        // Always offer Telnet as a generic option for TCP
        if (config.protocol == PortForwarder.PROTOCOL_TCP && !ProtocolTester.isTelnetService(config)) {
            options.add(getString(R.string.telnet_connect));
            actions.add(() -> TelnetActivity.launch(this, config));
        }

        // Always offer protocol test
        options.add(getString(R.string.protocol_test));
        actions.add(() -> testConfig(config));

        if (options.size() == 1) {
            // Only test available, just run it
            actions.get(0).run();
        } else {
            String[] items = options.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.connect_test_title, config.name))
                    .setItems(items, (d, which) -> actions.get(which).run())
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        }
    }

    // 新增方法：显示设置对话框
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.settings));
        
        String[] options = {getString(R.string.clear_all_configs), getString(R.string.export_configs), getString(R.string.import_configs), getString(R.string.about_app), getString(R.string.exit_app)};
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    showClearConfigsDialog();
                    break;
                case 1:
                    ConfigExporter.startExport(MainActivity.this);
                    break;
                case 2:
                    ConfigExporter.startImport(MainActivity.this);
                    break;
                case 3:
                    showAboutDialog();
                    break;
                case 4:
                    // 退出应用：先停止所有转发，再停止服务，最后关闭所有 Activity
                    AlertDialog exitDialog = new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.exit_app))
                            .setMessage(getString(R.string.exit_confirm))
                            .setPositiveButton(getString(R.string.exit), (d, w) -> {
                                // Stop all forwards
                                new Thread(() -> {
                                    PortForwarder.stopAllForwards();
                                    runOnUiThread(() -> {
                                        // Stop the service
                                        Intent serviceIntent = new Intent(MainActivity.this, PortForwarderService.class);
                                        stopService(serviceIntent);
                                        // Unbind
                                        if (isServiceBound) {
                                            try {
                                                unbindService(serviceConnection);
                                                isServiceBound = false;
                                            } catch (Exception e) {
                                                Log.e(TAG, "Error unbinding service", e);
                                            }
                                        }
                                        finishAffinity();
                                    });
                                }).start();
                            })
                            .setNegativeButton(getString(R.string.cancel), null)
                            .create();

                    // 在显示后修改按钮文字颜色，确保在不同主题下可见
                    exitDialog.setOnShowListener(d -> {
                        exitDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                        exitDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                                .setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                    });

                    exitDialog.show();
                    break;
            }
        });
        
        builder.show();
    }

    // 新增方法：显示清除配置确认对话框
    private void showClearConfigsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_all_configs))
                .setMessage(getString(R.string.clear_confirm))
                .setPositiveButton(getString(R.string.ok), (dialog, which) -> {
                    PortForwarder.stopAllForwards();
                    configManager.clearAllConfigs();
                    loadConfigs();
                    showToast(getString(R.string.all_configs_cleared));
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    // 新增方法：显示关于对话框
    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.about_title))
                .setMessage(getString(R.string.about_message, "3.0"))
                .setPositiveButton(getString(R.string.ok), null)
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
                showToast(getString(R.string.start_success, config.name));
                refreshStatus();
            } else {
                showToast(getString(R.string.start_failed, getErrorMessage(sessionId)));
            }
        } catch (Exception e) {
            showToast(getString(R.string.start_failed, e.getMessage()));
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
                showToast(getString(R.string.stopped, config.name));
                refreshStatus();
            } else {
                showToast(getString(R.string.stop_failed));
            }
        } else {
            showToast(getString(R.string.no_running_forward));
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
                .setTitle(getString(R.string.delete_config))
                .setMessage(getString(R.string.delete_confirm, config.name))
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    // 先停止相关转发
                    onStopConfig(config);
                    
                    // 删除配置
                    configManager.deleteForwardConfig(config.id);
                    loadConfigs();
                    showToast(getString(R.string.deleted_config, config.name));
                })
                .setNegativeButton(getString(R.string.cancel), null)
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
                    tvScanStatus.setText(getString(R.string.scanning_all_networks));
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
                    tvScanStatus.setText(getString(R.string.found_devices_scanning, devices.size()));
                });
            }

            @Override
            public void onScanProgress(int current, int total) {
                runOnUiThread(() -> {
                    double progress = (double) current / total * 100;
                    tvScanStatus.setText(getString(R.string.scan_progress_format,
                            progress, current, total, devices.size()));
                });
            }

            @Override
            public void onScanFinished(List<LanDevice> allDevices) {
                runOnUiThread(() -> {
                    pbScanning.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    
                    if (allDevices.size() > 0) {
                        // 统计不同网络的设备数量
                        int wifiDevices = 0, homeDevices = 0, enterpriseDevices = 0, privateDevices = 0, otherDevices = 0;
                        for (LanDevice device : allDevices) {
                            if (device.hostname.contains("[" + getString(R.string.network_wifi) + "]")) {
                                wifiDevices++;
                            } else if (device.hostname.contains("[" + getString(R.string.network_home) + "]")) {
                                homeDevices++;
                            } else if (device.hostname.contains("[" + getString(R.string.network_enterprise) + "]")) {
                                enterpriseDevices++;
                            } else if (device.hostname.contains("[" + getString(R.string.network_private) + "]")) {
                                privateDevices++;
                            } else {
                                otherDevices++;
                            }
                        }
                        
                        StringBuilder statusText = new StringBuilder();
                        statusText.append(getString(R.string.scan_complete_format, allDevices.size()));
                        if (wifiDevices > 0) statusText.append(getString(R.string.wifi_network_count, wifiDevices));
                        if (homeDevices > 0) statusText.append(getString(R.string.home_network_count, homeDevices));
                        if (enterpriseDevices > 0) statusText.append(getString(R.string.enterprise_network_count, enterpriseDevices));
                        if (privateDevices > 0) statusText.append(getString(R.string.private_network_count, privateDevices));
                        if (otherDevices > 0) statusText.append(getString(R.string.other_network_count, otherDevices));
                        statusText.append(getString(R.string.click_to_select_ip));
                        
                        tvScanStatus.setText(statusText.toString());
                    } else {
                        tvScanStatus.setText(getString(R.string.scan_no_devices));
                    }
                });
            }

            @Override
            public void onScanError(String error) {
                runOnUiThread(() -> {
                    pbScanning.setVisibility(View.GONE);
                    btnRescan.setEnabled(true);
                    tvScanStatus.setText(getString(R.string.scan_failed, error));
                    showToast(getString(R.string.scan_failed, error));
                });
            }
        });

        // 设备点击事件
        deviceAdapter.setOnDeviceClickListener(device -> {
            targetHostEdit.setText(device.ipAddress);
            lanScanner.cleanup();
            scanDialog.dismiss();
            showToast(getString(R.string.selected_ip, device.ipAddress));
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
            showToast(getString(R.string.enable_wifi_first));
            return;
        }

        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
            showToast(getString(R.string.connect_wifi_first));
            return;
        }

        String ssid = wifiInfo.getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>") || ssid.isEmpty()) {
            showToast(getString(R.string.cannot_get_wifi_name));
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

        tvCurrentSsid.setText(getString(R.string.current_wifi, finalSsid));
        
        // 显示MAC地址
        String currentBssid = wifiInfo.getBSSID();
        if (currentBssid != null && !currentBssid.equals("<unknown ssid>") && !currentBssid.isEmpty()) {
            tvCurrentBssid.setText(getString(R.string.mac_address_format, currentBssid.toUpperCase()));
            tvCurrentBssid.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
        } else {
            tvCurrentBssid.setText(getString(R.string.mac_address_unavailable));
            tvCurrentBssid.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
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
            tvProgressMessage.setText(getString(R.string.smart_config_in_progress));
            tvConfigResult.setVisibility(View.GONE);
        };

        Runnable hideProgress = () -> {
            contentView.setVisibility(View.VISIBLE);
            progressView.setVisibility(View.GONE);
        };

        btnStartConfig.setOnClickListener(v -> {
            String password = etWifiPassword.getText().toString();
            if (password.trim().isEmpty()) {
                showToast(getString(R.string.input_wifi_password));
                return;
            }
            
            String deviceCountStr = etDeviceCount.getText().toString().trim();
            int deviceCount;
            try {
                deviceCount = deviceCountStr.isEmpty() ? 1 : Integer.parseInt(deviceCountStr);
                if (deviceCount < 1 || deviceCount > 10) {
                    showToast(getString(R.string.device_count_range));
                    return;
                }
            } catch (NumberFormatException e) {
                showToast(getString(R.string.invalid_device_count));
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
                progressMessage.setText(getString(R.string.wifi_error));
                resultView.setText(getString(R.string.wifi_error_detail));
                resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
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
                            progressMessage.setText(getString(R.string.smart_config_cancelled));
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
                            progressMessage.setText(getString(R.string.smart_config_success));
                            resultView.setText(getString(R.string.device_ip_mac_format, deviceIp, bssid));
                            resultView.setTextColor(ContextCompat.getColor(MainActivity.this, android.R.color.holo_green_dark));
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
            progressMessage.setText(getString(R.string.broadcasting_config));

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
                            progressMessage.setText(getString(R.string.smart_config_failed));
                            resultView.setText(getString(R.string.smart_config_failed_detail));
                            resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                            resultView.setVisibility(View.VISIBLE);
                            return;
                        }
                        
                        if (results.isEmpty()) {
                            // 没有收到任何响应
                            progressMessage.setText(getString(R.string.smart_config_timeout));
                            resultView.setText(getString(R.string.smart_config_timeout_detail));
                            resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
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
                            progressMessage.setText(getString(R.string.smart_config_failed));
                            resultView.setText(getString(R.string.smart_config_retry_detail));
                            resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                            resultView.setVisibility(View.VISIBLE);
                            return;
                        }

                        // 统计并显示所有成功的设备
                        StringBuilder successDevices = new StringBuilder();
                        int successCount = 0;
                        for (IEsptouchResult result : results) {
                            if (result.isSuc()) {
                                successCount++;
                                successDevices.append(getString(R.string.device_result_format,
                                    successCount,
                                    result.getInetAddress().getHostAddress(),
                                    result.getBssid()));
                            }
                        }
                        
                        if (successCount > 0) {
                            progressMessage.setText(getString(R.string.smart_config_success_count, successCount));
                            resultView.setText(successDevices.toString().trim());
                            resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
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
                        progressMessage.setText(getString(R.string.smart_config_error));
                        resultView.setText(getString(R.string.smart_config_error_detail, e.getMessage()));
                        resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                        resultView.setVisibility(View.VISIBLE);
                        isSmartConfigRunning = false;
                        updateSmartConfigButtonText();
                    });
                }
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "启动SmartConfig失败", e);
            progressMessage.setText(getString(R.string.smart_config_start_failed));
            resultView.setText(getString(R.string.smart_config_start_failed_detail, e.getMessage()));
            resultView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            resultView.setVisibility(View.VISIBLE);
            isSmartConfigRunning = false;
            updateSmartConfigButtonText();
        }
    }

    private void showStopSmartConfigDialog() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.stop_smart_config))
                .setMessage(getString(R.string.stop_smart_config_confirm))
                .setPositiveButton(getString(R.string.stop), (dialog, which) -> stopSmartConfig())
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void stopSmartConfig() {
        if (esptouchTask != null && isSmartConfigRunning) {
            esptouchTask.interrupt();
            esptouchTask = null;
            isSmartConfigRunning = false;
            updateSmartConfigButtonText();
            showToast(getString(R.string.smart_config_stopped));
        }
    }

    private void updateSmartConfigButtonText() {
        if (btnSmartConfig != null) {
            btnSmartConfig.setText(isSmartConfigRunning ? getString(R.string.btn_smart_config_stop) : getString(R.string.btn_smart_config_start));
        }
    }

    private void showSmartConfigSuccessDialog(String deviceIp) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.smart_config_success_title))
                .setMessage(getString(R.string.smart_config_success_message, deviceIp))
                .setPositiveButton(getString(R.string.create_config), (dialog, which) -> {
                    // 预填充设备IP创建配置
                    ForwardConfig newConfig = new ForwardConfig(getString(R.string.esp32_device), PortForwarder.PROTOCOL_TCP, 8080, deviceIp, 80);
                    showConfigEditDialog(newConfig);
                })
                .setNegativeButton(getString(R.string.later), null)
                .show();
    }

}