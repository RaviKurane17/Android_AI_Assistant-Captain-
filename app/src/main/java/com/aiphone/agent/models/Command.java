package com.aiphone.agent.models;

public class Command {
    public String id;
    public String targetDeviceId;
    public String action;
    public String status; // pending, processing, success, failed
    public String number;
    public String name;
    public String message;
    public int value;
    public String time;
    public long timestamp;

    public Command() {}
}
