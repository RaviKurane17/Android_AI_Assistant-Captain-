package com.aiphone.agent.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.aiphone.agent.R;
import com.aiphone.agent.ui.DashboardActivity;
import com.aiphone.agent.utils.GroqAgent;
import com.aiphone.agent.utils.OfflineCommandParser;
import com.aiphone.agent.utils.SecurePrefsManager;
import com.aiphone.agent.utils.WakeChimePlayer;

import java.util.ArrayList;

import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;
import ai.picovoice.porcupine.PorcupineException;

public class WakeWordService extends Service {

    private static final String TAG = "WakeWordService";
    private static final int NOTIFICATION_ID = 200;
    private static final String CHANNEL_ID = "WakeWordChannel";

    // ── Porcupine wake-word engine ─────────────────────────────────────────
    private PorcupineManager porcupineManager;

    // ── Google SpeechRecognizer (recreated fresh after every use) ──────────
    private SpeechRecognizer commandRecognizer;
    private boolean isRecognizerListening = false;

    // ── Overlay views ──────────────────────────────────────────────────────
    private WindowManager windowManager;
    private View overlayView;
    private TextView tvOverlayStatus;
    private TextView tvPartialText;
    private View orb1, orb2, orb3, orb4, orb5, orb6, orb7;
    private boolean isOverlayShowing = false;

    // ── Waveform animation ─────────────────────────────────────────────────
    private final Handler waveHandler = new Handler(Looper.getMainLooper());
    private android.animation.AnimatorSet currentWaveAnim;

    // ── Keys ───────────────────────────────────────────────────────────────
    private String apiKey = "";
    private String picovoiceKey = "";

    // ── Mic release delay before restarting Porcupine (prevents race) ─────
    private static final long MIC_RELEASE_DELAY_MS = 350;

