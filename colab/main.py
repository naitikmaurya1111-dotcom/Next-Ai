from fastapi import FastAPI, WebSocket, WebSocketDisconnect, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import List
import os
import json
import time
import logging
from aiofiles import open as aio_open
from agy_runner import run_agy_command, cancel_agy_command

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

    async def connect(self, websocket: WebSocket):
        await websocket.accept()
        self.active_connections.append(websocket)
        logger.info(f"Client connected. Total: {len(self.active_connections)}")

    def disconnect(self, websocket: WebSocket):
        if websocket in self.active_connections:
            self.active_connections.remove(websocket)
        logger.info(f"Client disconnected. Total: {len(self.active_connections)}")

    async def send(self, message: str, websocket: WebSocket):
        try:
            await websocket.send_text(message)
        except Exception as e:
            logger.warning(f"Failed to send message: {e}")

    async def broadcast(self, message: str):
        for connection in self.active_connections:
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


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    """Accept file upload from Android app, save to /tmp."""
    try:
        # Sanitize filename
        safe_name = os.path.basename(file.filename or "upload")
        file_path = f"/tmp/agychat_{safe_name}"
        async with aio_open(file_path, "wb") as out_file:
            content = await file.read()
            await out_file.write(content)
        logger.info(f"Uploaded file: {file_path} ({len(content)} bytes)")
        return {
            "filename": safe_name,
            "path": file_path,
            "size": len(content),
            "status": "uploaded"
        }
    except Exception as e:
        logger.error(f"Upload failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


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

    import asyncio as _asyncio
    ping_job = _asyncio.create_task(ping_task())

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
                # Handle stream cancellation request
                if payload.get("type") == "cancel":
                    cancel_id = payload.get("conversation_id", "")
                    logger.info(f"Cancellation requested for conversation: {cancel_id}")
                    cancelled = cancel_agy_command(cancel_id)
                    await manager.send(json.dumps({
                        "type": "done",
                        "content": "[Generation stopped by user]",
                        "timestamp": time.time() if "time" in globals() else 0
                    }), websocket)
                    continue

                user_message = payload.get("message", raw)
                conv_id = payload.get("conversation_id", "")
                effort = payload.get("effort", "high")
                model = payload.get("model", "")
                memories = payload.get("memories", [])
                custom_instructions = payload.get("custom_instructions")
                auto_memory = payload.get("auto_memory", True)
                is_temporary = payload.get("is_temporary", False)
                file_name = payload.get("file_name")
                file_data = payload.get("file_data")
                if file_name and file_data:
                    try:
                        import base64
                        safe_name = os.path.basename(file_name)
                        os.makedirs("/tmp/uploads", exist_ok=True)
                        save_path = f"/tmp/uploads/{safe_name}"
                        with open(save_path, "wb") as f:
                            f.write(base64.b64decode(file_data))
                        user_message = f"[User attached file: {save_path}]\n\n{user_message}"
                        logger.info(f"Saved uploaded file to {save_path}")
                    except Exception as upload_err:
                        logger.error(f"Failed to process file attachment: {upload_err}")
            except json.JSONDecodeError:
                user_message = raw  # Treat as plain text
                custom_instructions = None
                auto_memory = True
                is_temporary = False

            # Stream agy command output back to client with model, effort, memories, and custom instructions
            async for event in run_agy_command(
                user_message,
                conv_id,
                effort,
                model,
                memories,
                custom_instructions=custom_instructions,
                is_auto_memory=auto_memory,
                is_temporary=is_temporary
            ):
                await manager.send(event, websocket)

    except WebSocketDisconnect:
        ping_job.cancel()
        manager.disconnect(websocket)
        logger.info("WebSocket client disconnected normally")
    except Exception as e:
        ping_job.cancel()
        logger.error(f"WebSocket error: {e}")
        manager.disconnect(websocket)
