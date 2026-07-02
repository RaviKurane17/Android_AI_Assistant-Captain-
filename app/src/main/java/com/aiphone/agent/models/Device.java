package com.aiphone.agent.models;

public class Device {
    public String deviceId;
    public String model;
    public String status; // online, offline
    public long lastSeen;
    public int batteryLevel;

    public Device() {}
}
