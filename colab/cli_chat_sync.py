#!/usr/bin/env python3
"""
cli_chat_sync.py — Complete Antigravity CLI Chat & Terminal Progress Manager
=============================================================================
Organizes, exports, and syncs the entire terminal conversation history,
transcripts, SQLite databases, brain artifacts, and project progress
directly into Google Drive (/content/drive/MyDrive/NextAI_CLI_Chat_History).

Allows instant 1-command recovery of the entire pair programming session
in any subsequent Google Colab runtime.

Usage:
  python3 cli_chat_sync.py sync      # Full sync: format chats to Markdown, copy DBs & create backup
  python3 cli_chat_sync.py restore   # Restore all chats & brain state in a new Colab runtime
  python3 cli_chat_sync.py status    # Check sync status and conversation stats
  python3 cli_chat_sync.py daemon    # Run auto-sync background daemon (every 3 minutes)
"""

import os
import sys
import time
import json
import re
import shutil
import sqlite3
import tarfile
import subprocess
from datetime import datetime, timezone
from pathlib import Path

# Paths
DRIVE_DIR = Path("/content/drive/MyDrive")
SYNC_TARGET_DIR = DRIVE_DIR / "NextAI_CLI_Chat_History"
GEMINI_DIR = Path("/root/.gemini/antigravity-cli")
PROJECT_DIR = Path("/content/Next-Ai")
CONVERSATION_ID = "b885e03f-9af6-4038-b0f6-5185f2344b9c"

def is_drive_mounted() -> bool:
    return DRIVE_DIR.exists() and os.path.isdir(DRIVE_DIR)

def checkpoint_db(db_path: Path):
    if not db_path.exists():
        return
    try:
        conn = sqlite3.connect(str(db_path))
        conn.execute("PRAGMA wal_checkpoint(FULL);")
        conn.commit()
        conn.close()
    except Exception as e:
        print(f"  ⚠️ Warning checkpointing {db_path.name}: {e}")

