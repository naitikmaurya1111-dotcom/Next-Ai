# 🧠 Next AI & Antigravity Session Resume Context
> **Last Synced**: `2026-09-14 11:29:00 UTC`  
> **Conversation ID**: `cb710f7f-149f-4a1a-b48c-fec1f966c99f`  
> **Git Head SHA**: `e967e3f`  
> **Active App Version**: `v1.0.54`  
> **GitHub Repo**: [naitikmaurya1111-dotcom/Next-Ai](https://github.com/naitikmaurya1111-dotcom/Next-Ai)

---

## 🎯 Executive Summary & Mission
You are pair programming on **Next AI**, a premium Android Chat App inspired by ChatGPT, Claude, and Gemini, integrated with Google Colab running the **Antigravity CLI** (`agy`) bridge server via WebSocket.

### 🔑 Critical User Rules & Instructions
1. **GitHub Pushes**: **ALWAYS ask the user for explicit confirmation before pushing to GitHub (`git push`). NEVER push automatically without permission.**
2. **Drive Persistence**: When a session restarts, resume context seamlessly from this Google Drive backup (`SESSION_RESUME.md`).
3. **Reasoning Effort / Thinking Level**: User controls Low, Medium, High via UI; bridge auto-maps without CLI flag conflicts.

---

## 📱 Android App Architecture & Features
- **Package**: `com.agychat.app`
- **UI Framework**: Jetpack Compose (Material 3 Dynamic Theme, Claude Terracotta styling)
- **Dependency Injection**: Hilt
- **Local DB**: SQLite via Room (`ChatDao`, `MemoryDao`, `ConversationEntity`, `MessageEntity`, `MemoryEntity`)
- **Network**: OkHttp WebSocket client (`AgyWebSocketClient`) with 20s ping intervals, auto-reconnect, and cancellation support (cancel events)
- **Persistent ChatGPT-Style Memory & Personalization Architecture**:
  - `MemoryEntity` and `MemoryDao`: CRUD, category tags (`project`, `preference`, `personal`, `style`, `general`), search, and batch import/export.
  - `ManageMemorySheet.kt`: Modal sheet with real-time search, category filters, inline editing, JSON export & import, and template inspiration chips.
  - `CustomInstructionsSheet.kt`: Full two-part customization (User Profile & Background + Response Style & Formatting) with tone preset chips (`Direct & Concise`, `Technical`, `Educational`, `Warm`).
  - **Autonomous Real-time Memory Extraction**: Model emits `<memory_update>` tags during natural conversation; bridge extracts and emits `memory_updated` WebSocket events, and chat displays interactive `✨ Memory updated` pills on assistant messages.
  - **Temporary / Incognito Chat**: Ephemeral mode where memories are paused, excluded from prompt, and not saved.
  - Natural commands in chat: `/remember <fact>`, `/forget <query>`, and `/memory`.
  - Automatic memory and custom instructions backup to Google Drive (`user_memories.json`, `custom_instructions.json`).
- **Thinking Level & Model Configuration**:
  - Full support for Gemini 3.8 Flash, 3.7 Flash, 3.6 Flash, 3.1 Pro, Claude Sonnet 4.6, Claude Opus 4.6, and GPT-OSS 120B.
  - CLI flag conflict resolved via canonical suffix mapping in `resolve_model_and_effort()`.
- **Active Stop Generation**:
  - Claude/ChatGPT style red stop square in `ClaudeFloatingInputBar` when streaming is in progress.
  - Subprocess cancellation via `active_processes` tracking in `agy_runner.py`.
- **Slash Command Plugins** (8 implemented in `PluginDrawer`):
  1. `/goal` — Run long-running tasks autonomously until done
  2. `/plan` — Multi-step planning before execution
  3. `/boost` — Deep thinking and rigorous multi-perspective review
  4. `/schedule` — Schedule timers or recurring cron jobs
  5. `/browser` — Autonomous web search and page reading
  6. `/learn` — Persist custom user preferences and corrections
  7. `/grill-me` — Interactive interview to clarify requirements
  8. `/teamwork-preview` — Multi-agent team coordination
- **Latest Downloadable Release**:
  - Tag: `v1.0.54`
  - URL: `https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases`

---

## 🌉 Colab Bridge Server & CLI Execution
- **Location**: `/content/Next-Ai/colab`
- **Stack**: FastAPI + Uvicorn (Port 8000) + Cloudflare Tunnel (`cloudflared`)
- **Execution**: Runs `/root/.local/bin/agy -p "<message>" --output-format stream-json --dangerously-skip-permissions`
- **Session Continuity**: Retains `--conversation cb710f7f-149f-4a1a-b48c-fec1f966c99f` across turns.
- **Launcher**: `/content/Next-Ai/colab/start_bridge.sh`

---

## 📂 Restored Antigravity State
- **Conversation DBs**: `/root/.gemini/antigravity-cli/conversations/`
- **Brain Artifacts & Transcripts**: `/root/.gemini/antigravity-cli/brain/cb710f7f-149f-4a1a-b48c-fec1f966c99f/`
- **History & Summaries**: `/root/.gemini/antigravity-cli/conversation_summaries.db`, `history.jsonl`

---

## 🚀 Immediate Actions Upon Resuming
1. Verify Colab Bridge server is running: `curl -s http://127.0.0.1:8000/health || bash /content/Next-Ai/colab/start_bridge.sh`
2. Provide the user with the active WebSocket URL.
3. Greet the user, confirm session resumed from Google Drive, and ask how they'd like to proceed!
