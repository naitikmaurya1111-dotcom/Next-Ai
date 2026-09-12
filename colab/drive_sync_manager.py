#!/usr/bin/env python3
"""
drive_sync_manager.py — Next AI & Antigravity Session Sync & Restore Manager
===========================================================================
Ensures that all conversation history, brain artifacts, project state, and settings
are automatically preserved across Google Colab runtimes using Google Drive.

Usage:
  python3 drive_sync_manager.py backup     # Back up current session to Google Drive
  python3 drive_sync_manager.py restore    # Restore previous session from Google Drive
  python3 drive_sync_manager.py status     # Check backup status
  python3 drive_sync_manager.py daemon     # Run auto-sync background daemon (every 5 min)
"""

import os
import sys
import time
import json
import shutil
import tarfile
import sqlite3
import signal
import subprocess
from datetime import datetime, timezone
from pathlib import Path

# Paths configuration
DRIVE_MOUNT_DIR = Path("/content/drive/MyDrive")
DRIVE_BACKUP_DIR = DRIVE_MOUNT_DIR / "NextAI_Backup"
STAGING_BACKUP_DIR = Path("/content/NextAI_Backup_Staging")
GEMINI_DIR = Path("/root/.gemini/antigravity-cli")
PROJECT_DIR = Path("/content/Next-Ai")
RESUME_FILE_NAME = "SESSION_RESUME.md"
SNAPSHOT_FILE_NAME = "session_snapshot.json"
ARCHIVE_FILE_NAME = "nextai_session_latest.tar.gz"

CONVERSATION_ID = os.environ.get("CONVERSATION_ID", "b885e03f-9af6-4038-b0f6-5185f2344b9c")

def is_drive_mounted() -> bool:
    """Check if Google Drive is mounted at /content/drive/MyDrive."""
    return DRIVE_MOUNT_DIR.exists() and os.path.isdir(DRIVE_MOUNT_DIR)

def get_target_dir() -> Path:
    """Return Google Drive backup dir if mounted, else local staging dir."""
    if is_drive_mounted():
        DRIVE_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        return DRIVE_BACKUP_DIR
    else:
        STAGING_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        return STAGING_BACKUP_DIR

def flush_sqlite_wal(db_path: Path):
    """Safely checkpoint and flush SQLite WAL journal to disk before backing up."""
    if not db_path.exists():
        return
    try:
        conn = sqlite3.connect(str(db_path))
        conn.execute("PRAGMA wal_checkpoint(FULL);")
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"  ⚠️ Warning flushing {db_path.name}: {e}")

def generate_session_resume_md(target_path: Path, git_sha: str = "7da0dd9"):
    """Generate comprehensive markdown summary for the model to resume instantly."""
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    
    content = f"""# 🧠 Next AI & Antigravity Session Resume Context
> **Last Synced**: `{now_str}`  
> **Conversation ID**: `{CONVERSATION_ID}`  
> **Git Head SHA**: `{git_sha}`  
> **Active App Version**: `v1.1.0`  
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
  - Tag: `v1.0.18`
  - URL: `https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases/download/v1.0.18/app-debug.apk`

---

## 🌉 Colab Bridge Server & CLI Execution
- **Location**: `/content/Next-Ai/colab`
- **Stack**: FastAPI + Uvicorn (Port 8000) + Cloudflare Tunnel (`cloudflared`)
- **Execution**: Runs `/root/.local/bin/agy -p "<message>" --output-format stream-json --dangerously-skip-permissions`
- **Session Continuity**: Retains `--conversation {CONVERSATION_ID}` across turns.
- **Launcher**: `/content/Next-Ai/colab/start_bridge.sh`

---

## 📂 Restored Antigravity State
- **Conversation DBs**: `/root/.gemini/antigravity-cli/conversations/`
- **Brain Artifacts & Transcripts**: `/root/.gemini/antigravity-cli/brain/{CONVERSATION_ID}/`
- **History & Summaries**: `/root/.gemini/antigravity-cli/conversation_summaries.db`, `history.jsonl`

---

## 🚀 Immediate Actions Upon Resuming
1. Verify Colab Bridge server is running: `curl -s http://127.0.0.1:8000/health || bash /content/Next-Ai/colab/start_bridge.sh`
2. Provide the user with the active WebSocket URL.
3. Greet the user, confirm session resumed from Google Drive, and ask how they'd like to proceed!
"""
    with open(target_path, "w", encoding="utf-8") as f:
        f.write(content)

