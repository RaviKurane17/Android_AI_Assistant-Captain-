package com.aiphone.agent.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.switchmaterial.SwitchMaterial;
import android.provider.Settings;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.aiphone.agent.R;
import com.aiphone.agent.services.AgentAccessibilityService;
import com.aiphone.agent.services.CommandForegroundService;
import com.aiphone.agent.services.WakeWordService;
import com.aiphone.agent.utils.CommandExecutor;
import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.aiphone.agent.utils.SecurePrefsManager;

public class DashboardActivity extends AppCompatActivity {

    private boolean isWakeWordEnabled = false;

    // NOTE: No SpeechRecognizer here! The Dashboard routes speech through
    // WakeWordService to avoid microphone conflicts.

    private TextView tvCommandHistory;
    private android.os.Handler historyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable historyRunnable = new Runnable() {
        @Override
        public void run() {
            updateHistoryUI();
            historyHandler.postDelayed(this, 2000);
        }
    };
    private static final int SYSTEM_ALERT_WINDOW_PERMISSION = 2084;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        
        com.aiphone.agent.utils.SmartIntentClassifier.init(this);
        
        // Proactive Greeting
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            String[] greetings = {
                "Ji Boss, kya kar rahe ho aap?",
                "Hello Boss! How are you feeling today?",
                "Captain is online and ready for you, Boss."
            };
            int idx = new java.util.Random().nextInt(greetings.length);
            com.aiphone.agent.utils.TTSManager.speak(this, greetings[idx]);
        }, 1500);

        ImageView btnPcLink = findViewById(R.id.btnPcLink);
        btnPcLink.setOnClickListener(v -> {
            Intent intent = new Intent(this, PcLinkActivity.class);
            startActivity(intent);
        });

        ImageView btnSettingsGear = findViewById(R.id.btnSettingsGear);
        btnSettingsGear.setOnClickListener(v -> openSettingsActivity());

        SwitchMaterial switchFloatingWidget = findViewById(R.id.switchFloatingWidget);
        SharedPreferences prefs = getSharedPreferences("AgentPrefs", MODE_PRIVATE);
        boolean widgetEnabled = prefs.getBoolean("floating_widget", false);
        switchFloatingWidget.setChecked(widgetEnabled);

        switchFloatingWidget.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("floating_widget", isChecked).apply();
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(overlayIntent, SYSTEM_ALERT_WINDOW_PERMISSION);
                } else {
                    startService(new Intent(this, com.aiphone.agent.services.FloatingWidgetService.class));
                }
            } else {
                stopService(new Intent(this, com.aiphone.agent.services.FloatingWidgetService.class));
            }
        });

        SwitchMaterial switchOfflineMode = findViewById(R.id.switchOfflineMode);
        boolean offlineEnabled = prefs.getBoolean("offline_mode", false);
        switchOfflineMode.setChecked(offlineEnabled);
        switchOfflineMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("offline_mode", isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "Offline Privacy Shield Enabled! No data will leave your device.", Toast.LENGTH_LONG).show();
            }
        });

        Button btnVoiceCommand = findViewById(R.id.btnVoiceCommand);

        tvCommandHistory = findViewById(R.id.tvCommandHistory);
        if (tvCommandHistory != null) {
            tvCommandHistory.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        }

        // KEY FIX: Route button through WakeWordService (no separate SpeechRecognizer)
        // This prevents the mic conflict that caused recognition failures.
        btnVoiceCommand.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                btnVoiceCommand.setText("🎙️ Captain is listening...");
                btnVoiceCommand.setAlpha(0.7f);
                // Tell WakeWordService to start listening (same path as wake word)
                Intent listenIntent = new Intent(this, com.aiphone.agent.services.WakeWordService.class);
                listenIntent.setAction("ACTION_FORCE_LISTEN");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(listenIntent);
                } else {
                    startService(listenIntent);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP ||
                       event.getAction() == MotionEvent.ACTION_CANCEL) {
                btnVoiceCommand.setText("🎙️ HOLD TO SPEAK");
                btnVoiceCommand.setAlpha(1f);
            }
            return false;
        });

        // Tap also works (not just hold)
        btnVoiceCommand.setOnClickListener(v -> {
            btnVoiceCommand.setText("🎙️ Captain is listening...");
            btnVoiceCommand.setAlpha(0.7f);
            Intent listenIntent = new Intent(this, com.aiphone.agent.services.WakeWordService.class);
            listenIntent.setAction("ACTION_FORCE_LISTEN");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(listenIntent);
            } else {
                startService(listenIntent);
            }
            // Reset button after 5s fallback
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                btnVoiceCommand.setText("🎙️ HOLD TO SPEAK");
                btnVoiceCommand.setAlpha(1f);
            }, 5000);
        });
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    private void updateHistoryUI() {
        if (tvCommandHistory == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (com.aiphone.agent.utils.GroqAgent.chatHistory) {
            if (com.aiphone.agent.utils.GroqAgent.chatHistory.isEmpty()) {
                sb.append("No recent commands.");
            } else {
                for (org.json.JSONObject msg : com.aiphone.agent.utils.GroqAgent.chatHistory) {
                    String role = msg.optString("role");
                    String content = msg.optString("content");
                    if ("user".equals(role)) {
                        sb.append("You: ").append(content).append("\n\n");
                    } else if ("assistant".equals(role)) {
                        try {
                            org.json.JSONObject act = new org.json.JSONObject(content);
                            String actStr = act.optString("action", "");
                            String msgStr = act.optString("message", "");
                            sb.append("Captain: ").append(actStr).append(msgStr.isEmpty() ? "" : " - " + msgStr).append("\n\n");
                        } catch (Exception e) {
                            sb.append("Captain: ").append(content).append("\n\n");
                        }
                    }
                }
            }
        }
        tvCommandHistory.setText(sb.toString().trim());
    }

    @Override
    protected void onResume() {
        super.onResume();
        historyHandler.post(historyRunnable);
        SharedPreferences prefs = getSharedPreferences("AgentPrefs", MODE_PRIVATE);
        boolean shouldWakeWordBeEnabled = prefs.getBoolean("wake_word_enabled", false);
        
        if (shouldWakeWordBeEnabled != isWakeWordEnabled) {
            isWakeWordEnabled = shouldWakeWordBeEnabled;
            Intent serviceIntent = new Intent(this, WakeWordService.class);
            if (isWakeWordEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
            } else {
                stopService(serviceIntent);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        historyHandler.removeCallbacks(historyRunnable);
    }

    private void sendVoiceCommandToFirebase(String deviceId, String text) {
        Toast.makeText(this, "Sent: " + text, Toast.LENGTH_SHORT).show();
        java.util.Map<String, Object> cmd = new java.util.HashMap<>();
        cmd.put("targetDeviceId", "PC"); // Force route to PC
        cmd.put("action", "voice_command");
        cmd.put("message", text);
        cmd.put("status", "pending");
        cmd.put("timestamp", System.currentTimeMillis());
        FirebaseDatabase.getInstance().getReference("commands").push().setValue(cmd);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SYSTEM_ALERT_WINDOW_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService(new Intent(this, com.aiphone.agent.services.FloatingWidgetService.class));
            } else {
                Toast.makeText(this, "Permission denied for Floating Widget.", Toast.LENGTH_SHORT).show();
                SwitchMaterial sw = findViewById(R.id.switchFloatingWidget);
                if (sw != null) sw.setChecked(false);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // No SpeechRecognizer to clean up here — handled by WakeWordService
    }
}
