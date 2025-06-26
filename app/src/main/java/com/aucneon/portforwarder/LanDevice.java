package com.aucneon.portforwarder;

/**
 * 局域网设备信息
 */
public class LanDevice {
    public String ipAddress;
    public String hostname;
    public String macAddress;
    public boolean isReachable;
    public long responseTime;
    
    public LanDevice(String ipAddress) {
        this.ipAddress = ipAddress;
        this.hostname = "未知";
        this.macAddress = "未知";
        this.isReachable = false;
        this.responseTime = -1;
    }
    
    public LanDevice(String ipAddress, String hostname, String macAddress, boolean isReachable, long responseTime) {
        this.ipAddress = ipAddress;
        this.hostname = hostname != null ? hostname : "未知";
        this.macAddress = macAddress != null ? macAddress : "未知";
        this.isReachable = isReachable;
        this.responseTime = responseTime;
    }
    
    @Override
    public String toString() {
        return String.format("LanDevice{ip='%s', hostname='%s', mac='%s', reachable=%s, time=%dms}",
                ipAddress, hostname, macAddress, isReachable, responseTime);
    }
} 