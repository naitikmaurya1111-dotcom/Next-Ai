#!/usr/bin/env python3
"""
update_gist_url.py — Next AI Dynamic Colab Bridge URL Auto-Sync
===============================================================
Publishes the active Cloudflare or ngrok tunnel URL to a permanent GitHub Gist.
The Next AI Android app queries this Gist on launch to automatically connect
without the user ever having to copy-paste URLs across Colab restarts.
"""

import sys
import os
import json
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_GIST_ID = "93a5f994e43134016362692fe4bfc510"

def get_github_token() -> str:
    """Retrieve GitHub token from Drive backup or environment."""
    for p in [
        Path("/content/drive/MyDrive/NextAI_Backup/.github_token"),
        Path("/content/drive/MyDrive/NextAI_CLI_Chat_History/.github_token"),
        Path("/root/.github_token")
    ]:
        if p.exists():
            token = p.read_text().strip()
            if token:
                return token
    return os.environ.get("GITHUB_TOKEN", "").strip()

def get_gist_id() -> str:
    """Retrieve permanent Gist ID from Drive backup or fallback."""
    for p in [
        Path("/content/drive/MyDrive/NextAI_Backup/.gist_id"),
        Path("/content/drive/MyDrive/NextAI_CLI_Chat_History/.gist_id")
    ]:
        if p.exists():
            gid = p.read_text().strip()
            if gid:
                return gid
    return DEFAULT_GIST_ID

def save_gist_id(gist_id: str):
    """Save Gist ID to Google Drive so it persists forever."""
    for p in [
        Path("/content/drive/MyDrive/NextAI_Backup/.gist_id"),
        Path("/content/drive/MyDrive/NextAI_CLI_Chat_History/.gist_id")
    ]:
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(gist_id.strip() + "\n")
        except Exception:
            pass

def update_or_create_gist(ws_url: str, http_url: str = "") -> bool:
    token = get_github_token()
    if not token:
        print("⚠️ Warning: No GitHub token found. Cannot update Gist URL automatically.")
        return False

    gist_id = get_gist_id()
    now_iso = datetime.now(timezone.utc).isoformat()
    payload_content = json.dumps({
        "ws_url": ws_url,
        "http_url": http_url,
        "updated_at": now_iso
    }, indent=2)

    headers = {
        "Authorization": f"token {token}",
        "User-Agent": "NextAI-Colab-Bridge",
        "Accept": "application/vnd.github+json"
    }

    # 1. Try updating existing Gist
    if gist_id:
        patch_data = {
            "description": "Next AI Live Colab Bridge URL",
            "files": {
                "nextai_live_url.json": {
                    "content": payload_content
                }
            }
        }
        try:
            req = urllib.request.Request(
                f"https://api.github.com/gists/{gist_id}",
                data=json.dumps(patch_data).encode("utf-8"),
                headers=headers,
                method="PATCH"
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status in (200, 201):
                    print(f"✅ Live WebSocket URL published to permanent GitHub Gist: {gist_id}")
                    return True
        except urllib.error.HTTPError as e:
            if e.code != 404:
                print(f"⚠️ Failed to update Gist {gist_id}: {e}")
                return False
            print(f"ℹ️ Gist {gist_id} not found, creating a new one...")
        except Exception as e:
            print(f"⚠️ Error updating Gist: {e}")
            return False

    # 2. Create new Gist if needed
    create_data = {
        "description": "Next AI Live Colab Bridge URL",
        "public": True,
        "files": {
            "nextai_live_url.json": {
                "content": payload_content
            }
        }
    }
    try:
        req = urllib.request.Request(
            "https://api.github.com/gists",
            data=json.dumps(create_data).encode("utf-8"),
            headers=headers,
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            new_id = data.get("id")
            if new_id:
                save_gist_id(new_id)
                print(f"🎉 Created permanent GitHub Gist for Live URL: {new_id}")
                return True
    except Exception as e:
        print(f"⚠️ Failed to create Gist: {e}")
        return False

    return False

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 update_gist_url.py <ws_url> [http_url]")
        sys.exit(1)

    ws = sys.argv[1].strip()
    http = sys.argv[2].strip() if len(sys.argv) > 2 else ""
    if not http and ws.startswith("wss://"):
        http = ws.replace("wss://", "https://").rstrip("/ws")
    elif not http and ws.startswith("ws://"):
        http = ws.replace("ws://", "http://").rstrip("/ws")

    success = update_or_create_gist(ws, http)
    sys.exit(0 if success else 1)
