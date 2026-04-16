package com.aucneon.portforwarder;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Locale;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转发配置列表适配器
 */
public class ForwardAdapter extends RecyclerView.Adapter<ForwardAdapter.ViewHolder> {
    
    private List<ForwardConfig> configs;
    private OnConfigActionListener listener;
    private OnItemLongClickListener longClickListener;
    private Context context;
    private final Map<Integer, TrafficSample> trafficSamples = new HashMap<>();

    private static class TrafficSample {
        final long timestampMs;
        final long upstreamBytes;
        final long downstreamBytes;

        TrafficSample(long timestampMs, long upstreamBytes, long downstreamBytes) {
            this.timestampMs = timestampMs;
            this.upstreamBytes = upstreamBytes;
            this.downstreamBytes = downstreamBytes;
        }
    }

    public interface OnConfigActionListener {
        void onStartConfig(ForwardConfig config);
        void onStopConfig(ForwardConfig config);
        void onEditConfig(ForwardConfig config);
        void onDeleteConfig(ForwardConfig config);
        void onToggleAutoStart(ForwardConfig config, boolean autoStart);
        void onToggleEnabled(ForwardConfig config, boolean enabled);
    }

    public interface OnItemLongClickListener {
        void onLongClick(ForwardConfig config);
    }
    
    public ForwardAdapter(Context context, List<ForwardConfig> configs) {
        this.context = context;
        this.configs = configs;
    }
    
    public void setOnConfigActionListener(OnConfigActionListener listener) {
        this.listener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.longClickListener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_forward_config, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ForwardConfig config = configs.get(position);
        holder.bind(config);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            // Partial update - only refresh running status
            ForwardConfig config = configs.get(position);
            holder.updateStatus(config);
        }
    }
    
    @Override
    public int getItemCount() {
        return configs.size();
    }
    
    public void updateConfigs(List<ForwardConfig> newConfigs) {
        this.configs = newConfigs;
        trafficSamples.clear();
        notifyDataSetChanged();
    }
    
    public void notifyConfigChanged(int configId) {
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).id == configId) {
                notifyItemChanged(i);
                break;
            }
        }
    }
    
    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tvName;
        private TextView tvRule;
        private TextView tvStatus;
        private SwitchCompat swEnabled;
        private SwitchCompat swAutoStart;
        private Button btnStart;
        private Button btnStop;
        private Button btnEdit;
        private Button btnDelete;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            
            tvName = itemView.findViewById(R.id.tv_config_name);
            tvRule = itemView.findViewById(R.id.tv_config_rule);
            tvStatus = itemView.findViewById(R.id.tv_config_status);
            swEnabled = itemView.findViewById(R.id.sw_enabled);
//            swAutoStart = itemView.findViewById(R.id.sw_auto_start);
            btnStart = itemView.findViewById(R.id.btn_start_config);
            btnStop = itemView.findViewById(R.id.btn_stop_config);
            btnEdit = itemView.findViewById(R.id.btn_edit_config);
            btnDelete = itemView.findViewById(R.id.btn_delete_config);
        }
        
        public void bind(ForwardConfig config) {
            // 设置基本信息
            tvName.setText(config.name != null ? config.name : context.getString(R.string.unnamed));
            tvRule.setText(config.getForwardRule());

            // 更新运行状态
            updateStatus(config);

            // 设置开关状态
            swEnabled.setOnCheckedChangeListener(null);
//            swAutoStart.setOnCheckedChangeListener(null);
            
            swEnabled.setChecked(config.enabled);
//            swAutoStart.setChecked(config.autoStart);
//            swAutoStart.setEnabled(config.enabled);
            
            // 设置监听器
            swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) {
                    listener.onToggleEnabled(config, isChecked);
                }
            });
            
//            swAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
//                if (listener != null) {
//                    listener.onToggleAutoStart(config, isChecked);
//                }
//            });
            
            btnStart.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStartConfig(config);
                }
            });
            
            btnStop.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onStopConfig(config);
                }
            });
            
            btnEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEditConfig(config);
                }
            });
            
            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteConfig(config);
                }
            });

            // 长按显示连接/测试选项
            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onLongClick(config);
                }
                return true;
            });

            // 禁用状态的处理
            if (!config.enabled) {
                itemView.setAlpha(0.5f);
            } else {
                itemView.setAlpha(1.0f);
            }
        }

        public void updateStatus(ForwardConfig config) {
            // 检查转发状态
            boolean isRunning = false;
            int sessionId = -1;
            Map<Integer, PortForwarder.ForwardInfo> activeForwards = PortForwarder.getAllForwards();
            for (PortForwarder.ForwardInfo info : activeForwards.values()) {
                if (info.listenPort == config.listenPort &&
                    info.protocol == config.protocol) {
                    isRunning = true;
                    sessionId = info.sessionId;
                    break;
                }
            }

            // 更新状态显示 (iOS style)
            if (isRunning) {
                String speedText = updateTrafficSpeed(config, sessionId);
                tvStatus.setText(context.getString(R.string.forward_running, sessionId) + "  " + speedText);
                tvStatus.setTextColor(ContextCompat.getColor(context, android.R.color.white));
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
                btnStart.setBackgroundResource(R.drawable.button_start_running_small);
            } else {
                tvStatus.setText(context.getString(R.string.forward_stopped));
                tvStatus.setTextColor(ContextCompat.getColor(context, android.R.color.white));
                btnStart.setEnabled(config.enabled);
                btnStop.setEnabled(false);
                btnStart.setBackgroundResource(R.drawable.button_start_small);
                trafficSamples.remove(config.id);
            }
        }

        private String updateTrafficSpeed(ForwardConfig config, int sessionId) {
            PortForwarder.TrafficInfo trafficInfo = PortForwarder.getForwardTraffic(sessionId);
            long nowMs = System.currentTimeMillis();

            TrafficSample previous = trafficSamples.get(config.id);
            trafficSamples.put(config.id, new TrafficSample(
                    nowMs,
                    trafficInfo.upstreamBytes,
                    trafficInfo.downstreamBytes
            ));

            if (previous == null) {
                return "↑ --/s  ↓ --/s";
            }

            long dt = nowMs - previous.timestampMs;
            if (dt <= 0) {
                return "↑ --/s  ↓ --/s";
            }

            long upDelta = Math.max(0, trafficInfo.upstreamBytes - previous.upstreamBytes);
            long downDelta = Math.max(0, trafficInfo.downstreamBytes - previous.downstreamBytes);

            double upBytesPerSec = upDelta * 1000.0 / dt;
            double downBytesPerSec = downDelta * 1000.0 / dt;

                return String.format(
                    Locale.getDefault(),
                    "↑ %s/s  ↓ %s/s",
                    formatRate(upBytesPerSec),
                    formatRate(downBytesPerSec)
                );
        }

        private String formatRate(double bytesPerSec) {
            if (bytesPerSec < 1024) {
                return String.format(Locale.getDefault(), "%.0f B", bytesPerSec);
            }
            if (bytesPerSec < 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.1f KB", bytesPerSec / 1024.0);
            }
            return String.format(Locale.getDefault(), "%.2f MB", bytesPerSec / (1024.0 * 1024.0));
        }
    }
} 