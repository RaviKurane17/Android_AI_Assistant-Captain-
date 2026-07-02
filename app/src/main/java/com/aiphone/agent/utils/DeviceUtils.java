package com.aiphone.agent.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.UUID;

public class DeviceUtils {
    private static final String PREFS_NAME = "AgentPrefs";
    private static final String KEY_DEVICE_ID = "device_id";

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }

    public static String getDeviceModel() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }
}