    // ─────────────────────────────────────────────────────────────────────
    // Broadcast receivers
    // ─────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver ttsDoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ACTION_TTS_DONE".equals(intent.getAction())) {
                if (com.aiphone.agent.utils.CommandExecutor.isContinuousMode) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> startCommandListening(), 200);
                } else {
                    scheduleWakeWordRestart(MIC_RELEASE_DELAY_MS);
                }
            }
        }
    };

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                com.aiphone.agent.utils.TTSManager.speak(context, "Boss, phone is plugged in and charging.");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                com.aiphone.agent.utils.TTSManager.speak(context, "Boss, charger disconnected.");
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        apiKey = SecurePrefsManager.getApiKey(this);
        picovoiceKey = SecurePrefsManager.getPicovoiceKey(this);

        setupOverlay();
        setupPorcupine();

        IntentFilter ttsDoneFilter = new IntentFilter("ACTION_TTS_DONE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ttsDoneReceiver, ttsDoneFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(ttsDoneReceiver, ttsDoneFilter);
        }

        IntentFilter powerFilter = new IntentFilter();
        powerFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        powerFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(powerReceiver, powerFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_FORCE_LISTEN".equals(intent.getAction())) {
            wakeWordDetected();
        } else {
            startPorcupineListening();
        }
        return START_STICKY;   // Restart if killed by Android
    }

    @Override
    public void onDestroy() {
        destroyPorcupine();
        destroyRecognizer();
        hideOverlay();
        try {
            unregisterReceiver(ttsDoneReceiver);
            unregisterReceiver(powerReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Porcupine (wake word)
    // ─────────────────────────────────────────────────────────────────────

    private void setupPorcupine() {
        if (picovoiceKey.isEmpty()) {
            Log.e(TAG, "Picovoice AccessKey is missing!");
            Toast.makeText(this, "Picovoice key missing — wake word disabled.", Toast.LENGTH_LONG).show();
            return;
        }
        String keywordPath = extractAsset("captain.ppn");
        if (keywordPath == null) {
            Toast.makeText(this, "captain.ppn not found in assets!", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(picovoiceKey)
                    .setKeywordPath(keywordPath)
                    .build(getApplicationContext(), (keywordIndex) -> wakeWordDetected());
        } catch (PorcupineException e) {
            Log.e(TAG, "Porcupine setup failed", e);
            Toast.makeText(this, "Picovoice Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startPorcupineListening() {
        if (porcupineManager != null) {
            try { porcupineManager.start(); } catch (PorcupineException e) {
                Log.e(TAG, "Porcupine start failed", e);
            }
        }
    }

    private void stopPorcupineListening() {
        if (porcupineManager != null) {
            try { porcupineManager.stop(); } catch (PorcupineException e) {
                Log.e(TAG, "Porcupine stop failed", e);
            }
        }
    }

    private void destroyPorcupine() {
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
                porcupineManager.delete();
            } catch (PorcupineException ignored) {}
            porcupineManager = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SpeechRecognizer  — KEY FIX: always recreate fresh
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds a fresh SpeechRecognizer with properly configured intent.
     * Called every single time before startListening() to avoid the
     * "recognizer stuck after error" bug.
     */
    private void recreateRecognizer() {
        destroyRecognizer();

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition NOT available on this device!");
            return;
        }

        commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        commandRecognizer.setRecognitionListener(new CaptainRecognitionListener());
    }

    private void destroyRecognizer() {
        if (commandRecognizer != null) {
            try {
                commandRecognizer.stopListening();
                commandRecognizer.cancel();
                commandRecognizer.destroy();
            } catch (Exception ignored) {}
            commandRecognizer = null;
        }
        isRecognizerListening = false;
    }

    /**
     * Builds the recognition Intent with all advanced settings.
     */
    private Intent buildRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

        // Language model — free form handles natural speech best
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // Primary language + Hindi as preferred fallback (Hinglish support)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN");
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);

        // ── KEY FIX: get partial results so overlay shows live transcription ──
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        // ── KEY FIX: silence detection tuning (default is too aggressive) ──
        // Wait 1500ms of complete silence before finishing (was ~500ms)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        // Wait 1000ms of partial silence before "possibly done"
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
        // Start recording immediately, don't wait for speech to begin
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);

        // Get top 3 results for confidence filtering
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        // Force ONLINE recognition for best accuracy
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        return intent;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Recognition flow
    // ─────────────────────────────────────────────────────────────────────

    private void wakeWordDetected() {
        // Stop TTS if speaking (voice interruption)
        if (com.aiphone.agent.utils.TTSManager.isSpeaking) {
            com.aiphone.agent.utils.TTSManager.stop();
        }

        // Stop Porcupine so the mic is free for SpeechRecognizer
        stopPorcupineListening();

        // Play chime
        WakeChimePlayer.playChime(this);

        showOverlay("Listening for your command...");

        // Small delay so Porcupine fully releases the mic before SR grabs it
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            recreateRecognizer();
            if (commandRecognizer != null) {
                commandRecognizer.startListening(buildRecognitionIntent());
                isRecognizerListening = true;
            }
        }, 200);
    }

    private void startCommandListening() {
        stopPorcupineListening();
        showOverlay("Still listening, Boss...");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            recreateRecognizer();
            if (commandRecognizer != null) {
                commandRecognizer.startListening(buildRecognitionIntent());
                isRecognizerListening = true;
            }
        }, 200);
    }

    /** Delayed restart prevents mic-release race condition */
    private void scheduleWakeWordRestart(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            destroyRecognizer();
            startPorcupineListening();
        }, delayMs);
    }

    // ─────────────────────────────────────────────────────────────────────
    // RecognitionListener  — full implementation
    // ─────────────────────────────────────────────────────────────────────

    private class CaptainRecognitionListener implements RecognitionListener {

        @Override
        public void onReadyForSpeech(Bundle params) {
            updateOverlayText("Listening...");
            startWaveAnimation();
        }

        @Override
        public void onBeginningOfSpeech() {
            updateOverlayText("I hear you...");
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Animate orbs based on real microphone volume
            animateOrbsToVolume(rmsdB);
        }

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            stopWaveAnimation();
            updateOverlayText("Processing...");
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> partial = partialResults.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (partial != null && !partial.isEmpty()) {
                String partialText = partial.get(0);
                if (!partialText.isEmpty()) {
                    updatePartialText(partialText);
                }
            }
        }

        @Override
        public void onResults(Bundle results) {
            isRecognizerListening = false;
            stopWaveAnimation();
            hideOverlay();

            ArrayList<String> data = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);

            if (data != null && !data.isEmpty()) {
                // Pick the best (most confident) result — first item is always highest confidence
                String command = data.get(0).trim();

                if (!command.isEmpty()) {
                    Log.d(TAG, "Recognized command: " + command);
                    Toast.makeText(WakeWordService.this,
                            "You: " + command, Toast.LENGTH_SHORT).show();

                    // Try offline first, then online Groq AI
                    boolean handledOffline = OfflineCommandParser.parseAndExecute(
                            WakeWordService.this, command);
                    if (!handledOffline) {
                        if (apiKey != null && !apiKey.isEmpty()) {
                            GroqAgent.processCommand(WakeWordService.this, command, apiKey);
                        } else {
                            com.aiphone.agent.utils.TTSManager.speak(WakeWordService.this,
                                    "Boss, my API key is missing. Please set it in settings.");
                        }
                    }
                }
            }

            // Restart Porcupine (or keep listening in continuous mode)
            if (!com.aiphone.agent.utils.CommandExecutor.isContinuousMode
                    && !com.aiphone.agent.utils.TTSManager.isSpeaking) {
                scheduleWakeWordRestart(MIC_RELEASE_DELAY_MS);
            }
            // If TTS is speaking → ttsDoneReceiver will restart Porcupine when done
        }

        @Override
        public void onError(int error) {
            isRecognizerListening = false;
            stopWaveAnimation();
            hideOverlay();

            String errorMsg = getErrorMessage(error);
            Log.w(TAG, "SpeechRecognizer error: " + error + " (" + errorMsg + ")");

            // ── KEY FIX: handle ALL error types properly ──
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO:
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    // Hard errors — can't recover without user action
                    Log.e(TAG, "Microphone permission error: " + errorMsg);
                    com.aiphone.agent.utils.TTSManager.speak(WakeWordService.this,
                            "Microphone permission issue, Boss.");
                    scheduleWakeWordRestart(1000);
                    break;

                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    // ── KEY FIX: busy = destroy and recreate, then restart Porcupine ──
                    Log.w(TAG, "Recognizer busy — destroying and restarting");
                    destroyRecognizer();
                    scheduleWakeWordRestart(700);
                    break;

                case SpeechRecognizer.ERROR_SERVER:
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    // Network errors — try offline parser, then restart
                    com.aiphone.agent.utils.TTSManager.speak(WakeWordService.this,
                            "Network issue. I'll switch to offline mode for now.");
                    scheduleWakeWordRestart(MIC_RELEASE_DELAY_MS);
                    break;

                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // Normal — user didn't say anything or speech not recognized
                    scheduleWakeWordRestart(MIC_RELEASE_DELAY_MS);
                    break;

                default:
                    // Any other error — just restart
                    scheduleWakeWordRestart(MIC_RELEASE_DELAY_MS);
                    break;
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio hardware error";
            case SpeechRecognizer.ERROR_CLIENT: return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No recognition match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech input";
            default: return "Unknown error " + error;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Overlay — Hey Siri style
    // ─────────────────────────────────────────────────────────────────────

    private void setupOverlay() {
        if (!Settings.canDrawOverlays(this)) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.layout_siri_overlay, null);
        tvOverlayStatus = overlayView.findViewById(R.id.tvOverlayStatus);
        tvPartialText = overlayView.findViewById(R.id.tvPartialText);
        orb1 = overlayView.findViewById(R.id.orb1);
        orb2 = overlayView.findViewById(R.id.orb2);
        orb3 = overlayView.findViewById(R.id.orb3);
        orb4 = overlayView.findViewById(R.id.orb4);
        orb5 = overlayView.findViewById(R.id.orb5);
        orb6 = overlayView.findViewById(R.id.orb6);
        orb7 = overlayView.findViewById(R.id.orb7);
    }

    private void showOverlay(String message) {
        if (overlayView == null || windowManager == null || !Settings.canDrawOverlays(this)) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            if (tvOverlayStatus != null) tvOverlayStatus.setText(message);
            if (tvPartialText != null) tvPartialText.setText("");

            if (!isOverlayShowing) {
                try {
                    int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            layoutType,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT
                    );
                    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

                    // Slide-up entrance animation
                    overlayView.setTranslationY(400f);
                    overlayView.setAlpha(0f);
                    windowManager.addView(overlayView, params);
                    isOverlayShowing = true;

                    overlayView.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(250)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();

                } catch (Exception e) {
                    Log.e(TAG, "Failed to show overlay", e);
                }
            }
        });
    }

    private void hideOverlay() {
        if (overlayView != null && isOverlayShowing && windowManager != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    overlayView.animate()
                            .translationY(400f)
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction(() -> {
                                try {
                                    if (isOverlayShowing) {
                                        windowManager.removeView(overlayView);
                                        isOverlayShowing = false;
                                    }
                                } catch (Exception ignored) {}
                            })
                            .start();
                } catch (Exception e) {
                    try {
                        windowManager.removeView(overlayView);
                        isOverlayShowing = false;
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private void updateOverlayText(String message) {
        if (tvOverlayStatus != null) {
            new Handler(Looper.getMainLooper()).post(
                    () -> tvOverlayStatus.setText(message));
        }
    }

    private void updatePartialText(String partial) {
        if (tvPartialText != null) {
            new Handler(Looper.getMainLooper()).post(
                    () -> tvPartialText.setText(partial));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Waveform orb animations
    // ─────────────────────────────────────────────────────────────────────

    private void startWaveAnimation() {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (orb1 == null) return;
            animateOrb(orb1, 0);
            animateOrb(orb2, 100);
            animateOrb(orb3, 200);
            animateOrb(orb4, 300);
            animateOrb(orb5, 400);
            animateOrb(orb6, 150);
            animateOrb(orb7, 50);
        });
    }

    private void animateOrb(View orb, long startDelay) {
        if (orb == null) return;
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator.ofFloat(
                orb, "scaleY", 1f, 2.5f, 1f);
        scaleY.setDuration(600);
        scaleY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        scaleY.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        scaleY.setStartDelay(startDelay);
        set.play(scaleY);
        set.start();
        orb.setTag(set);
    }

    private void animateOrbsToVolume(float rmsdB) {
        if (orb1 == null) return;
        // Map rms dB (-2 to 12) to scale (1.0 to 3.0)
        float normalized = Math.max(0, Math.min(1, (rmsdB + 2f) / 14f));
        float scale = 1f + normalized * 2.0f;
        new Handler(Looper.getMainLooper()).post(() -> {
            // Center orbs bounce most, outer orbs less
            if (orb4 != null) orb4.setScaleY(scale);
            if (orb3 != null) orb3.setScaleY(scale * 0.9f);
            if (orb5 != null) orb5.setScaleY(scale * 0.9f);
            if (orb2 != null) orb2.setScaleY(scale * 0.7f);
            if (orb6 != null) orb6.setScaleY(scale * 0.7f);
            if (orb1 != null) orb1.setScaleY(scale * 0.5f);
            if (orb7 != null) orb7.setScaleY(scale * 0.5f);
        });
    }

    private void stopWaveAnimation() {
        new Handler(Looper.getMainLooper()).post(() -> {
            resetOrb(orb1);
            resetOrb(orb2);
            resetOrb(orb3);
            resetOrb(orb4);
            resetOrb(orb5);
            resetOrb(orb6);
            resetOrb(orb7);
        });
    }

    private void resetOrb(View orb) {
        if (orb == null) return;
        Object tag = orb.getTag();
        if (tag instanceof android.animation.AnimatorSet) {
            ((android.animation.AnimatorSet) tag).cancel();
        }
        orb.setScaleY(1f);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Captain Wake Word", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Listens for 'Captain' wake word");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, DashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Captain is awake")
                .setContentText("Say 'Captain' anytime — I'm always listening")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Asset extractor (for captain.ppn)
    // ─────────────────────────────────────────────────────────────────────

    private String extractAsset(String assetName) {
        java.io.File file = new java.io.File(getFilesDir(), assetName);
        if (file.exists()) return file.getAbsolutePath();
        try (java.io.InputStream is = getAssets().open(assetName);
             java.io.FileOutputStream os = new java.io.FileOutputStream(file)) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract asset: " + assetName, e);
            return null;
        }
        return file.getAbsolutePath();
    }
}
