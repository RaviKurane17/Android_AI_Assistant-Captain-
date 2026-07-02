package com.aiphone.agent.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.aiphone.agent.R;
import com.aiphone.agent.utils.PermissionsHelper;

public class PermissionsActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permissions);

        Button btnRequestPermissions = findViewById(R.id.btnRequestPermissions);
        Button btnNotificationAccess = findViewById(R.id.btnNotificationAccess);

        btnRequestPermissions.setOnClickListener(v -> {
            if (!PermissionsHelper.hasPermissions(this, PermissionsHelper.getRequiredPermissions())) {
                PermissionsHelper.requestPermissions(this, PERMISSION_REQUEST_CODE);
            } else {
                Toast.makeText(this, "Permissions already granted", Toast.LENGTH_SHORT).show();
                checkAndProceed();
            }
        });

        btnNotificationAccess.setOnClickListener(v -> {
            if (!isNotificationServiceEnabled()) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                Toast.makeText(this, "Notification access already granted", Toast.LENGTH_SHORT).show();
            }
        });

        if (!Settings.canDrawOverlays(this)) {
            Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName()));
            startActivity(overlayIntent);
        }


        
        checkAndProceed();
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null) {
            final String[] names = flat.split(":");
            for (int i = 0; i < names.length; i++) {
                final ComponentName cn = ComponentName.unflattenFromString(names[i]);
                if (cn != null && cn.getPackageName().equals(pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkAndProceed() {
        if (PermissionsHelper.hasPermissions(this, PermissionsHelper.getRequiredPermissions()) && isNotificationServiceEnabled() && Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, DashboardActivity.class));
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                checkAndProceed();
            } else {
                Toast.makeText(this, "All permissions are required to function.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndProceed();
    }
}
