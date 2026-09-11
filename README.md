# Next AI 🚀
## Claude-like Android Assistant App for Google Antigravity CLI (`agy`)

[![Build APK](https://github.com/naitikmaurya1111-dotcom/Next-Ai/actions/workflows/build.yml/badge.svg)](https://github.com/naitikmaurya1111-dotcom/Next-Ai/actions/workflows/build.yml)

An advanced, beautiful Android application built with **Jetpack Compose**, **Material 3**, and **Clean Architecture**, connected live to the **Antigravity CLI** running in Google Colab.

---

## 📱 Download the App

👉 **[Latest Release & APK Download](https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases/latest)**

1. Download `app-debug.apk` onto your Android phone.
2. Open the file and tap **Install** (allow "Install unknown apps" if prompted).
3. Open **Next AI** → **Settings** → Paste your Colab Bridge WebSocket URL → Tap **Connect**.

---

## ✨ Features

- **Claude Aesthetic UI**: Signature warm terracotta palette, soft borders, and dark/light mode support.
- **Thinking Process Accordion**: Expandable reasoning block showing the model's inner thoughts and planning.
- **Syntax Highlighted Code Blocks**: Monospace font, language tags, horizontal scrolling, and a dedicated **Copy Code** button.
- **Quick-Action Slash Chips**: One-tap quick chips above the input bar for instant commands.
- **8 Antigravity Slash Plugins**:
  - `⚡ /boost`: Deep reasoning, multi-perspective thinking, and code verification.
  - `🎯 /goal`: Autonomous goal-oriented loop until objective is fully accomplished.
  - `📋 /plan`: Step-by-step phased architecture plan before implementing.
  - `🌐 /browser`: Live web lookup, documentation reading, and web scraping.
  - `⏱️ /schedule`: One-shot timers and cron automation.
  - `🧠 /learn`: Persist preferences and rules into the agent brain.
  - `🔥 /grill-me`: Interactive interview stress-testing system design.
  - `👥 /teamwork-preview`: Multi-agent team orchestration.
- **Real-time Token Streaming**: Token-by-token streaming via native `stream-json` bridge.
- **Room Database Offline Storage**: Full local conversation history with instant switching and deletion.
- **Markdown Export**: One-tap export to formatted `.md` via Android system share sheet.

---

## ⚡ Colab Bridge Server

The bridge server runs in Google Colab and connects your phone to the `agy` CLI via a public Cloudflare tunnel:

```bash
python3 /content/Next-Ai/colab/run_server.py
```

This starts the FastAPI WebSocket server and prints your live public WebSocket URL (`wss://xxxx.trycloudflare.com/ws`).
