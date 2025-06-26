package com.aucneon.portforwarder;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;

/**
 * 转发配置列表适配器
 */
public class ForwardAdapter extends RecyclerView.Adapter<ForwardAdapter.ViewHolder> {
    
    private List<ForwardConfig> configs;
    private OnConfigActionListener listener;
    private Context context;
    
    public interface OnConfigActionListener {
        void onStartConfig(ForwardConfig config);
        void onStopConfig(ForwardConfig config);
        void onEditConfig(ForwardConfig config);
        void onDeleteConfig(ForwardConfig config);
        void onToggleAutoStart(ForwardConfig config, boolean autoStart);
        void onToggleEnabled(ForwardConfig config, boolean enabled);
    }
    
    public ForwardAdapter(Context context, List<ForwardConfig> configs) {
        this.context = context;
        this.configs = configs;
    }
    
    public void setOnConfigActionListener(OnConfigActionListener listener) {
        this.listener = listener;
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
    public int getItemCount() {
        return configs.size();
    }
    
    public void updateConfigs(List<ForwardConfig> newConfigs) {
        this.configs = newConfigs;
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
        private Switch swEnabled;
        private Switch swAutoStart;
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
            tvName.setText(config.name != null ? config.name : "未命名");
            tvRule.setText(config.getForwardRule());
            
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
            
            // 更新状态显示
            if (isRunning) {
                tvStatus.setText("运行中 [" + sessionId + "]");
                tvStatus.setTextColor(context.getResources().getColor(android.R.color.holo_green_dark));
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
            } else {
                tvStatus.setText("已停止");
                tvStatus.setTextColor(context.getResources().getColor(android.R.color.holo_red_dark));
                btnStart.setEnabled(config.enabled);
                btnStop.setEnabled(false);
            }
            
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
            
            // 禁用状态的处理
            if (!config.enabled) {
                itemView.setAlpha(0.6f);
            } else {
                itemView.setAlpha(1.0f);
            }
        }
    }
} 