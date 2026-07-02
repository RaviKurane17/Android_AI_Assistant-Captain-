package com.aiphone.agent.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.aiphone.agent.utils.GroqAgent;
import com.aiphone.agent.utils.SecurePrefsManager;
import com.aiphone.agent.utils.TTSManager;

public class CommandReceiver extends BroadcastReceiver {
    private static final String TAG = "CommandReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.aiphone.agent.ACTION_EXECUTE_COMMAND".equals(intent.getAction())) {
            String command = intent.getStringExtra("command");
            Log.d(TAG, "Received scheduled command: " + command);
            
            if (command != null && !command.isEmpty()) {
                TTSManager.init(context);
                boolean handledOffline = com.aiphone.agent.utils.OfflineCommandParser.parseAndExecute(context, command);
                
                if (!handledOffline) {
                    String apiKey = SecurePrefsManager.getApiKey(context);
                    if (apiKey != null && !apiKey.isEmpty()) {
                        GroqAgent.processCommand(context, command, apiKey);
                    } else {
                        Log.e(TAG, "API key missing for scheduled command");
                    }
                }
            }
        }
    }
}