def backup():
    """Execute complete backup of Antigravity CLI state and project to Google Drive."""
    print("🔄 Starting session backup...")
    start_time = time.time()

    # Determine destination
    target_dir = get_target_dir()
    is_mounted = is_drive_mounted()
    dest_label = "Google Drive (MyDrive/NextAI_Backup)" if is_mounted else "Local Staging (/content/NextAI_Backup_Staging)"
    print(f"📁 Destination: {dest_label}")

    # Flush SQLite DBs
    conv_dir = GEMINI_DIR / "conversations"
    if conv_dir.exists():
        for db_file in conv_dir.glob("*.db"):
            flush_sqlite_wal(db_file)
    sum_db = GEMINI_DIR / "conversation_summaries.db"
    if sum_db.exists():
        flush_sqlite_wal(sum_db)

    # Get Git SHA if available
    git_sha = "unknown"
    if PROJECT_DIR.exists():
        try:
            res = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res.returncode == 0:
                git_sha = res.stdout.strip()
        except Exception:
            pass

    # Create temporary staging directory for the archive
    tmp_archive_dir = Path("/tmp/nextai_backup_tmp")
    if tmp_archive_dir.exists():
        shutil.rmtree(tmp_archive_dir)
    tmp_archive_dir.mkdir(parents=True, exist_ok=True)

    # 1. Copy essential Antigravity state
    tmp_gemini = tmp_archive_dir / "antigravity-cli"
    tmp_gemini.mkdir(parents=True, exist_ok=True)

    # Copy conversations
    if conv_dir.exists():
        shutil.copytree(conv_dir, tmp_gemini / "conversations", dirs_exist_ok=True)
    # Copy brain
    brain_dir = GEMINI_DIR / "brain"
    if brain_dir.exists():
        shutil.copytree(brain_dir, tmp_gemini / "brain", dirs_exist_ok=True)
    # Copy metadata files
    for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
        fpath = GEMINI_DIR / fname
        if fpath.exists():
            shutil.copy2(fpath, tmp_gemini / fname)

    # 2. Generate resume documentation
    resume_md_path = tmp_archive_dir / RESUME_FILE_NAME
    generate_session_resume_md(resume_md_path, git_sha=git_sha)

    # 3. Create metadata snapshot json
    snapshot_data = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "conversation_id": CONVERSATION_ID,
        "git_sha": git_sha,
        "app_version": "v1.0.10",
        "drive_mounted": is_mounted
    }
    with open(tmp_archive_dir / SNAPSHOT_FILE_NAME, "w") as f:
        json.dump(snapshot_data, f, indent=2)

    # 4. Create compressed tarball
    archive_path = target_dir / ARCHIVE_FILE_NAME
    temp_tar_path = Path("/tmp") / ARCHIVE_FILE_NAME
    print(f"📦 Compressing state into {ARCHIVE_FILE_NAME}...")
    with tarfile.open(temp_tar_path, "w:gz") as tar:
        tar.add(str(tmp_archive_dir), arcname=".")

    shutil.move(str(temp_tar_path), str(archive_path))

    # Also copy SESSION_RESUME.md directly to target_dir for quick inspection
    shutil.copy2(str(resume_md_path), str(target_dir / RESUME_FILE_NAME))
    shutil.copy2(str(tmp_archive_dir / SNAPSHOT_FILE_NAME), str(target_dir / SNAPSHOT_FILE_NAME))

    # Also copy to /content/SESSION_RESUME.md and /content/Next-Ai/SESSION_RESUME.md
    try:
        shutil.copy2(str(resume_md_path), "/content/SESSION_RESUME.md")
        shutil.copy2(str(resume_md_path), str(PROJECT_DIR / RESUME_FILE_NAME))
    except Exception:
        pass

    # If Drive is mounted AND staging exists, sync staging to Drive as well
    if is_mounted and STAGING_BACKUP_DIR.exists():
        try:
            for item in STAGING_BACKUP_DIR.glob("*"):
                shutil.copy2(item, DRIVE_BACKUP_DIR / item.name)
        except Exception:
            pass

    # Cleanup temp dir
    shutil.rmtree(tmp_archive_dir, ignore_errors=True)

    elapsed = round(time.time() - start_time, 2)
    size_mb = round(archive_path.stat().st_size / (1024 * 1024), 2)
    print(f"✅ Backup complete in {elapsed}s! Archive size: {size_mb} MB")
    print(f"📄 Resume Context saved to: {target_dir / RESUME_FILE_NAME}")
    if not is_mounted:
        print("💡 NOTE: Google Drive is not mounted yet. Backup is safely stored in local staging.")
        print("   Run `drive.mount('/content/drive')` in Colab to sync directly to Google Drive.")
    return True

