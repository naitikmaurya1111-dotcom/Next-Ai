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
from agy_runner import run_agy_command, cancel_agy_command

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

# Preserved streaming events per conversation across disconnects
conversation_buffers: Dict[str, List[str]] = {}
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
    """Manages active WebSocket connections."""

    def __init__(self):
        self.active_connections: List[WebSocket] = []
        self._locks: Dict[WebSocket, asyncio.Lock] = {}

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        self._locks[websocket] = asyncio.Lock()
        logger.info(f"Client connected. Total: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        self._locks.pop(websocket, None)
        logger.info(f"Client disconnected. Total: {len(self.active_connections)}")

    async def send(self, message: str, websocket: WebSocket):
        lock = self._locks.get(websocket)
        if lock:
            async with lock:
                try:
                    await websocket.send_text(message)
                except Exception as e:
                    logger.warning(f"Failed to send message: {e}")
        else:
            try:
                await websocket.send_text(message)
            except Exception as e:
                logger.warning(f"Failed to send message: {e}")

    async def broadcast(self, message: str):
        for connection in list(self.active_connections):
            await self.send(message, connection)


manager = ConnectionManager()


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "ok",
        "connected_clients": len(manager.active_connections),
        "service": "AGY Chat Colab Bridge"
    }


@app.get("/models")
async def get_models():
    """Return list of all supported AI models in Antigravity CLI."""
    from agy_runner import AVAILABLE_MODELS
    return {
        "models": AVAILABLE_MODELS,
        "count": len(AVAILABLE_MODELS)
    }


MAX_INLINE_PAYLOAD_SIZE = 2 * 1024 * 1024  # 2 MB threshold for inline base64 / text over WebSocket or JSON

def resolve_colab_file_path(path_str: str) -> str:
    """Safely and quickly resolves file:/// URLs or relative paths to real file system locations without blocking event loop."""
    import urllib.parse
    clean = str(path_str or "").strip()
    if "?path=" in clean:
        clean = clean.split("?path=")[-1].split("&")[0]
    clean = urllib.parse.unquote(clean).replace("file://", "").strip().rstrip(".,:;()[]{}'\"`>").lstrip("([{<'\"`")
    clean = urllib.parse.unquote(clean).strip().rstrip(".,:;()[]{}'\"`>").lstrip("([{<'\"`")
    if not clean:
        return ""
    if os.path.exists(clean) and not os.path.isdir(clean):
        return clean
    
    fname = os.path.basename(clean)

    # Check most likely candidate locations first (fast O(1) checks)
    immediate_candidates = [
        os.path.join("/content/Next-Ai", clean.lstrip("/")),
        os.path.join("/content", clean.lstrip("/")),
        os.path.join("/tmp", fname),
        os.path.join("/tmp/uploads", fname),
        os.path.join("/root/.gemini/antigravity-cli/scratch", fname),
        os.path.join("/content/Next-Ai/colab", fname),
        os.path.join("/content/Next-Ai", fname),
    ]
    for candidate in immediate_candidates:
        if os.path.exists(candidate) and not os.path.isdir(candidate):
            return candidate

    # If not found immediately, search only bounded directories with strict pruning
    search_roots = [
        "/root/.gemini/antigravity-cli/scratch",
        "/tmp",
        "/tmp/uploads",
        "/content/Next-Ai"
    ]
    excluded_dirs = {".git", ".gradle", "build", "node_modules", ".cache", "drive", "__pycache__", "venv", ".venv", ".idea"}
    
    for root_dir in search_roots:
        if os.path.exists(root_dir):
            for root, dirs, files in os.walk(root_dir):
                # Prune excluded dirs and limit search depth to 3
                dirs[:] = [d for d in dirs if d not in excluded_dirs and not d.startswith(".")]
                rel_depth = os.path.relpath(root, root_dir).count(os.sep)
                if rel_depth > 3:
                    dirs.clear()
                if fname in files:
                    candidate = os.path.join(root, fname)
                    if os.path.exists(candidate) and not os.path.isdir(candidate):
                        return candidate
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

    # Send welcome handshake
    await manager.send(
        json.dumps({"type": "connected", "content": "AGY Chat Bridge ready"}),
        websocket
    )

    # Keep-alive ping task to prevent Cloudflare 100s WebSocket timeout
    async def ping_task():
        while True:
            await asyncio.sleep(45)
            try:
                await manager.send(
                    json.dumps({"type": "ping", "content": "", "timestamp": time.time()}),
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

                # Handle client ping heartbeat
                if payload.get("action") == "ping" or payload.get("type") == "ping":
                    await manager.send(json.dumps({
                        "type": "pong",
                        "timestamp": time.time()
                    }), websocket)
                    continue

                # Handle resume_conversation for seamless reconnect across app minimization
                if payload.get("action") == "resume_conversation" or payload.get("type") == "resume_conversation":
                    resume_cid = payload.get("conversation_id", "")
                    after_seq = payload.get("after_seq", -1)
                    logger.info(f"Resume conversation requested for: {resume_cid}, after_seq: {after_seq}")
                    if resume_cid:
                        conversation_websockets[resume_cid] = websocket
                        buffered = conversation_buffers.get(resume_cid, [])
                        logger.info(f"Replaying buffered events for {resume_cid} (total {len(buffered)}, after_seq={after_seq})")
                        for buf_event in buffered:
                            try:
                                ev_obj = json.loads(buf_event)
                                ev_seq = ev_obj.get("seq", -1)
                                if after_seq == -1 or ev_seq > after_seq:
                                    await manager.send(buf_event, websocket)
                            except Exception:
                                await manager.send(buf_event, websocket)
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

            # Cancel previous task for this conversation if starting a new prompt
            if conv_id:
                old_task = active_generation_tasks.get(conv_id)
                if old_task and not old_task.done():
                    cancel_agy_command(conv_id)
                    old_task.cancel()
                conversation_websockets[conv_id] = websocket
                conversation_buffers[conv_id] = []

            current_conv_id = conv_id

            async def stream_worker(msg, cid, eff, mdl, mems, c_inst, pers, a_mem, is_temp, hist, c_meta):
                try:
                    conversation_buffers[cid] = []
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
                        client_metadata=c_meta
                    ):
                        try:
                            ev_obj = json.loads(event)
                            ev_obj["seq"] = seq_counter
                            seq_counter += 1
                            event_with_seq = json.dumps(ev_obj)
                        except Exception:
                            event_with_seq = event

                        conversation_buffers.setdefault(cid, []).append(event_with_seq)
                        target_ws = conversation_websockets.get(cid) or websocket
                        if target_ws in manager.active_connections:
                            await manager.send(event_with_seq, target_ws)
                except asyncio.CancelledError:
                    logger.info(f"Stream worker cancelled for conversation: {cid}")
                except Exception as ex:
                    logger.error(f"Stream worker error: {ex}")
                    err_event = json.dumps({
                        "type": "error",
                        "content": f"Bridge streaming error: {ex}",
                        "timestamp": time.time()
                    })
                    conversation_buffers.setdefault(cid, []).append(err_event)
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
                    client_metadata
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
