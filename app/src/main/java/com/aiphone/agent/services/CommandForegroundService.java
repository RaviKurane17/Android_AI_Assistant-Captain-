package com.aiphone.agent.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.aiphone.agent.R;
import com.aiphone.agent.models.Command;
import com.aiphone.agent.models.Response;
import com.aiphone.agent.utils.CommandExecutor;
import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class CommandForegroundService extends Service {
    private static final String TAG = "CommandFgService";
    private static final String CHANNEL_ID = "CommandServiceChannel";

    private DatabaseReference dbRef;
    private ValueEventListener commandListener;
    private String deviceId;
    private TextToSpeech tts;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        dbRef = FirebaseDatabase.getInstance().getReference();
        deviceId = DeviceUtils.getDeviceId(this);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                tts.setLanguage(new Locale("en", "IN"));
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Phone Agent Active")
                .setContentText("Listening for commands...")
                .setSmallIcon(R.drawable.ic_launcher)
                .build();

        startForeground(1, notification);
        listenForCommands();
        updateDeviceStatus("online");

        return START_STICKY;
    }

    private void listenForCommands() {
        if (commandListener != null) {
            dbRef.child("commands").removeEventListener(commandListener);
        }

        commandListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    Command command = child.getValue(Command.class);
                    if (command != null && deviceId.equals(command.targetDeviceId) && "pending".equals(command.status)) {
                        command.id = child.getKey();
                        processCommand(command);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "Listen failed.", error.toException());
            }
        };

        dbRef.child("commands").addValueEventListener(commandListener);
    }

    @SuppressLint("MissingPermission")
    private void processCommand(Command command) {
        Log.d(TAG, "Processing command: " + command.action);
        
        // Mark as processing
        dbRef.child("commands").child(command.id).child("status").setValue("processing");

        Response response = new Response();
        response.commandId = command.id;
        response.targetDeviceId = deviceId;
        response.action = command.action;
        response.timestamp = System.currentTimeMillis();

        try {
            switch (command.action) {
                case "battery":
                    int battery = CommandExecutor.getBatteryLevel(this);
                    response.battery = battery;
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "location":
                    CommandExecutor.getLocation(this).addOnSuccessListener(location -> {
                        if (location != null) {
                            response.latitude = location.getLatitude();
                            response.longitude = location.getLongitude();
                            response.status = "success";
                        } else {
                            response.status = "error";
                            response.error = "Location unavailable";
                        }
                        sendResponse(response, command);
                    }).addOnFailureListener(e -> {
                        response.status = "error";
                        response.error = e.getMessage();
                        sendResponse(response, command);
                    });
                    break;
                case "contact_search":
                    String[] match = CommandExecutor.searchContact(this, command.name);
                    if (match != null) {
                        response.number = match[1];
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Contact not found";
                    }
                    sendResponse(response, command);
                    break;
                case "sms":
                    CommandExecutor.sendSMS(command.number, command.message);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "call":
                    CommandExecutor.makeCall(this, command.number);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "open_app":
                    boolean opened = CommandExecutor.openApp(this, command.name);
                    if (opened) {
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "App not found";
                    }
                    sendResponse(response, command);
                    break;
                case "home":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().goHome();
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "lock_screen":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().lockScreen();
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "screenshot":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().takeScreenshot();
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "open_notifications":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().openNotifications();
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "read_screen":
                    if (AgentAccessibilityService.getInstance() != null) {
                        response.message = AgentAccessibilityService.getInstance().readScreenContent();
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "sys_info":
                    response.message = CommandExecutor.getStorageAndRam(this);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "torch":
                    CommandExecutor.setTorch(this, command.value == 1);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "bluetooth":
                    CommandExecutor.setBluetooth(this, command.value == 1);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "wifi":
                    CommandExecutor.setWifi(this, command.value == 1);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "reply_notification":
                    // command.name is the packageName (e.g. "com.whatsapp") or just "whatsapp"
                    // command.number is the title (Contact name)
                    // command.message is the reply text
                    String pkgName = command.name;
                    if (pkgName != null && pkgName.equalsIgnoreCase("whatsapp")) pkgName = "com.whatsapp";
                    if (pkgName != null && pkgName.equalsIgnoreCase("telegram")) pkgName = "org.telegram.messenger";
                    
                    boolean replied = NotificationCaptureService.replyTo(pkgName, command.number, command.message);
                    response.status = replied ? "success" : "error";
                    if (!replied) response.error = "Could not find a pending reply intent for " + command.number + ". Did they send a message recently?";
                    sendResponse(response, command);
                    break;
                case "vision_capture":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().captureVision(command.id);
                        // Response handled asynchronously in captureVision
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                        sendResponse(response, command);
                    }
                    break;
                case "auto_click":
                    if (AgentAccessibilityService.getInstance() != null) {
                        boolean clicked = AgentAccessibilityService.getInstance().autoClick(command.message);
                        response.status = clicked ? "success" : "error";
                        if (!clicked) response.error = "Could not find text to click";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "auto_scroll":
                    if (AgentAccessibilityService.getInstance() != null) {
                        boolean forward = command.message == null || !command.message.equalsIgnoreCase("up");
                        boolean scrolled = AgentAccessibilityService.getInstance().autoScroll(forward);
                        response.status = scrolled ? "success" : "error";
                        if (!scrolled) response.error = "Could not scroll";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "auto_type":
                    if (AgentAccessibilityService.getInstance() != null) {
                        boolean typed = AgentAccessibilityService.getInstance().autoType(command.message);
                        response.status = typed ? "success" : "error";
                        if (!typed) response.error = "Could not find active text input";
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "unlock_screen":
                    if (AgentAccessibilityService.getInstance() != null) {
                        String pin = com.aiphone.agent.utils.SecurePrefsManager.getPin(this);
                        if (pin == null) pin = command.message; // Fallback to parameter
                        if (pin != null) {
                            boolean unlocked = AgentAccessibilityService.getInstance().unlockPhone(pin);
                            response.status = unlocked ? "success" : "error";
                        } else {
                            response.status = "error";
                            response.error = "No PIN saved or provided";
                        }
                    } else {
                        response.status = "error";
                        response.error = "Accessibility service not running";
                    }
                    sendResponse(response, command);
                    break;
                case "find_phone":
                    CommandExecutor.findPhone(this);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "speak":
                    if (tts != null && command.message != null && !command.message.isEmpty()) {
                        if (command.message.matches(".*[\u0900-\u097F].*")) {
                            tts.setLanguage(new Locale("hi", "IN"));
                        } else {
                            tts.setLanguage(new Locale("en", "IN"));
                        }
                        tts.speak(command.message, TextToSpeech.QUEUE_FLUSH, null, null);
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "TTS not ready or message missing";
                    }
                    sendResponse(response, command);
                    break;
                case "play_media":
                    if (AgentAccessibilityService.getInstance() != null) {
                        AgentAccessibilityService.getInstance().isWaitingForYoutubePlay = true;
                    }
                    CommandExecutor.playMedia(this, command.name);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "web_search":
                    CommandExecutor.webSearch(this, command.message);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "set_clipboard":
                    CommandExecutor.setClipboard(this, command.message);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "read_sms":
                    response.message = CommandExecutor.readSms(this);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "read_call_log":
                    response.message = CommandExecutor.readCallLog(this);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "take_picture":
                    Intent camIntent = new Intent(this, com.aiphone.agent.ui.CameraActivity.class);
                    camIntent.putExtra("commandId", command.id);
                    if (command.message != null && command.message.equalsIgnoreCase("front")) {
                        camIntent.putExtra("camera_type", "front");
                    } else {
                        camIntent.putExtra("camera_type", "back");
                    }
                    camIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(camIntent);
                    // CameraActivity will send the response back when done.
                    break;
                case "whatsapp":
                    String wNumber = command.number;
                    if (wNumber == null && command.name != null) {
                        String[] wMatch = CommandExecutor.searchContact(this, command.name);
                        if (wMatch != null) {
                            wNumber = wMatch[1];
                        }
                    }
                    if (wNumber != null) {
                        if (AgentAccessibilityService.getInstance() != null) {
                            AgentAccessibilityService.getInstance().isWaitingForWhatsappSend = true;
                        }
                        CommandExecutor.sendWhatsApp(this, wNumber, command.message);
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Contact number not found";
                    }
                    sendResponse(response, command);
                    break;
                case "write_note":
                    CommandExecutor.writeNote(this, command.message);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "volume":
                    CommandExecutor.setVolume(this, command.value);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "brightness":
                    CommandExecutor.setBrightness(this, command.value);
                    response.status = "success";
                    sendResponse(response, command);
                    break;
                case "alarm":
                    String[] parts = command.time.split(":");
                    if (parts.length == 2) {
                        int hour = Integer.parseInt(parts[0]);
                        int minute = Integer.parseInt(parts[1]);
                        CommandExecutor.setAlarm(this, hour, minute, command.message);
                        response.status = "success";
                    } else {
                        response.status = "error";
                        response.error = "Invalid time format. Use HH:MM";
                    }
                    sendResponse(response, command);
                    break;
                default:
                    response.status = "error";
                    response.error = "Unknown command";
                    sendResponse(response, command);
                    break;
            }
        } catch (Exception e) {
            response.status = "error";
            response.error = e.getMessage();
            sendResponse(response, command);
        }
    }

    private void sendResponse(Response response, Command command) {
        // Update command status
        dbRef.child("commands").child(command.id).child("status").setValue(response.status);

        // Create response doc
        dbRef.child("responses").push().setValue(response);
    }

    private void updateDeviceStatus(String status) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("status", status);
        updates.put("lastSeen", System.currentTimeMillis());
        updates.put("batteryLevel", CommandExecutor.getBatteryLevel(this));
        updates.put("deviceId", deviceId);
        updates.put("model", DeviceUtils.getDeviceModel());
        
        dbRef.child("devices").child(deviceId).updateChildren(updates);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Command Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (commandListener != null) {
            dbRef.child("commands").removeEventListener(commandListener);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        updateDeviceStatus("offline");
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
