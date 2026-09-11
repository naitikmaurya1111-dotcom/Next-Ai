import asyncio
import json
import logging
import time

logger = logging.getLogger(__name__)

# Slash commands supported by agy CLI natively
SLASH_COMMANDS = [
    "/goal", "/plan", "/boost", "/schedule",
    "/browser", "/learn", "/grill-me", "/teamwork-preview"
]

def _make_event(event_type: str, content: str = "", **kwargs) -> str:
    """Create a JSON-framed WebSocket event."""
    payload = {
        "type": event_type,
        "content": content,
        "timestamp": time.time(),
        **kwargs
    }
    return json.dumps(payload)


async def run_agy_command(message: str):
    """
    Runs agy command asynchronously and yields JSON-framed output lines.

    Event types:
      - 'chunk'  : a partial response line (streaming)
      - 'done'   : response complete (end of stream)
      - 'error'  : an error occurred
      - 'info'   : informational status message
    """
    message_trimmed = message.strip()
    if not message_trimmed:
        yield _make_event("error", "Empty message received")
        return

    # Build command args
    # agy CLI accepts the message directly; slash commands are passed as-is
    cmd_args = ["agy"]

    is_slash = any(message_trimmed.lower().startswith(sc) for sc in SLASH_COMMANDS)
    cmd_args.append(message_trimmed)

    logger.info(f"Running agy command: {' '.join(cmd_args)}")
    yield _make_event("info", f"Running: {' '.join(cmd_args)}")

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd_args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT,
            limit=1024 * 1024  # 1MB buffer
        )

        buffer = ""
        while True:
            if process.stdout is None:
                break

            try:
                chunk = await asyncio.wait_for(
                    process.stdout.read(256), timeout=120.0
                )
            except asyncio.TimeoutError:
                yield _make_event("error", "Command timed out after 120 seconds")
                process.kill()
                return

            if not chunk:
                break

            buffer += chunk.decode("utf-8", errors="replace")
            # Emit complete lines as chunks
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                if line:
                    yield _make_event("chunk", line)

        # Flush remaining buffer
        if buffer.strip():
            yield _make_event("chunk", buffer.strip())

        await process.wait()

        if process.returncode != 0 and process.returncode is not None:
            yield _make_event(
                "error",
                f"Command exited with code {process.returncode}"
            )
        else:
            yield _make_event("done", "Response complete")

    except FileNotFoundError:
        yield _make_event(
            "error",
            "agy CLI not found. Please make sure Antigravity CLI is installed and in PATH."
        )
    except Exception as e:
        logger.exception(f"Error running agy command: {e}")
        yield _make_event("error", f"Internal error: {str(e)}")
