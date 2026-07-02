package com.aiphone.agent.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.aiphone.agent.R;
import com.aiphone.agent.services.CommandForegroundService;
import com.aiphone.agent.utils.CommandExecutor;
import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class PcLinkActivity extends AppCompatActivity {

    private TextView tvDeviceId;
    private TextView tvStatus;
    private TextView tvBattery;
    private TextView tvCpu;
    private TextView tvRam;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pc_link);

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        tvDeviceId = findViewById(R.id.tvDeviceId);
        tvStatus = findViewById(R.id.tvStatus);
        tvBattery = findViewById(R.id.tvBattery);
        tvCpu = findViewById(R.id.tvCpu);
        tvRam = findViewById(R.id.tvRam);

        Button btnStartService = findViewById(R.id.btnStartService);
        Button btnStopService = findViewById(R.id.btnStopService);

        String deviceId = DeviceUtils.getDeviceId(this);
        tvDeviceId.setText(deviceId);

        updateBattery();
        listenToStatus(deviceId);

        btnStartService.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, CommandForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        });

        btnStopService.setOnClickListener(v -> {
            Intent serviceIntent = new Intent(this, CommandForegroundService.class);
            stopService(serviceIntent);
        });
    }

    private void updateBattery() {
        int battery = CommandExecutor.getBatteryLevel(this);
        tvBattery.setText(battery + "%");
    }

    private void listenToStatus(String deviceId) {
        FirebaseDatabase.getInstance().getReference("devices").child(deviceId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String status = snapshot.child("status").getValue(String.class);
                        if ("online".equals(status)) {
                            tvStatus.setText("CONNECTED");
                            tvStatus.setTextColor(getColor(R.color.captain_neon_blue));
                        } else {
                            tvStatus.setText("DISCONNECTED");
                            tvStatus.setTextColor(android.graphics.Color.RED);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });

        FirebaseDatabase.getInstance().getReference("pc_stats").child(deviceId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String cpu = snapshot.child("cpu").getValue(String.class);
                        String ram = snapshot.child("ram").getValue(String.class);
                        
                        if (cpu != null) tvCpu.setText(cpu);
                        else tvCpu.setText("--%");
                        
                        if (ram != null) tvRam.setText(ram);
                        else tvRam.setText("--%");
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }
}
