<div align="center">

# 🤖 AI Phone Agent (Captain)

### *Your phone. Your voice. Your AI companion.*

[![Android](https://img.shields.io/badge/Platform-Android%20API%2026%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Firebase](https://img.shields.io/badge/Firebase-Realtime%20DB-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)](https://firebase.google.com)
[![Groq](https://img.shields.io/badge/AI-Groq%20LLM-F54E42?style=for-the-badge&logo=openai&logoColor=white)](https://groq.com)
[![Picovoice](https://img.shields.io/badge/Wake%20Word-Porcupine-70B8FF?style=for-the-badge)](https://picovoice.ai)
[![License](https://img.shields.io/badge/License-MIT-blueviolet?style=for-the-badge)](LICENSE)

<br/>

> **AI Phone Agent** is a next-generation Android AI assistant that listens, understands, and acts.
> Control your phone — and your PC — with just your voice. No cloud dependency for core features.
> Powered by Groq's ultra-fast LLM, Picovoice offline wake-word detection, Firebase real-time sync,
> and a full cross-device PC bridge.

<br/>

</div>

---

## ✨ Features

### 🎙️ Always-On Wake Word
- **Offline Porcupine** wake-word detection — works without internet
- Custom wake chime audio feedback
- No battery-draining cloud polling
- Siri-style animated overlay on activation

### 🧠 Groq-Powered AI Brain
- **Ultra-fast inference** via Groq's LLaMA models
- Full conversational memory & context window
- Automatic model probing & fallback
- Streaming responses for instant feedback

### 📱 Deep Device Control
- **Accessibility Service** for full app control
- Call management, SMS, contacts
- Media playback, volume, brightness
- App launch, navigation, notifications

### 💻 PC Bridge (Cross-Device)
- **Firebase Realtime Sync** between phone ↔ PC
- Control Windows PC entirely from your phone
- Open apps, type text, take screenshots, lock/unlock
- Real-time PC status monitoring (CPU, RAM)

### 🔒 Security First
- **Biometric authentication** (fingerprint / face)
- **Encrypted SharedPreferences** (AES-256-GCM) for all API keys
- PIN lock screen
- No API keys hardcoded — user enters at runtime

### 🎥 AI Vision (Camera)
- **CameraX** integration for visual AI tasks
- Image capture & AI analysis pipeline
- Real-time camera feed access via voice trigger

### 🔔 Notification Intelligence
- Reads & summarizes incoming notifications
- Smart intent classification
- Voice-triggered notification replies

### ⚙️ Floating Widget
- Always-on-top floating overlay
- Quick-access agent controls
- Non-intrusive minimal UI

---

## 🏗️ Architecture

```text
┌─────────────────────────────────────────────────────────────────┐
│                        AI Phone Agent                           │
│                                                                 │
│  ┌──────────────┐   ┌────────────────┐   ┌───────────────────┐ │
│  │  WakeWord    │   │  Groq LLM      │   │  Command          │ │
│  │  Service     │──▶│  Agent         │──▶│  Executor         │ │
│  │  (Porcupine) │   │  (llama-3.1)   │   │  (Device Actions) │ │
│  └──────────────┘   └────────────────┘   └───────────────────┘ │
│         │                   │                      │            │
│         ▼                   ▼                      ▼            │
│  ┌──────────────┐   ┌────────────────┐   ┌───────────────────┐ │
│  │  Siri-Style  │   │  Offline NLP   │   │  Accessibility    │ │
│  │  Overlay UI  │   │  Command Parser│   │  Service          │ │
│  └──────────────┘   └────────────────┘   └───────────────────┘ │
│                              │                                  │
│                    ┌─────────▼──────────┐                      │
│                    │   Firebase RTDB    │                       │
│                    │  (Cross-Device     │                       │
│                    │   Command Bridge)  │                       │
│                    └─────────┬──────────┘                      │
└──────────────────────────────┼──────────────────────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │  captain_pc_agent   │
                    │  (Windows PC Agent) │
                    │  python script      │
                    └─────────────────────┘
```

### 📁 Project Structure

```text
Android Ai Agent/
├── 📱 app/
│   └── src/main/java/com/aiphone/agent/
│       ├── 🎨 ui/                          # Activities
│       │   ├── SplashActivity.java         # Launch screen
│       │   ├── LoginActivity.java          # Authentication
│       │   ├── DashboardActivity.java      # Main control hub
│       │   ├── SettingsActivity.java       # API keys & config
│       │   ├── PcLinkActivity.java         # PC Bridge controls
│       │   ├── CameraActivity.java         # AI Vision
│       │   ├── PermissionsActivity.java    # Permission onboarding
│       │   └── LockActivity.java           # PIN/Biometric lock
│       │
│       ├── ⚙️ services/                    # Background Services
│       │   ├── WakeWordService.java        # Always-on Porcupine + STT
│       │   ├── CommandForegroundService.java # Firebase command listener
│       │   ├── AgentAccessibilityService.java # Deep device control
│       │   ├── FloatingWidgetService.java  # Overlay widget
│       │   ├── AgentTileService.java       # Quick Settings tile
│       │   └── NotificationCaptureService.java # Notification reader
│       │
│       ├── 🧠 utils/                       # Core Logic
│       │   ├── GroqAgent.java              # LLM API client + history
│       │   ├── CommandExecutor.java        # Device action dispatcher
│       │   ├── OfflineCommandParser.java   # Offline NLP engine
│       │   ├── SmartIntentClassifier.java  # Intent recognition
│       │   ├── SecurePrefsManager.java     # Encrypted key storage
│       │   ├── TTSManager.java             # Text-to-Speech
│       │   ├── WakeChimePlayer.java        # Audio feedback
│       │   └── PermissionsHelper.java      # Runtime permissions
│       │
│       ├── 📊 models/                      # Data models
│       │   ├── Command.java
│       │   ├── Response.java
│       │   └── Device.java
│       │
│       └── 📡 receivers/
│           └── CommandReceiver.java        # Broadcast receiver
│
├── 🐍 captain_pc_agent.py                  # Windows PC Agent script
├── 📋 .env.example                         # Environment config template
└── 📖 README.md
```

---

## 🚀 Getting Started

### Prerequisites

| Requirement | Details |
|-------------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android Device/Emulator | API 26+ (Android 8.0 Oreo) |
| Firebase Project | Realtime Database enabled |
| Groq API Key | Free at [console.groq.com](https://console.groq.com) |
| Picovoice Access Key | Free at [console.picovoice.ai](https://console.picovoice.ai) |

---

### 🔧 Android App Setup

#### Step 1 — Clone the repository

```bash
git clone https://github.com/RaviKurane17/Android_AI_Assistant-Captain-.git
cd Android_AI_Assistant-Captain-
```

#### Step 2 — Configure Firebase

1. Go to [Firebase Console](https://console.firebase.google.com) → Create / select your project
2. Enable **Realtime Database** (Start in test mode for development)
3. Add an **Android app** with package name: `com.aiphone.agent`
4. Download `google-services.json` and place it in **`app/`**

> ⚠️ `google-services.json` is **gitignored** — never commit this file!

#### Step 3 — Build & Run

```bash
# Open in Android Studio, sync Gradle, then run on device
# Or via CLI:
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Step 4 — First-Launch Configuration

On first launch, the app will guide you through:

1. **Grant Permissions** — Accessibility, Overlay, Microphone, Notification Access
2. **Enter API Keys** in Settings:
   - 🔑 **Groq API Key** → [console.groq.com](https://console.groq.com)
   - 🔑 **Picovoice Access Key** → [console.picovoice.ai](https://console.picovoice.ai)
3. **Set PIN** for lock screen security
4. Enable **Wake Word** toggle on the Dashboard

---

### 💻 PC Agent Setup (Optional)

The PC Agent lets your phone control your Windows PC via Firebase.

#### Step 1 — Configure environment

```bash
# In the project root on your PC:
copy .env.example .env
```

Edit `.env` with your real values:

```env
FIREBASE_CREDENTIALS_PATH=firebase_service_account.json
FIREBASE_DATABASE_URL=https://YOUR_PROJECT_ID-default-rtdb.firebaseio.com/
PC_DEVICE_ID=PC
```

#### Step 2 — Get Firebase Service Account

1. Firebase Console → Project Settings → **Service Accounts**
2. Click **Generate New Private Key** → save as `firebase_service_account.json`
3. Place it in the same folder as `captain_pc_agent.py`

> ⚠️ `firebase_service_account.json` is **gitignored** — never commit this file!

#### Step 3 — Install & Run

```bash
pip install firebase-admin pyautogui psutil python-dotenv
python captain_pc_agent.py
```

#### Step 4 — Link from your phone

Open the app → **PC Link** tab → your PC will appear online!

---

## 🎙️ Voice Commands

Once the wake word is detected, you can say:

| Category | Example Commands |
|----------|-----------------|
| 📱 **Apps** | *"Open Instagram"*, *"Close Spotify"*, *"Switch to WhatsApp"* |
| 📞 **Calls** | *"Call Mom"*, *"Reject the call"*, *"Put on speaker"* |
| 💬 **Messages** | *"Read my last message"*, *"Reply okay"* |
| 🔊 **Media** | *"Pause music"*, *"Volume up"*, *"Next song"* |
| 💡 **Settings** | *"Turn on WiFi"*, *"Increase brightness"*, *"Enable DND"* |
| 💻 **PC Control** | *"Open Chrome on my PC"*, *"Lock my PC"*, *"Take a screenshot"* |
| 🧠 **AI Chat** | *"What's the weather?"*, *"Translate this"*, *"Summarize my notifications"* |

---

## 🔐 Security Overview

| Layer | Implementation |
|-------|---------------|
| API Key Storage | `EncryptedSharedPreferences` (AES-256-GCM) |
| Lock Screen | PIN + Biometric (AndroidX Biometric API) |
| Firebase Config | `google-services.json` — gitignored |
| PC Credentials | `.env` file — gitignored |
| Service Account | Never embedded in source code |
| Network | HTTPS-only API calls |

---

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java (Android) + Python (PC Agent) |
| Min SDK | Android API 26 (Oreo 8.0) |
| AI / LLM | [Groq API](https://groq.com) — LLaMA 3.1 8B Instant |
| Wake Word | [Picovoice Porcupine](https://picovoice.ai) |
| Database | Firebase Realtime Database |
| Auth | Firebase Authentication |
| TTS | Android TextToSpeech (en-IN locale) |
| Vision | AndroidX CameraX |
| Security | AndroidX Security Crypto (AES-256-GCM) |
| ML Kit | Google ML Kit Entity Extraction |
| UI | Material Design 3 |

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. **Fork** the repository
2. Create your feature branch: `git checkout -b feature/AmazingFeature`
3. Commit your changes: `git commit -m 'Add AmazingFeature'`
4. Push to the branch: `git push origin feature/AmazingFeature`
5. Open a **Pull Request**

### 🐛 Reporting Bugs

Please open an issue with:
- Device model & Android version
- Steps to reproduce
- Expected vs actual behavior
- Logcat output (if applicable)

---

## 📜 License

```
MIT License

Copyright (c) 2026 Ravindra Kurane

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

## ⭐ Acknowledgements

- [Groq](https://groq.com) — for blazing-fast LLM inference
- [Picovoice](https://picovoice.ai) — for offline wake-word technology
- [Firebase](https://firebase.google.com) — for real-time cross-device sync
- [Google ML Kit](https://developers.google.com/ml-kit) — for on-device ML

---

<div align="center">

**Made with ❤️ and lots of ☕**

*If you find this project useful, please give it a ⭐ on GitHub!*

[![GitHub Stars](https://img.shields.io/github/stars/RaviKurane17/Android_AI_Assistant-Captain-?style=social)](https://github.com/RaviKurane17/Android_AI_Assistant-Captain-)

</div>
