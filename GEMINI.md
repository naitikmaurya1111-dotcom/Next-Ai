# 🧠 Antigravity & Next AI Workspace Rules

## 1. 🔄 Session Resume Protocol ("Resume from previous chat")
Whenever the user asks to **"resume from previous chat"**, **"continue from where we left off"**, **"restore session"**, or mentions previous progress:
1. **Restore Session**: Run:
   ```bash
   python3 /content/Next-Ai/colab/drive_sync_manager.py restore
   ```
   *(or run `nextai-restore`)*
2. **Read Resume Context**: Read `/content/SESSION_RESUME.md` (or `/content/drive/MyDrive/NextAI_Backup/SESSION_RESUME.md`) to absorb all previous conversation context, decisions, architecture, and current status.
3. **Check Bridge Server**: Ensure the Colab bridge server is running:
   ```bash
   curl -s http://127.0.0.1:8000/health || bash /content/Next-Ai/colab/start_bridge.sh
   ```
4. **Greet the User & Confirm**:
   - Confirm the session was successfully restored from Google Drive.
   - Present a concise summary of the restored state (Android App version, active features, latest APK link, active Colab bridge WebSocket URL).
   - Ask the user how they would like to proceed.

---

## 2. ⚠️ GitHub Push Approval Requirement (MANDATORY)
- **NEVER run `git push` automatically without asking.**
- **ALWAYS ask the user for explicit confirmation and approval before pushing any code to GitHub.**
- Inform the user of what was changed and wait for their permission before executing `git push`.

---

## 3. 💾 Google Drive Persistence
- Before finishing any session or after completing significant updates, ensure progress is backed up:
  ```bash
  python3 /content/Next-Ai/colab/drive_sync_manager.py backup
  ```
  *(or run `nextai-backup`)*
- Google Drive backups are stored at `/content/drive/MyDrive/NextAI_Backup/`.
