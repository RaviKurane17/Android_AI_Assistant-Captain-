package com.aiphone.agent.services;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import android.util.Base64;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import android.view.Display;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import com.aiphone.agent.models.Response;
import com.aiphone.agent.utils.DeviceUtils;
import com.google.firebase.database.FirebaseDatabase;

public class AgentAccessibilityService extends AccessibilityService {

    private static final String TAG = "AgentAccService";
    private static AgentAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility Service Connected");
    }

    public static AgentAccessibilityService getInstance() {
        return instance;
    }

    public boolean lockScreen() {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    public boolean goHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public boolean takeScreenshot() {
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    public boolean openNotifications() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public String readScreenContent() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return "Screen content unavailable.";
        
        StringBuilder sb = new StringBuilder();
        traverseNode(rootNode, sb);
        rootNode.recycle();
        
        String content = sb.toString().trim();
        return content.isEmpty() ? "No text visible on screen." : content;
    }

    private void traverseNode(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        
        if (node.getText() != null && node.getText().length() > 0) {
            sb.append(node.getText()).append("\n");
        } else if (node.getContentDescription() != null && node.getContentDescription().length() > 0) {
            sb.append(node.getContentDescription()).append("\n");
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            traverseNode(child, sb);
            if (child != null) {
                child.recycle();
            }
        }
    }

    public boolean isWaitingForWhatsappSend = false;
    public boolean isWaitingForYoutubePlay = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();

        if (isWaitingForWhatsappSend && pkg.contains("whatsapp")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (findAndClickByContentDesc(root, "Send")) {
                    isWaitingForWhatsappSend = false;
                }
            }
        }

        if (isWaitingForYoutubePlay && pkg.contains("youtube")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                // YouTube might just need to click the first video in the list
                // Search for node with content-desc containing "Go to channel" which usually means it's a video item,
                // or just clicking the first large clickable view.
                // For simplicity, click the first view that is clickable and has a content desc longer than 20 chars (usually video titles).
                if (clickFirstVideo(root)) {
                    isWaitingForYoutubePlay = false;
                }
            }
        }
    }

    private boolean findAndClickByContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null) return false;
        if (node.getContentDescription() != null && node.getContentDescription().toString().equalsIgnoreCase(desc) && node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findAndClickByContentDesc(child, desc)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    private boolean clickFirstVideo(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence desc = node.getContentDescription();
        CharSequence text = node.getText();
        
        String descStr = desc != null ? desc.toString().toLowerCase() : "";
        String textStr = text != null ? text.toString().toLowerCase() : "";
        
        // Videos usually have long descriptions or text containing views/ago
        boolean isVideo = (descStr.length() > 20 || textStr.contains("views") || textStr.contains("ago")) 
                          && !descStr.contains("search") && !textStr.contains("search");
                          
        if (isVideo) {
            // If the node itself is clickable, click it
            if (node.isClickable()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            // Otherwise try to click its parent
            AccessibilityNodeInfo parent = node.getParent();
            while (parent != null) {
                if (parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    parent.recycle();
                    return true;
                }
                AccessibilityNodeInfo nextParent = parent.getParent();
                parent.recycle();
                parent = nextParent;
            }
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (clickFirstVideo(child)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    public void captureVision(String commandId) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Executor executor = Executors.newSingleThreadExecutor();
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult screenshotResult) {
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.getHardwareBuffer(), screenshotResult.getColorSpace());
                    if (bitmap != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos);
                        byte[] compressedBytes = baos.toByteArray();
                        String base64Image = Base64.encodeToString(compressedBytes, Base64.DEFAULT);

                        Response response = new Response();
                        response.commandId = commandId;
                        response.targetDeviceId = DeviceUtils.getDeviceId(AgentAccessibilityService.this);
                        response.action = "vision_capture";
                        response.status = "success";
                        response.message = base64Image;
                        response.timestamp = System.currentTimeMillis();

                        FirebaseDatabase.getInstance().getReference("responses")
                                .child(commandId)
                                .setValue(response);
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    sendErrorResponse(commandId, "Failed to capture screen, code: " + errorCode);
                }
            });
        } else {
            sendErrorResponse(commandId, "Screen capture requires Android 11+");
        }
    }

    private void sendErrorResponse(String commandId, String errorMsg) {
        Response response = new Response();
        response.commandId = commandId;
        response.targetDeviceId = DeviceUtils.getDeviceId(this);
        response.action = "vision_capture";
        response.status = "error";
        response.error = errorMsg;
        response.timestamp = System.currentTimeMillis();
        FirebaseDatabase.getInstance().getReference("responses").child(commandId).setValue(response);
    }

    public boolean autoClick(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        return findAndClickByText(root, text);
    }

    private boolean findAndClickByText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase()) && node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (findAndClickByText(child, text)) {
                if (child != null) child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    public boolean autoScroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        return scrollNode(root, forward);
    }

    private boolean scrollNode(AccessibilityNodeInfo node, boolean forward) {
        if (node == null) return false;
        if (node.isScrollable()) {
            node.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (scrollNode(node.getChild(i), forward)) return true;
        }
        return false;
    }

    public boolean autoType(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null || !focused.isEditable()) {
            focused = findFirstEditableNode(root);
        }

        if (focused != null && focused.isEditable()) {
            // Try to click/focus the field if it isn't focused already
            if (!focused.isFocused()) {
                focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                try { Thread.sleep(300); } catch (Exception e) {} // Give UI time to focus
            }
            
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            focused.recycle();
            return success;
        }
        return false;
    }

    private AccessibilityNodeInfo findFirstEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isVisibleToUser()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo editable = findFirstEditableNode(node.getChild(i));
            if (editable != null) return editable;
        }
        return null;
    }

    public boolean unlockPhone(String pin) {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
        android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG + ":unlock");
        wl.acquire(3000);
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;

            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
                android.graphics.Path path = new android.graphics.Path();
                // Centralized fast fling to trigger unlock screen
                path.moveTo(width / 2f, height * 0.8f);
                path.lineTo(width / 2f, height * 0.3f);
                builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200));
                
                dispatchGesture(builder.build(), new GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                        // Wait 1.5s for keypad animation to finish
                        enterPinSequence(pin, width, height, 1500);
                    }
                }, null);
            }, 1000); // Wait 1 second for the screen to fully wake up before swiping
            
            return true;
        }
        return false;
    }

    private void enterPinSequence(String pin, int width, int height, long startDelay) {
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        long delay = startDelay;
        
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            handler.postDelayed(() -> {
                if (!autoClick(String.valueOf(c))) {
                    tapPinCoordinate(c, width, height);
                }
            }, delay);
            delay += 600;
        }
        
        handler.postDelayed(() -> {
            if (!autoClick("OK") && !autoClick("Enter") && !autoClick("Done")) {
                tapPinCoordinate('E', width, height);
            }
        }, delay + 600);
    }

    private void tapPinCoordinate(char c, int width, int height) {
        int x = 0, y = 0;
        // Adjusted coordinates for OnePlus Nord (AC2001)
        // Shifting UP by another 0.10 based on user feedback
        switch (c) {
            case '1': x = (int)(width * 0.20); y = (int)(height * 0.35); break;
            case '2': x = (int)(width * 0.50); y = (int)(height * 0.35); break;
            case '3': x = (int)(width * 0.80); y = (int)(height * 0.35); break;
            case '4': x = (int)(width * 0.20); y = (int)(height * 0.45); break;
            case '5': x = (int)(width * 0.50); y = (int)(height * 0.45); break;
            case '6': x = (int)(width * 0.80); y = (int)(height * 0.45); break;
            case '7': x = (int)(width * 0.20); y = (int)(height * 0.55); break;
            case '8': x = (int)(width * 0.50); y = (int)(height * 0.55); break;
            case '9': x = (int)(width * 0.80); y = (int)(height * 0.55); break;
            case '0': x = (int)(width * 0.50); y = (int)(height * 0.65); break;
            case 'E': x = (int)(width * 0.80); y = (int)(height * 0.65); break; // Enter/OK
            default: return;
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50));
            dispatchGesture(builder.build(), null, null);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}
