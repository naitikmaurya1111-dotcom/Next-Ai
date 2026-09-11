# 🧠 Next AI & Antigravity Session Resume Context
> **Last Synced**: `2026-09-11 18:23:50 UTC`  
> **Conversation ID**: `045d867f-f060-4d70-b425-c2f6b980df73`  
> **Git Head SHA**: `776e34e`  
> **Active App Version**: `v1.0.10`  
> **GitHub Repo**: [naitikmaurya1111-dotcom/Next-Ai](https://github.com/naitikmaurya1111-dotcom/Next-Ai)

---

## 🎯 Executive Summary & Mission
You are pair programming on **Next AI**, a premium, Claude-like Android Chat App integrated with Google Colab running the **Antigravity CLI** (`agy`) bridge server via WebSocket.

### 🔑 Critical User Rules & Instructions
1. **GitHub Pushes**: **ALWAYS ask the user for explicit confirmation before pushing to GitHub (`git push`). NEVER push automatically without permission.**
2. **Drive Persistence**: When a session restarts, resume context seamlessly from this Google Drive backup (`SESSION_RESUME.md`).
3. **Reasoning Effort**: Defaults to `high`.

---

## 📱 Android App Architecture & Current Status
- **Package**: `com.agychat.app`
- **UI Framework**: Jetpack Compose (Material 3 Dynamic Theme)
- **Dependency Injection**: Hilt
- **Local DB**: SQLite via Room (`ChatDao`, `ConversationEntity`, `MessageEntity`)
- **Network**: OkHttp WebSocket client (`AgyWebSocketClient`) with 20s ping intervals and auto-reconnect
- **Navigation & ViewModel Scoping**:
  - `sharedChatViewModel: ChatViewModel = hiltViewModel()` is hoisted to the root `AGYChatNavHost` level in `MainActivity.kt`.
  - All screens (`ChatScreen`, `SettingsScreen`, `HistoryScreen`) share the exact same ViewModel instance so the WebSocket connection remains active when switching screens.
  - Auto-connects on startup to the saved bridge URL via `SharedPreferences("next_ai_prefs")`.
- **Slash Command Plugins** (all 8 implemented as cards in `PluginDrawer` and chips):
  1. `/goal` — Run long-running tasks autonomously until done
  2. `/plan` — Multi-step planning before execution
  3. `/boost` — Deep thinking and rigorous multi-perspective review
  4. `/schedule` — Schedule timers or recurring cron jobs
  5. `/browser` — Autonomous web search and page reading
  6. `/learn` — Persist custom user preferences and corrections
  7. `/grill-me` — Interactive interview to clarify requirements
  8. `/teamwork-preview` — Multi-agent team coordination
- **Latest Downloadable Release**:
  - Tag: `v1.0.10`
  - URL: `https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases/download/v1.0.10/app-debug.apk`

---

## 🌉 Colab Bridge Server & CLI Execution
- **Location**: `/content/Next-Ai/colab`
- **Stack**: FastAPI + Uvicorn (Port 8000) + Cloudflare Tunnel (`cloudflared`)
- **Execution**: Runs `/root/.local/bin/agy -p "<message>" --output-format stream-json --dangerously-skip-permissions`
- **Session Continuity**: Retains `--conversation 045d867f-f060-4d70-b425-c2f6b980df73` across turns.
- **Launcher**: `/content/Next-Ai/colab/start_bridge.sh`

---

## 📂 Restored Antigravity State
- **Conversation DBs**: `/root/.gemini/antigravity-cli/conversations/`
- **Brain Artifacts & Transcripts**: `/root/.gemini/antigravity-cli/brain/045d867f-f060-4d70-b425-c2f6b980df73/`
- **History & Summaries**: `/root/.gemini/antigravity-cli/conversation_summaries.db`, `history.jsonl`

---

## 🚀 Immediate Actions Upon Resuming
1. Verify Colab Bridge server is running: `curl -s http://127.0.0.1:8000/health || bash /content/Next-Ai/colab/start_bridge.sh`
2. Provide the user with the active WebSocket URL.
3. Greet the user, confirm session resumed from Google Drive, and ask how they'd like to proceed!
