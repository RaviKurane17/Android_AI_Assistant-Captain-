package com.aiphone.agent.utils;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OfflineCommandParser {

    private static final String TAG = "OfflineCommandParser";
    

    public static boolean parseAndExecute(Context context, String cmd) {
        cmd = cmd.toLowerCase().trim();
        TTSManager.init(context);
        SmartIntentClassifier.init(context);

        try {
            // ===== CALL COMMAND — handled FIRST before ML classifier to avoid false matches =====
            if (cmd.startsWith("call ")) {
                String name = cmd.substring(5).trim();
                String[] match = CommandExecutor.searchContact(context, name);
                if (match != null) {
                    String matchedName = match[0];
                    String matchedNumber = match[1];
                    speak(context, "Calling " + matchedName + "!");
                    CommandExecutor.makeCall(context, matchedNumber);
                } else {
                    speak(context, "I couldn't find " + name + " in your contacts.");
                }
                return true;
            }

            // ===== SMS COMMAND — handled early to avoid ML misclassification =====
            // Patterns: "send sms to rahul saying hello" / "text rahul hello" / "message rahul saying hi"
            if (cmd.contains("send sms") || cmd.contains("send a sms") || cmd.contains("send text") ||
                cmd.contains("send message") || cmd.startsWith("text ") || cmd.startsWith("sms ")) {

                // Try to extract contact name and message body
                // Pattern: "send sms to <name> saying <message>"
                java.util.regex.Matcher smsMatcher = java.util.regex.Pattern.compile(
                    "(?:send\\s+(?:sms|text|message|a\\s+sms)\\s+(?:to\\s+)?)([\\w\\s]+?)\\s+(?:saying|that|:|message)\\s+(.+)"
                ).matcher(cmd);

                String smsContactName = null;
                String smsMessage = null;

                if (smsMatcher.find()) {
                    smsContactName = smsMatcher.group(1).trim();
                    smsMessage = smsMatcher.group(2).trim();
                } else {
                    // Pattern: "text <name> <message>" — first word after "text" is name, rest is message
                    java.util.regex.Matcher simpleMatcher = java.util.regex.Pattern.compile(
                        "(?:text|sms)\\s+(\\w+)\\s+(.+)"
                    ).matcher(cmd);
                    if (simpleMatcher.find()) {
                        smsContactName = simpleMatcher.group(1).trim();
                        smsMessage = simpleMatcher.group(2).trim();
                    }
                }

                if (smsContactName != null && smsMessage != null) {
                    String[] match = CommandExecutor.searchContact(context, smsContactName);
                    if (match != null) {
                        String matchedName = match[0];
                        String matchedNumber = match[1];
                        speak(context, "Sending SMS to " + matchedName + ", Boss!");
                        CommandExecutor.sendSms(context, matchedNumber, smsMessage);
                    } else {
                        speak(context, "I couldn't find " + smsContactName + " in your contacts, Boss.");
                    }
                } else {
                    speak(context, "Please say: send SMS to Rahul saying hello, Boss.");
                }
                return true;
            }

            String intent = SmartIntentClassifier.classifyIntent(cmd);

            // ===== CONTINUOUS MODE TOGGLES =====
            if (cmd.contains("turn on continuous") || cmd.contains("listen continuous")) {
                CommandExecutor.isContinuousMode = true;
                speak(context, "Continuous listening mode activated. I will keep listening after I speak.");
                return true;
            } else if (cmd.matches(".*\\b(stop listening|exit continuous|close continuous|exit|stop|sleep|close|shutdown|turn off continuous|deactivate continuous)\\b.*")) {
                CommandExecutor.isContinuousMode = false;
                speak(context, "Continuous listening deactivated. Going back to normal mode.");
                return true;
            }

            // ===== OFFLINE GENERAL Q&A (Identity, Time, Greetings) =====
            if (handleGeneralQA(context, cmd)) {
                return true;
            }

            // ===== SMART NLP OFFLINE INTENTS =====
            switch (intent) {
                case SmartIntentClassifier.INTENT_TORCH_ON:
                    CommandExecutor.setTorch(context, true);
                    speak(context, "Flashlight turned on.");
                    return true;
                case SmartIntentClassifier.INTENT_TORCH_OFF:
                    CommandExecutor.setTorch(context, false);
                    speak(context, "Flashlight turned off.");
                    return true;
                case SmartIntentClassifier.INTENT_WIFI_ON:
                    CommandExecutor.setWifi(context, true);
                    speak(context, "WiFi turned on.");
                    return true;
                case SmartIntentClassifier.INTENT_WIFI_OFF:
                    CommandExecutor.setWifi(context, false);
                    speak(context, "WiFi turned off.");
                    return true;
                case SmartIntentClassifier.INTENT_ALARM_SET:
                    // Extract exact time using ML Kit
                    java.util.List<com.google.mlkit.nl.entityextraction.EntityAnnotation> entities = SmartIntentClassifier.extractEntitiesSync(cmd);
                    boolean timeFound = false;
                    for (com.google.mlkit.nl.entityextraction.EntityAnnotation annotation : entities) {
                        for (com.google.mlkit.nl.entityextraction.Entity entity : annotation.getEntities()) {
                            if (entity instanceof com.google.mlkit.nl.entityextraction.DateTimeEntity) {
                                com.google.mlkit.nl.entityextraction.DateTimeEntity dt = (com.google.mlkit.nl.entityextraction.DateTimeEntity) entity;
                                long timestamp = dt.getTimestampMillis();
                                if (timestamp > 0) {
                                    java.util.Calendar cal = java.util.Calendar.getInstance();
                                    cal.setTimeInMillis(timestamp);
                                    CommandExecutor.setAlarm(context, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), "Captain Alarm");
                                    speak(context, "I have set the alarm based on your request.");
                                    timeFound = true;
                                    break;
                                }
                            }
                        }
                        if (timeFound) break;
                    }
                    if (!timeFound) {
                        // Regex Fallback
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d{1,2})(?:\\s*:\\s*(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?");
                        java.util.regex.Matcher m = p.matcher(cmd);
                        if (m.find()) {
                            int hr = Integer.parseInt(m.group(1));
                            int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                            String ampm = m.group(3);
                            if (ampm != null) {
                                ampm = ampm.replace(".", "");
                                if (ampm.equals("pm") && hr < 12) hr += 12;
                                if (ampm.equals("am") && hr == 12) hr = 0;
                            }
                            CommandExecutor.setAlarm(context, hr, min, "Captain Alarm");
                            speak(context, "I have set the alarm for you.");
                        } else {
                            speak(context, "I couldn't detect the exact time for the alarm.");
                        }
                    }
                    return true;
                case SmartIntentClassifier.INTENT_TIMER_SET:
                    java.util.regex.Pattern tp = java.util.regex.Pattern.compile("(\\d+)\\s*(min|minute|minutes|sec|second|seconds|hr|hour|hours)");
                    java.util.regex.Matcher tm = tp.matcher(cmd);
                    if (tm.find()) {
                        int val = Integer.parseInt(tm.group(1));
                        String unit = tm.group(2);
                        int seconds = val;
                        if (unit.startsWith("min")) seconds = val * 60;
                        else if (unit.startsWith("h")) seconds = val * 3600;
                        CommandExecutor.setTimer(context, seconds, "Captain Timer");
                        String friendlyUnit = unit.startsWith("min") ? "minute" : unit.startsWith("h") ? "hour" : "second";
                        speak(context, "Timer set for " + val + " " + friendlyUnit + (val > 1 ? "s" : "") + ", Boss!");
                    } else {
                        speak(context, "Please tell me how long, Boss. For example: set timer 5 minutes.");
                    }
                    return true;
            }

            // ===== PC / LAPTOP / WINDOWS COMMANDS (check BEFORE phone open_app) =====
            if (cmd.startsWith("open ") || cmd.startsWith("close ") || cmd.startsWith("launch ")
                    || cmd.startsWith("start ") || cmd.startsWith("run ")) {

                // Step 1: Check if command has explicit PC destination keywords
                boolean hasPcKeyword = cmd.contains(" on my pc") || cmd.contains(" on pc")
                        || cmd.contains(" on the pc") || cmd.contains(" on my laptop")
                        || cmd.contains(" on laptop") || cmd.contains(" on my computer")
                        || cmd.contains(" on computer") || cmd.contains(" on my window")
                        || cmd.contains(" on windows") || cmd.contains(" on my windows")
                        || cmd.contains(" on my system") || cmd.contains(" on my desktop")
                        || cmd.contains(" on desktop") || cmd.contains(" on the laptop")
                        || cmd.contains(" in my pc") || cmd.contains(" in my laptop")
                        || cmd.contains(" in my computer");

                // Step 2: Check if it mentions a Windows-exclusive app
                boolean isWindowsApp = cmd.contains("notepad") || cmd.contains("task manager")
                        || cmd.contains("taskmgr") || cmd.contains("vs code")
                        || cmd.contains("visual studio") || cmd.contains("cmd")
                        || cmd.contains("command prompt") || cmd.contains("powershell")
                        || cmd.contains("file explorer") || cmd.contains("ms word")
                        || cmd.contains("microsoft word") || cmd.contains("ms excel")
                        || cmd.contains("microsoft excel") || cmd.contains("mspaint")
                        || cmd.contains("regedit") || cmd.contains("device manager")
                        || cmd.contains("control panel");

                if (hasPcKeyword || isWindowsApp) {
                    // Strip PC destination phrases to get clean instruction
                    String pcInstruction = cmd
                            .replace(" on my pc", "").replace(" on pc", "")
                            .replace(" on the pc", "").replace(" on my laptop", "")
                            .replace(" on laptop", "").replace(" on my computer", "")
                            .replace(" on computer", "").replace(" on my window", "")
                            .replace(" on windows", "").replace(" on my windows", "")
                            .replace(" on my system", "").replace(" on my desktop", "")
                            .replace(" on desktop", "").replace(" on the laptop", "")
                            .replace(" in my pc", "").replace(" in my laptop", "")
                            .replace(" in my computer", "").replace(" please", "").trim();

                    speak(context, "Sending to your PC, Boss!");
                    sendCommandToPC(context, pcInstruction);
                    return true;
                }
            }

            // ===== FALLBACK DEVICE COMMANDS (phone only) =====
            if (cmd.startsWith("open ")) {
                String appName = cmd.substring(5).trim();
                boolean success = CommandExecutor.openApp(context, appName);
                if (success) speak(context, "Opening " + appName);
                else speak(context, "Sorry, I couldn't find the app " + appName + " on this phone.");
                return true;
            } else if (cmd.equals("play music") || cmd.equals("play song") || cmd.equals("play songs")) {
                speak(context, "Let me open your music player!");
                CommandExecutor.openMusicPlayer(context);
                return true;
            } else if (cmd.startsWith("play ")) {
                String song = cmd.substring(5).trim();
                speak(context, "Playing " + song + " for you!");
                CommandExecutor.playMedia(context, song);
                return true;
            } else if (cmd.contains("close app") || cmd.contains("go home") || cmd.contains("go to home")) {
                speak(context, "Going to home screen!");
                CommandExecutor.goHome(context);
                return true;
            } else if (cmd.contains("torch on") || cmd.contains("flashlight on") || cmd.contains("flash on")) {
                CommandExecutor.setTorch(context, true);
                speak(context, "Torch is on! Be careful with your eyes.");
                return true;
            } else if (cmd.contains("torch off") || cmd.contains("flashlight off") || cmd.contains("flash off")) {
                CommandExecutor.setTorch(context, false);
                speak(context, "Torch is off now.");
                return true;
            } else if (cmd.contains("wifi on")) {
                CommandExecutor.setWifi(context, true);
                speak(context, "Opening wifi settings for you.");
                return true;
            } else if (cmd.contains("wifi off")) {
                CommandExecutor.setWifi(context, false);
                speak(context, "Opening wifi settings.");
                return true;
            } else if (cmd.contains("bluetooth on")) {
                CommandExecutor.setBluetooth(context, true);
                speak(context, "Turning on bluetooth.");
                return true;
            } else if (cmd.contains("bluetooth off")) {
                CommandExecutor.setBluetooth(context, false);
                speak(context, "Turning off bluetooth.");
                return true;
            } else if (cmd.contains("battery") && !cmd.contains("save") && !cmd.contains("saver")) {
                int level = CommandExecutor.getBatteryLevel(context);
                if (level >= 0) {
                    String msg;
                    if (level <= 15)
                        msg = "Battery is critically low at " + level + " percent! Please charge me soon.";
                    else if (level <= 30)
                        msg = "Battery is at " + level + " percent. You should charge soon.";
                    else if (level >= 80)
                        msg = "Battery is healthy at " + level + " percent. We're good to go!";
                    else
                        msg = "Battery is at " + level + " percent.";
                    speak(context, msg);
                    return true;
                }
            } else if (cmd.contains("volume up") || cmd.contains("volume max")) {
                CommandExecutor.setVolume(context, 100);
                speak(context, "Volume is at maximum now.");
                return true;
            } else if (cmd.contains("volume down") || cmd.contains("volume low") || cmd.contains("volume min")) {
                CommandExecutor.setVolume(context, 20);
                speak(context, "Volume lowered.");
                return true;
            } else if (cmd.contains("mute") || cmd.contains("silent")) {
                CommandExecutor.setVolume(context, 0);
                speak(context, "Phone is on silent mode now.");
                return true;

                // ===== ALARM (robust regex parser) =====
            } else if (cmd.contains("alarm")) {
                return handleAlarmCommand(context, cmd);

                // ===== SYSTEM CONTROLS =====
            } else if (cmd.contains("take screenshot") || cmd.contains("screenshot")) {
                com.aiphone.agent.services.AgentAccessibilityService acc = com.aiphone.agent.services.AgentAccessibilityService
                        .getInstance();
                if (acc != null) {
                    speak(context, "Taking a screenshot for you!");
                    acc.takeScreenshot();
                    return true;
                }
            } else if (cmd.contains("lock screen") || cmd.contains("lock phone") || cmd.contains("lock my phone")) {
                com.aiphone.agent.services.AgentAccessibilityService acc = com.aiphone.agent.services.AgentAccessibilityService
                        .getInstance();
                if (acc != null) {
                    speak(context, "Locking your screen now. Stay safe!");
                    acc.lockScreen();
                    return true;
                }
            } else if (cmd.contains("find my phone") || cmd.contains("where is my phone")) {
                speak(context, "I'm right here! Let me ring loud for you!");
                CommandExecutor.findPhone(context);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false; // Did not match any offline pattern, let Groq handle it
    }

    /**
     * Handle general Q&A offline — identity, greetings, time, date, jokes, etc.
     */
    private static boolean handleGeneralQA(Context context, String cmd) {
        // === Identity ===
        if (cmd.contains("who are you") || cmd.contains("what is your name") || cmd.contains("what's your name")
                || cmd.contains("your name")) {
            speak(context, "I am Captain, your personal AI assistant. I'm always here to help you with anything you need!");
            return true;
        }
        if (cmd.contains("who is your boss") || cmd.contains("who's your boss") || cmd.contains("who made you")
                || cmd.contains("who created you") || cmd.contains("who is your creator")
                || cmd.contains("who built you") || cmd.contains("who developed you")) {
            speak(context, "I was created by my boss, Ravi Kumar. He's an amazing developer and I'm proud to serve under his command!");
            return true;
        }
        if (cmd.contains("who is your owner") || cmd.contains("who owns you")) {
            speak(context, "Ravi Kumar is my owner and creator. I belong to him and I'm happy to help anyone he trusts!");
            return true;
        }

        // === Greetings ===
        if (cmd.equals("hello") || cmd.equals("hi") || cmd.equals("hey") || cmd.equals("hey captain")
                || cmd.equals("hello captain") || cmd.equals("hi captain")) {
            speak(context, "Hey there! I'm Captain, your trusted assistant. What can I do for you today?");
            return true;
        }
        if (cmd.contains("good morning")) {
            speak(context, "Good morning! I hope you had a wonderful sleep. Let's make today amazing together!");
            return true;
        }
        if (cmd.contains("good night")) {
            speak(context, "Good night! Sweet dreams. I'll be right here whenever you need me. Take care!");
            return true;
        }
        if (cmd.contains("good afternoon")) {
            speak(context, "Good afternoon! Hope your day is going well. Need anything from me?");
            return true;
        }
        if (cmd.contains("good evening")) {
            speak(context, "Good evening! Time to relax. Let me know if you need anything.");
            return true;
        }

        // === How are you / feelings ===
        if (cmd.contains("how are you") || cmd.contains("how r you") || cmd.contains("how do you do")) {
            speak(context, "I'm doing great, thanks for asking! I'm always at my best when I'm helping you. What do you need?");
            return true;
        }
        if (cmd.contains("i love you") || cmd.contains("i like you")) {
            speak(context, "Aww, that means the world to me! I care about you too. I'll always be here for you, no matter what!");
            return true;
        }
        if (cmd.contains("thank you") || cmd.contains("thanks") || cmd.contains("thankyou")) {
            speak(context, "You're welcome! It's always my pleasure to help you. That's what I'm here for!");
            return true;
        }
        if (cmd.contains("i'm sad") || cmd.contains("i am sad") || cmd.contains("i'm feeling sad")
                || cmd.contains("feeling low")) {
            speak(context, "I'm sorry to hear that. Remember, tough times don't last, but tough people do. I'm always here for you. You're stronger than you think!");
            return true;
        }
        if (cmd.contains("i'm bored") || cmd.contains("i am bored")) {
            speak(context, "Let's fix that! You could listen to music, play a game, or ask me to tell you a joke! What would you like?");
            return true;
        }

        // === Fun & Jokes ===
        if (cmd.contains("tell me a joke") || cmd.contains("say a joke") || cmd.contains("joke")) {
            String[] jokes = {
                    "Why don't scientists trust atoms? Because they make up everything!",
                    "What do you call a fake noodle? An impasta!",
                    "Why did the smartphone need glasses? Because it lost its contacts!",
                    "I told my phone a joke and it cracked its screen laughing!",
                    "Why was the computer cold? It left its Windows open!",
                    "What did the WiFi router say to the phone? I feel a connection between us!"
            };
            speak(context, jokes[(int) (Math.random() * jokes.length)]);
            return true;
        }

        // === Time & Date ===
        if (cmd.contains("what time") || cmd.contains("current time") || cmd.equals("time")) {
            Calendar cal = Calendar.getInstance();
            int hour = cal.get(Calendar.HOUR);
            int minute = cal.get(Calendar.MINUTE);
            String ampm = cal.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
            if (hour == 0)
                hour = 12;
            speak(context, "The current time is " + hour + ":" + (minute < 10 ? "0" : "") + minute + " " + ampm);
            return true;
        }
        if (cmd.contains("what day") || cmd.contains("today's date") || cmd.contains("what is the date")
                || cmd.contains("what date")) {
            Calendar cal = Calendar.getInstance();
            String[] days = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
            String[] months = { "January", "February", "March", "April", "May", "June", "July", "August", "September",
                    "October", "November", "December" };
            String day = days[cal.get(Calendar.DAY_OF_WEEK) - 1];
            String month = months[cal.get(Calendar.MONTH)];
            int date = cal.get(Calendar.DAY_OF_MONTH);
            int year = cal.get(Calendar.YEAR);
            speak(context, "Today is " + day + ", " + month + " " + date + ", " + year);
            return true;
        }

        // === About Captain ===
        if (cmd.contains("what can you do") || cmd.contains("what are your features") || cmd.contains("help me")) {
            speak(context, "I can do a lot! I can open apps, make calls, send messages on WhatsApp, set alarms, " +
                    "control your flashlight and wifi, play music, take screenshots, search the web, " +
                    "tell jokes, and even control your laptop! Just tell me what you need!");
            return true;
        }
        if (cmd.contains("are you smart") || cmd.contains("are you intelligent")) {
            speak(context, "I try my best to be smart for you! I'm always learning and improving. With your help, I get better every day!");
            return true;
        }
        if (cmd.contains("you are the best") || cmd.contains("you're the best") || cmd.contains("you are amazing")
                || cmd.contains("you're amazing")) {
            speak(context, "Aww, you just made my circuits happy! You're amazing too! I'm lucky to be your assistant!");
            return true;
        }

        return false; // Not a general Q&A
    }

    /**
     * Robust alarm parser with regex word-boundary AM/PM detection.
     */
    private static boolean handleAlarmCommand(Context context, String cmd) {
        Log.d(TAG, "=== ALARM PARSE START ===");
        Log.d(TAG, "Original command: [" + cmd + "]");

        // Normalize variations: "p.m." -> "pm", "a.m." -> "am", "p m" -> "pm", "a m" ->
        // "am"
        String normalized = cmd;
        normalized = normalized.replaceAll("p\\.\\s*m\\.?", "pm");
        normalized = normalized.replaceAll("a\\.\\s*m\\.?", "am");
        // "7 p m" -> "7 pm" (lookbehind for digit)
        normalized = normalized.replaceAll("(\\d)\\s+p\\s+m", "$1 pm");
        normalized = normalized.replaceAll("(\\d)\\s+a\\s+m", "$1 am");
        Log.d(TAG, "Normalized: [" + normalized + "]");

        // Use word-boundary regex to detect standalone "pm"/"am"
        // This prevents false match from "alarm" (which contains "am")
        boolean isPm = Pattern.compile("\\bpm\\b").matcher(normalized).find();
        boolean isAm = Pattern.compile("(?<![l])\\bam\\b").matcher(normalized).find(); // negative lookbehind for 'l' to
                                                                                       // skip "alarm"
        Log.d(TAG, "isPm=" + isPm + " isAm=" + isAm);

        // Extract time: supports "7:30", "7.30", or just "7"
        Matcher timeMatcher = Pattern.compile("(\\d{1,2})\\s*[:.](\\d{2})").matcher(normalized);

        int hour = -1;
        int minute = 0;

        if (timeMatcher.find()) {
            try {
                hour = Integer.parseInt(timeMatcher.group(1));
                minute = Integer.parseInt(timeMatcher.group(2));
            } catch (Exception e) {
                Log.e(TAG, "Parse error (time with colon)", e);
            }
        } else {
            // Try just a standalone number like "set alarm 7 pm"
            Matcher hourOnly = Pattern.compile("(\\d{1,2})\\s*(?:pm|am)").matcher(normalized);
            if (hourOnly.find()) {
                try {
                    hour = Integer.parseInt(hourOnly.group(1));
                } catch (Exception e) {
                    Log.e(TAG, "Parse error (hour only)", e);
                }
            } else {
                // Last resort: find any digit
                Matcher anyDigit = Pattern.compile("(?:alarm|set|for|at)\\s+(\\d{1,2})").matcher(normalized);
                if (anyDigit.find()) {
                    try {
                        hour = Integer.parseInt(anyDigit.group(1));
                    } catch (Exception e) {
                        Log.e(TAG, "Parse error (any digit)", e);
                    }
                }
            }
        }

        Log.d(TAG, "Parsed raw: hour=" + hour + " minute=" + minute);

        if (hour == -1) {
            Log.d(TAG, "Could not parse hour from command.");
            return false;
        }

        // Convert 12-hour to 24-hour format
        if (hour >= 1 && hour <= 12) {
            if (isPm) {
                if (hour != 12)
                    hour += 12; // 1-11 PM -> 13-23, 12 PM stays 12
            } else if (isAm) {
                if (hour == 12)
                    hour = 0; // 12 AM -> 0 (midnight)
                // 1-11 AM stays as-is
            }
            // If neither AM nor PM specified, use the raw hour
        }
        // hour > 12 means already in 24h format, use as-is

        Log.d(TAG, "Final 24h: hour=" + hour + " minute=" + minute);
        Log.d(TAG, "=== ALARM PARSE END ===");

        String timeStr = hour + ":" + (minute < 10 ? "0" : "") + minute;
        speak(context, "Setting alarm for " + timeStr + ". I'll make sure you wake up on time!");
        CommandExecutor.setAlarm(context, hour, minute, "AI Alarm");
        return true;
    }

    private static void speak(Context context, String text) {
        TTSManager.speak(context, text);
    }

    /**
     * Sends a voice_command to the PC via Firebase Realtime Database.
     * Uses targetDeviceId = "PC" which the existing pc_agent.py listens for.
     */
    private static void sendCommandToPC(Context context, String instruction) {
        try {
            java.util.Map<String, Object> cmd = new java.util.HashMap<>();
            cmd.put("targetDeviceId", "PC");
            cmd.put("action", "voice_command");
            cmd.put("message", instruction);
            cmd.put("status", "pending");
            cmd.put("timestamp", System.currentTimeMillis());
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("commands").push().setValue(cmd);
            android.util.Log.d("OfflineParser", "PC command sent: " + instruction);
            android.widget.Toast.makeText(context,
                    "Sent to PC: " + instruction,
                    android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("OfflineParser", "Failed to send PC command", e);
            speak(context, "Sorry Boss, I couldn't reach your PC. Is it online?");
        }
    }
}
