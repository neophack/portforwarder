package com.aucneon.portforwarder;

import java.io.Serializable;

/**
 * 转发配置类
 * 用于保存和加载转发设置
 */
public class ForwardConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public int id;
    public String name;
    public int protocol;
    public int listenPort;
    public String targetHost;
    public int targetPort;
    public boolean autoStart;
    public boolean enabled;
    public long createTime;
    public long lastModifyTime;
    
    public ForwardConfig() {
        this.createTime = System.currentTimeMillis();
        this.lastModifyTime = this.createTime;
        this.enabled = true;
        this.autoStart = false;
    }
    
    public ForwardConfig(String name, int protocol, int listenPort, String targetHost, int targetPort) {
        this();
        this.name = name;
        this.protocol = protocol;
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }
    
    public String getProtocolName() {
        return protocol == PortForwarder.PROTOCOL_TCP ? "TCP" : "UDP";
    }
    
    public String getForwardRule() {
        return String.format("%s %d -> %s:%d", getProtocolName(), listenPort, targetHost, targetPort);
    }
    
    @Override
    public String toString() {
        return String.format("[%s] %s", name != null ? name : "未命名", getForwardRule());
    }
    
    public void updateModifyTime() {
        this.lastModifyTime = System.currentTimeMillis();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ForwardConfig that = (ForwardConfig) obj;
        return id == that.id;
    }
    
    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }
} 