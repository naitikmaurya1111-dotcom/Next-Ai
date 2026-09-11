# AGY Chat — Next AI
## A Claude-like Android AI Chat App connected to Antigravity CLI

[![Build APK](https://github.com/YOUR_USERNAME/Next-Ai/actions/workflows/build.yml/badge.svg)](https://github.com/YOUR_USERNAME/Next-Ai/actions/workflows/build.yml)

---

## 📱 Download

Go to **[Releases](../../releases/latest)** → Download `app-debug.apk` → Install on Android.

> Enable **"Install from unknown sources"** in Android Settings → Security.

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 💬 Real-time Chat | Streams responses from AGY CLI as you watch |
| 🔌 8 Slash Plugins | /goal, /plan, /boost, /schedule, /browser, /learn, /grill-me, /teamwork-preview |
| 🌙 Dark/Light Theme | Material You dynamic colors |
| 💾 Google Drive Backup | Auto-backup of all conversations & files |
| 📜 History | Search & restore past conversations |
| 📎 File Upload | Send files to AGY from your phone |
| 🔗 WebSocket | Live streaming connection to Colab |

---

## 🚀 Quick Setup

### 1. Start Colab Bridge
Upload `colab/` folder to Google Colab and run `setup_colab.ipynb`.  
Copy the `wss://xxxx.ngrok-free.app/ws` URL shown.

### 2. Install APK  
Download latest APK from [Releases](../../releases/latest).

### 3. Connect
Open app → **⋮ Menu → Settings** → Paste URL → **Connect**.

---

## 🏗️ Architecture

```
Android App (Kotlin + Jetpack Compose)
├── MVVM + Clean Architecture
├── Hilt Dependency Injection
├── Room (local DB)
├── OkHttp WebSocket (streaming)
└── Google Drive API v3

Colab Bridge (Python FastAPI)
├── WebSocket endpoint
├── AGY CLI subprocess runner
├── ngrok tunnel
└── Drive backup
```

---

## 🛠️ Build Locally

```bash
git clone https://github.com/YOUR_USERNAME/Next-Ai.git
cd Next-Ai/android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Requirements:** Android Studio Ladybug | JDK 17

---

## 📡 WebSocket Protocol

```json
// Android → Server
{"message": "/goal Build a REST API", "conversation_id": "uuid"}

// Server → Android (streaming)
{"type": "chunk", "content": "Starting...", "timestamp": 1234}
{"type": "done",  "content": "Complete",   "timestamp": 1235}
{"type": "error", "content": "agy not found", "timestamp": 1236}
```

---

*Built with ❤️ using Kotlin, Jetpack Compose, FastAPI, and Antigravity CLI*