def restore():
    """Restore Antigravity CLI state and project context from Google Drive."""
    print("🔄 Restoring session from Google Drive...")
    source_dir = None
    if is_drive_mounted() and (DRIVE_BACKUP_DIR / ARCHIVE_FILE_NAME).exists():
        source_dir = DRIVE_BACKUP_DIR
        print(f"📂 Found backup in Google Drive: {DRIVE_BACKUP_DIR}")
    elif (STAGING_BACKUP_DIR / ARCHIVE_FILE_NAME).exists():
        source_dir = STAGING_BACKUP_DIR
        print(f"📂 Found backup in local staging: {STAGING_BACKUP_DIR}")
    else:
        print("❌ Error: No backup archive found in Google Drive or staging!")
        print("   If you are in a new Colab runtime, please ensure Google Drive is mounted:")
        print("   from google.colab import drive; drive.mount('/content/drive')")
        return False

    archive_path = source_dir / ARCHIVE_FILE_NAME
    print(f"📦 Extracting {archive_path.name}...")

    tmp_restore_dir = Path("/tmp/nextai_restore_tmp")
    if tmp_restore_dir.exists():
        shutil.rmtree(tmp_restore_dir)
    tmp_restore_dir.mkdir(parents=True, exist_ok=True)

    with tarfile.open(str(archive_path), "r:gz") as tar:
        if hasattr(tarfile, "data_filter"):
            tar.extractall(path=str(tmp_restore_dir), filter="data")
        else:
            tar.extractall(path=str(tmp_restore_dir))

    # Restore Antigravity directory
    restored_gemini = tmp_restore_dir / "antigravity-cli"
    if restored_gemini.exists():
        GEMINI_DIR.mkdir(parents=True, exist_ok=True)
        # Conversations
        if (restored_gemini / "conversations").exists():
            (GEMINI_DIR / "conversations").mkdir(parents=True, exist_ok=True)
            shutil.copytree(restored_gemini / "conversations", GEMINI_DIR / "conversations", dirs_exist_ok=True)
        # Brain
        if (restored_gemini / "brain").exists():
            (GEMINI_DIR / "brain").mkdir(parents=True, exist_ok=True)
            shutil.copytree(restored_gemini / "brain", GEMINI_DIR / "brain", dirs_exist_ok=True)
        # Metadata
        for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
            src = restored_gemini / fname
            if src.exists():
                shutil.copy2(src, GEMINI_DIR / fname)

    # Restore SESSION_RESUME.md
    resume_md = tmp_restore_dir / RESUME_FILE_NAME
    if resume_md.exists():
        shutil.copy2(str(resume_md), "/content/SESSION_RESUME.md")
        if PROJECT_DIR.exists():
            shutil.copy2(str(resume_md), str(PROJECT_DIR / RESUME_FILE_NAME))

    shutil.rmtree(tmp_restore_dir, ignore_errors=True)

    # Restore GitHub token if present in backup dir
    token_file = source_dir / ".github_token"
    if token_file.exists():
        try:
            token = token_file.read_text().strip()
            os.environ["GITHUB_TOKEN"] = token
            if PROJECT_DIR.exists():
                subprocess.run(
                    ["git", "remote", "set-url", "origin", f"https://naitikmaurya1111-dotcom:{token}@github.com/naitikmaurya1111-dotcom/Next-Ai.git"],
                    cwd=str(PROJECT_DIR),
                    check=False
                )
            print("🔑 GitHub Personal Access Token restored and git remote configured!")
        except Exception as e:
            print(f"⚠️ Note loading GitHub token: {e}")

    print("✅ Antigravity CLI state, conversations, and brain artifacts restored successfully!")

    # Check / start bridge server
    print("🔌 Checking Colab Bridge Server status...")
    try:
        res = subprocess.run(["curl", "-s", "http://127.0.0.1:8000/health"], capture_output=True, text=True, timeout=3)
        if "ok" in res.stdout:
            print("⚡ Colab Bridge Server is already RUNNING on port 8000.")
        else:
            print("🚀 Starting Colab Bridge Server...")
            subprocess.Popen(["bash", "/content/Next-Ai/colab/start_bridge.sh"], cwd="/content/Next-Ai/colab")
    except Exception:
        print("🚀 Starting Colab Bridge Server...")
        subprocess.Popen(["bash", "/content/Next-Ai/colab/start_bridge.sh"], cwd="/content/Next-Ai/colab")

    print("\n" + "=" * 65)
    print("🎉 SESSION RESTORATION COMPLETE!")
    print("=" * 65)
    if (Path("/content/SESSION_RESUME.md")).exists():
        with open("/content/SESSION_RESUME.md") as f:
            lines = [line.strip() for line in f.readlines() if line.startswith("> ") or line.startswith("## ")]
            print("\n".join(lines[:8]))
    print("=" * 65 + "\n")
    return True

