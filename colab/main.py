from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, HTTPException
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from typing import List, Optional, Dict
import os
import json
import time
import logging
import asyncio
import base64
from aiofiles import open as aio_open
from agy_runner import run_agy_command, cancel_agy_command, get_host_environment_summary

BINARY_EXTENSIONS = {
    ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".ico",
    ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt",
    ".zip", ".tar", ".gz", ".7z", ".rar",
    ".mp3", ".wav", ".ogg", ".m4a", ".mp4", ".mov", ".avi", ".mkv",
    ".bin", ".wasm", ".exe", ".so", ".dylib"
}

def is_binary_file(path: str) -> bool:
    ext = os.path.splitext(path)[1].lower()
    if ext in BINARY_EXTENSIONS:
        return True
    try:
        with open(path, "rb") as f:
            chunk = f.read(1024)
            return b"\x00" in chunk
    except Exception:
        return False

# Server process start time for uptime tracking
SERVER_START_TIME = time.time()

def get_server_uptime() -> dict:
    """Calculate bridge server uptime in seconds and human-readable format."""
    uptime_sec = round(time.time() - SERVER_START_TIME, 1)
    hours = int(uptime_sec // 3600)
    minutes = int((uptime_sec % 3600) // 60)
    seconds = int(uptime_sec % 60)
    uptime_human = f"{hours}h {minutes}m {seconds}s" if hours > 0 else f"{minutes}m {seconds}s"
    return {
        "uptime_seconds": uptime_sec,
        "uptime_human": uptime_human,
        "start_time": SERVER_START_TIME
    }

class ConversationReplayManager:
    """
    Thread-safe, bounded conversation event replay buffer.
    Guarantees seamless stream continuation across app backgrounding,
    device sleep, and Cloudflare tunnel drops with zero lost tokens.
    """
    def __init__(self, max_events_per_conv: int = 2000, max_conversations: int = 100):
        self._buffers: Dict[str, List[str]] = {}
        self._locks: Dict[str, asyncio.Lock] = {}
        self._metadata: Dict[str, dict] = {}
        self.max_events = max_events_per_conv
        self.max_conversations = max_conversations

    def _get_lock(self, conv_id: str) -> asyncio.Lock:
        if conv_id not in self._locks:
            self._locks[conv_id] = asyncio.Lock()
        return self._locks[conv_id]

    async def reset(self, conv_id: str):
        """Start a fresh replay buffer for a new turn."""
        if not conv_id:
            return
        lock = self._get_lock(conv_id)
        async with lock:
            self._buffers[conv_id] = []
            self._metadata[conv_id] = {
                "created_at": time.time(),
                "updated_at": time.time(),
                "last_seq": -1,
                "status": "streaming"
            }
            self._prune_if_needed()

    async def append(self, conv_id: str, event_json: str, seq: int):
        """Append an event to the conversation's replay buffer."""
        if not conv_id:
            return
        lock = self._get_lock(conv_id)
        async with lock:
            buf = self._buffers.setdefault(conv_id, [])
            buf.append(event_json)
            if len(buf) > self.max_events:
                buf.pop(0)
            meta = self._metadata.setdefault(conv_id, {
                "created_at": time.time(),
                "updated_at": time.time(),
                "last_seq": -1,
                "status": "streaming"
            })
            meta["updated_at"] = time.time()
            meta["last_seq"] = max(meta.get("last_seq", -1), seq)

    async def set_status(self, conv_id: str, status: str):
        """Update conversation buffer status (streaming, completed, error, cancelled)."""
        if not conv_id:
            return
        lock = self._get_lock(conv_id)
        async with lock:
            if conv_id in self._metadata:
                self._metadata[conv_id]["status"] = status
                self._metadata[conv_id]["updated_at"] = time.time()

    async def get_events_after(self, conv_id: str, after_seq: int = -1) -> tuple[list[str], int, str]:
        """
        Atomically fetch all events with seq > after_seq.
        Returns: (events_to_replay, latest_seq, status)
        """
        if not conv_id:
            return [], -1, "idle"
        lock = self._get_lock(conv_id)
        async with lock:
            buf = list(self._buffers.get(conv_id, []))
            meta = dict(self._metadata.get(conv_id, {}))
            latest_seq = meta.get("last_seq", -1)
            status = meta.get("status", "idle")

            if after_seq == -1:
                return buf, latest_seq, status

            filtered = []
            for ev in buf:
                try:
                    ev_obj = json.loads(ev)
                    ev_seq = ev_obj.get("seq", -1)
                    if ev_seq > after_seq:
                        filtered.append(ev)
                except Exception:
                    filtered.append(ev)
            return filtered, latest_seq, status

    def get_summary(self) -> dict:
        """Return diagnostic overview of replay buffers."""
        now = time.time()
        summary = {}
        for cid, meta in self._metadata.items():
            summary[cid] = {
                "event_count": len(self._buffers.get(cid, [])),
                "last_seq": meta.get("last_seq", -1),
                "status": meta.get("status", "idle"),
                "age_seconds": round(now - meta.get("updated_at", now), 1)
            }
        return summary

    def _prune_if_needed(self):
        if len(self._buffers) > self.max_conversations:
            sorted_cids = sorted(
                self._metadata.keys(),
                key=lambda k: self._metadata[k].get("updated_at", 0)
            )
            to_remove = sorted_cids[:len(sorted_cids) - self.max_conversations]
            for cid in to_remove:
                self._buffers.pop(cid, None)
                self._locks.pop(cid, None)
                self._metadata.pop(cid, None)

# Global replay buffer and tasks
replay_manager = ConversationReplayManager()
conversation_buffers: Dict[str, List[str]] = replay_manager._buffers
active_generation_tasks: Dict[str, asyncio.Task] = {}
conversation_websockets: Dict[str, WebSocket] = {}

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="AGY Chat Colab Bridge",
    description="WebSocket bridge between Android app and Antigravity CLI",
    version="1.0.0"
)

# Enable CORS for all origins (Android app + web)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class ConnectionManager:
    """Manages active WebSocket connections with detailed metrics, health, and latency tracking."""

    def __init__(self):
        self.active_connections: List[WebSocket] = []
        self._locks: Dict[WebSocket, asyncio.Lock] = {}
        self.connection_meta: Dict[WebSocket, dict] = {}
        self._conn_counter: int = 0

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        self._locks[websocket] = asyncio.Lock()
        self._conn_counter += 1

        client_host = "unknown"
        if websocket.client and websocket.client.host:
            client_host = websocket.client.host

        self.connection_meta[websocket] = {
            "id": f"conn_{self._conn_counter}",
            "client_ip": client_host,
            "connected_at": time.time(),
            "last_ping_at": 0.0,
            "last_pong_at": 0.0,
            "latency_ms": None,
            "messages_sent": 0,
            "messages_received": 0,
            "active_conversation": None
        }
        logger.info(f"Client connected: {self.connection_meta[websocket]['id']} ({client_host}). Total: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        self._locks.pop(websocket, None)
        meta = self.connection_meta.pop(websocket, None)
        cid = meta["id"] if meta else "unknown"
        logger.info(f"Client disconnected: {cid}. Total: {len(self.active_connections)}")

    def record_received(self, websocket: WebSocket):
        if websocket in self.connection_meta:
            self.connection_meta[websocket]["messages_received"] += 1

    def record_latency(self, websocket: WebSocket, latency_ms: float):
        if websocket in self.connection_meta:
            self.connection_meta[websocket]["latency_ms"] = round(latency_ms, 1)
            self.connection_meta[websocket]["last_pong_at"] = time.time()

    def set_active_conversation(self, websocket: WebSocket, conv_id: str):
        if websocket in self.connection_meta:
            self.connection_meta[websocket]["active_conversation"] = conv_id

    async def send(self, message: str, websocket: WebSocket) -> bool:
        lock = self._locks.get(websocket)
        success = False
        if lock:
            async with lock:
                try:
                    await websocket.send_text(message)
                    success = True
                except Exception as e:
                    logger.warning(f"Failed to send message: {e}")
        else:
            try:
                await websocket.send_text(message)
                success = True
            except Exception as e:
                logger.warning(f"Failed to send message: {e}")

        if success and websocket in self.connection_meta:
            self.connection_meta[websocket]["messages_sent"] += 1
        return success

    async def broadcast(self, message: str):
        for connection in list(self.active_connections):
            await self.send(message, connection)

    def get_active_diagnostics(self) -> List[dict]:
        now = time.time()
        diagnostics = []
        for ws, meta in self.connection_meta.items():
            diagnostics.append({
                "connection_id": meta["id"],
                "client_ip": meta["client_ip"],
                "connected_seconds": round(now - meta["connected_at"], 1),
                "latency_ms": meta["latency_ms"],
                "messages_sent": meta["messages_sent"],
                "messages_received": meta["messages_received"],
                "active_conversation": meta["active_conversation"]
            })
        return diagnostics


manager = ConnectionManager()


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "ok",
        "connected_clients": len(manager.active_connections),
        "service": "AGY Chat Colab Bridge",
        "uptime": get_server_uptime()["uptime_human"]
    }


@app.get("/models")
async def get_models():
    """Return list of all supported AI models in Antigravity CLI."""
    from agy_runner import AVAILABLE_MODELS
    return {
        "models": AVAILABLE_MODELS,
        "count": len(AVAILABLE_MODELS)
    }


@app.get("/api/system/status")
async def system_status():
    """Detailed host runtime environment, live memory, CPU load, uptime, and active connection tracking."""
    env = get_host_environment_summary()
    uptime_info = get_server_uptime()
    conn_diagnostics = manager.get_active_diagnostics()
    replay_summary = replay_manager.get_summary()

    return {
        "status": "ok",
        "uptime": {
            "server_uptime_seconds": uptime_info["uptime_seconds"],
            "server_uptime_human": uptime_info["uptime_human"],
            "server_start_time": uptime_info["start_time"],
            "system_uptime_seconds": env.get("system_uptime_seconds"),
            "system_uptime_human": env.get("system_uptime_human"),
        },
        "live_memory": {
            "total_gb": env.get("ram_total_gb") or env.get("ram_gb"),
            "used_gb": env.get("ram_used_gb"),
            "free_gb": env.get("ram_free_gb"),
            "available_gb": env.get("ram_available_gb"),
            "usage_percent": env.get("ram_usage_percent"),
        },
        "cpu_load": {
            "cpu_count": env.get("cpu_count"),
            "load_1m": env.get("cpu_load_1m"),
            "load_5m": env.get("cpu_load_5m"),
            "load_15m": env.get("cpu_load_15m"),
            "usage_percent": env.get("cpu_percent"),
        },
        "disk": {
            "total_gb": env.get("disk_total_gb"),
            "used_gb": env.get("disk_used_gb"),
            "free_gb": env.get("disk_free_gb"),
            "usage_percent": env.get("disk_usage_percent"),
        },
        "connections": {
            "connected_clients": len(manager.active_connections),
            "active_connections": conn_diagnostics,
        },
        "active_streaming": {
            "active_tasks_count": len(active_generation_tasks),
            "active_conversations": list(active_generation_tasks.keys()),
        },
        "replay_buffer": {
            "buffered_conversations_count": len(conversation_buffers),
            "conversations": replay_summary,
        },
        "environment": env,
        "connected_clients": len(manager.active_connections),
        "buffered_conversations": len(conversation_buffers),
        "timestamp": time.time()
    }


@app.get("/api/skills")
async def get_skills():
    """List all installed skills in Antigravity CLI (both custom and builtin)."""
    skills_list = []
    roots = ["/root/.gemini/config/skills", "/root/.gemini/antigravity-cli/builtin/skills"]
    for r in roots:
        if os.path.exists(r):
            for item in os.listdir(r):
                item_path = os.path.join(r, item)
                if os.path.isdir(item_path):
                    skill_md = os.path.join(item_path, "SKILL.md")
                    desc = ""
                    if os.path.exists(skill_md):
                        try:
                            with open(skill_md, "r", encoding="utf-8") as f:
                                for line in f:
                                    if line.startswith("description:"):
                                        desc = line.split(":", 1)[1].strip()
                                        break
                        except Exception:
                            pass
                    skills_list.append({
                        "name": item,
                        "description": desc or f"Antigravity skill: {item}",
                        "path": item_path,
                        "is_builtin": "builtin" in r
                    })
    return {"skills": skills_list, "count": len(skills_list)}


MAX_INLINE_PAYLOAD_SIZE = 8 * 1024 * 1024  # 8 MB threshold for inline base64 / text over WebSocket or JSON

def resolve_colab_file_path(path_str: str) -> str:
    """Safely and thoroughly resolves file:/// URLs, relative paths, or basenames to real file system locations across Drive, Workspace, and Antigravity Brain."""
    import urllib.parse
    import glob
    clean = str(path_str or "").strip()
    if "?path=" in clean:
        clean = clean.split("?path=")[-1].split("&")[0]
    elif "?" in clean:
        clean = clean.split("?")[0]
    if "#" in clean:
        clean = clean.split("#")[0]
    if clean.startswith("[") and "](" in clean and clean.endswith(")"):
        clean = clean.split("](")[-1].rstrip(")")
    clean = urllib.parse.unquote(clean).replace("file://", "").strip().rstrip(".,:;()[]{}'\"`>").lstrip("([{<'\"`")
    clean = urllib.parse.unquote(clean).strip().rstrip(".,:;()[]{}'\"`>").lstrip("([{<'\"`")
    if not clean:
        return ""
    if os.path.exists(clean) and not os.path.isdir(clean):
        return clean
    
    fname = os.path.basename(clean)

    # Check high-priority candidate locations (Drive, Workspace, Brain, Temp)
    immediate_candidates = [
        # Google Drive persistent mirrors
        os.path.join("/content/drive/MyDrive", clean.removeprefix("/content/").lstrip("/")),
        os.path.join("/content/drive/MyDrive", clean.lstrip("/")),
        os.path.join("/content/drive/MyDrive", fname),
        os.path.join("/content/drive/MyDrive/NextAI_Backup", fname),
        os.path.join("/content/drive/MyDrive/NextAI_CLI_Chat_History", fname),
        # Colab workspace
        os.path.join("/content", clean.lstrip("/")),
        os.path.join("/content", fname),
        # Next-Ai repository
        os.path.join("/content/Next-Ai", clean.lstrip("/")),
        os.path.join("/content/Next-Ai", fname),
        os.path.join("/content/Next-Ai/colab", fname),
        # Uploads and temporary files
        os.path.join("/tmp/uploads", fname),
        os.path.join("/tmp", fname),
    ]
    for candidate in immediate_candidates:
        if os.path.exists(candidate) and not os.path.isdir(candidate):
            return candidate

    # Search Antigravity Brain directories (where write_to_file creates artifacts)
    try:
        brain_matches = glob.glob(f"/root/.gemini/antigravity-cli/brain/**/{fname}", recursive=True)
        for bm in brain_matches:
            if os.path.exists(bm) and not os.path.isdir(bm):
                return bm
    except Exception:
        pass

    # If still not found, search Google Drive and Colab with bounded walk
    search_roots = [
        "/content/drive/MyDrive",
        "/content",
        "/tmp"
    ]
    excluded_dirs = {".git", ".gradle", "build", "node_modules", ".cache", "__pycache__", "venv", ".venv", ".idea"}
    
    for root_dir in search_roots:
        if os.path.exists(root_dir):
            for root, dirs, files in os.walk(root_dir):
                dirs[:] = [d for d in dirs if d not in excluded_dirs and not d.startswith(".")]
                rel_depth = os.path.relpath(root, root_dir).count(os.sep)
                if rel_depth > 3:
                    dirs.clear()
                if fname in files:
                    candidate = os.path.join(root, fname)
                    if os.path.exists(candidate) and not os.path.isdir(candidate):
                        return candidate
                # Case-insensitive fallback
                for f in files:
                    if f.lower() == fname.lower():
                        candidate = os.path.join(root, f)
                        if os.path.exists(candidate) and not os.path.isdir(candidate):
                            return candidate

    # 4. Antigravity Brain Transcripts Fallback: recover files generated by write_to_file / replace_file_content
    try:
        for log_path in glob.glob('/root/.gemini/antigravity-cli/brain/**/transcript*.jsonl', recursive=True):
            with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
                for line in f:
                    if 'write_to_file' in line:
                        try:
                            obj = json.loads(line)
                            for call in obj.get('tool_calls', []):
                                if 'write_to_file' in call.get('name', ''):
                                    args = call.get('args', {})
                                    tf = str(args.get('TargetFile', '')).strip('"\'')
                                    if os.path.basename(tf).lower() == fname.lower() or tf.lower() == clean.lower():
                                        c = args.get('CodeContent', '')
                                        if isinstance(c, str):
                                            c_clean = c.strip('"\'') if c.startswith('"') and c.endswith('"') else c
                                            os.makedirs("/tmp", exist_ok=True)
                                            rec_path = os.path.join("/tmp", fname)
                                            with open(rec_path, 'w', encoding='utf-8') as rf:
                                                rf.write(c_clean)
                                            return rec_path
                        except Exception:
                            continue
    except Exception:
        pass

    return clean


@app.get("/api/file")
async def get_file_content(path: str):
    """Fetch file content generated by Antigravity CLI tools with size safeguards."""
    if not path or not path.strip():
        raise HTTPException(status_code=400, detail="Path parameter is required")
    resolved = resolve_colab_file_path(path)
    if not resolved or not os.path.exists(resolved) or os.path.isdir(resolved):
        raise HTTPException(status_code=404, detail=f"File not found: {path}")
    try:
        binary = is_binary_file(resolved)
        f_size = os.path.getsize(resolved)
        f_name = os.path.basename(resolved)

        if f_size > MAX_INLINE_PAYLOAD_SIZE:
            return {
                "status": "too_large",
                "filename": f_name,
                "path": resolved,
                "size": f_size,
                "is_binary": binary,
                "download_url": f"/api/file/download?path={resolved}",
                "message": f"File is large ({f_size // (1024 * 1024)} MB). Use HTTP download endpoint."
            }

        if binary:
            async with aio_open(resolved, "rb") as f:
                b_data = await f.read()
            return {
                "status": "ok",
                "filename": f_name,
                "path": resolved,
                "size": f_size,
                "is_binary": True,
                "base64_content": base64.b64encode(b_data).decode("ascii"),
                "content": ""
            }
        else:
            async with aio_open(resolved, "r", encoding="utf-8", errors="replace") as f:
                content = await f.read()
            return {
                "status": "ok",
                "filename": f_name,
                "path": resolved,
                "size": f_size,
                "is_binary": False,
                "content": content
            }
    except Exception as e:
        logger.error(f"Error reading file {resolved}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/file/download")
async def download_file(path: str):
    """Download file generated by Antigravity CLI tools directly."""
    if not path or not path.strip():
        raise HTTPException(status_code=400, detail="Path parameter is required")
    resolved = resolve_colab_file_path(path)
    if not resolved or not os.path.exists(resolved) or os.path.isdir(resolved):
        raise HTTPException(status_code=404, detail=f"File not found: {path}")
    fname = os.path.basename(resolved)
    ext = os.path.splitext(fname)[1].lower()
    media_type = "application/octet-stream"
    if ext == ".pdf":
        media_type = "application/pdf"
    elif ext in (".png", ".jpg", ".jpeg", ".webp", ".gif"):
        media_type = f"image/{ext.replace('.', '').replace('jpg', 'jpeg')}"
    elif ext in (".txt", ".md", ".py", ".kt", ".java", ".json", ".csv"):
        media_type = "text/plain; charset=utf-8"
    return FileResponse(
        path=resolved,
        filename=fname,
        media_type=media_type
    )


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    """Accept file upload from Android app, save to /tmp/uploads with size limits."""
    try:
        os.makedirs("/tmp/uploads", exist_ok=True)
        safe_name = os.path.basename(file.filename or "upload")
        timestamp = int(time.time() * 1000)
        file_path = f"/tmp/uploads/{timestamp}_{safe_name}"
        max_bytes = 100 * 1024 * 1024  # 100MB safeguard
        total_read = 0
        async with aio_open(file_path, "wb") as out_file:
            while True:
                chunk = await file.read(64 * 1024)
                if not chunk:
                    break
                total_read += len(chunk)
                if total_read > max_bytes:
                    raise HTTPException(status_code=413, detail="File too large (max 100MB)")
                await out_file.write(chunk)
        logger.info(f"Uploaded file via HTTP: {file_path} ({total_read} bytes)")
        return {
            "filename": safe_name,
            "server_path": file_path,
            "path": file_path,
            "size": total_read,
            "status": "uploaded"
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Upload failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/sync/backup")
async def api_sync_backup(payload: dict):
    """Receive full Next AI app state and save directly to Google Drive."""
    try:
        drive_dir = "/content/drive/MyDrive/NextAI_Backup"
        os.makedirs(drive_dir, exist_ok=True)
        backup_path = os.path.join(drive_dir, "nextai_app_cloud_backup.json")
        with open(backup_path, "w") as f:
            json.dump(payload, f, indent=2)

        os.makedirs("/tmp/nextai_backup", exist_ok=True)
        with open("/tmp/nextai_backup/nextai_app_cloud_backup.json", "w") as f:
            json.dump(payload, f, indent=2)

        logger.info(f"Successfully saved cloud backup to {backup_path}")
        return {
            "status": "success",
            "message": "Saved to Google Drive (/MyDrive/NextAI_Backup)",
            "path": backup_path,
            "timestamp": time.time()
        }
    except Exception as e:
        logger.error(f"Cloud backup failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/sync/restore")
async def api_sync_restore():
    """Read full Next AI app state from Google Drive."""
    candidate_paths = [
        "/content/drive/MyDrive/NextAI_Backup/nextai_app_cloud_backup.json",
        "/content/drive/MyDrive/NextAI_Backup/nextai_app_sync.json",
        "/tmp/nextai_backup/nextai_app_cloud_backup.json"
    ]
    for p in candidate_paths:
        if os.path.exists(p):
            try:
                with open(p, "r") as f:
                    data = json.load(f)
                return {"status": "success", "data": data, "source": p}
            except Exception as e:
                logger.error(f"Failed to read backup from {p}: {e}")

    raise HTTPException(status_code=404, detail="No cloud backup found on Google Drive")


@app.get("/api/sync/status")
async def api_sync_status():
    """Return Google Drive mount and backup status."""
    drive_mounted = os.path.exists("/content/drive/MyDrive")
    backup_file = "/content/drive/MyDrive/NextAI_Backup/nextai_app_cloud_backup.json"
    exists = os.path.exists(backup_file)
    size = os.path.getsize(backup_file) if exists else 0
    mtime = os.path.getmtime(backup_file) if exists else 0
    return {
        "drive_mounted": drive_mounted,
        "backup_exists": exists,
        "backup_size_bytes": size,
        "backup_timestamp": mtime
    }


@app.get("/history")
async def get_history():
    """Return list of AGY conversation history files."""
    history_dir = os.path.expanduser("~/.gemini/antigravity-cli/brain")
    history_files = []
    try:
        if os.path.exists(history_dir):
            for root, dirs, files in os.walk(history_dir):
                for f in files:
                    if f.endswith(".jsonl"):
                        full_path = os.path.join(root, f)
                        stat = os.stat(full_path)
                        history_files.append({
                            "path": full_path,
                            "name": f,
                            "size": stat.st_size,
                            "modified": stat.st_mtime
                        })
        # Sort by most recent first
        history_files.sort(key=lambda x: x["modified"], reverse=True)
        return {"history_files": history_files, "count": len(history_files)}
    except Exception as e:
        logger.error(f"Failed to fetch history: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    Main WebSocket endpoint.
    
    Protocol (JSON):
      Client → Server: {"message": "...", "conversation_id": "..."}
      Server → Client: {"type": "chunk"|"done"|"error"|"info", "content": "...", "timestamp": ...}
    """
    await manager.connect(websocket)

    # Send welcome handshake with real-time host environment telemetry
    await manager.send(
        json.dumps({
            "type": "connected",
            "content": "AGY Chat Bridge ready",
            "environment": get_host_environment_summary()
        }),
        websocket
    )

    # Dynamic keep-alive ping task (25s interval to prevent Cloudflare 100s timeout)
    async def ping_task():
        ping_idx = 0
        while True:
            await asyncio.sleep(25)
            ping_idx += 1
            now = time.time()
            if websocket in manager.connection_meta:
                manager.connection_meta[websocket]["last_ping_at"] = now
            try:
                await manager.send(
                    json.dumps({
                        "type": "ping",
                        "ping_id": f"ping_{ping_idx}",
                        "server_timestamp": now,
                        "timestamp": now
                    }),
                    websocket
                )
            except Exception:
                break

    ping_job = asyncio.create_task(ping_task())
    current_generation_task: Optional[asyncio.Task] = None
    current_conv_id: Optional[str] = None

    try:
        while True:
            raw = await websocket.receive_text()
            manager.record_received(websocket)
            logger.info(f"Received: {raw[:200]}")

            # Parse incoming message
            user_message = raw
            conv_id = ""
            effort = "high"
            model = ""
            memories = []
            try:
                payload = json.loads(raw)
                if not isinstance(payload, dict):
                    payload = {"message": raw}
                # Handle stream cancellation request (Stop Generating)
                if payload.get("type") == "cancel":
                    cancel_id = payload.get("conversation_id", "") or current_conv_id or ""
                    logger.info(f"Cancellation requested for conversation: {cancel_id}")
                    if cancel_id:
                        cancel_agy_command(cancel_id)
                    if current_generation_task and not current_generation_task.done():
                        current_generation_task.cancel()
                        current_generation_task = None
                    await manager.send(json.dumps({
                        "type": "done",
                        "content": "",
                        "timestamp": time.time()
                    }), websocket)
                    continue

                # Handle Cloud Sync Backup request
                if payload.get("action") == "cloud_sync_backup":
                    b_data = payload.get("backup_data", {})
                    try:
                        drive_dir = "/content/drive/MyDrive/NextAI_Backup"
                        os.makedirs(drive_dir, exist_ok=True)
                        b_path = os.path.join(drive_dir, "nextai_app_cloud_backup.json")
                        with open(b_path, "w") as f:
                            json.dump(b_data, f, indent=2)

                        os.makedirs("/tmp/nextai_backup", exist_ok=True)
                        with open("/tmp/nextai_backup/nextai_app_cloud_backup.json", "w") as f:
                            json.dump(b_data, f, indent=2)

                        logger.info("Successfully processed cloud sync backup")
                        await manager.send(json.dumps({
                            "type": "cloud_sync_result",
                            "status": "success",
                            "message": "Saved to Google Drive (/MyDrive/NextAI_Backup)",
                            "timestamp": time.time() * 1000
                        }), websocket)
                    except Exception as b_err:
                        logger.error(f"Cloud sync backup failed: {b_err}")
                        await manager.send(json.dumps({
                            "type": "cloud_sync_result",
                            "status": "error",
                            "message": f"Drive backup failed: {b_err}"
                        }), websocket)
                    continue

                # Handle Cloud Sync Restore request
                if payload.get("action") == "cloud_sync_restore":
                    candidate_paths = [
                        "/content/drive/MyDrive/NextAI_Backup/nextai_app_cloud_backup.json",
                        "/content/drive/MyDrive/NextAI_Backup/nextai_app_sync.json",
                        "/tmp/nextai_backup/nextai_app_cloud_backup.json"
                    ]
                    found_data = None
                    for p in candidate_paths:
                        if os.path.exists(p):
                            try:
                                with open(p, "r") as f:
                                    found_data = json.load(f)
                                break
                            except Exception:
                                pass

                    if found_data is not None:
                        logger.info("Sent cloud restore data back to client")
                        await manager.send(json.dumps({
                            "type": "cloud_restore_data",
                            "status": "success",
                            "data": found_data
                        }), websocket)
                    else:
                        await manager.send(json.dumps({
                            "type": "cloud_restore_data",
                            "status": "error",
                            "message": "No backup found in Google Drive (/MyDrive/NextAI_Backup)"
                        }), websocket)
                    continue

                # Handle client pong heartbeat (RTT measurement)
                if payload.get("action") == "pong" or payload.get("type") == "pong":
                    s_ts = payload.get("server_timestamp") or payload.get("timestamp")
                    if s_ts:
                        try:
                            s_float = float(s_ts)
                            rtt_ms = round((time.time() - s_float) * 1000, 2)
                            if 0 < rtt_ms < 60000:
                                manager.record_latency(websocket, rtt_ms)
                        except Exception:
                            pass
                    continue

                # Handle client ping heartbeat
                if payload.get("action") == "ping" or payload.get("type") == "ping":
                    c_ts = payload.get("client_timestamp") or payload.get("timestamp") or time.time()
                    req_cwd = payload.get("cwd", "/content")
                    now_ms = time.time() * 1000
                    latency_ms = None
                    try:
                        c_float = float(c_ts)
                        if c_float > 1e11:  # timestamp in ms
                            latency_ms = max(1.0, round(now_ms - c_float, 1))
                        elif c_float > 1e8:  # timestamp in seconds
                            latency_ms = max(1.0, round((time.time() - c_float) * 1000, 1))
                    except Exception:
                        pass
                    if latency_ms is not None:
                        manager.record_latency(websocket, latency_ms)

                    pong_payload = {
                        "type": "pong",
                        "client_timestamp": c_ts,
                        "server_timestamp": time.time(),
                        "timestamp": time.time(),
                        "environment": get_host_environment_summary(req_cwd)
                    }
                    if latency_ms is not None:
                        pong_payload["latency_ms"] = latency_ms
                    await manager.send(json.dumps(pong_payload), websocket)
                    continue

                # Handle get_environment request from client
                if payload.get("action") == "get_environment" or payload.get("type") == "get_environment":
                    req_cwd = payload.get("cwd", "/content")
                    await manager.send(json.dumps({
                        "type": "environment_info",
                        "environment": get_host_environment_summary(req_cwd),
                        "timestamp": time.time()
                    }), websocket)
                    continue

                # Handle resume_conversation for seamless reconnect across Cloudflare tunnel drops
                if payload.get("action") == "resume_conversation" or payload.get("type") == "resume_conversation":
                    resume_cid = payload.get("conversation_id", "")
                    after_seq = payload.get("after_seq", -1)
                    logger.info(f"Resume conversation requested for: {resume_cid}, after_seq: {after_seq}")
                    if resume_cid:
                        conversation_websockets[resume_cid] = websocket
                        manager.set_active_conversation(websocket, resume_cid)
                        events_to_replay, latest_seq, status = await replay_manager.get_events_after(resume_cid, after_seq)
                        logger.info(f"Replaying {len(events_to_replay)} buffered events for {resume_cid} (after_seq={after_seq}, latest={latest_seq}, status={status})")
                        for buf_event in events_to_replay:
                            await manager.send(buf_event, websocket)
                        # Replay sync acknowledgment
                        await manager.send(json.dumps({
                            "type": "resume_sync",
                            "conversation_id": resume_cid,
                            "replayed_count": len(events_to_replay),
                            "after_seq": after_seq,
                            "latest_seq": latest_seq,
                            "status": status,
                            "timestamp": time.time()
                        }), websocket)
                    continue

                # Handle get_file request for artifacts / workspace files
                if payload.get("action") == "get_file":
                    req_path = payload.get("path", "")
                    resolved = resolve_colab_file_path(req_path)
                    if resolved and os.path.exists(resolved) and not os.path.isdir(resolved):
                        try:
                            f_size = os.path.getsize(resolved)
                            f_name = os.path.basename(resolved)
                            # Large file safeguard: prevent WebSocket frame overflow & Android client OOM
                            if f_size > MAX_INLINE_PAYLOAD_SIZE:
                                await manager.send(json.dumps({
                                    "type": "file_data",
                                    "status": "too_large",
                                    "path": resolved,
                                    "filename": f_name,
                                    "size": f_size,
                                    "download_url": f"/api/file/download?path={resolved}",
                                    "message": f"File is large ({max(1, f_size // (1024 * 1024))} MB). Streaming via HTTP download."
                                }), websocket)
                                continue

                            if is_binary_file(resolved):
                                async with aio_open(resolved, "rb") as f:
                                    b_data = await f.read()
                                await manager.send(json.dumps({
                                    "type": "file_data",
                                    "status": "ok",
                                    "path": resolved,
                                    "filename": f_name,
                                    "size": f_size,
                                    "is_binary": True,
                                    "base64_content": base64.b64encode(b_data).decode("ascii"),
                                    "content": ""
                                }), websocket)
                            else:
                                async with aio_open(resolved, "r", encoding="utf-8", errors="replace") as f:
                                    f_content = await f.read()
                                await manager.send(json.dumps({
                                    "type": "file_data",
                                    "status": "ok",
                                    "path": resolved,
                                    "filename": f_name,
                                    "size": f_size,
                                    "is_binary": False,
                                    "content": f_content
                                }), websocket)
                        except Exception as f_err:
                            await manager.send(json.dumps({
                                "type": "file_data",
                                "status": "error",
                                "message": f"Could not read file: {f_err}"
                            }), websocket)
                    else:
                        await manager.send(json.dumps({
                            "type": "file_data",
                            "status": "error",
                            "message": f"File not found on server: {req_path}"
                        }), websocket)
                    continue

                # Handle message feedback telemetry
                if payload.get("action") == "message_feedback" or payload.get("type") == "message_feedback":
                    msg_id = payload.get("message_id", "")
                    fb_type = payload.get("feedback", "neutral")
                    conv_id = payload.get("conversation_id", "")
                    logger.info(f"Message feedback received: message_id={msg_id}, conv_id={conv_id}, feedback={fb_type}")
                    continue

                user_message = payload.get("message", raw)
                conv_id = payload.get("conversation_id", "")
                effort = payload.get("effort", "high")
                model = payload.get("model", "")
                cwd = payload.get("cwd", "/content")
                memories = payload.get("memories", [])
                custom_instructions = payload.get("custom_instructions")
                personalization = payload.get("personalization")  # Full Personalization profile
                auto_memory = payload.get("auto_memory", True)
                is_temporary = payload.get("is_temporary", False)
                history = payload.get("history", [])  # Multi-turn conversation turns for full context retention
                client_metadata = payload.get("client_metadata")  # Device, OS version, app version, screen, theme

                # Multi-attachment files processing
                attached_files = payload.get("files", [])
                if not attached_files and payload.get("file_name") and payload.get("file_data"):
                    attached_files = [{
                        "name": payload.get("file_name"),
                        "data": payload.get("file_data"),
                        "is_image": payload.get("file_is_image", False)
                    }]

                file_prefixes = []
                if attached_files:
                    os.makedirs("/tmp/uploads", exist_ok=True)
                    for f_idx, f_item in enumerate(attached_files):
                        try:
                            f_name = f_item.get("name", f"file_{f_idx}")
                            f_server_path = f_item.get("server_path") or f_item.get("path")
                            f_data = f_item.get("data")
                            if f_server_path and os.path.exists(f_server_path):
                                file_prefixes.append(f"[User attached file: {f_server_path}]")
                                logger.info(f"Referenced pre-uploaded attachment: {f_server_path}")
                            elif f_data:
                                s_name = f"{f_idx}_{os.path.basename(f_name)}"
                                s_path = f"/tmp/uploads/{s_name}"
                                # Protect against decoding oversized files
                                if len(f_data) > 35 * 1024 * 1024:
                                    logger.warning(f"Attachment {f_name} exceeds 25MB, skipping")
                                    continue
                                decoded_bytes = base64.b64decode(f_data)
                                async with aio_open(s_path, "wb") as f_out:
                                    await f_out.write(decoded_bytes)
                                file_prefixes.append(f"[User attached file: {s_path}]")
                                logger.info(f"Saved uploaded attachment: {s_path}")
                        except Exception as f_err:
                            logger.error(f"Failed to process attachment {f_idx}: {f_err}")

                if file_prefixes:
                    user_message = "\n".join(file_prefixes) + "\n\n" + user_message
            except json.JSONDecodeError:
                user_message = raw  # Treat as plain text
                custom_instructions = None
                personalization = None
                auto_memory = True
                is_temporary = False
                history = []
                client_metadata = None
                cwd = "/content"

            # Cancel previous task for this conversation if starting a new prompt
            if conv_id:
                old_task = active_generation_tasks.get(conv_id)
                if old_task and not old_task.done():
                    cancel_agy_command(conv_id)
                    old_task.cancel()
                conversation_websockets[conv_id] = websocket
                manager.set_active_conversation(websocket, conv_id)
                await replay_manager.reset(conv_id)

            current_conv_id = conv_id

            async def stream_worker(msg, cid, eff, mdl, mems, c_inst, pers, a_mem, is_temp, hist, c_meta, work_dir):
                try:
                    if cid:
                        await replay_manager.reset(cid)
                    seq_counter = 0
                    async for event in run_agy_command(
                        msg,
                        cid,
                        eff,
                        mdl,
                        mems,
                        custom_instructions=c_inst,
                        personalization=pers,
                        is_auto_memory=a_mem,
                        is_temporary=is_temp,
                        history=hist,
                        client_metadata=c_meta,
                        cwd=work_dir
                    ):
                        try:
                            ev_obj = json.loads(event)
                            ev_obj["seq"] = seq_counter
                            if cid:
                                ev_obj["conversation_id"] = cid
                            seq_counter += 1
                            event_with_seq = json.dumps(ev_obj)
                        except Exception:
                            event_with_seq = event

                        if cid:
                            await replay_manager.append(cid, event_with_seq, seq_counter - 1)
                        target_ws = conversation_websockets.get(cid) or websocket
                        if target_ws in manager.active_connections:
                            await manager.send(event_with_seq, target_ws)

                    if cid:
                        await replay_manager.set_status(cid, "completed")
                except asyncio.CancelledError:
                    logger.info(f"Stream worker cancelled for conversation: {cid}")
                    if cid:
                        await replay_manager.set_status(cid, "cancelled")
                except Exception as ex:
                    logger.error(f"Stream worker error: {ex}")
                    err_event = json.dumps({
                        "type": "error",
                        "content": f"Bridge streaming error: {ex}",
                        "conversation_id": cid,
                        "seq": seq_counter,
                        "timestamp": time.time()
                    })
                    if cid:
                        await replay_manager.append(cid, err_event, seq_counter)
                        await replay_manager.set_status(cid, "error")
                    target_ws = conversation_websockets.get(cid) or websocket
                    if target_ws in manager.active_connections:
                        try:
                            await manager.send(err_event, target_ws)
                        except Exception:
                            pass
                finally:
                    active_generation_tasks.pop(cid, None)

            current_generation_task = asyncio.create_task(
                stream_worker(
                    user_message,
                    conv_id,
                    effort,
                    model,
                    memories,
                    custom_instructions,
                    personalization,
                    auto_memory,
                    is_temporary,
                    history,
                    client_metadata,
                    cwd
                )
            )
            if conv_id:
                active_generation_tasks[conv_id] = current_generation_task

    except WebSocketDisconnect:
        logger.info("WebSocket client disconnected normally")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
    finally:
        ping_job.cancel()
        manager.disconnect(websocket)
        # Clear socket reference if this socket disconnected, but KEEP generation running in background!
        for k, v in list(conversation_websockets.items()):
            if v == websocket:
                conversation_websockets.pop(k, None)
