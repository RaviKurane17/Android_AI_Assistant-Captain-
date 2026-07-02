package com.aiphone.agent.services;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;

public class NotificationCaptureService extends NotificationListenerService {
    private static final String TAG = "NotifCaptureService";
    public static Map<String, android.app.PendingIntent> replyIntents = new HashMap<>();
    public static Map<String, android.app.RemoteInput> remoteInputs = new HashMap<>();
    public static final List<String> recentNotificationTexts = Collections.synchronizedList(new LinkedList<>());
    private DatabaseReference dbRef;
    private String deviceId;

    @Override
    public void onCreate() {
        super.onCreate();
        dbRef = FirebaseDatabase.getInstance().getReference();
        deviceId = DeviceUtils.getDeviceId(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String packageName = sbn.getPackageName();
        if (isTargetApp(packageName)) {
            Notification notification = sbn.getNotification();
            String title = notification.extras.getString(Notification.EXTRA_TITLE);
            String text = notification.extras.getString(Notification.EXTRA_TEXT);

            if (title != null || text != null) {
                Log.d(TAG, "Captured notification from " + packageName + ": " + title + " - " + text);
                
                String appName = packageName;
                if (packageName.contains("whatsapp")) appName = "WhatsApp";
                else if (packageName.contains("telegram")) appName = "Telegram";
                else if (packageName.contains("instagram")) appName = "Instagram";
                else if (packageName.contains("gm")) appName = "Gmail";
                
                String notifStr = appName + " from " + title + " says " + text;
                synchronized(recentNotificationTexts) {
                    recentNotificationTexts.add(0, notifStr);
                    if (recentNotificationTexts.size() > 5) {
                        recentNotificationTexts.remove(5);
                    }
                }
                
                extractAndSaveReplyAction(notification, packageName, title);
                saveNotificationToDatabase(packageName, title, text);
            }
        }
    }

    public static String getRecentNotifications() {
        synchronized(recentNotificationTexts) {
            if (recentNotificationTexts.isEmpty()) return "You have no new notifications.";
            return "You have " + recentNotificationTexts.size() + " recent notifications. " + String.join(". ", recentNotificationTexts);
        }
    }

    private void extractAndSaveReplyAction(Notification notification, String packageName, String title) {
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action.getRemoteInputs() != null) {
                    for (android.app.RemoteInput remoteInput : action.getRemoteInputs()) {
                        if (action.title != null && action.title.toString().toLowerCase().contains("reply")) {
                            String key = (packageName + "_" + title).toLowerCase();
                            replyIntents.put(key, action.actionIntent);
                            remoteInputs.put(key, remoteInput);
                        }
                    }
                }
            }
        }
    }

    public static boolean replyTo(String packageName, String title, String message) {
        String key = (packageName + "_" + title).toLowerCase();
        android.app.PendingIntent intent = replyIntents.get(key);
        android.app.RemoteInput remoteInput = remoteInputs.get(key);
        if (intent != null && remoteInput != null) {
            android.os.Bundle localBundle = new android.os.Bundle();
            localBundle.putCharSequence(remoteInput.getResultKey(), message);
            android.content.Intent fillInIntent = new android.content.Intent();
            android.app.RemoteInput.addResultsToIntent(new android.app.RemoteInput[]{remoteInput}, fillInIntent, localBundle);
            try {
                intent.send(null, 0, fillInIntent);
                return true;
            } catch (android.app.PendingIntent.CanceledException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private boolean isTargetApp(String packageName) {
        return packageName.equals("com.whatsapp") ||
               packageName.equals("org.telegram.messenger") ||
               packageName.equals("com.instagram.android") ||
               packageName.equals("com.google.android.gm");
    }

    private void saveNotificationToDatabase(String packageName, String title, String text) {
        Map<String, Object> notifData = new HashMap<>();
        notifData.put("deviceId", deviceId);
        notifData.put("package", packageName);
        notifData.put("title", title);
        notifData.put("text", text);
        notifData.put("timestamp", System.currentTimeMillis());

        dbRef.child("notifications").push().setValue(notifData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Notification saved"))
                .addOnFailureListener(e -> Log.e(TAG, "Error saving notification", e));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Optional: handle removal
    }
}
