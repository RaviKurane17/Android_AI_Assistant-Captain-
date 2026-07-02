package com.aiphone.agent.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Secure wrapper around SharedPreferences using AndroidX EncryptedSharedPreferences.
 * Used for storing sensitive data like API keys, PINs, etc.
 * Falls back to regular SharedPreferences if encryption fails.
 */
public class SecurePrefsManager {

    private static final String TAG = "SecurePrefsManager";
    private static final String SECURE_PREFS_NAME = "SecureAgentPrefs";
    private static SharedPreferences securePrefs;

    public static SharedPreferences getSecurePrefs(Context context) {
        if (securePrefs == null) {
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                securePrefs = EncryptedSharedPreferences.create(
                        context,
                        SECURE_PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to regular prefs", e);
                securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE);
            }
        }
        return securePrefs;
    }

    // Convenience methods for sensitive keys
    public static void saveApiKey(Context context, String key) {
        getSecurePrefs(context).edit().putString("groq_api_key", key).apply();
    }

    public static String getApiKey(Context context) {
        return getSecurePrefs(context).getString("groq_api_key", "");
    }

    public static void savePicovoiceKey(Context context, String key) {
        getSecurePrefs(context).edit().putString("picovoice_access_key", key).apply();
    }

    public static String getPicovoiceKey(Context context) {
        return getSecurePrefs(context).getString("picovoice_access_key", "");
    }

    public static void savePin(Context context, String pin) {
        getSecurePrefs(context).edit().putString("saved_pin", pin).apply();
    }

    public static String getPin(Context context) {
        return getSecurePrefs(context).getString("saved_pin", "");
    }
}
