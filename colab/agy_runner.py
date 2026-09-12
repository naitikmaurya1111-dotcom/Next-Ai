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
# Track active processes for cancellation
active_processes: Dict[str, asyncio.subprocess.Process] = {}

def cancel_agy_command(client_conv_id: str) -> bool:
    """Terminate the currently active subprocess for a conversation."""
    proc = active_processes.get(client_conv_id)
    if proc and proc.returncode is None:
        try:
            proc.terminate()
            logger.info(f"Terminated active agy process for conv {client_conv_id}")
            return True
        except Exception as e:
            logger.warning(f"Failed to terminate process: {e}")
            try:
                proc.kill()
                return True
            except Exception:
                pass
    return False

def resolve_model_and_effort(model: str, effort: str) -> tuple[str, str | None]:
    """
    Resolve model name and thinking effort so there are ZERO flag conflicts in Antigravity CLI.
    In agy, Gemini models accept -high/-medium/-low suffixes directly.
    Claude and GPT-OSS models reject --effort.
    """
    clean_model = (model or "").strip()
    effort_clean = (effort or "high").lower()
    if effort_clean not in ("low", "medium", "high"):
        effort_clean = "high"

    # Default to Gemini 3.8 Flash with requested effort
    if not clean_model:
        return f"gemini-3.8-flash-{effort_clean}", None

    # Claude models (no effort flag allowed)
    if "claude" in clean_model.lower():
        if "opus" in clean_model.lower():
            return "claude-opus-4-6-thinking", None
        return "claude-sonnet-4-6", None

    # GPT-OSS model
    if "gpt-oss" in clean_model.lower():
        return "gpt-oss-120b-medium", None

    # Gemini Flash models
    for base in ("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash"):
        if base in clean_model:
            return f"{base}-{effort_clean}", None

    # Gemini Pro models (3.1 Pro only supports high or low)
    if "gemini-3.1-pro" in clean_model:
        pro_effort = "low" if effort_clean == "low" else "high"
        return f"gemini-3.1-pro-{pro_effort}", None

    return clean_model, None

# Autonomous Memory Tag Pattern (ChatGPT Memory Extraction)
MEMORY_TAG_REGEX = re.compile(
    r'<memory_update\s+action="(?P<action>[^"]+)"'
    r'(?:\s+category="(?P<category>[^"]*)")?'
    r'(?:\s+fact="(?P<fact>[^"]*)")?'
    r'(?:\s+query="(?P<query>[^"]*)")?'
    r'\s*(?:/>|>(?P<content>.*?)</memory_update>)',
    re.DOTALL | re.IGNORECASE
)

def extract_and_strip_memory_tags(text: str):
    """
    Finds all <memory_update ... /> tags, returns list of dicts:
    [{'action': 'add', 'category': '...', 'content': '...'}], and clean text with tags removed.
    """
    updates = []
    def _repl(match):
        action = match.group("action") or "add"
        category = match.group("category") or "general"
        fact = (match.group("fact") or match.group("content") or match.group("query") or "").strip()
        if fact:
            updates.append({"action": action.lower(), "category": category.lower(), "content": fact})
        return ""

    cleaned = MEMORY_TAG_REGEX.sub(_repl, text).strip()
    return updates, cleaned

