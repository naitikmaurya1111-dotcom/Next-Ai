#!/usr/bin/env python3
"""
cli_chat_sync.py — Complete Antigravity CLI Chat & Terminal Progress Manager
=============================================================================
Organizes, exports, and syncs all terminal conversation history,
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

def get_active_conversation_id() -> str:
    """Dynamically determine the current active conversation ID."""
    env_id = os.environ.get("CONVERSATION_ID")
    if env_id and (GEMINI_DIR / "brain" / env_id).exists():
        return env_id

    sum_db = GEMINI_DIR / "conversation_summaries.db"
    if sum_db.exists():
        try:
            conn = sqlite3.connect(str(sum_db))
            c = conn.cursor()
            c.execute("SELECT conversation_id FROM conversation_summaries WHERE step_count > 0 ORDER BY last_modified_time DESC LIMIT 1;")
            row = c.fetchone()
            conn.close()
            if row and row[0]:
                return row[0]
        except Exception:
            pass

    brain_dir = GEMINI_DIR / "brain"
    if brain_dir.exists():
        dirs = [p for p in brain_dir.iterdir() if p.is_dir() and not p.name.startswith(".")]
        if dirs:
            dirs.sort(key=lambda x: x.stat().st_mtime, reverse=True)
            return dirs[0].name

    return "2fd3f6ca-bd12-4113-8b81-7b9d56153473"

def get_latest_git_info():
    """Retrieve Git SHA, commit message, and active tag."""
    sha = "unknown"
    msg = "unknown"
    tag = "v1.0.50"
    if PROJECT_DIR.exists():
        try:
            res = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res.returncode == 0:
                sha = res.stdout.strip()
            res2 = subprocess.run(["git", "log", "-1", "--pretty=%B"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res2.returncode == 0:
                msg = res2.stdout.strip().split("\n")[0]
            res3 = subprocess.run(["git", "describe", "--tags", "--abbrev=0"], cwd=str(PROJECT_DIR), capture_output=True, text=True)
            if res3.returncode == 0 and res3.stdout.strip():
                tag = res3.stdout.strip()
        except Exception:
            pass
    return sha, msg, tag

def parse_transcript_to_dialogues(transcript_path: Path):
    """Parse transcript_full.jsonl or transcript.jsonl into structured user-assistant dialogue turns."""
    if not transcript_path.exists():
        return []

    dialogues = []
    current_turn = None

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
                    # Sanitize any GitHub tokens to comply with GitHub Push Protection
                    clean_req = re.sub(r"ghp_[A-Za-z0-9_]{20,}", "[REDACTED_GITHUB_TOKEN]", clean_req)
                    clean_req = re.sub(r"github_pat_[A-Za-z0-9_]{20,}", "[REDACTED_GITHUB_TOKEN]", clean_req)

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
                        clean_resp = re.sub(r"ghp_[A-Za-z0-9_]{20,}", "[REDACTED_GITHUB_TOKEN]", content)
                        clean_resp = re.sub(r"github_pat_[A-Za-z0-9_]{20,}", "[REDACTED_GITHUB_TOKEN]", clean_resp)
                        current_turn["response"] = clean_resp
                        current_turn["response_time"] = created_at
            except Exception:
                pass

    return dialogues

def format_dialogues_markdown(dialogues, title: str, conv_id: str, git_sha: str, git_msg: str, tag: str, now_str: str) -> str:
    """Format an array of dialogue turns into structured markdown."""
    lines = [
        f"# 📜 {title}",
        f"> **Last Synced**: `{now_str}`  ",
        f"> **Conversation ID**: `{conv_id}`  ",
        f"> **Current Git SHA**: `{git_sha}` (`{git_msg}`)  ",
        f"> **Active App Version**: `{tag}`  ",
        f"> **Total Dialogues Recorded**: `{len(dialogues)}`  ",
        "> **Saved Location**: Google Drive (`/MyDrive/NextAI_CLI_Chat_History`)",
        "",
        "---",
        "",
        "## 📑 Table of Contents",
    ]

    for d in dialogues:
        req_title = d["request"].replace("\n", " ")[:70].strip()
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
            for t in d["tools"][:15]:
                lines.append(f"- `{t}`")
            if len(d["tools"]) > 15:
                lines.append(f"- *...and {len(d['tools']) - 15} additional tools*")
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

    return "\n".join(lines)

def export_all_conversation_sessions(target_sessions_dir: Path, sha: str, msg: str, tag: str, now_str: str):
    """Export every conversation in conversation_summaries.db into its own markdown log file."""
    target_sessions_dir.mkdir(parents=True, exist_ok=True)
    sum_db = GEMINI_DIR / "conversation_summaries.db"
    if not sum_db.exists():
        return []

    sessions_meta = []
    try:
        conn = sqlite3.connect(str(sum_db))
        c = conn.cursor()
        c.execute("SELECT conversation_id, title, step_count, last_modified_time FROM conversation_summaries WHERE step_count > 0 ORDER BY last_modified_time DESC;")
        rows = c.fetchall()
        conn.close()
    except Exception as e:
        print(f"⚠️ Warning querying summaries: {e}")
        return []

    for cid, title, steps, mtime in rows:
        t_full = GEMINI_DIR / "brain" / cid / ".system_generated" / "logs" / "transcript_full.jsonl"
        if not t_full.exists():
            t_full = GEMINI_DIR / "brain" / cid / ".system_generated" / "logs" / "transcript.jsonl"
        if not t_full.exists():
            continue

        dialogues = parse_transcript_to_dialogues(t_full)
        if not dialogues:
            continue

        safe_title = re.sub(r"[^a-zA-Z0-9_-]", "_", (title or "session").strip().lower())
        safe_title = re.sub(r"_+", "_", safe_title)[:40].strip("_")
        date_prefix = (mtime[:10] if mtime else datetime.now().strftime("%Y-%m-%d")).replace("-", "")
        file_name = f"{date_prefix}_{cid[:8]}_{safe_title}.md"
        out_path = target_sessions_dir / file_name

        md_content = format_dialogues_markdown(
            dialogues,
            f"Next AI Session — {title or cid[:8]}",
            cid, sha, msg, tag, now_str
        )
        out_path.write_text(md_content, encoding="utf-8")

        sessions_meta.append({
            "id": cid,
            "title": title or "(untitled session)",
            "steps": steps,
            "turns": len(dialogues),
            "mtime": mtime[:19] if mtime else "unknown",
            "file": file_name
        })

    return sessions_meta

def generate_master_chat_history(dialogues, sessions_meta, target_path: Path, active_cid: str, sha: str, msg: str, tag: str, now_str: str):
    """Generate the master CLI_CHAT_HISTORY.md index and active conversation transcript."""
    lines = [
        "# 📜 Next AI — Terminal & Antigravity CLI Master Chat History",
        f"> **Last Synced**: `{now_str}`  ",
        f"> **Active Conversation ID**: `{active_cid}`  ",
        f"> **Current Git SHA**: `{sha}` (`{msg}`)  ",
        f"> **Active App Version**: `{tag}`  ",
        f"> **Active Turn Count**: `{len(dialogues)}`  ",
        f"> **Total Sessions Archived**: `{len(sessions_meta)}`  ",
        "> **Saved Location**: Google Drive (`/MyDrive/NextAI_CLI_Chat_History`)",
        "",
        "---",
        "",
        "## 📚 Archive of All CLI Sessions & Dialogues",
        "Every session transcript is preserved in dedicated Markdown logs inside [`sessions/`](./sessions/):",
        "",
        "| Date / Time (UTC) | Conversation ID | Title / Topic | Steps | User Turns | Detailed Log |",
        "| :--- | :--- | :--- | :--- | :--- | :--- |"
    ]

    for s in sessions_meta:
        is_curr = " **(Active)**" if s["id"] == active_cid else ""
        lines.append(f"| `{s['mtime']}` | `{s['id'][:8]}...`{is_curr} | {s['title']} | {s['steps']} | {s['turns']} | [`{s['file']}`](./sessions/{s['file']}) |")

    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append(f"## 💬 Current Active Session Transcript (`{active_cid}`)")
    lines.append("")

    if dialogues:
        for d in dialogues:
            req_title = d["request"].replace("\n", " ")[:70].strip()
            lines.append(f"### <a id=\"turn-{d['turn']}\"></a>💬 Turn {d['turn']} — Step {d['step']}: {req_title}")
            lines.append(f"**Timestamp**: `{d['time']}`  ")
            lines.append("")
            lines.append("#### 👤 User Request:")
            lines.append("```text")
            lines.append(d["request"])
            lines.append("```")
            lines.append("")

            if d["tools"]:
                lines.append("#### 🛠️ Key Actions / Tools Executed:")
                for t in d["tools"][:15]:
                    lines.append(f"- `{t}`")
                if len(d["tools"]) > 15:
                    lines.append(f"- *...and {len(d['tools']) - 15} additional tools*")
                lines.append("")

            lines.append("#### 🤖 Assistant Response:")
            resp = d["response"].strip()
            if resp:
                lines.append(resp)
            else:
                lines.append("*(In progress / executing commands)*")
            lines.append("")
            lines.append("---")
            lines.append("")
    else:
        lines.append("*(No dialogue turns recorded in active session yet)*")

    target_path.write_text("\n".join(lines), encoding="utf-8")

def generate_recovery_summary(target_path: Path, conv_id: str, sha: str, msg: str, tag: str, now_str: str):
    """Generate high-density summary for the next AI session to read."""
    content = f"""# 🧠 Next AI & Antigravity Session Progress Recovery
