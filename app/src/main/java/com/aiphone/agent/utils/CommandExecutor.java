package com.aiphone.agent.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.wifi.WifiManager;
import android.provider.AlarmClock;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Settings;
import android.app.ActivityManager;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Environment;
import android.os.StatFs;
import android.bluetooth.BluetoothAdapter;
import android.media.AudioAttributes;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;

import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

import android.provider.AlarmClock;
import android.app.NotificationManager;
import android.widget.Toast;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStats;
import android.app.AppOpsManager;
import android.app.ActivityManager;
import android.os.Environment;
import android.os.StatFs;
import java.util.Calendar;
import java.util.List;

public class CommandExecutor {
    private static final String TAG = "CommandExecutor";
    public static boolean isContinuousMode = false;

    public static int getBatteryLevel(Context context) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (int) ((level / (float) scale) * 100);
        }
        return -1;
    }

    public static void makeCall(Context context, String number) {
        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + number));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(callIntent);
        } catch (SecurityException e) {
            Log.e(TAG, "Call permission not granted", e);
            throw e;
        }
    }

    public static void sendSMS(String number, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(number, null, message, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS", e);
            throw e;
        }
    }

    @SuppressLint("Range")
    public static String[] searchContact(Context context, String nameQuery) {
        String queryLower = nameQuery.toLowerCase().trim();
        if (queryLower.isEmpty()) return null;
        
        String[] queryWords = queryLower.split("\\s+");
        
        String bestNumber = null;
        String bestName = null;
        double bestScore = 0.0;

        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };

        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                    if (name != null) {
                        String nameLower = name.toLowerCase().trim();
                        String number = cursor.getString(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
                        
                        double currentScore = 0.0;
                        
                        // 1. Exact match (Highest Priority)
                        if (nameLower.equals(queryLower)) {
                            cursor.close();
                            return new String[]{name, number};
                        }
                        
                        // 2. Substring match (e.g., "ravi" in "ravi kumar")
                        if (nameLower.contains(queryLower)) {
                            currentScore = 0.9;
                        } 
                        // 3. Fuzzy Token Match (e.g., "shubham pari" matches "shubham mapari seti")
                        else {
                            int matchedWords = 0;
                            for (String qWord : queryWords) {
                                if (nameLower.contains(qWord)) {
                                    matchedWords++;
                                }
                            }
                            // Calculate score based on how many spoken words were found
                            currentScore = (double) matchedWords / queryWords.length * 0.8;
                        }
                        
                        if (currentScore > bestScore) {
                            bestScore = currentScore;
                            bestName = name;
                            bestNumber = number;
                        } else if (currentScore == bestScore && currentScore > 0) {
                            // Tie-breaker: pick the shorter name (more exact)
                            if (bestName != null && name.length() < bestName.length()) {
                                bestName = name;
                                bestNumber = number;
                            }
                        }
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        
        // Threshold: at least half of the spoken words must match
        if (bestScore >= 0.4 && bestNumber != null) {
            return new String[]{bestName, bestNumber};
        }
        
        return null;
    }

    @SuppressLint("MissingPermission")
    public static Task<Location> getLocation(Context context) {
        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        return fusedLocationClient.getLastLocation();
    }

    public static boolean openApp(Context context, String appName) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        for (ApplicationInfo packageInfo : packages) {
            String installedAppName = pm.getApplicationLabel(packageInfo).toString();
            if (installedAppName.toLowerCase().contains(appName.toLowerCase())) {
                Intent launchIntent = pm.getLaunchIntentForPackage(packageInfo.packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launchIntent);
                    return true;
                }
            }
        }
        return false;
    }
    
    @SuppressLint("MissingPermission")
    public static void sendSms(Context context, String number, String message) {
        try {
            android.telephony.SmsManager smsManager;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(android.telephony.SmsManager.class);
            } else {
                smsManager = android.telephony.SmsManager.getDefault();
            }

            if (smsManager == null) {
                Toast.makeText(context, "SMS service not available.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Split long messages automatically
            java.util.ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() == 1) {
                smsManager.sendTextMessage(number, null, message, null, null);
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null);
            }

            Toast.makeText(context, "SMS sent to " + number, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Log.e(TAG, "SMS permission denied", e);
            Toast.makeText(context, "SMS permission not granted. Please allow it in settings.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS directly, opening SMS app instead.", e);
            // Fallback: open the SMS app
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("smsto:" + number));
            intent.putExtra("sms_body", message);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public static void sendWhatsApp(Context context, String number, String message) {
        // Ensure number has country code or is formatted properly. For WhatsApp it needs to be just digits.
        String cleanNumber = number.replaceAll("[^0-9]", "");
        if (cleanNumber.length() == 10) {
            cleanNumber = "91" + cleanNumber; // Default to India if exactly 10 digits
        } else if (cleanNumber.length() == 0) {
            throw new IllegalArgumentException("Invalid phone number");
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("http://api.whatsapp.com/send?phone=" + cleanNumber + "&text=" + Uri.encode(message)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setPackage("com.whatsapp");
            
            com.aiphone.agent.services.AgentAccessibilityService acc = com.aiphone.agent.services.AgentAccessibilityService.getInstance();
            if (acc != null) {
                acc.isWaitingForWhatsappSend = true;
            }
            
            context.startActivity(intent);
        } catch (Exception e) {
            // Fallback if WhatsApp is not installed or package name changed
            Intent fallbackIntent = new Intent(Intent.ACTION_VIEW);
            fallbackIntent.setData(Uri.parse("http://api.whatsapp.com/send?phone=" + cleanNumber + "&text=" + Uri.encode(message)));
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallbackIntent);
        }
    }

    public static void goHome(Context context) {
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(startMain);
    }

    public static void setVolume(Context context, int level) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int targetVolume = (int) ((level / 100.0) * maxVolume);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI);
        }
    }

    public static void setBrightness(Context context, int level) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (Settings.System.canWrite(context)) {
                int brightness = (int) ((level / 100.0) * 255);
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, brightness);
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                throw new SecurityException("Permission needed to write settings. Settings opened, please try again after granting.");
            }
        }
    }

    public static void setAlarm(Context context, int hour, int minute, String message) {
        Intent intent = new Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void setTimer(Context context, int seconds, String message) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void setDNDMode(Context context, boolean enable) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            if (nm.isNotificationPolicyAccessGranted()) {
                nm.setInterruptionFilter(enable ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL);
                Toast.makeText(context, enable ? "DND Enabled" : "DND Disabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, "Please grant DND permission first.", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        }
    }

    @SuppressLint("MissingPermission")
    public static void getWeather(Context context) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Assume TTSManager exists or replace with custom logic
            return;
        }
        
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);
        client.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                new Thread(() -> {
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + location.getLatitude() + "&longitude=" + location.getLongitude() + "&current_weather=true");
                        conn = (HttpURLConnection) url.openConnection();
                        if (conn.getResponseCode() == 200) {
                            String responseStr;
                            try (Scanner s = new Scanner(conn.getInputStream()).useDelimiter("\\A")) {
                                responseStr = s.hasNext() ? s.next() : "";
                            }
                            JSONObject res = new JSONObject(responseStr);
                            JSONObject current = res.getJSONObject("current_weather");
                            double temp = current.getDouble("temperature");
                            Log.d(TAG, "Temperature: " + temp);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }).start();
            }
        });
    }

    public static void setTorch(Context context, boolean state) {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager != null) {
                String cameraId = cameraManager.getCameraIdList()[0];
                cameraManager.setTorchMode(cameraId, state);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set torch", e);
            throw new RuntimeException("Torch not available");
        }
    }

    @SuppressLint("MissingPermission")
    public static void setBluetooth(Context context, boolean state) {
        // BluetoothAdapter.enable()/disable() are deprecated on API 33+
        // Use Settings panel to let user toggle Bluetooth
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter != null) {
                if (state && !bluetoothAdapter.isEnabled()) {
                    bluetoothAdapter.enable();
                } else if (!state && bluetoothAdapter.isEnabled()) {
                    bluetoothAdapter.disable();
                }
            }
        }
    }

    public static void setWifi(Context context, boolean state) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            Intent intent = new Intent(Settings.Panel.ACTION_WIFI);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiManager.setWifiEnabled(state);
            }
        }
    }

    public static String getStorageAndRam(Context context) {
        ActivityManager actManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long totalRam = memInfo.totalMem / (1024 * 1024 * 1024);
        long availRam = memInfo.availMem / (1024 * 1024 * 1024);
        long usedRam = totalRam - availRam;

        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
        long bytesTotal = stat.getBlockSizeLong() * stat.getBlockCountLong();
        long totalStorage = bytesTotal / (1024 * 1024 * 1024);
        long usedStorage = (bytesTotal - bytesAvailable) / (1024 * 1024 * 1024);

        return String.format("RAM: %dGB used out of %dGB. Storage: %dGB used out of %dGB.", usedRam, totalRam, usedStorage, totalStorage);
    }

    public static void findPhone(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        }
        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (alarmUri == null) alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        Ringtone ringtone = RingtoneManager.getRingtone(context, alarmUri);
        if (ringtone != null) {
            // Use AudioAttributes instead of deprecated setStreamType
            AudioAttributes audioAttrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ringtone.setAudioAttributes(audioAttrs);
            ringtone.play();
            // Stop after 10 seconds
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(ringtone::stop, 10000);
        }
    }

    public static void openMusicPlayer(Context context) {
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            throw new RuntimeException("No music app found");
        }
    }

    public static void playMedia(Context context, String query) {
        Intent intent = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            throw new RuntimeException("No app found to play media");
        }
    }

    public static void webSearch(Context context, String query) {
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        intent.putExtra(SearchManager.QUERY, query);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            throw new RuntimeException("No app found to perform web search");
        }
    }

    public static void navigate(Context context, String location) {
        Uri gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(location));
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mapIntent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(mapIntent);
        } else {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(location)));
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(webIntent);
        }
    }
    public static void setBatterySaver(Context context) {
        try {
            setBrightness(context, 10);
        } catch (SecurityException e) {
            // Permission requested via setBrightness, ignore
        }
        setBluetooth(context, false);
        setWifi(context, false);
    }

    public static void setClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Copied from PC", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }
    }
    
    public static void scheduleCommand(Context context, int delayMinutes, String command) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                throw new SecurityException("Permission needed to schedule exact alarms.");
            }
        }
        
        Intent intent = new Intent("com.aiphone.agent.ACTION_EXECUTE_COMMAND");
        intent.setPackage(context.getPackageName());
        intent.putExtra("command", command);
        
        int reqCode = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        long triggerAtMillis = System.currentTimeMillis() + (delayMinutes * 60 * 1000L);
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
    }

    public static String getAppUsage(Context context, String appName) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) {
            Intent intent = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return "I need permission to view your app usage. Please grant it in the settings that just opened, then ask me again.";
        }
        
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        long startTime = cal.getTimeInMillis();
        long endTime = System.currentTimeMillis();
        
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
        if (stats == null || stats.isEmpty()) return "I couldn't find any usage stats for today.";
        
        String targetPackage = "";
        String searchApp = appName.toLowerCase();
        if (searchApp.contains("instagram")) targetPackage = "com.instagram.android";
        else if (searchApp.contains("whatsapp")) targetPackage = "com.whatsapp";
        else if (searchApp.contains("youtube")) targetPackage = "com.google.android.youtube";
        else if (searchApp.contains("facebook")) targetPackage = "com.facebook.katana";
        else if (searchApp.contains("tiktok")) targetPackage = "com.zhiliaoapp.musically";
        else return "Sorry, I can only check usage for major apps right now like WhatsApp, Instagram, or YouTube.";
        
        long totalTimeInForeground = 0;
        for (UsageStats stat : stats) {
            if (stat.getPackageName().equals(targetPackage)) {
                totalTimeInForeground += stat.getTotalTimeInForeground();
            }
        }
        
        if (totalTimeInForeground == 0) return "You haven't used " + appName + " today. Good job!";
        
        long minutes = (totalTimeInForeground / 1000) / 60;
        long hours = minutes / 60;
        minutes = minutes % 60;
        
        if (hours > 0) {
            return "You have spent " + hours + " hours and " + minutes + " minutes on " + appName + " today.";
        } else {
            return "You have spent " + minutes + " minutes on " + appName + " today.";
        }
    }
    
    public static void playOnYouTube(Context context, String query) {
        Intent intent = new Intent(Intent.ACTION_SEARCH);
        intent.setPackage("com.google.android.youtube");
        intent.putExtra("query", query);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) != null) {
            context.startActivity(intent);
        } else {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)));
            webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(webIntent);
        }
    }
    
    public static void makeWhatsAppCall(Context context, String number, boolean isVideo) {
        Toast.makeText(context, "WhatsApp Calls require Accessibility or explicit ContentProvider IDs. Not implemented fully yet.", Toast.LENGTH_LONG).show();
        // Fallback to opening chat
        sendWhatsApp(context, number, "Call me!");
    }
    
    public static String getSystemHealth(Context context) {
        int battery = getBatteryLevel(context);
        
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memoryInfo);
        long availableRamMB = memoryInfo.availMem / (1024 * 1024);
        
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
        long availableStorageGB = bytesAvailable / (1024 * 1024 * 1024);
        
        StringBuilder report = new StringBuilder("System Scan Complete. ");
        report.append("Battery is at ").append(battery).append(" percent. ");
        report.append("Available RAM is ").append(availableRamMB).append(" megabytes. ");
        report.append("Free storage is ").append(availableStorageGB).append(" gigabytes. ");
        
        if (battery < 20) report.append("Warning: Battery is low. ");
        if (availableRamMB < 500) report.append("Warning: RAM is very low. ");
        
        return report.toString();
    }

    public static String readSms(Context context) {
        StringBuilder sb = new StringBuilder();
        Cursor cursor = context.getContentResolver().query(Uri.parse("content://sms/inbox"), null, null, null, "date DESC LIMIT 5");
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    sb.append("[").append(address).append("]: ").append(body).append("\n");
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        return sb.toString().trim().isEmpty() ? "No recent SMS." : sb.toString().trim();
    }

    public static String readCallLog(Context context) {
        StringBuilder sb = new StringBuilder();
        @SuppressLint("MissingPermission") Cursor cursor = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC LIMIT 5");
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                    String typeStr = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    int type = Integer.parseInt(typeStr);
                    String callType = type == CallLog.Calls.MISSED_TYPE ? "Missed" : (type == CallLog.Calls.INCOMING_TYPE ? "Incoming" : "Outgoing");
                    sb.append("[").append(callType).append("] ").append(number).append("\n");
                } while (cursor.moveToNext());
            }
            cursor.close();
        }
        return sb.toString().trim().isEmpty() ? "No recent calls." : sb.toString().trim();
    }

    public static void writeNote(Context context, String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        Intent chooser = Intent.createChooser(intent, "Save Note");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooser);
    }
}
