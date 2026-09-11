import asyncio
import json
import logging
import os
import shutil
import time

logger = logging.getLogger(__name__)

SLASH_COMMANDS = [
    "/goal", "/plan", "/boost", "/schedule",
    "/browser", "/learn", "/grill-me", "/teamwork-preview"
]

def _make_event(event_type: str, content: str = "", **kwargs) -> str:
    """Create a JSON-framed WebSocket event for the Android client."""
    payload = {
        "type": event_type,
        "content": content,
        "timestamp": time.time(),
        **kwargs
    }
    return json.dumps(payload)

def get_agy_path() -> str:
    """Find the path to the agy binary."""
    agy_which = shutil.which("agy")
    if agy_which:
        return agy_which
    candidates = [
        "/root/.local/bin/agy",
        "/usr/local/bin/agy",
        "/usr/bin/agy",
        os.path.expanduser("~/.local/bin/agy")
    ]
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return "agy"

async def run_agy_command(message: str, conversation_id: str = ""):
    """
    Runs agy command asynchronously with native stream-json output
    and yields real-time JSON-framed tokens directly to the Android app.
    """
    message_trimmed = message.strip()
    if not message_trimmed:
        yield _make_event("error", "Empty prompt received.")
        return

    agy_bin = get_agy_path()

    # Build command args
    # -c / --continue keeps conversation continuity
    cmd_args = [
        agy_bin,
        "-c",
        "--dangerously-skip-permissions",
        "--output-format", "stream-json",
        "-p", message_trimmed
    ]

    logger.info(f"Executing: {' '.join(cmd_args[:4])} -p ...")
    yield _make_event("info", "AGY thinking...")

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd_args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )

        accumulated_text = ""

        # Process stream-json lines from agy
        while True:
            if process.stdout is None:
                break

            line_bytes = await process.stdout.readline()
            if not line_bytes:
                break

            raw_line = line_bytes.decode("utf-8", errors="replace").strip()
            if not raw_line:
                continue

            try:
                data = json.loads(raw_line)
                event_type = data.get("event")

                if event_type == "step_update":
                    step_update = data.get("step_update", {})
                    text_delta = step_update.get("text_delta")
                    if text_delta:
                        accumulated_text += text_delta
                        yield _make_event("chunk", text_delta)

                elif event_type == "result":
                    result = data.get("result", {})
                    final_response = result.get("response", accumulated_text)
                    yield _make_event("done", final_response)

            except json.JSONDecodeError:
                # In case plain text or log messages leak into stdout
                yield _make_event("chunk", raw_line)

        await process.wait()

        if process.returncode != 0 and process.returncode is not None:
            stderr_out = ""
            if process.stderr:
                err_bytes = await process.stderr.read()
                stderr_out = err_bytes.decode("utf-8", errors="replace").strip()
            if not accumulated_text:
                yield _make_event("error", f"AGY CLI exited ({process.returncode}): {stderr_out}")
            else:
                yield _make_event("done", accumulated_text)
        else:
            if not accumulated_text:
                yield _make_event("done", "")

    except FileNotFoundError:
        yield _make_event(
            "error",
            "Antigravity CLI (agy) not found in system PATH. Make sure it is installed in Colab."
        )
    except Exception as e:
        logger.exception(f"Error in run_agy_command: {e}")
        yield _make_event("error", f"Bridge error: {str(e)}")
