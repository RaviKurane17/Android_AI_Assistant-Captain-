package com.aiphone.agent.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.aiphone.agent.R;
import com.aiphone.agent.utils.SecurePrefsManager;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageView btnSettingsBack = findViewById(R.id.btnSettingsBack);
        btnSettingsBack.setOnClickListener(v -> finish());

        EditText etSettingsPin = findViewById(R.id.etSettingsPin);
        EditText etSettingsPicovoice = findViewById(R.id.etSettingsPicovoice);
        EditText etSettingsApiKey = findViewById(R.id.etSettingsApiKey);
        EditText etSettingsModel = findViewById(R.id.etSettingsModel);
        EditText etSettingsBossInfo = findViewById(R.id.etSettingsBossInfo);
        SwitchCompat switchSettingsWakeWord = findViewById(R.id.switchSettingsWakeWord);
        Button btnSettingsSave = findViewById(R.id.btnSettingsSave);

        // Non-sensitive prefs
        SharedPreferences prefs = getSharedPreferences("AgentPrefs", MODE_PRIVATE);
        etSettingsModel.setText(prefs.getString("custom_groq_model", ""));
        etSettingsBossInfo.setText(prefs.getString("boss_info", ""));
        
        // Sensitive prefs loaded from encrypted storage
        etSettingsPin.setText(SecurePrefsManager.getPin(this));
        etSettingsPicovoice.setText(SecurePrefsManager.getPicovoiceKey(this));
        etSettingsApiKey.setText(SecurePrefsManager.getApiKey(this));
        
        // Load the saved wake word state
        boolean isWakeWordEnabled = prefs.getBoolean("wake_word_enabled", false);
        switchSettingsWakeWord.setChecked(isWakeWordEnabled);

        btnSettingsSave.setOnClickListener(v -> {
            boolean enabled = switchSettingsWakeWord.isChecked();
            String picovoiceKey = etSettingsPicovoice.getText().toString().trim();
            String apiKey = etSettingsApiKey.getText().toString().trim();
            String customModel = etSettingsModel.getText().toString().trim();
            String bossInfo = etSettingsBossInfo.getText().toString().trim();
            String pin = etSettingsPin.getText().toString().trim();
            
            // Save sensitive data to encrypted storage
            SecurePrefsManager.savePin(this, pin);
            SecurePrefsManager.savePicovoiceKey(this, picovoiceKey);
            SecurePrefsManager.saveApiKey(this, apiKey);
            
            // Save non-sensitive data to regular prefs
            prefs.edit()
                .putString("custom_groq_model", customModel)
                .putString("boss_info", bossInfo)
                .putBoolean("wake_word_enabled", enabled)
                .apply();
            
            if (!apiKey.isEmpty()) {
                if (!customModel.isEmpty()) {
                    // Force the active model to the custom model the user typed
                    prefs.edit().putString("active_groq_model", customModel).apply();
                } else {
                    // Or auto probe if left empty
                    com.aiphone.agent.utils.GroqAgent.probeAndSaveWorkingModel(this, apiKey);
                }
            }
            
            Toast.makeText(this, "Settings Saved! Testing API...", Toast.LENGTH_SHORT).show();
            finish(); // Close settings after saving
        });
    }
}