def status():
    """Display current Google Drive backup and session status."""
    is_mounted = is_drive_mounted()
    print("================ Next AI Session & Backup Status ================")
    print(f"Google Drive Mounted: {'✅ YES (/content/drive/MyDrive)' if is_mounted else '❌ NO (Mount via drive.mount)'}")
    
    target = DRIVE_BACKUP_DIR if is_mounted else STAGING_BACKUP_DIR
    archive = target / ARCHIVE_FILE_NAME
    if archive.exists():
        size_mb = round(archive.stat().st_size / (1024 * 1024), 2)
        mtime = datetime.fromtimestamp(archive.stat().st_mtime, timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        print(f"Last Backup Archive:  {archive.name} ({size_mb} MB)")
        print(f"Last Backup Time:     {mtime}")
    else:
        print("Last Backup Archive:  None found yet.")

    conv_db = GEMINI_DIR / "conversations" / f"{CONVERSATION_ID}.db"
    if conv_db.exists():
        print(f"Current Conversation: {CONVERSATION_ID} ({round(conv_db.stat().st_size / 1024, 1)} KB)")
    print("==================================================================")

def daemon(interval_seconds=300):
    """Run auto-backup daemon every interval_seconds (default 5 minutes)."""
    print(f"🔄 Next AI Auto-Backup Daemon started (sync interval: {interval_seconds}s)...")
    
    def handle_exit(signum, frame):
        print("\n🛑 Auto-Backup Daemon received exit signal. Performing final backup...")
        backup()
        sys.exit(0)

    signal.signal(signal.SIGTERM, handle_exit)
    signal.signal(signal.SIGINT, handle_exit)

    # Initial backup on start
    backup()

    while True:
        try:
            time.sleep(interval_seconds)
            print(f"\n⏰ [{datetime.now(timezone.utc).strftime('%H:%M:%S UTC')}] Scheduled auto-backup triggered...")
            backup()
        except Exception as e:
            print(f"⚠️ Error in auto-backup: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 drive_sync_manager.py [backup|restore|status|daemon]")
        sys.exit(1)

    cmd = sys.argv[1].lower()
    if cmd == "backup":
        backup()
    elif cmd == "restore":
        restore()
    elif cmd == "status":
        status()
    elif cmd == "daemon":
        interval = int(sys.argv[2]) if len(sys.argv) > 2 else 300
        daemon(interval)
    else:
        print(f"Unknown command: {cmd}")
        print("Usage: python3 drive_sync_manager.py [backup|restore|status|daemon]")
        sys.exit(1)
