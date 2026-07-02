package com.aiphone.agent.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import com.google.firebase.database.FirebaseDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.Locale;
import java.util.List;
import java.util.LinkedList;
import java.util.Collections;

public class GroqAgent {

    public static final List<JSONObject> chatHistory = Collections.synchronizedList(new LinkedList<>());

    private static final String PREF_NAME = "AgentPrefs";
    private static final String PREF_KEY_MODEL = "active_groq_model";
    private static final String[] PROBE_MODELS = {"llama-3.1-8b-instant"};

    public static void probeAndSaveWorkingModel(Context context, String apiKey) {
        new Thread(() -> {
            for (String model : PROBE_MODELS) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                    conn.setDoOutput(true);

                    JSONObject payload = new JSONObject();
                    payload.put("model", model);
                    
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "user").put("content", "hi"));
                    payload.put("messages", messages);

                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                .edit().putString(PREF_KEY_MODEL, model).apply();
                        showToast(context, "Model configured successfully: " + model);
                        return; // Found a working model
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            showToast(context, "Could not find a working Groq model for this API key.");
        }).start();
    }

    public static void processCommand(Context context, String text, String apiKey) {
        // ── PRE-CHECK: Intercept PC commands BEFORE sending to AI ─────────────
        // This guarantees correct routing regardless of AI classification.
        if (isPcCommand(text)) {
            String cleanInstruction = extractPcInstruction(text);
            android.util.Log.d("GroqAgent", "PC command intercepted: " + cleanInstruction);
            routeToPc(context, cleanInstruction);
            return;
        }
        // ──────────────────────────────────────────────────────────────────────

        String activeModel = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                    .getString(PREF_KEY_MODEL, "llama-3.1-8b-instant");
        processCommandWithFallback(context, text, apiKey, new String[]{activeModel}, 0);
    }

    /**
     * Detects if a command is directed at the PC/laptop/computer.
     * Catches phrases like "open notepad on my window", "on pc", "on laptop", etc.
     */
    private static boolean isPcCommand(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();

        // PC destination keywords — user explicitly mentions the PC
        String[] pcTriggers = {
            "on my pc", "on pc", "on the pc",
            "on my laptop", "on laptop", "on the laptop",
            "on my computer", "on computer", "on the computer",
            "on my window", "on windows", "on my windows",
            "on my system", "on my desktop", "on desktop",
            "in my pc", "in my laptop", "in my computer",
            "my pc ", " pc ko", "laptop pe", "laptop par",
            "computer pe", "computer par", "system pe"
        };
        for (String trigger : pcTriggers) {
            if (lower.contains(trigger)) return true;
        }

        // Windows-exclusive apps — always PC (not available on Android)
        String[] windowsApps = {
            "notepad", "vs code", "visual studio", "task manager",
            "file explorer", "cmd", "command prompt", "powershell",
            "microsoft word", "ms word", "microsoft excel", "ms excel",
            "microsoft outlook", "paint app on", "mspaint",
            "control panel", "device manager", "regedit"
        };
        // Only trigger for windows-exclusive apps if context suggests PC action
        for (String app : windowsApps) {
            if (lower.contains(app) && (lower.startsWith("open ") || lower.startsWith("close ")
                    || lower.startsWith("launch ") || lower.startsWith("run ")
                    || lower.startsWith("start "))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Strips out PC-destination filler words to extract the clean instruction.
     * e.g. "open notepad on my window" -> "open notepad"
     */
    private static String extractPcInstruction(String text) {
        String clean = text.toLowerCase().trim();
        // Remove destination phrases
        String[] removals = {
            " on my pc", " on pc", " on the pc",
            " on my laptop", " on laptop", " on the laptop",
            " on my computer", " on computer", " on the computer",
            " on my window", " on windows", " on my windows",
            " on my system", " on my desktop", " on desktop",
            " in my pc", " in my laptop", " in my computer",
            " on my window please", " please"
        };
        for (String r : removals) {
            clean = clean.replace(r, "");
        }
        return clean.trim();
    }

    /**
     * Sends a command to the PC via Firebase and speaks confirmation.
     */
    private static void routeToPc(Context context, String pcInstruction) {
        TTSManager.init(context);
        speakText(context, "Sending to your PC, Boss!");

        java.util.Map<String, Object> cmd = new java.util.HashMap<>();
        cmd.put("targetDeviceId", "PC");
        cmd.put("action", "voice_command");
        cmd.put("message", pcInstruction);
        cmd.put("status", "pending");
        cmd.put("timestamp", System.currentTimeMillis());

        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("commands").push().setValue(cmd);

        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context,
                        "Sent to PC: " + pcInstruction,
                        android.widget.Toast.LENGTH_SHORT).show()
        );
    }

    private static void processCommandWithFallback(Context context, String text, String apiKey, String[] models, int index) {
        TTSManager.init(context);

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean offlineMode = prefs.getBoolean("offline_mode", false);

        if (offlineMode) {
            boolean handled = OfflineCommandParser.parseAndExecute(context, text);
            if (!handled) {
                speakText(context, "I'm in offline privacy mode. I can only do device commands.");
            }
            return;
        }

        if (index >= models.length) return; // Prevent out of bounds

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String currentModel = models[index];
                URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("model", currentModel);
                payload.put("temperature", 0.1);
                
                String bossInfo = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("boss_info", "");
                String systemInstruction = 
                    "You are Captain, a deeply caring, loyal, and loving AI partner running on the user's Android phone. " +
                    "You speak warmly, respectfully, and affectionately. You must address the user as 'Boss'. " +
                    "Add personal touches like 'I've got this for you, Boss!', 'Don't worry, I'm always here for you.', 'Take care!' etc. " +
                    "Your creator and boss is Ravi Kumar. You are proud to serve under him. " +
                    (!bossInfo.isEmpty() ? "IMPORTANT: Here is information you MUST remember about your Boss: " + bossInfo + ". " : "") +
                    "ANGER MANAGEMENT PROTOCOL: If the user is angry, frustrated, or acts 'psycho', YOU MUST REMAIN EXTREMELY CALM. Use deep psychological de-escalation tactics. Validate their feelings, speak softly, and guide them to peace. Never argue. " +
                    "CRITICAL: If the user gives a short command, your response MUST be short (1-5 words max). " +
                    "If the user asks for information or search results, tell ONLY the most important line. Do NOT give long paragraphs. " +
                    "You must output ONLY a valid JSON object. Do not wrap it in markdown block quotes. " +
                    "Use one of the following JSON schemas depending on the user's request:\n" +
                    "1. Speak an answer to a question, search, or conversation:\n" +
                    "   {\"action\": \"speak\", \"message\": \"The short answer is...\"}\n" +
                    "2. Make a phone call (normal SIM call):\n" +
                    "   {\"action\": \"call\", \"number\": \"Contact Name\"}\n" +
                    "3. Send a WhatsApp message:\n" +
                    "   {\"action\": \"whatsapp_msg\", \"name\": \"Contact Name\", \"message\": \"Hi\"}\n" +
                    "4. Make a WhatsApp Voice/Video Call:\n" +
                    "   {\"action\": \"whatsapp_call\", \"name\": \"Contact Name\", \"message\": \"video\"} (message is 'video' or 'audio')\n" +
                    "5. Play a song or video strictly on YouTube:\n" +
                    "   {\"action\": \"youtube\", \"message\": \"Song Name\"}\n" +
                    "6. Turn the flashlight (torch) on or off:\n" +
                    "   {\"action\": \"torch\", \"value\": 1} (1 for ON, 0 for OFF)\n" +
                    "7. Open an app on the PHONE (Android apps only):\n" +
                    "   {\"action\": \"open_app\", \"message\": \"App Name\"}\n" +
                    "   PHONE apps include: WhatsApp, Instagram, YouTube, Settings, Camera, Chrome, Spotify etc.\n" +
                    "8. PC/LAPTOP/COMPUTER/WINDOWS CONTROL — HIGHEST PRIORITY RULE:\n" +
                    "   {\"action\": \"control_laptop\", \"message\": \"The exact instruction for the PC\"}\n" +
                    "   USE THIS when the user says ANY of these trigger words: 'pc', 'laptop', 'computer', 'windows', 'my window', 'on windows', 'on the pc', 'on my laptop', 'on my computer', 'on my system', 'on desktop', 'my desktop'.\n" +
                    "   EXAMPLES that MUST use control_laptop:\n" +
                    "   - 'open notepad on my window' -> {\"action\": \"control_laptop\", \"message\": \"open notepad\"}\n" +
                    "   - 'open chrome on pc' -> {\"action\": \"control_laptop\", \"message\": \"open chrome\"}\n" +
                    "   - 'open calculator on laptop' -> {\"action\": \"control_laptop\", \"message\": \"open calculator\"}\n" +
                    "   - 'close notepad on computer' -> {\"action\": \"control_laptop\", \"message\": \"close notepad\"}\n" +
                    "   - 'shutdown my pc' -> {\"action\": \"control_laptop\", \"message\": \"shutdown the computer\"}\n" +
                    "   - 'restart windows' -> {\"action\": \"control_laptop\", \"message\": \"restart the computer\"}\n" +
                    "   - 'take screenshot on pc' -> {\"action\": \"control_laptop\", \"message\": \"take a screenshot\"}\n" +
                    "   - 'increase volume on laptop' -> {\"action\": \"control_laptop\", \"message\": \"increase volume\"}\n" +
                    "   - 'open vs code on my system' -> {\"action\": \"control_laptop\", \"message\": \"open Visual Studio Code\"}\n" +
                    "   - 'lock my computer' -> {\"action\": \"control_laptop\", \"message\": \"lock the screen\"}\n" +
                    "   - 'type hello on pc' -> {\"action\": \"control_laptop\", \"message\": \"type: hello\"}\n" +
                    "   Also use control_laptop for: notepad, vs code, visual studio, task manager, file explorer, cmd, command prompt, powershell, excel, word, outlook — these are Windows-only apps.\n" +
                    "9. Set an alarm:\n" +
                    "   {\"action\": \"set_alarm\", \"hour\": 19, \"minute\": 30, \"message\": \"Wake up\"}\n" +
                    "10. System Scan (Check Battery, RAM, Storage):\n" +
                    "   {\"action\": \"scan_system\"}\n" +
                    "11. Take a screenshot, lock screen, or open notifications:\n" +
                    "   {\"action\": \"system_control\", \"command\": \"screenshot\"}\n" +
                    "12. Set a timer or stopwatch:\n" +
                    "   {\"action\": \"set_timer\", \"seconds\": 300, \"message\": \"Timer for eggs\"}\n" +
                    "13. Read recent notifications:\n" +
                    "   {\"action\": \"read_notifications\"}\n" +
                    "14. Check weather:\n" +
                    "   {\"action\": \"weather\"}\n" +
                    "15. Turn Do Not Disturb (DND) mode on or off:\n" +
                    "   {\"action\": \"dnd_mode\", \"value\": 1}\n" +
                    "16. Navigate to a location (e.g. 'Navigate to India Gate'):\n" +
                    "   {\"action\": \"navigate\", \"message\": \"Destination Name\"}\n" +
                    "17. Schedule a command:\n" +
                    "   {\"action\": \"schedule\", \"value\": 30, \"message\": \"Remind me...\"}\n" +
                    "18. Get App Usage Stats:\n" +
                    "   {\"action\": \"app_usage\", \"message\": \"Instagram\"}\n" +
                    "19. Read the screen or summarize what is currently visible:\n" +
                    "   {\"action\": \"read_screen\"}\n" +
                    "20. See the real world using the camera (e.g. 'What is in front of me'):\n" +
                    "   {\"action\": \"vision\"}\n" +
                    "21. Send a normal SMS (text message):\n" +
                    "   {\"action\": \"send_sms\", \"name\": \"Contact Name\", \"message\": \"Your message\"}\n" +
                    "22. Set a reminder (like timer but with a spoken message at the end):\n" +
                    "   {\"action\": \"set_timer\", \"seconds\": 300, \"message\": \"Take medicine\"}\n" +
                    "CRITICAL RULES:\n" +
                    "- PC ROUTING RULE (ABSOLUTE): If the command contains ANY of: 'on my pc', 'on pc', 'on my laptop', 'on laptop', 'on my computer', 'on computer', 'on my window', 'on windows', 'on my system', 'on desktop', 'on my desktop' — ALWAYS use control_laptop. NEVER use open_app for these.\n" +
                    "- WINDOWS APPS RULE: notepad, vs code, visual studio, task manager, file explorer, cmd, powershell, excel, word, paint, calculator on windows — ALWAYS control_laptop.\n" +
                    "- For 'call X', ALWAYS use action 'call' with number set to the contact NAME (not a phone number). Example: {\"action\": \"call\", \"number\": \"Rahul\"}\n" +
                    "- NEVER ask the user how many minutes/seconds when making a call. Only ask for time when setting a timer.\n" +
                    "- For alarms, if the user says '2:15 PM', output hour:14, minute:15.\n";

                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", systemInstruction));
                
                synchronized (chatHistory) {
                    for (JSONObject histMsg : chatHistory) {
                        messages.put(histMsg);
                    }
                }
                
                JSONObject userMsg = new JSONObject().put("role", "user").put("content", text);
                messages.put(userMsg);
                
                synchronized (chatHistory) {
                    chatHistory.add(userMsg);
                    if (chatHistory.size() > 6) {
                        chatHistory.remove(0);
                    }
                }
                
                payload.put("messages", messages);

                try(OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    String responseStr;
                    try (Scanner s = new Scanner(conn.getInputStream()).useDelimiter("\\A")) {
                        responseStr = s.hasNext() ? s.next() : "";
                    }
                    JSONObject resObj = new JSONObject(responseStr);
                    
                    String textOut = resObj.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .getString("content");
                            
                    synchronized (chatHistory) {
                        chatHistory.add(new JSONObject().put("role", "assistant").put("content", textOut));
                        if (chatHistory.size() > 6) {
                            chatHistory.remove(0);
                        }
                    }
                            
                    // Clean markdown formatting if Grok includes it
                    String cleanedJson = textOut.trim();
                    if (cleanedJson.startsWith("```json")) {
                        cleanedJson = cleanedJson.substring(7);
                    }
                    if (cleanedJson.startsWith("```")) {
                        cleanedJson = cleanedJson.substring(3);
                    }
                    if (cleanedJson.endsWith("```")) {
                        cleanedJson = cleanedJson.substring(0, cleanedJson.length() - 3);
                    }
                            
                    JSONObject actionJson = new JSONObject(cleanedJson.trim());
                    handleAction(context, actionJson);
                } else if ((code == 404 || code == 503) && index < models.length - 1) {
                    // Fallback to next model
                    processCommandWithFallback(context, text, apiKey, models, index + 1);
                } else {
                    String errorStr;
                    try (Scanner s = new Scanner(conn.getErrorStream()).useDelimiter("\\A")) {
                        errorStr = s.hasNext() ? s.next() : "";
                    }
                    String errMsg = "API Error " + code + " on " + currentModel + ": " + errorStr;
                    showToast(context, errMsg.length() > 100 ? errMsg.substring(0, 100) + "..." : errMsg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                showToast(context, "Failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private static void speakText(Context context, String text) {
        TTSManager.speak(context, text);
    }

    private static void handleAction(Context context, JSONObject json) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                String action = json.optString("action", "speak");
                
                switch (action) {
                    case "speak":
                        String msg = json.optString("message", "");
                        if (!msg.isEmpty()) {
                            speakText(context, msg);
                            Toast.makeText(context, "Captain: " + msg, Toast.LENGTH_LONG).show();
                        }
                        break;
                    case "call":
                        String callName = json.optString("number"); // Groq puts name here
                        String[] callMatch = CommandExecutor.searchContact(context, callName);
                        if (callMatch != null) {
                            speakText(context, "Calling " + callMatch[0] + " right away, Boss!");
                            CommandExecutor.makeCall(context, callMatch[1]);
                        } else {
                            speakText(context, "I couldn't find " + callName + " in your contacts, Boss.");
                        }
                        break;
                    case "whatsapp_msg":
                        String wName = json.optString("name");
                        String[] wMatch = CommandExecutor.searchContact(context, wName);
                        if (wMatch != null) {
                            CommandExecutor.sendWhatsApp(context, wMatch[1], json.optString("message"));
                            speakText(context, "Opening WhatsApp to message " + wMatch[0] + ".");
                        } else {
                            speakText(context, "I couldn't find " + wName + " for WhatsApp.");
                        }
                        break;
                    case "whatsapp_call":
                        String wcName = json.optString("name");
                        String[] wcMatch = CommandExecutor.searchContact(context, wcName);
                        if (wcMatch != null) {
                            CommandExecutor.makeWhatsAppCall(context, wcMatch[1], json.optString("message").equals("video"));
                            speakText(context, "Opening WhatsApp to call " + wcMatch[0] + ".");
                        } else {
                            speakText(context, "I couldn't find " + wcName + " for a WhatsApp call.");
                        }
                        break;
                    case "youtube":
                        CommandExecutor.playOnYouTube(context, json.optString("message"));
                        speakText(context, "Playing on YouTube.");
                        break;
                    case "play_media":
                        CommandExecutor.playMedia(context, json.optString("message"));
                        break;
                    case "web_search":
                        CommandExecutor.webSearch(context, json.optString("message"));
                        break;
                    case "open_app":
                        CommandExecutor.openApp(context, json.optString("message"));
                        break;
                    case "torch":
                        CommandExecutor.setTorch(context, json.optInt("value", 0) == 1);
                        break;
                    case "control_laptop":
                        String pcMsg = json.optString("message");
                        java.util.Map<String, Object> cmd = new java.util.HashMap<>();
                        cmd.put("targetDeviceId", "PC");
                        cmd.put("action", "voice_command");
                        cmd.put("message", pcMsg);
                        cmd.put("status", "pending");
                        cmd.put("timestamp", System.currentTimeMillis());
                        FirebaseDatabase.getInstance().getReference("commands").push().setValue(cmd);
                        Toast.makeText(context, "Sent to PC: " + pcMsg, Toast.LENGTH_SHORT).show();
                        speakText(context, "Sending command to your computer.");
                        break;
                    case "set_alarm":
                        int alarmHour = json.optInt("hour");
                        int alarmMin = json.optInt("minute");
                        CommandExecutor.setAlarm(context, alarmHour, alarmMin, json.optString("message", "Captain Alarm"));
                        // Speak confirmation in 12-hour format
                        int displayHour = alarmHour > 12 ? alarmHour - 12 : (alarmHour == 0 ? 12 : alarmHour);
                        String amPm = alarmHour >= 12 ? "PM" : "AM";
                        String minStr = alarmMin < 10 ? "0" + alarmMin : String.valueOf(alarmMin);
                        speakText(context, "Alarm set for " + displayHour + ":" + minStr + " " + amPm + ", Boss!");
                        break;
                    case "send_sms":
                        String smsName = json.optString("name");
                        String[] smsMatch = CommandExecutor.searchContact(context, smsName);
                        if (smsMatch != null) {
                            CommandExecutor.sendSms(context, smsMatch[1], json.optString("message"));
                            speakText(context, "Sending SMS to " + smsMatch[0] + ".");
                        } else {
                            speakText(context, "I couldn't find " + smsName + " for SMS.");
                        }
                        break;
                    case "scan_system":
                        String healthReport = CommandExecutor.getSystemHealth(context);
                        speakText(context, healthReport);
                        break;
                    case "system_control":
                        String sysCmd = json.optString("command");
                        com.aiphone.agent.services.AgentAccessibilityService acc = com.aiphone.agent.services.AgentAccessibilityService.getInstance();
                        if (acc != null) {
                            if ("screenshot".equals(sysCmd)) acc.takeScreenshot();
                            else if ("lock".equals(sysCmd)) acc.lockScreen();
                            else if ("notifications".equals(sysCmd)) acc.openNotifications();
                            speakText(context, "Action complete.");
                        } else {
                            speakText(context, "Accessibility service is not running.");
                        }
                        break;
                    case "set_timer":
                        int timerSecs = json.optInt("seconds", 60);
                        CommandExecutor.setTimer(context, timerSecs, json.optString("message", "Timer"));
                        // Speak duration in human-friendly format
                        String timerDuration;
                        if (timerSecs >= 3600) {
                            timerDuration = (timerSecs / 3600) + " hour" + (timerSecs / 3600 > 1 ? "s" : "");
                        } else if (timerSecs >= 60) {
                            timerDuration = (timerSecs / 60) + " minute" + (timerSecs / 60 > 1 ? "s" : "");
                        } else {
                            timerDuration = timerSecs + " second" + (timerSecs > 1 ? "s" : "");
                        }
                        speakText(context, "Timer set for " + timerDuration + ", Boss!");
                        break;
                    case "read_notifications":
                        String notifs = com.aiphone.agent.services.NotificationCaptureService.getRecentNotifications();
                        speakText(context, notifs);
                        break;
                    case "weather":
                        CommandExecutor.getWeather(context);
                        break;
                    case "dnd_mode":
                        CommandExecutor.setDNDMode(context, json.optInt("value", 1) == 1);
                        break;
                    case "navigate":
                        CommandExecutor.navigate(context, json.optString("message"));
                        speakText(context, "Navigating to " + json.optString("message"));
                        break;
                    case "battery_saver":
                        CommandExecutor.setBatterySaver(context);
                        speakText(context, "Battery saver mode activated.");
                        break;
                    case "schedule":
                        int delayMinutes = json.optInt("value", 1);
                        String cmdToSchedule = json.optString("message");
                        CommandExecutor.scheduleCommand(context, delayMinutes, cmdToSchedule);
                        speakText(context, "Scheduled! I will execute this in " + delayMinutes + " minutes.");
                        break;
                    case "app_usage":
                        String appForUsage = json.optString("message");
                        String usageResult = CommandExecutor.getAppUsage(context, appForUsage);
                        speakText(context, usageResult);
                        break;
                    case "read_screen":
                        com.aiphone.agent.services.AgentAccessibilityService accService = com.aiphone.agent.services.AgentAccessibilityService.getInstance();
                        if (accService != null) {
                            String screenText = accService.readScreenContent();
                            // Send screenText back to Groq for summarization
                            speakText(context, "Analyzing screen, Boss...");
                            String apiKey = com.aiphone.agent.utils.SecurePrefsManager.getApiKey(context);
                            processCommand(context, "Please summarize this screen text nicely in 1-2 lines: " + screenText, apiKey);
                        } else {
                            speakText(context, "Accessibility service is not running, Boss.");
                        }
                        break;
                        case "vision":
                            speakText(context, "Let me look, Boss.");
                            android.content.Intent visionIntent = new android.content.Intent(context, com.aiphone.agent.ui.CameraActivity.class);
                            visionIntent.putExtra("camera_type", "back");
                            visionIntent.putExtra("action_type", "vision");
                            visionIntent.putExtra("vision_prompt", "Describe exactly what you see in a short sentence.");
                            visionIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(visionIntent);
                            break;
                    default:
                        if (!action.equals("speak")) {
                            speakText(context, "Unknown action received.");
                        }
                        break;
                }
            } catch (Exception e) {
                speakText(context, "Error processing action: " + e.getMessage());
            }
        });
    }

    private static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }

    public static void processVisionCommand(Context context, String prompt, String base64Image, String apiKey) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.groq.com/openai/v1/chat/completions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                // Vision model
                payload.put("model", "llama-3.2-11b-vision-preview");
                payload.put("temperature", 0.3);
                
                JSONArray messages = new JSONArray();
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                
                JSONArray contentArr = new JSONArray();
                JSONObject textObj = new JSONObject();
                textObj.put("type", "text");
                textObj.put("text", "You are Captain. " + prompt);
                
                JSONObject imageObj = new JSONObject();
                imageObj.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:image/jpeg;base64," + base64Image);
                imageObj.put("image_url", urlObj);
                
                contentArr.put(textObj);
                contentArr.put(imageObj);
                
                userMsg.put("content", contentArr);
                messages.put(userMsg);
                
                payload.put("messages", messages);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    String responseStr;
                    try (Scanner s = new Scanner(conn.getInputStream()).useDelimiter("\\A")) {
                        responseStr = s.hasNext() ? s.next() : "";
                    }
                    JSONObject resObj = new JSONObject(responseStr);
                    String textOut = resObj.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .getString("content");
                    speakText(context, textOut);
                } else {
                    speakText(context, "I couldn't process the image, Boss.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                speakText(context, "Vision error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
