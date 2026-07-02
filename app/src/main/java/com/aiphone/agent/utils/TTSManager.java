package com.aiphone.agent.utils;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

public class TTSManager {
    private static final String TAG = "TTSManager";
    private static TextToSpeech tts;
    public static volatile boolean isSpeaking = false;
    private static Context appContext;
    private static boolean isInitialized = false;

    public static void init(Context context) {
        if (tts == null) {
            appContext = context.getApplicationContext();

            // Prefer Google TTS engine (higher quality than manufacturer TTS)
            String googleTtsEngine = "com.google.android.tts";
            tts = new TextToSpeech(appContext, status -> {
                if (status != TextToSpeech.ERROR) {
                    isInitialized = true;

                    // Set language
                    int langResult = tts.setLanguage(new Locale("en", "IN"));
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                            || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Fallback to English US
                        tts.setLanguage(Locale.US);
                    }

                    // Load speech rate from preferences (default = natural speed)
                    SharedPreferences prefs = appContext.getSharedPreferences(
                            "AgentPrefs", Context.MODE_PRIVATE);
                    float speechRate = prefs.getFloat("tts_speed", 1.05f);
                    float pitch = prefs.getFloat("tts_pitch", 0.95f);
                    tts.setSpeechRate(speechRate);
                    tts.setPitch(pitch);

                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            Log.d(TAG, "TTS started");
                            isSpeaking = true;
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            Log.d(TAG, "TTS finished");
                            isSpeaking = false;
                            // Notify WakeWordService to restart listening after TTS is done
                            Intent intent = new Intent("ACTION_TTS_DONE");
                            intent.setPackage(appContext.getPackageName());
                            appContext.sendBroadcast(intent);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            Log.e(TAG, "TTS error");
                            isSpeaking = false;
                            Intent intent = new Intent("ACTION_TTS_DONE");
                            intent.setPackage(appContext.getPackageName());
                            appContext.sendBroadcast(intent);
                        }
                    });

                    Log.d(TAG, "TTS initialized successfully");
                } else {
                    Log.e(TAG, "TTS initialization failed with status: " + status);
                    isInitialized = false;
                }
            }, googleTtsEngine);
        }
    }

    public static void speak(Context context, String text) {
        if (tts == null) {
            init(context);
        }
        if (text == null || text.trim().isEmpty()) return;

        // Set immediately so WakeWordService knows not to restart Porcupine yet
        isSpeaking = true;

        // Auto-detect Hindi (Devanagari) for language switching
        if (text.matches(".*[\u0900-\u097F].*")) {
            tts.setLanguage(new Locale("hi", "IN"));
        } else {
            // Reset to English-India for other text
            int langResult = tts.setLanguage(new Locale("en", "IN"));
            if (langResult == TextToSpeech.LANG_MISSING_DATA
                    || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US);
            }
        }

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "captain_utterance");

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "captain_utterance");
    }

    public static void stop() {
        if (tts != null && isSpeaking) {
            tts.stop();
            isSpeaking = false;
        }
    }

    public static void setSpeechRate(float rate) {
        if (tts != null) {
            tts.setSpeechRate(rate);
        }
    }

    public static void setPitch(float pitch) {
        if (tts != null) {
            tts.setPitch(pitch);
        }
    }

    public static void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInitialized = false;
        }
    }
}