def get_latest_git_info():
    sha = "unknown"
    msg = "unknown"
    if PROJECT_DIR.exists():
        try:
            res = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res.returncode == 0:
                sha = res.stdout.strip()
            res2 = subprocess.run(["git", "log", "-1", "--pretty=%B"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res2.returncode == 0:
                msg = res2.stdout.strip().split("\n")[0]
        except Exception:
            pass
    return sha, msg

def parse_transcript_to_dialogues(transcript_path: Path):
    """Parse transcript_full.jsonl into structured user-assistant dialogue turns."""
    if not transcript_path.exists():
        return []

    dialogues = []
    current_turn = None
    tool_calls_in_turn = []

    with open(transcript_path, "r", encoding="utf-8") as f:
        for line in f:
            try:
                data = json.loads(line)
                step_type = data.get("type")
                source = data.get("source")
                created_at = data.get("created_at", "")
                content = data.get("content", "")
                step_idx = data.get("step_index", 0)

                if step_type == "USER_INPUT" or source == "USER_EXPLICIT":
                    # Clean tags
                    clean_req = re.sub(r"<USER_REQUEST>\s*", "", content)
                    clean_req = re.sub(r"\s*</USER_REQUEST>.*", "", clean_req, flags=re.DOTALL).strip()
                    if not clean_req and "<CONTEXT_SUMMARY>" in content:
                        clean_req = "[Session Resumed from Previous Context Summary]"

                    current_turn = {
                        "turn": len(dialogues) + 1,
                        "step": step_idx,
                        "time": created_at,
                        "request": clean_req,
                        "tools": [],
                        "response": "",
                        "response_time": ""
                    }
                    dialogues.append(current_turn)
                    tool_calls_in_turn = []

                elif current_turn is not None:
                    # Collect tools called
                    tool_calls = data.get("tool_calls", [])
                    if tool_calls:
                        for tc in tool_calls:
                            name = tc.get("name", "tool")
                            summary = tc.get("args", {}).get("toolSummary") or tc.get("args", {}).get("toolAction") or name
                            if summary and summary not in current_turn["tools"]:
                                current_turn["tools"].append(summary)

                    if step_type == "PLANNER_RESPONSE" and content:
                        current_turn["response"] = content
                        current_turn["response_time"] = created_at
            except Exception:
                pass

    return dialogues

def generate_chat_history_markdown(dialogues, target_path: Path):
    """Generate human-readable Markdown of all CLI chat turns."""
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    sha, msg = get_latest_git_info()

    lines = [
        "# 📜 Next AI — Terminal & Antigravity CLI Chat History",
        f"> **Last Synced**: `{now_str}`  ",
        f"> **Conversation ID**: `{CONVERSATION_ID}`  ",
        f"> **Current Git SHA**: `{sha}` (`{msg}`)  ",
        f"> **Total Dialogues Recorded**: `{len(dialogues)}`  ",
        "> **Saved Location**: Google Drive (`/MyDrive/NextAI_CLI_Chat_History`)",
        "",
        "---",
        "",
        "## 📑 Table of Contents",
    ]

    for d in dialogues:
        req_title = d["request"].replace("\n", " ")[:60].strip()
        lines.append(f"- [Turn {d['turn']} (Step {d['step']}): {req_title}](#turn-{d['turn']})")

    lines.append("")
    lines.append("---")
    lines.append("")

    for d in dialogues:
        lines.append(f"## <a id=\"turn-{d['turn']}\"></a>💬 Turn {d['turn']} — Step {d['step']}")
        lines.append(f"**Timestamp**: `{d['time']}`  ")
        lines.append("")
        lines.append("### 👤 User Request:")
        lines.append("```text")
        lines.append(d["request"])
        lines.append("```")
        lines.append("")

        if d["tools"]:
            lines.append("### 🛠️ Key Actions / Tools Executed:")
            for t in d["tools"][:12]:
                lines.append(f"- `{t}`")
            if len(d["tools"]) > 12:
                lines.append(f"- *...and {len(d['tools']) - 12} additional tools*")
            lines.append("")

        lines.append("### 🤖 Assistant Response:")
        resp = d["response"].strip()
        if resp:
            lines.append(resp)
        else:
            lines.append("*(In progress / executing commands)*")
        lines.append("")
        lines.append("---")
        lines.append("")

    with open(target_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

def generate_recovery_summary(target_path: Path):
    """Generate high-density summary for the next AI session to read."""
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    sha, msg = get_latest_git_info()

    content = f"""# 🧠 Next AI & Antigravity Session Progress Recovery
> **Last Synced**: `{now_str}`  
> **Conversation ID**: `{CONVERSATION_ID}`  
> **Git Head SHA**: `{sha}` (`{msg}`)  
> **Active App Version**: `v1.0.20`  
> **Latest APK Download**: [app-debug.apk](https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases/download/v1.0.20/app-debug.apk)  
> **GitHub Repo**: [naitikmaurya1111-dotcom/Next-Ai](https://github.com/naitikmaurya1111-dotcom/Next-Ai)

---

## 🎯 Executive Summary & Mission
You are pair programming on **Next AI**, an advanced Android Chat App inspired by ChatGPT and Claude, connected to Google Colab running the **Antigravity CLI** (`agy`) bridge server via WebSocket.

### 🔑 Critical User Rules & Instructions
1. **GitHub Pushes**: **ALWAYS ask the user for explicit confirmation before pushing to GitHub (`git push`). NEVER push automatically without permission.** (User previously approved push for v1.0.20).
2. **Drive Persistence**: Session and CLI chat history are fully saved in `/content/drive/MyDrive/NextAI_CLI_Chat_History`.
3. **Reasoning Effort**: Defaults to `high`.

---

## 📱 App Current State & Features
- **UI Framework**: Jetpack Compose (Material 3 Dynamic Theme, Claude Terracotta styling)
- **Tablet Responsive Architecture**:
  - Split-screen docked collapsible sidebar (`280.dp`) when `screenWidthDp >= 600`.
  - Centered reading column & floating composer bounded by `840.dp`.
  - 2x2 action starter grid on tablets, 1-column stack on phones.
  - Centered modal sheets (`ManageMemorySheet`, `CustomInstructionsSheet`, `ModelBottomSheet`) bounded by `640.dp` to `680.dp`.
- **Memory & Personalization (ChatGPT 2026 Standard)**:
  - Autonomous memory extraction via `<memory_update>` tags during streaming into Room SQLite (`MemoryEntity`).
  - Searchable, categorized (`project`, `preference`, `personal`, `style`, `general`), editable memory sheet with JSON import/export.
  - Two-part custom instructions sheet with tone presets (`Direct & Concise`, `Technical`, `Educational`, `Warm`).
- **Slash Commands & Plugins** (all 8 cards implemented):
  `/goal`, `/plan`, `/boost`, `/schedule`, `/browser`, `/learn`, `/grill-me`, `/teamwork-preview`.
- **Latest Build & Release**:
  - Release: `v1.0.20`
  - GitHub Actions Workflow: Passed 100% cleanly
  - APK URL: `https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases/download/v1.0.20/app-debug.apk`

---

## 🌉 Colab Bridge Server
- Location: `/content/Next-Ai/colab`
- Server: FastAPI WebSocket on port 8000 + Cloudflare Tunnel
- Start Command: `bash /content/Next-Ai/colab/start_bridge.sh`
- Health check: `curl -s http://127.0.0.1:8000/health`

---

## 🚀 How To Resume
1. Restore Antigravity CLI state: `python3 /content/drive/MyDrive/NextAI_CLI_Chat_History/restore_cli.py`
2. Check Colab Bridge: `curl -s http://127.0.0.1:8000/health || bash /content/Next-Ai/colab/start_bridge.sh`
3. Greet user with latest Git commit (`{sha}`), app release (`v1.0.20`), and continue pair programming!
"""
    with open(target_path, "w", encoding="utf-8") as f:
        f.write(content)

def generate_recovery_command_file(target_path: Path):
    """Write exact prompt file for the user to copy/paste to the AI in next runtime."""
    prompt_text = """To resume your session in the next Colab runtime, copy and paste this exact prompt to the AI:

================================ COPY THIS PROMPT ================================
Resume our session from Google Drive:
1. Run: python3 /content/drive/MyDrive/NextAI_CLI_Chat_History/restore_cli.py
2. Read /content/drive/MyDrive/NextAI_CLI_Chat_History/SESSION_PROGRESS_RECOVERY.md
3. Check our chat transcript in /content/drive/MyDrive/NextAI_CLI_Chat_History/CLI_CHAT_HISTORY.md
4. Confirm our state is fully restored and tell me the current app status and next steps.
==================================================================================
"""
    with open(target_path, "w", encoding="utf-8") as f:
        f.write(prompt_text)

def sync():
    """Execute complete sync of CLI chat history, DBs, and progress to Google Drive."""
    print("🔄 Starting Antigravity CLI Chat & Progress Sync...")
    start_time = time.time()

    if not is_drive_mounted():
        print("❌ Error: Google Drive is not mounted at /content/drive/MyDrive!")
        print("   Please mount Google Drive first: from google.colab import drive; drive.mount('/content/drive')")
        return False

    SYNC_TARGET_DIR.mkdir(parents=True, exist_ok=True)
    raw_dir = SYNC_TARGET_DIR / "raw_data"
    raw_dir.mkdir(parents=True, exist_ok=True)

    # 1. Flush SQLite DBs
    conv_dir = GEMINI_DIR / "conversations"
    if conv_dir.exists():
        for db_file in conv_dir.glob("*.db"):
            checkpoint_db(db_file)
    sum_db = GEMINI_DIR / "conversation_summaries.db"
    if sum_db.exists():
        checkpoint_db(sum_db)

    # 2. Parse and generate CLI_CHAT_HISTORY.md
    transcript_full = GEMINI_DIR / "brain" / CONVERSATION_ID / ".system_generated" / "logs" / "transcript_full.jsonl"
    if not transcript_full.exists():
        # Fallback to compact transcript if full not found
        transcript_full = GEMINI_DIR / "brain" / CONVERSATION_ID / ".system_generated" / "logs" / "transcript.jsonl"

    print(f"📖 Parsing transcripts from {transcript_full}...")
    dialogues = parse_transcript_to_dialogues(transcript_full)
    print(f"💬 Found {len(dialogues)} dialogue turns.")

    chat_md_path = SYNC_TARGET_DIR / "CLI_CHAT_HISTORY.md"
    generate_chat_history_markdown(dialogues, chat_md_path)
    print(f"📄 Saved formatted chat history: {chat_md_path}")

    # Copy to project root as well
    if PROJECT_DIR.exists():
        shutil.copy2(str(chat_md_path), str(PROJECT_DIR / "CLI_CHAT_HISTORY.md"))

    # 3. Generate SESSION_PROGRESS_RECOVERY.md
    recovery_md_path = SYNC_TARGET_DIR / "SESSION_PROGRESS_RECOVERY.md"
    generate_recovery_summary(recovery_md_path)
    print(f"📄 Saved session recovery briefing: {recovery_md_path}")

    # 4. Generate RECOVER_COMMAND.txt
    cmd_file_path = SYNC_TARGET_DIR / "RECOVER_COMMAND.txt"
    generate_recovery_command_file(cmd_file_path)
    print(f"📋 Saved one-click restore prompt: {cmd_file_path}")

    # 5. Backup raw conversation databases and brain transcripts
    print("📦 Backing up raw conversation DBs & transcripts...")
    if conv_dir.exists():
        shutil.copytree(conv_dir, raw_dir / "conversations", dirs_exist_ok=True)
    brain_dir = GEMINI_DIR / "brain"
    if brain_dir.exists():
        shutil.copytree(brain_dir, raw_dir / "brain", dirs_exist_ok=True)
    for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
        fpath = GEMINI_DIR / fname
        if fpath.exists():
            shutil.copy2(fpath, raw_dir / fname)

    # 6. Create compressed state archive in Drive
    archive_path = SYNC_TARGET_DIR / "antigravity_cli_full_state.tar.gz"
    tmp_archive = Path("/tmp/cli_backup_tmp.tar.gz")
    print(f"🗜️ Compressing full Antigravity CLI state into {archive_path.name}...")
    with tarfile.open(tmp_archive, "w:gz") as tar:
        tar.add(str(raw_dir), arcname=".")
    shutil.move(str(tmp_archive), str(archive_path))

    # 7. Create standalone restore script inside Drive folder
    standalone_restore_code = generate_standalone_restore_script()
    restore_script_path = SYNC_TARGET_DIR / "restore_cli.py"
    with open(restore_script_path, "w", encoding="utf-8") as f:
        f.write(standalone_restore_code)
    restore_script_path.chmod(0o755)
    print(f"⚡ Saved standalone restore script: {restore_script_path}")

    elapsed = round(time.time() - start_time, 2)
    archive_mb = round(archive_path.stat().st_size / (1024 * 1024), 2)
    print(f"\n✅ SYNC COMPLETE in {elapsed}s!")
    print(f"📁 Drive Folder: {SYNC_TARGET_DIR}")
    print(f"📦 Full State Archive: {archive_mb} MB")
    print(f"📜 Formatted Chat History: {chat_md_path} ({len(dialogues)} turns)")
    return True

def restore():
    """Restore all chats and CLI state in a new Colab runtime."""
    print("🔄 Restoring Antigravity CLI state and chats from Google Drive...")
    if not is_drive_mounted():
        print("❌ Error: Google Drive is not mounted at /content/drive/MyDrive!")
        print("   Please run: from google.colab import drive; drive.mount('/content/drive')")
        return False

    archive_path = SYNC_TARGET_DIR / "antigravity_cli_full_state.tar.gz"
    if not archive_path.exists():
        print(f"❌ Error: Archive not found at {archive_path}!")
        return False

    print(f"📦 Extracting {archive_path.name} to {GEMINI_DIR}...")
    GEMINI_DIR.mkdir(parents=True, exist_ok=True)

    tmp_dir = Path("/tmp/cli_restore_tmp")
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    tmp_dir.mkdir(parents=True, exist_ok=True)

    with tarfile.open(str(archive_path), "r:gz") as tar:
        if hasattr(tarfile, "data_filter"):
            tar.extractall(path=str(tmp_dir), filter="data")
        else:
            tar.extractall(path=str(tmp_dir))

    # Restore components
    if (tmp_dir / "conversations").exists():
        (GEMINI_DIR / "conversations").mkdir(parents=True, exist_ok=True)
        shutil.copytree(tmp_dir / "conversations", GEMINI_DIR / "conversations", dirs_exist_ok=True)
    if (tmp_dir / "brain").exists():
        (GEMINI_DIR / "brain").mkdir(parents=True, exist_ok=True)
        shutil.copytree(tmp_dir / "brain", GEMINI_DIR / "brain", dirs_exist_ok=True)
    for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
        src = tmp_dir / fname
        if src.exists():
            shutil.copy2(src, GEMINI_DIR / fname)

    shutil.rmtree(tmp_dir, ignore_errors=True)

    # Copy summaries to /content
    for md_name in ["CLI_CHAT_HISTORY.md", "SESSION_PROGRESS_RECOVERY.md"]:
        src_md = SYNC_TARGET_DIR / md_name
        if src_md.exists():
            shutil.copy2(str(src_md), f"/content/{md_name}")
            if PROJECT_DIR.exists():
                shutil.copy2(str(src_md), str(PROJECT_DIR / md_name))

    print("✅ Antigravity CLI state, conversation databases, and full transcripts restored successfully!")

    # Check / start bridge server
    print("🔌 Checking Colab Bridge Server status...")
    try:
        res = subprocess.run(["curl", "-s", "http://127.0.0.1:8000/health"], capture_output=True, text=True, timeout=3)
        if "ok" in res.stdout:
            print("⚡ Colab Bridge Server is RUNNING on port 8000.")
        else:
            print("🚀 Starting Colab Bridge Server...")
            if (PROJECT_DIR / "colab" / "start_bridge.sh").exists():
                subprocess.Popen(["bash", str(PROJECT_DIR / "colab" / "start_bridge.sh")], cwd=str(PROJECT_DIR / "colab"))
    except Exception:
        pass

    print("\n" + "=" * 65)
    print("🎉 SESSION & CLI CHATS RESTORED SUCCESSFULLY!")
    print(f"📖 Full Chat History available at: {SYNC_TARGET_DIR / 'CLI_CHAT_HISTORY.md'}")
    print(f"📄 Summary available at: {SYNC_TARGET_DIR / 'SESSION_PROGRESS_RECOVERY.md'}")
    print("=" * 65 + "\n")
    return True

def status():
    """Print sync and conversation status."""
    is_mounted = is_drive_mounted()
    print("================ Next AI CLI Chat Sync Status ================")
    print(f"Google Drive Mounted: {'✅ YES' if is_mounted else '❌ NO'}")
    archive = SYNC_TARGET_DIR / "antigravity_cli_full_state.tar.gz"
    if archive.exists():
        size_mb = round(archive.stat().st_size / (1024 * 1024), 2)
        mtime = datetime.fromtimestamp(archive.stat().st_mtime, timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
        print(f"Latest State Archive: {archive.name} ({size_mb} MB)")
        print(f"Last Sync Time:       {mtime}")
    else:
        print("Latest State Archive: None found yet.")

    chat_md = SYNC_TARGET_DIR / "CLI_CHAT_HISTORY.md"
    if chat_md.exists():
        lines = len(chat_md.read_text(encoding="utf-8").splitlines())
        print(f"Chat History File:    CLI_CHAT_HISTORY.md ({lines} lines)")

    conv_db = GEMINI_DIR / "conversations" / f"{CONVERSATION_ID}.db"
    if conv_db.exists():
        print(f"Active Conversation:  {CONVERSATION_ID} ({round(conv_db.stat().st_size / (1024*1024), 2)} MB)")
    print("===============================================================")

def daemon(interval_seconds=180):
    """Run auto-sync daemon in background."""
    print(f"🔄 CLI Chat Auto-Sync Daemon started (sync interval: {interval_seconds}s)...")
    sync()
    while True:
        try:
            time.sleep(interval_seconds)
            sync()
        except Exception as e:
            print(f"⚠️ Error in auto-sync: {e}")

def generate_standalone_restore_script() -> str:
    """Generate self-contained restore script saved directly in Google Drive."""
    return f"""#!/usr/bin/env python3
import os
import sys
import shutil
import tarfile
import subprocess
from pathlib import Path

DRIVE_DIR = Path("/content/drive/MyDrive/NextAI_CLI_Chat_History")
GEMINI_DIR = Path("/root/.gemini/antigravity-cli")
PROJECT_DIR = Path("/content/Next-Ai")
ARCHIVE = DRIVE_DIR / "antigravity_cli_full_state.tar.gz"

print("🔄 Restoring Next AI Antigravity CLI Session from Google Drive...")
if not ARCHIVE.exists():
    print(f"❌ Error: {{ARCHIVE}} not found. Ensure Google Drive is mounted.")
    sys.exit(1)

GEMINI_DIR.mkdir(parents=True, exist_ok=True)
tmp_dir = Path("/tmp/cli_standalone_restore")
if tmp_dir.exists():
    shutil.rmtree(tmp_dir)
tmp_dir.mkdir(parents=True, exist_ok=True)

with tarfile.open(str(ARCHIVE), "r:gz") as tar:
    if hasattr(tarfile, "data_filter"):
        tar.extractall(path=str(tmp_dir), filter="data")
    else:
        tar.extractall(path=str(tmp_dir))

if (tmp_dir / "conversations").exists():
    (GEMINI_DIR / "conversations").mkdir(parents=True, exist_ok=True)
    shutil.copytree(tmp_dir / "conversations", GEMINI_DIR / "conversations", dirs_exist_ok=True)
if (tmp_dir / "brain").exists():
    (GEMINI_DIR / "brain").mkdir(parents=True, exist_ok=True)
    shutil.copytree(tmp_dir / "brain", GEMINI_DIR / "brain", dirs_exist_ok=True)
for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
    src = tmp_dir / fname
    if src.exists():
        shutil.copy2(src, GEMINI_DIR / fname)

shutil.rmtree(tmp_dir, ignore_errors=True)

# Copy markdown context files to /content
for f in ["CLI_CHAT_HISTORY.md", "SESSION_PROGRESS_RECOVERY.md"]:
    src = DRIVE_DIR / f
    if src.exists():
        shutil.copy2(str(src), f"/content/{{f}}")

print("✅ Antigravity CLI chats, databases, and transcripts restored successfully!")

# Start bridge server if present
if (PROJECT_DIR / "colab" / "start_bridge.sh").exists():
    print("🔌 Starting Colab Bridge Server...")
    subprocess.Popen(["bash", str(PROJECT_DIR / "colab" / "start_bridge.sh")], cwd=str(PROJECT_DIR / "colab"))

print("🎉 DONE! You can now resume your AI conversation with full context.")
"""

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sync()
    else:
        cmd = sys.argv[1].lower()
        if cmd == "sync":
            sync()
        elif cmd == "restore":
            restore()
        elif cmd == "status":
            status()
        elif cmd == "daemon":
            interval = int(sys.argv[2]) if len(sys.argv) > 2 else 180
            daemon(interval)
        else:
            print(f"Unknown command: {cmd}")
            print("Usage: python3 cli_chat_sync.py [sync|restore|status|daemon]")