> **Last Synced**: `{now_str}`  
> **Conversation ID**: `{conv_id}`  
> **Git Head SHA**: `{sha}` (`{msg}`)  
> **Active App Version**: `{tag}`  
> **Latest APK Download**: [{tag} Releases](https://github.com/naitikmaurya1111-dotcom/Next-Ai/releases)  
> **GitHub Repo**: [naitikmaurya1111-dotcom/Next-Ai](https://github.com/naitikmaurya1111-dotcom/Next-Ai)

---

## 🎯 Executive Summary & Mission
You are pair programming on **Next AI**, an advanced Android Chat App inspired by ChatGPT and Claude, connected to Google Colab running the **Antigravity CLI** (`agy`) bridge server via WebSocket.

### 🔑 Critical User Rules & Instructions
1. **GitHub Pushes**: **ALWAYS ask the user for explicit confirmation before pushing to GitHub (`git push`). NEVER push automatically without permission.**
2. **Drive Persistence**: All sessions, transcripts, SQLite databases, and project progress are preserved in `/content/drive/MyDrive/NextAI_CLI_Chat_History` and `/content/drive/MyDrive/NextAI_Backup`.
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
  - Release Tag: `{tag}`
  - Git Commit: `{sha}`
  - GitHub Actions Workflow: Configured with automated APK build artifacts

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
3. Greet user with latest Git commit (`{sha}`), app release (`{tag}`), and continue pair programming!
"""
    target_path.write_text(content, encoding="utf-8")

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
    target_path.write_text(prompt_text, encoding="utf-8")

def generate_standalone_restore_script() -> str:
    """Generate self-contained restore script saved directly in Google Drive."""
    return """#!/usr/bin/env python3
import os
import sys
import shutil
import tarfile
import subprocess
from pathlib import Path

DRIVE_DIR = Path("/content/drive/MyDrive/NextAI_CLI_Chat_History")
BACKUP_DIR = Path("/content/drive/MyDrive/NextAI_Backup")
GEMINI_DIR = Path("/root/.gemini/antigravity-cli")
PROJECT_DIR = Path("/content/Next-Ai")
ARCHIVE = DRIVE_DIR / "antigravity_cli_full_state.tar.gz"

print("🔄 Restoring Next AI Antigravity CLI Session from Google Drive...")
if not ARCHIVE.exists() and (BACKUP_DIR / "nextai_session_latest.tar.gz").exists():
    ARCHIVE = BACKUP_DIR / "nextai_session_latest.tar.gz"

if not ARCHIVE.exists():
    print(f"❌ Error: Backup archive not found. Ensure Google Drive is mounted.")
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

src_root = tmp_dir / "antigravity-cli" if (tmp_dir / "antigravity-cli").exists() else tmp_dir

if (src_root / "conversations").exists():
    (GEMINI_DIR / "conversations").mkdir(parents=True, exist_ok=True)
    shutil.copytree(src_root / "conversations", GEMINI_DIR / "conversations", dirs_exist_ok=True)
if (src_root / "brain").exists():
    (GEMINI_DIR / "brain").mkdir(parents=True, exist_ok=True)
    shutil.copytree(src_root / "brain", GEMINI_DIR / "brain", dirs_exist_ok=True)
for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
    src = src_root / fname
    if src.exists():
        shutil.copy2(src, GEMINI_DIR / fname)

shutil.rmtree(tmp_dir, ignore_errors=True)

# Copy markdown context files to /content
for f in ["CLI_CHAT_HISTORY.md", "SESSION_PROGRESS_RECOVERY.md", "SESSION_RESUME.md"]:
    for d in [DRIVE_DIR, BACKUP_DIR]:
        p = d / f
        if p.exists():
            shutil.copy2(str(p), f"/content/{f}")
            break

# Restore GitHub token if present
for d in [BACKUP_DIR, DRIVE_DIR]:
    tok = d / ".github_token"
    if tok.exists():
        token_str = tok.read_text().strip()
        os.environ["GITHUB_TOKEN"] = token_str
        subprocess.run(["git", "config", "--global", "credential.helper", "store"], check=False)
        with open("/root/.git-credentials", "w") as gf:
            gf.write(f"https://x-access-token:{token_str}@github.com\\n")
        os.chmod("/root/.git-credentials", 0o600)
        break

print("✅ Antigravity CLI chats, databases, and transcripts restored successfully!")

# Start bridge server if present
if (PROJECT_DIR / "colab" / "start_bridge.sh").exists():
    print("🔌 Starting Colab Bridge Server...")
    subprocess.Popen(["bash", str(PROJECT_DIR / "colab" / "start_bridge.sh")], cwd=str(PROJECT_DIR / "colab"))

print("🎉 DONE! You can now resume your AI conversation with full context.")
"""

def sync():
    """Execute complete sync of CLI chat history, DBs, and progress to Google Drive."""
    print("🔄 Starting Antigravity CLI Chat & Progress Sync...")
    start_time = time.time()

    if not is_drive_mounted():
        print("❌ Error: Google Drive is not mounted at /content/drive/MyDrive!")
        print("   Please mount Google Drive first: from google.colab import drive; drive.mount('/content/drive')")
        return False

    SYNC_TARGET_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Flush SQLite DBs
    conv_dir = GEMINI_DIR / "conversations"
    if conv_dir.exists():
        for db_file in conv_dir.glob("*.db"):
            checkpoint_db(db_file)
    sum_db = GEMINI_DIR / "conversation_summaries.db"
    if sum_db.exists():
        checkpoint_db(sum_db)

    # 2. Get active conversation ID and Git metadata
    active_cid = get_active_conversation_id()
    sha, msg, tag = get_latest_git_info()
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    print(f"🎯 Active Conversation ID: {active_cid}")
    print(f"🏷️ Git Head: {sha} ({tag})")

    # 3. Export all conversation sessions to individual markdown files
    sessions_dir = SYNC_TARGET_DIR / "sessions"
    print("📁 Archiving all conversation sessions into markdown files...")
    sessions_meta = export_all_conversation_sessions(sessions_dir, sha, msg, tag, now_str)
    print(f"✅ Archived {len(sessions_meta)} sessions into {sessions_dir}")

    # 4. Parse active transcript
    t_active = GEMINI_DIR / "brain" / active_cid / ".system_generated" / "logs" / "transcript_full.jsonl"
    if not t_active.exists():
        t_active = GEMINI_DIR / "brain" / active_cid / ".system_generated" / "logs" / "transcript.jsonl"

    active_dialogues = parse_transcript_to_dialogues(t_active) if t_active.exists() else []
    print(f"💬 Active dialogue turns recorded: {len(active_dialogues)}")

    # 5. Generate master CLI_CHAT_HISTORY.md
    chat_md_path = SYNC_TARGET_DIR / "CLI_CHAT_HISTORY.md"
    generate_master_chat_history(active_dialogues, sessions_meta, chat_md_path, active_cid, sha, msg, tag, now_str)
    print(f"📄 Saved master chat history: {chat_md_path}")

    # Copy to project root and /content as well
    try:
        shutil.copy2(str(chat_md_path), "/content/CLI_CHAT_HISTORY.md")
        if PROJECT_DIR.exists():
            shutil.copy2(str(chat_md_path), str(PROJECT_DIR / "CLI_CHAT_HISTORY.md"))
    except Exception:
        pass

    # 6. Generate SESSION_PROGRESS_RECOVERY.md
    recovery_md_path = SYNC_TARGET_DIR / "SESSION_PROGRESS_RECOVERY.md"
    generate_recovery_summary(recovery_md_path, active_cid, sha, msg, tag, now_str)
    print(f"📄 Saved session recovery briefing: {recovery_md_path}")
    try:
        shutil.copy2(str(recovery_md_path), "/content/SESSION_PROGRESS_RECOVERY.md")
        if PROJECT_DIR.exists():
            shutil.copy2(str(recovery_md_path), str(PROJECT_DIR / "SESSION_PROGRESS_RECOVERY.md"))
    except Exception:
        pass

    # 7. Generate RECOVER_COMMAND.txt
    cmd_file_path = SYNC_TARGET_DIR / "RECOVER_COMMAND.txt"
    generate_recovery_command_file(cmd_file_path)
    print(f"📋 Saved one-click restore prompt: {cmd_file_path}")

    # 8. Backup raw conversation databases and brain transcripts (staged locally in /tmp for speed)
    raw_dir = Path("/tmp/raw_cli_backup")
    if raw_dir.exists():
        shutil.rmtree(raw_dir)
    raw_dir.mkdir(parents=True, exist_ok=True)

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

    # Also persist GitHub token in both directories
    tok_file = DRIVE_DIR / "NextAI_Backup" / ".github_token"
    if tok_file.exists():
        try:
            shutil.copy2(str(tok_file), str(SYNC_TARGET_DIR / ".github_token"))
        except Exception:
            pass

    # 9. Create compressed state archive in Drive
    archive_path = SYNC_TARGET_DIR / "antigravity_cli_full_state.tar.gz"
    tmp_archive = Path("/tmp/cli_backup_tmp.tar.gz")
    if tmp_archive.exists():
        tmp_archive.unlink()

    print(f"🗜️ Compressing full Antigravity CLI state into {archive_path.name}...")
    with tarfile.open(tmp_archive, "w:gz") as tar:
        tar.add(str(raw_dir), arcname=".")
    shutil.move(str(tmp_archive), str(archive_path))
    shutil.rmtree(raw_dir, ignore_errors=True)

    # 10. Create standalone restore script inside Drive folder
    standalone_restore_code = generate_standalone_restore_script()
    restore_script_path = SYNC_TARGET_DIR / "restore_cli.py"
    restore_script_path.write_text(standalone_restore_code, encoding="utf-8")
    restore_script_path.chmod(0o755)
    print(f"⚡ Saved standalone restore script: {restore_script_path}")

    elapsed = round(time.time() - start_time, 2)
    archive_mb = round(archive_path.stat().st_size / (1024 * 1024), 2)
    print(f"\n✅ SYNC COMPLETE in {elapsed}s!")
    print(f"📁 Drive Folder: {SYNC_TARGET_DIR}")
    print(f"📦 Full State Archive: {archive_mb} MB")
    print(f"📜 Formatted Chat History: {chat_md_path} ({len(active_dialogues)} active turns, {len(sessions_meta)} sessions archived)")
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
        alt_archive = DRIVE_DIR / "NextAI_Backup" / "nextai_session_latest.tar.gz"
        if alt_archive.exists():
            archive_path = alt_archive
        else:
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

    src_root = tmp_dir / "antigravity-cli" if (tmp_dir / "antigravity-cli").exists() else tmp_dir

    if (src_root / "conversations").exists():
        (GEMINI_DIR / "conversations").mkdir(parents=True, exist_ok=True)
        shutil.copytree(src_root / "conversations", GEMINI_DIR / "conversations", dirs_exist_ok=True)
    if (src_root / "brain").exists():
        (GEMINI_DIR / "brain").mkdir(parents=True, exist_ok=True)
        shutil.copytree(src_root / "brain", GEMINI_DIR / "brain", dirs_exist_ok=True)
    for fname in ["conversation_summaries.db", "history.jsonl", "settings.json", "installation_id", "antigravity-oauth-token"]:
        src = src_root / fname
        if src.exists():
            shutil.copy2(src, GEMINI_DIR / fname)

    shutil.rmtree(tmp_dir, ignore_errors=True)

    # Restore GitHub token if present
    for d in [DRIVE_DIR / "NextAI_Backup", SYNC_TARGET_DIR]:
        tok = d / ".github_token"
        if tok.exists():
            token_str = tok.read_text().strip()
            os.environ["GITHUB_TOKEN"] = token_str
            subprocess.run(["git", "config", "--global", "credential.helper", "store"], check=False)
            with open("/root/.git-credentials", "w") as gf:
                gf.write(f"https://x-access-token:{token_str}@github.com\n")
            os.chmod("/root/.git-credentials", 0o600)
            break

    # Copy docs
    for f in ["CLI_CHAT_HISTORY.md", "SESSION_PROGRESS_RECOVERY.md"]:
        src = SYNC_TARGET_DIR / f
        if src.exists():
            shutil.copy2(str(src), f"/content/{f}")

    print("🔌 Checking Colab Bridge Server status...")
    try:
        res = subprocess.run(["curl", "-s", "http://127.0.0.1:8000/health"], capture_output=True, text=True, timeout=3)
        if "ok" in res.stdout:
            print("⚡ Colab Bridge Server is already RUNNING on port 8000.")
        else:
            print("🚀 Starting Colab Bridge Server...")
            if (PROJECT_DIR / "colab" / "start_bridge.sh").exists():
                subprocess.Popen(["bash", str(PROJECT_DIR / "colab" / "start_bridge.sh")], cwd=str(PROJECT_DIR / "colab"))
    except Exception:
        pass

    print("\n" + "=" * 65)
    print("🎉 SESSION & CLI CHATS RESTORED SUCCESSFULLY!")
    print(f"📖 Master Chat History: {SYNC_TARGET_DIR / 'CLI_CHAT_HISTORY.md'}")
    print(f"📄 Summary: {SYNC_TARGET_DIR / 'SESSION_PROGRESS_RECOVERY.md'}")
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

    active_cid = get_active_conversation_id()
    conv_db = GEMINI_DIR / "conversations" / f"{active_cid}.db"
    if conv_db.exists():
        print(f"Active Conversation:  {active_cid} ({round(conv_db.stat().st_size / (1024*1024), 2)} MB)")
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