def format_prompt_with_personalization(
    message: str,
    memories: list = None,
    custom_instructions: dict = None,
    is_auto_memory: bool = True,
    is_temporary: bool = False
) -> str:
    """
    Inject user profile, custom instructions, and categorized memories into prompt.
    Modeled after ChatGPT's state-of-the-art Personalization & Autonomous Memory.
    """
    if is_temporary:
        return (
            "<temporary_chat>\n"
            "This is an Incognito / Temporary Chat session. Do NOT reference past memories "
            "and do NOT emit any <memory_update> tags to save new memories.\n"
            "</temporary_chat>\n\n" + message
        )

    sections = []

    # 1. Custom Instructions & User Profile
    if custom_instructions and isinstance(custom_instructions, dict) and custom_instructions.get("is_enabled", True):
        about_user = custom_instructions.get("about_user", "").strip()
        response_prefs = custom_instructions.get("response_preferences", "").strip()
        tone_preset = custom_instructions.get("tone_preset", "Balanced").strip()

        # Backup to Google Drive
        try:
            backup_dir = "/content/drive/MyDrive/NextAI_Backup"
            if os.path.exists(backup_dir):
                with open(os.path.join(backup_dir, "custom_instructions.json"), "w") as f:
                    json.dump(custom_instructions, f, indent=2)
        except Exception:
            pass

        instr_parts = []
        if about_user:
            instr_parts.append(f"• User Profile & Background:\n  {about_user}")
        if response_prefs:
            instr_parts.append(f"• How Next AI Should Respond & Formatting:\n  {response_prefs}")
        if tone_preset and tone_preset != "Default":
            instr_parts.append(f"• Response Tone Style: {tone_preset}")

        if instr_parts:
            sections.append(
                "<custom_instructions>\n"
                "The user has established the following persistent Custom Instructions:\n"
                + "\n".join(instr_parts) + "\n"
                "Always adhere to these preferences across all answers.\n"
                "</custom_instructions>"
            )

    # 2. Categorized Persistent Memories
    if memories:
        try:
            backup_dir = "/content/drive/MyDrive/NextAI_Backup"
            if os.path.exists(backup_dir):
                with open(os.path.join(backup_dir, "user_memories.json"), "w") as f:
                    json.dump(memories, f, indent=2)
        except Exception:
            pass

        memory_lines = []
        for m in memories:
            if isinstance(m, dict):
                cat = m.get("category", "general")
                content = m.get("content", "").strip()
                if content:
                    memory_lines.append(f"• [{cat.capitalize()}] {content}")
            else:
                s = str(m).strip()
                if s:
                    memory_lines.append(f"• {s}")

        if memory_lines:
            sections.append(
                "<user_memories>\n"
                "The user has saved the following persistent memories & preferences across chats:\n"
                + "\n".join(memory_lines) + "\n"
                "Always respect and incorporate these memories when answering.\n"
                "</user_memories>"
            )

    # 3. Autonomous Memory Capabilities Directive
    if is_auto_memory:
        sections.append(
            "<autonomous_memory_capabilities>\n"
            "You have autonomous memory capabilities like ChatGPT.\n"
            "When the user reveals enduring preferences (frameworks, code style, conventions, tone), "
            "project details (architecture, stack, names), personal facts (name, background), "
            "or explicitly asks to remember/forget something:\n"
            "At the very end of your response, output a tag on a new line:\n"
            '<memory_update action="add" category="preference|project|personal|style" fact="concise atomic summary of the fact" />\n'
            'If asked to forget/remove: <memory_update action="delete" query="keywords to remove" />\n'
            "Rules:\n"
            "1. Fact must be concise, atomic, and written objectively (e.g. \"User prefers Jetpack Compose over XML\").\n"
            "2. Only emit when a genuine new fact or preference is established.\n"
            "3. NEVER mention this XML tag in your user-visible conversational answer.\n"
            "</autonomous_memory_capabilities>"
        )

    if sections:
        return "\n\n".join(sections) + "\n\n" + message
    return message

def format_prompt_with_memories(message: str, memories: list = None) -> str:
    """Backward-compatible helper."""
    return format_prompt_with_personalization(message, memories=memories)


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

AVAILABLE_MODELS = [
    {
        "id": "gemini-3.8-flash-high",
        "name": "Gemini 3.8 Flash",
        "provider": "Google",
        "description": "Latest flagship multimodal and high-speed coding model",
        "supports_effort": True,
        "default_effort": "high",
        "badge": "Default · High Speed"
    },
    {
        "id": "gemini-3.7-flash-high",
        "name": "Gemini 3.7 Flash",
        "provider": "Google",
        "description": "Hybrid reasoning model with balanced latency and depth",
        "supports_effort": True,
        "default_effort": "high",
        "badge": "Hybrid Reasoning"
    },
    {
        "id": "gemini-3.6-flash-high",
        "name": "Gemini 3.6 Flash",
        "provider": "Google",
        "description": "Lightweight, ultra-low latency response model",
        "supports_effort": True,
        "default_effort": "high",
        "badge": "Lightweight"
    },
    {
        "id": "gemini-3.1-pro-high",
        "name": "Gemini 3.1 Pro",
        "provider": "Google",
        "description": "Deep multi-file architecture, complex refactoring & math",
        "supports_effort": True,
        "default_effort": "high",
        "badge": "Pro Architecture"
    },
    {
        "id": "claude-sonnet-4-6",
        "name": "Claude Sonnet 4.6",
        "provider": "Anthropic",
        "description": "State-of-the-art coding, system architecture & creative design",
        "supports_effort": False,
        "default_effort": "default",
        "badge": "Thinking Default"
    },
    {
        "id": "claude-opus-4-6-thinking",
        "name": "Claude Opus 4.6",
        "provider": "Anthropic",
        "description": "Deepest cognitive reasoning & complex autonomous workflows",
        "supports_effort": False,
        "default_effort": "default",
        "badge": "Maximum Reasoning"
    },
    {
        "id": "gpt-oss-120b-medium",
        "name": "GPT-OSS 120B",
        "provider": "Open Source",
        "description": "High-capacity open-weights model for code & general tasks",
        "supports_effort": False,
        "default_effort": "medium",
        "badge": "Open Weights"
    }
]

