package com.aiphone.agent.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class AgentTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        SharedPreferences prefs = getSharedPreferences("AgentPrefs", MODE_PRIVATE);
        boolean isEnabled = prefs.getBoolean("wake_word_enabled", false);
        boolean newState = !isEnabled;
        
        prefs.edit().putBoolean("wake_word_enabled", newState).apply();
        
        Intent serviceIntent = new Intent(this, WakeWordService.class);
        if (newState) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
        }
        
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile != null) {
            SharedPreferences prefs = getSharedPreferences("AgentPrefs", MODE_PRIVATE);
            boolean isEnabled = prefs.getBoolean("wake_word_enabled", false);
            
            if (isEnabled) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("Captain Mic On");
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("Captain Mic Off");
            }
            tile.updateTile();
        }
    }
}
