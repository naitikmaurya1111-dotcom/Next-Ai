import asyncio
import json
import logging
import os
import re
import shutil
import time
from typing import Dict

logger = logging.getLogger(__name__)

# Map Android client conversation IDs to agy conversation IDs
conversation_map: Dict[str, str] = {}

# Regular expressions to strip ANSI escape codes, terminal probes, and control characters
ANSI_REGEX = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
TERMINAL_PROBE_REGEX = re.compile(r'//#\][^\n\r]*')
CONTROL_CHAR_REGEX = re.compile(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]')

def sanitize_text(text: str) -> str:
    """Strip all ANSI sequences, terminal query probes, and unprintable control chars."""
    if not text:
        return ""
    s = ANSI_REGEX.sub('', text)
    s = TERMINAL_PROBE_REGEX.sub('', s)
    s = CONTROL_CHAR_REGEX.sub('', s)
    return s

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

async def run_agy_command(message: str, client_conv_id: str = "", effort: str = "high"):
    """
    Runs agy command asynchronously with native stream-json output
    and yields real-time JSON-framed tokens (thinking, tool_event, chunk, done, error)
    directly to the Android app.
    """
    message_trimmed = message.strip()
    if not message_trimmed:
        yield _make_event("error", "Empty prompt received.")
        return

    agy_bin = get_agy_path()
    agy_conv_id = conversation_map.get(client_conv_id) if client_conv_id else None

    cmd_args = [agy_bin]

    if agy_conv_id:
        cmd_args.extend(["--conversation", agy_conv_id])

    if effort in ("low", "medium", "high"):
        cmd_args.extend(["--effort", effort])

    cmd_args.extend([
        "--dangerously-skip-permissions",
        "--output-format", "stream-json",
        "-p", message_trimmed
    ])

    logger.info(f"Executing: {' '.join(cmd_args[:4])} ... -p '{message_trimmed[:40]}'")
    yield _make_event("info", "AGY thinking...")

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd_args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )

        accumulated_text = ""

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

                if event_type == "init":
                    new_conv_id = data.get("conversation_id")
                    if new_conv_id and client_conv_id:
                        conversation_map[client_conv_id] = new_conv_id
                        logger.info(f"Mapped {client_conv_id} -> {new_conv_id}")

                elif event_type == "step_update":
                    step_update = data.get("step_update", {})
                    step_type = step_update.get("step_type", "")
                    state = step_update.get("state", "DONE")
                    tool_name = step_update.get("tool_name") or step_update.get("tool_info", {}).get("name")
                    text_delta = step_update.get("text_delta")

                    if step_type in ("tool", "tool_call") or tool_name:
                        tool_info = step_update.get("tool_info", {})
                        if not tool_name:
                            tool_name = tool_info.get("name", "tool")
                        params = tool_info.get("parameters", {})
                        output = tool_info.get("output", "")
                        duration = step_update.get("duration_seconds", 0.0)

                        yield _make_event(
                            "tool_event",
                            content=f"Tool {tool_name} ({state})",
                            tool_name=str(tool_name),
                            tool_state=str(state),
                            tool_params=params,
                            tool_output=sanitize_text(str(output)),
                            duration=float(duration or 0.0)
                        )
                    elif step_type in ("thought", "reasoning", "thinking") and text_delta:
                        cleaned_thought = sanitize_text(text_delta)
                        if cleaned_thought:
                            yield _make_event("thinking", cleaned_thought)
                    elif step_type == "agent_response" and text_delta:
                        cleaned_chunk = sanitize_text(text_delta)
                        if cleaned_chunk:
                            accumulated_text += cleaned_chunk
                            yield _make_event("chunk", cleaned_chunk)
                    elif text_delta:
                        cleaned_chunk = sanitize_text(text_delta)
                        if cleaned_chunk:
                            accumulated_text += cleaned_chunk
                            yield _make_event("chunk", cleaned_chunk)

                elif event_type == "result":
                    result = data.get("result", {})
                    final_response = result.get("response", accumulated_text)
                    cleaned_done = sanitize_text(final_response).strip()
                    yield _make_event("done", cleaned_done)

            except json.JSONDecodeError:
                # Filter out raw terminal escapes, telemetry, or unparsed logs from corrupting the response
                logger.debug(f"Ignoring non-JSON CLI stream line: {raw_line[:100]}")

        await process.wait()

        if process.returncode != 0 and process.returncode is not None:
            stderr_out = ""
            if process.stderr:
                err_bytes = await process.stderr.read()
                stderr_out = err_bytes.decode("utf-8", errors="replace").strip()
            stderr_cleaned = sanitize_text(stderr_out)
            if not accumulated_text:
                yield _make_event("error", f"AGY CLI error ({process.returncode}): {stderr_cleaned}")
            else:
                yield _make_event("done", sanitize_text(accumulated_text).strip())
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