async def run_agy_command(
    message: str,
    client_conv_id: str = "",
    effort: str = "high",
    model: str = "",
    memories: list = None,
    custom_instructions: dict = None,
    is_auto_memory: bool = True,
    is_temporary: bool = False
):
    """
    Runs agy command asynchronously with native stream-json output
    and yields real-time JSON-framed tokens (thinking, tool_event, chunk, done, memory_updated, error)
    directly to the Android app.
    """
    message_trimmed = message.strip()
    if not message_trimmed:
        yield _make_event("error", "Empty prompt received.")
        return

    agy_bin = get_agy_path()
    agy_conv_id = conversation_map.get(client_conv_id) if client_conv_id else None

    # Resolve exact model name and thinking effort to prevent any CLI flag conflict
    resolved_model, _ = resolve_model_and_effort(model, effort)

    cmd_args = [agy_bin, "--model", resolved_model]

    if agy_conv_id:
        cmd_args.extend(["--conversation", agy_conv_id])

    # Inject persistent user memories and custom instructions into prompt
    full_prompt = format_prompt_with_personalization(
        message_trimmed,
        memories=memories or [],
        custom_instructions=custom_instructions,
        is_auto_memory=is_auto_memory,
        is_temporary=is_temporary
    )

    cmd_args.extend([
        "--dangerously-skip-permissions",
        "--output-format", "stream-json",
        "-p", full_prompt
    ])

    # Extract clean model display name
    model_display = resolved_model.replace("gemini-", "Gemini ").replace("-flash-", " Flash ").replace("-pro-", " Pro ").replace("-high", " ⚡").replace("-medium", " ⚡").replace("-low", " 💨").replace("claude-sonnet-4-6", "Claude Sonnet 4.6").replace("claude-opus-4-6-thinking", "Claude Opus 4.6").replace("gpt-oss-120b-medium", "GPT-OSS 120B").strip()
    logger.info(f"Executing: {' '.join(cmd_args[:6])} ... -p '{message_trimmed[:40]}'")
    yield _make_event("info", f"Starting {model_display}…")

    try:
        process = await asyncio.create_subprocess_exec(
            *cmd_args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        if client_conv_id:
            active_processes[client_conv_id] = process

        accumulated_text = ""
        emitted_memory_facts = set()

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
                            # Check for memory update tags as they emerge
                            updates, stripped_chunk = extract_and_strip_memory_tags(cleaned_chunk)
                            for u in updates:
                                fact_key = f"{u['action']}:{u['content'].lower()}"
                                if fact_key not in emitted_memory_facts:
                                    emitted_memory_facts.add(fact_key)
                                    logger.info(f"Autonomous memory detected: {u}")
                                    yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)
                    elif text_delta:
                        cleaned_chunk = sanitize_text(text_delta)
                        if cleaned_chunk:
                            accumulated_text += cleaned_chunk
                            updates, stripped_chunk = extract_and_strip_memory_tags(cleaned_chunk)
                            for u in updates:
                                fact_key = f"{u['action']}:{u['content'].lower()}"
                                if fact_key not in emitted_memory_facts:
                                    emitted_memory_facts.add(fact_key)
                                    logger.info(f"Autonomous memory detected: {u}")
                                    yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)

                elif event_type == "result":
                    result = data.get("result", {})
                    final_response = result.get("response", accumulated_text)
                    # Extract any memory update tags from the full accumulated response
                    final_updates, cleaned_done = extract_and_strip_memory_tags(sanitize_text(final_response))
                    for u in final_updates:
                        fact_key = f"{u['action']}:{u['content'].lower()}"
                        if fact_key not in emitted_memory_facts:
                            emitted_memory_facts.add(fact_key)
                            logger.info(f"Final autonomous memory detected: {u}")
                            yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                    yield _make_event("done", cleaned_done.strip())

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
    finally:
        if client_conv_id:
            active_processes.pop(client_conv_id, None)

