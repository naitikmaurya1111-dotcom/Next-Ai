# 🧠 Next AI & Antigravity Session Resume Context
> **Last Synced**: `2026-09-12 06:21:00 UTC`  
> **Conversation ID**: `b885e03f-9af6-4038-b0f6-5185f2344b9c`  
> **Git Head SHA**: `5421524` (with local UI/UX overhaul commits)  
> **Active App Version**: `v1.2.0`  
> **GitHub Repo**: [naitikmaurya1111-dotcom/Next-Ai](https://github.com/naitikmaurya1111-dotcom/Next-Ai)

---

## 🎯 Executive Summary & Mission
You are pair programming on **Next AI**, a state-of-the-art Android Chat App inspired by ChatGPT and Claude, integrated with Google Colab running the **Antigravity CLI** (`agy`) bridge server via WebSocket.

### 🔑 Critical User Rules & Instructions
1. **GitHub Pushes**: **ALWAYS ask the user for explicit confirmation before pushing to GitHub (`git push`). NEVER push automatically without permission.**
2. **Drive Persistence**: When a session restarts, resume context seamlessly from this Google Drive backup (`SESSION_RESUME.md`).
3. **Reasoning Effort / Thinking Level**: User controls Low, Medium, High via UI; bridge auto-maps without CLI flag conflicts.

---

## 🎨 UI/UX & Frontend Overhaul (ChatGPT 2026 Standard)
- **Obsidian Dark & Clean Light Themes**:
  - `DarkBg` (`#121212`), `DarkSurface` (`#1C1C1E`), `DarkSurfaceElevated` (`#28282B`), `DarkBorder` (`#333336`), `DarkBorderSubtle` (`#242426`), `DarkTextPrimary` (`#ECECEC`), `DarkTextSecondary` (`#A1A1AA`).
- **Markdown & Code Rendering**:
  - `TableBlockView`: Scrollable Material 3 card with alternating rows, subtle borders, and header row styling.
  - Callout alert cards: `[!NOTE]` (Blue info), `[!TIP]` (Emerald tip), `[!WARNING]` (Amber warning), `[!IMPORTANT]` / `[!CAUTION]` (Terracotta error).
  - Code blocks: Language header, copy button with animated green checkmark + "Copied!" for 2 seconds.
- **Top App Bar**:
  - Centered interactive Model Selector pill (`✦ Gemini 3.8 Flash ▾`) with live connection status dot.
  - Incognito / Temporary Chat badge, 1-tap New Chat, and More Options (`⋮`) overflow menu.
- **Floating Composer**:
  - Inline dismissible active slash mode badges (`/browser`, `/boost`, `/plan`, `/goal`, `/remember`, `/schedule`, `/grill-me`, `/teamwork-preview`).
  - `AnimatedContent` dynamic transitions between Send (`↑`), Stop (`■`), and Mic (`🎙️`).
- **Unified Model & Reasoning Sheet**:
  - Segmented Reasoning Depth tab row (`🚀 Fast`, `⚡ Balanced`, `🧠 Deep`).
  - Model cards with brand sparks (Google ✦, Anthropic ✻, Open Source ⚡) and checkmark circles.
- **History Drawer**:
  - 5-tiered date grouping (`Today`, `Yesterday`, `Previous 7 Days`, `Previous 30 Days`, `Older`).
  - Real-time chat title search.
- **Typing Indicator**:
  - Assistant spark badge with bouncing wave dots and animated text.

---

## 📱 Architecture & Features
- **Package**: `com.agychat.app`
- **UI Framework**: Jetpack Compose (Material 3 Dynamic Theme)
- **Dependency Injection**: Hilt
- **Local DB**: Room SQLite (`ChatDao`, `MemoryDao`, `ConversationEntity`, `MessageEntity`, `MemoryEntity`)
- **Network**: OkHttp WebSocket client (`AgyWebSocketClient`) with auto-reconnect and cancellation support
- **Memory & Personalization**:
  - Autonomous extraction with `<memory_update>` tags
  - `ManageMemorySheet` with category filtering and JSON export/import
  - `CustomInstructionsSheet` with tone presets and preference chips
  - Temporary / Incognito Chat mode

---

## 🌐 Colab Bridge Architecture
- **Location**: `/content/Next-Ai/colab`
- **Stack**: FastAPI + Uvicorn (Port 8000) + Cloudflare Tunnel (`cloudflared`)
- **Execution**: Runs `/root/.local/bin/agy -p "<message>" --output-format stream-json --dangerously-skip-permissions`
- **Session Continuity**: Retains `--conversation b885e03f-9af6-4038-b0f6-5185f2344b9c` across turns.
- **Launcher**: `/content/Next-Ai/colab/start_bridge.sh`
