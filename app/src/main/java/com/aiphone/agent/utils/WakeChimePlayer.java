package com.aiphone.agent.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Plays the Siri activation sound effect from assets.
 * Only plays the first ~1 second of the sound for a clean chime.
 */
public class WakeChimePlayer {

    private static final String TAG = "WakeChimePlayer";
    private static MediaPlayer mediaPlayer;

    /**
     * Plays the first ~1 second of the Siri activation sound from assets/siri_chime.mp3
     */
    public static void playChime(Context context) {
        try {
            // Release previous player if still around
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception e) { /* ignore */ }
                mediaPlayer = null;
            }

            AssetFileDescriptor afd = context.getAssets().openFd("siri_chime.mp3");
            mediaPlayer = new MediaPlayer();
            
            // Set audio attributes to ALARM so it plays even if MUSIC stream is muted
            android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            mediaPlayer.setAudioAttributes(audioAttributes);
            
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.prepare();
            mediaPlayer.start();

            // Stop after ~1 second (only play the first chime part)
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    if (mediaPlayer != null) {
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping chime", e);
                }
            }, 1000); // 1 second

            mediaPlayer.setOnCompletionListener(mp -> {
                try {
                    mp.release();
                    mediaPlayer = null;
                } catch (Exception e) { /* ignore */ }
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to play siri chime from assets", e);
        }
    }
}
