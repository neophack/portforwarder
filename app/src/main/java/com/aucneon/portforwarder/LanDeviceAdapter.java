package com.aucneon.portforwarder;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/**
 * 局域网设备列表适配器
 */
public class LanDeviceAdapter extends RecyclerView.Adapter<LanDeviceAdapter.ViewHolder> {
    
    private List<LanDevice> devices;
    private OnDeviceClickListener listener;
    private Context context;
    
    public interface OnDeviceClickListener {
        void onDeviceClick(LanDevice device);
    }
    
    public LanDeviceAdapter(Context context, List<LanDevice> devices) {
        this.context = context;
        this.devices = devices;
    }
    
    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lan_device, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        LanDevice device = devices.get(position);
        holder.bind(device);
    }
    
    @Override
    public int getItemCount() {
        return devices.size();
    }
    
    public void updateDevices(List<LanDevice> newDevices) {
        this.devices = newDevices;
        notifyDataSetChanged();
    }
    
    public void addDevice(LanDevice device) {
        // 检查是否已存在相同IP的设备
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).ipAddress.equals(device.ipAddress)) {
                devices.set(i, device); // 更新现有设备信息
                notifyItemChanged(i);
                return;
            }
        }
        // 新设备，添加到列表
        devices.add(device);
        notifyItemInserted(devices.size() - 1);
    }
    
    class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tvDeviceIp;
        private TextView tvDeviceStatus;
        private TextView tvDeviceHostname;
        private TextView tvDeviceMac;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            
            tvDeviceIp = itemView.findViewById(R.id.tv_device_ip);
            tvDeviceStatus = itemView.findViewById(R.id.tv_device_status);
            tvDeviceHostname = itemView.findViewById(R.id.tv_device_hostname);
            tvDeviceMac = itemView.findViewById(R.id.tv_device_mac);
            
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        listener.onDeviceClick(devices.get(position));
                    }
                }
            });
        }
        
        public void bind(LanDevice device) {
            tvDeviceIp.setText(device.ipAddress);
            
            // 设置状态
            if (device.isReachable) {
                tvDeviceStatus.setText("在线");
                tvDeviceStatus.setBackgroundResource(R.drawable.button_start_small);
                if (device.responseTime > 0) {
                    tvDeviceStatus.setText(String.format("在线 (%dms)", device.responseTime));
                }
            } else {
                tvDeviceStatus.setText("离线");
                tvDeviceStatus.setBackgroundResource(R.drawable.button_stop_small);
            }
            
            // 设置主机名
            if (device.hostname != null && !device.hostname.equals("未知") && !device.hostname.isEmpty()) {
                tvDeviceHostname.setText("主机名: " + device.hostname);
                tvDeviceHostname.setVisibility(View.VISIBLE);
            } else {
                tvDeviceHostname.setText("主机名: 未知");
                tvDeviceHostname.setVisibility(View.VISIBLE);
            }
            
            // 设置MAC地址
            if (device.macAddress != null && !device.macAddress.equals("未知") && !device.macAddress.isEmpty()) {
                tvDeviceMac.setText("MAC: " + device.macAddress);
                tvDeviceMac.setVisibility(View.VISIBLE);
            } else {
                // 隐藏MAC地址显示，因为无法获取
                tvDeviceMac.setVisibility(View.GONE);
            }
        }
    }
} 