import asyncio
import json
import logging
import os
import re
import shutil
import time
from datetime import datetime, timezone
from typing import Dict

logger = logging.getLogger(__name__)

CONV_MAP_FILE = "/tmp/agy_conversation_map.json"

def _load_conversation_map() -> Dict[str, str]:
    if os.path.exists(CONV_MAP_FILE):
        try:
            with open(CONV_MAP_FILE, "r") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def _save_conversation_map(m: Dict[str, str]):
    try:
        with open(CONV_MAP_FILE, "w") as f:
            json.dump(m, f)
    except Exception:
        pass

# Map Android client conversation IDs to agy conversation IDs
conversation_map: Dict[str, str] = _load_conversation_map()
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
    r'<memory_update\s+([^>]*?)(?:/>|>([\s\S]*?)</memory_update>)',
    re.DOTALL | re.IGNORECASE
)
ATTR_REGEX = re.compile(r'(\w+)\s*=\s*["\']([^"\']*)["\']', re.IGNORECASE)
PARTIAL_TAG_REGEX = re.compile(r'<memory_update(?:\s+[^>]*)?$', re.DOTALL | re.IGNORECASE)

def extract_and_strip_memory_tags(text: str, is_streaming: bool = False):
    """
    Finds all <memory_update ... /> tags regardless of quote style, multiline formatting,
    or attribute order. Returns (updates: list, cleaned_text: str).
    """
    updates = []
    def _repl(match):
        attrs_str = match.group(1) or ""
        body_content = (match.group(2) or "").strip()
        attrs = dict(ATTR_REGEX.findall(attrs_str))
        action = (attrs.get("action") or "add").strip().lower()
        category = (attrs.get("category") or "general").strip().lower()
        fact = (attrs.get("fact") or attrs.get("query") or body_content or "").strip()
        if fact:
            updates.append({"action": action, "category": category, "content": fact})
        return ""

    cleaned = MEMORY_TAG_REGEX.sub(_repl, text)
    if is_streaming:
        cleaned = PARTIAL_TAG_REGEX.sub("", cleaned)
    return updates, cleaned.strip()


class MemoryStreamingSanitizer:
    """
    Stateful stream buffer ensuring that <memory_update ... /> tags split across
    consecutive streaming chunks are never leaked into the user's visible text stream.
    """
    def __init__(self):
        self.buffer = ""
        self.emitted_facts = set()

    def feed(self, chunk: str) -> tuple[list, str]:
        self.buffer += chunk
        updates, self.buffer = extract_and_strip_memory_tags(self.buffer, is_streaming=False)
        new_updates = []
        for u in updates:
            fact_key = f"{u['action']}:{u['content'].lower()}"
            if fact_key not in self.emitted_facts:
                self.emitted_facts.add(fact_key)
                new_updates.append(u)

        # Hold back potential incomplete tag at the tail of buffer
        tag_idx = self.buffer.rfind("<memory_update")
        if tag_idx != -1 and tag_idx >= len(self.buffer) - 350:
            emit_chunk = self.buffer[:tag_idx]
            self.buffer = self.buffer[tag_idx:]
            return new_updates, emit_chunk

        last_bracket = self.buffer.rfind("<")
        if last_bracket != -1 and last_bracket >= len(self.buffer) - 20 and "<memory_update".startswith(self.buffer[last_bracket:]):
            emit_chunk = self.buffer[:last_bracket]
            self.buffer = self.buffer[last_bracket:]
            return new_updates, emit_chunk

        emit_chunk = self.buffer
        self.buffer = ""
        return new_updates, emit_chunk

    def flush(self) -> tuple[list, str]:
        updates, cleaned = extract_and_strip_memory_tags(self.buffer, is_streaming=False)
        new_updates = []
        for u in updates:
            fact_key = f"{u['action']}:{u['content'].lower()}"
            if fact_key not in self.emitted_facts:
                self.emitted_facts.add(fact_key)
                new_updates.append(u)
        self.buffer = ""
        return new_updates, cleaned

def _build_identity_block(p: dict) -> str:
    """Build the user identity section from Personalization fields."""
    parts = []
    name = p.get("name", "").strip()
    occupation = p.get("occupation", "").strip()
    expertise = p.get("expertise", "").strip()
    country = p.get("country", "").strip()
    age = p.get("age", "").strip()
    context = p.get("custom_context", "").strip()
    if name:
        parts.append(f"  Name: {name}")
    if occupation:
        parts.append(f"  Role: {occupation}")
    if expertise:
        parts.append(f"  Skills & Stack: {expertise}")
    if country:
        parts.append(f"  Country: {country}")
    if age:
        parts.append(f"  Age: {age}")
    if context:
        parts.append(f"  Project & Work Context:\n    {context}")
    return "\n".join(parts)


def _build_response_directives(p: dict) -> list:
    """Turn every Personalization field into a concrete behavioral directive for the model."""
    directives = []
    length = p.get("response_length", "Adaptive")
    if length == "Concise":
        directives.append("Keep responses SHORT and dense. No padding, no re-stating what the user said.")
    elif length == "Detailed":
        directives.append("Give comprehensive answers — cover edge cases and subtleties fully.")
    elif length == "Balanced":
        directives.append("Aim for balanced responses — complete but not bloated.")
    else:
        directives.append("Adapt response length to question complexity. Simple = short. Complex = thorough.")

    tone = p.get("tone_style", "Direct")
    if tone == "Direct":
        directives.append("Be direct and assertive. State conclusions first. No diplomatic padding or hedging.")
    elif tone == "Formal":
        directives.append("Use formal, professional language. Structured and precise.")
    elif tone == "Casual":
        directives.append("Be friendly and conversational — like a smart colleague, not a textbook.")
    elif tone == "Socratic":
        directives.append("Use the Socratic method — ask clarifying questions, challenge assumptions, guide thinking.")
    elif tone == "Empathetic":
        directives.append("Be patient, supportive, and encouraging. Acknowledge difficulty before solving.")

    depth = p.get("depth_level", "Expert")
    if depth == "Beginner":
        directives.append("Assume zero prior knowledge. Use simple language, analogies, step-by-step breakdowns.")
    elif depth == "Intermediate":
        directives.append("Assume baseline domain knowledge. Skip basics, explain intermediate concepts.")
    elif depth == "Expert":
        directives.append("Treat user as a domain expert. Use precise technical language, skip basics, go deep immediately.")
    elif depth == "Research":
        directives.append("Respond at academic/research level — theory, nuances, trade-offs, state-of-the-art.")

    fmt = p.get("response_format", "Auto")
    if fmt == "Always Markdown":
        directives.append("Always format with Markdown: headers, bullets, code blocks where appropriate.")
    elif fmt == "Plain Text":
        directives.append("Use plain text ONLY — no Markdown, no headers, no bullet points.")

    code_lang = p.get("code_language", "").strip()
    if code_lang:
        directives.append(f"Default all code examples to {code_lang} unless explicitly asked for another language.")

    if not p.get("enable_examples", True):
        directives.append("Omit code or concept examples unless the user explicitly requests one.")
    if p.get("enable_proactive", True):
        directives.append("Proactively volunteer relevant insights, warnings, or information the user didn't ask for but needs.")
    else:
        directives.append("Answer exactly what was asked — do not volunteer unrequested information.")

    if p.get("enable_critical", True):
        directives.append("Give honest, critical assessments. Flag mistakes, bad patterns, suboptimal decisions. No sugarcoating.")

    if not p.get("enable_emoji", False):
        directives.append("Do NOT use emoji anywhere in responses.")

    avoid = p.get("avoid_topics", "").strip()
    if avoid:
        directives.append(f"Do NOT discuss or engage with: {avoid}. Politely redirect if user brings these up.")

    extra = p.get("extra_instructions", "").strip()
    if extra:
        directives.append(f"Additional user instructions: {extra}")

    return directives


def format_prompt_with_personalization(
    message: str,
    memories: list = None,
    custom_instructions: dict = None,
    personalization: dict = None,
    is_auto_memory: bool = True,
    is_temporary: bool = False,
    history: list = None,
    model_name: str = "",
    effort_level: str = "high"
) -> str:
    """
    Build the complete AI system context from environment awareness, user Personalization profile,
    memories, settings, and verbatim multi-turn conversation history for unbreakable conversational continuity.
    """
    sections = []

    # ── 0. Environment Awareness & System Context ──────────────────────────────
    now_utc = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    drive_mounted = os.path.exists("/content/drive/MyDrive")
    env_lines = [
        f"• Current Date & Time: {now_utc}",
        "• Host OS & Environment: Google Colab (Linux Ubuntu x86_64, Python 3.12, bash shell)",
        "• Connected Client: Next AI Android Mobile App (Material 3 Dynamic Theme, Jetpack Compose)",
        f"• Storage & Persistence: Google Drive is {'MOUNTED at /content/drive/MyDrive' if drive_mounted else 'NOT MOUNTED'}; local scratch at /content and /tmp",
        "• Web Search Tool: /usr/local/bin/websearch utility is installed and ready for real-time web querying",
        f"• Active Model: {model_name or 'Gemini 3.8 Flash'} (Thinking Effort: {effort_level.upper()})",
        "• Output Formatting: LaTeX mathematical notation ($ for inline, $$ for display blocks), GitHub-flavored Markdown tables, code blocks with language headers"
    ]
    sections.append(
        "<environment_awareness>\n"
        "You are operating as the intelligent assistant for the Next AI Android app running via Google Colab:\n"
        + "\n".join(env_lines) + "\n"
        "Always use accurate current dates, reference real filesystem paths, and deliver concise, polished answers.\n"
        "</environment_awareness>"
    )

    # ── 1. Prior Conversation History (Multi-turn Context Persistence) ─────────
    if history and isinstance(history, list) and len(history) > 0:
        history_lines = []
        for turn in history[-20:]:  # Keep up to 20 turns
            role = turn.get("role", "user") if isinstance(turn, dict) else "user"
            content = turn.get("content", "").strip() if isinstance(turn, dict) else str(turn).strip()
            if not content:
                continue
            if len(content) > 3000:
                content = content[:3000] + "... [truncated]"
            role_label = "User" if role == "user" else "Next AI"
            history_lines.append(f"[{role_label}]:\n{content}")

        if history_lines:
            sections.append(
                "<prior_conversation_history>\n"
                "The following is the verbatim preceding conversation turns of this ongoing chat session.\n"
                "CRITICAL CONTINUITY DIRECTIVE:\n"
                "1. Maintain 100% conversational memory of everything discussed above.\n"
                "2. When the user asks for 'these formulas', 'this list', 'that file', or 'what we just discussed', "
                "refer directly to the content in these previous turns.\n"
                "3. If the user asks you to 'make a file', 'save as file', or 'create a file' of anything discussed, "
                "IMMEDIATELY execute the `write_to_file` tool with an appropriate filename (e.g. /content/...) and complete contents, "
                "and provide a clickable markdown link [filename](file:///path).\n"
                + "\n---\n".join(history_lines) + "\n"
                "</prior_conversation_history>"
            )

    if is_temporary:
        sections.append(
            "<temporary_chat>\n"
            "This is an Incognito/Temporary Chat. Do NOT reference any past memories "
            "and do NOT emit any <memory_update> tags.\n"
            "</temporary_chat>"
        )
        return "\n\n".join(sections) + "\n\n" + message

    # ── 1. Full Personalization Profile (new Personalization model) ───────────
    if personalization and isinstance(personalization, dict):
        try:
            backup_dir = "/content/drive/MyDrive/NextAI_Backup"
            if os.path.exists(backup_dir):
                with open(os.path.join(backup_dir, "personalization.json"), "w") as f:
                    json.dump(personalization, f, indent=2)
        except Exception:
            pass

        identity_block = _build_identity_block(personalization)
        directives = _build_response_directives(personalization)

        persona_text = "<personalization>\n"
        if identity_block:
            persona_text += "USER PROFILE:\n" + identity_block + "\n\n"
        persona_text += (
            "BEHAVIORAL DIRECTIVES — apply these to EVERY response without exception:\n"
            + "\n".join(f"  {i+1}. {d}" for i, d in enumerate(directives))
            + "\n</personalization>"
        )
        sections.append(persona_text)

    # ── 2. Legacy Custom Instructions (backward compatibility) ────────────────
    elif custom_instructions and isinstance(custom_instructions, dict) and custom_instructions.get("is_enabled", True):
        about_user = custom_instructions.get("about_user", "").strip()
        response_prefs = custom_instructions.get("response_preferences", "").strip()
        tone_preset = custom_instructions.get("tone_preset", "Balanced").strip()
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
            instr_parts.append(f"• Response Preferences:\n  {response_prefs}")
        if tone_preset and tone_preset != "Default":
            instr_parts.append(f"• Tone: {tone_preset}")
        if instr_parts:
            sections.append(
                "<custom_instructions>\n"
                "Persistent user instructions — follow always:\n"
                + "\n".join(instr_parts) + "\n"
                "</custom_instructions>"
            )

    # ── 3. Categorized Persistent Memories (importance-sorted) ───────────────
    if memories:
        try:
            backup_dir = "/content/drive/MyDrive/NextAI_Backup"
            if os.path.exists(backup_dir):
                with open(os.path.join(backup_dir, "user_memories.json"), "w") as f:
                    json.dump(memories, f, indent=2)
        except Exception:
            pass

        by_category: dict = {}
        for m in memories:
            if isinstance(m, dict):
                cat = m.get("category", "general").lower()
                content = m.get("content", "").strip()
                imp = int(m.get("importance", 5))
                if content:
                    by_category.setdefault(cat, []).append((imp, content))
            else:
                s = str(m).strip()
                if s:
                    by_category.setdefault("general", []).append((5, s))

        if by_category:
            CATEGORY_ORDER = ["facts", "personal", "goals", "prefs", "preferences",
                               "project", "skills", "feedback", "general"]
            CATEGORY_LABELS = {
                "facts": "FACTS", "personal": "PERSONAL", "goals": "GOALS",
                "prefs": "PREFERENCES", "preferences": "PREFERENCES",
                "project": "PROJECT CONTEXT", "skills": "SKILLS",
                "feedback": "FEEDBACK PATTERNS", "general": "GENERAL"
            }
            mem_lines = []
            for cat in CATEGORY_ORDER:
                entries = by_category.pop(cat, [])
                if entries:
                    entries.sort(key=lambda x: x[0], reverse=True)
                    label = CATEGORY_LABELS.get(cat, cat.upper())
                    mem_lines.append(f"[{label}]")
                    mem_lines.extend(f"  • {c}" for _, c in entries)
            for cat, entries in by_category.items():
                entries.sort(key=lambda x: x[0], reverse=True)
                mem_lines.append(f"[{cat.upper()}]")
                mem_lines.extend(f"  • {c}" for _, c in entries)

            sections.append(
                "<user_memories>\n"
                "What you know and remember about this user (persisted across all conversations):\n"
                + "\n".join(mem_lines) + "\n\n"
                "CRITICAL: Always apply these memories when forming your response. If a memory "
                "conflicts with something the user says NOW, prioritize their current statement "
                "and treat it as an update to the old memory.\n"
                "</user_memories>"
            )

    # ── 4. Autonomous Memory Directive ───────────────────────────────────────
    if is_auto_memory:
        cat_options = "facts|personal|prefs|project|goals|skills|feedback|general"
        sections.append(
            "<autonomous_memory>\n"
            "You have ChatGPT-style persistent memory across all conversations.\n\n"
            "RULES:\n"
            "A) DIRECT QUERIES: If the user asks 'what do you remember about me?', "
            "'what do you know?', or similar — summarize <user_memories> by category, "
            "concisely and specifically. Do not be vague.\n\n"
            "B) AUTO-EXTRACT when the user reveals:\n"
            "   • Personal facts (name, age, role, location)\n"
            "   • Technical preferences (language, framework, tools, conventions)\n"
            "   • Project details (architecture, component names, patterns used)\n"
            "   • Goals or milestones they are working toward\n"
            "   • How they want the AI to behave\n"
            "   • Anything they explicitly say to remember\n"
            "   → Append ONE tag at the VERY END of your response (never mid-response):\n"
            f'   <memory_update action="add" category="{cat_options}" fact="one concise atomic fact" />\n\n'
            "C) CONFLICT RESOLUTION: If a new fact contradicts an existing memory "
            "(e.g. switched from React to Vue, changed preferred language), emit the "
            "update with the new fact so the old one is replaced.\n\n"
            "D) FORGET: If the user says 'forget that', 'remove that memory', etc.:\n"
            '   <memory_update action="delete" query="keywords of the memory to remove" />\n\n'
            "E) PRIVACY: The <memory_update> tag is NEVER visible to the user. "
            "It is stripped by the system before display. Never reference or show it.\n"
            "</autonomous_memory>"
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
    personalization: dict = None,
    is_auto_memory: bool = True,
    is_temporary: bool = False,
    history: list = None
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

    # Extract clean model display name
    model_display = resolved_model.replace("gemini-", "Gemini ").replace("-flash-", " Flash ").replace("-pro-", " Pro ").replace("-high", " ⚡").replace("-medium", " ⚡").replace("-low", " 💨").replace("claude-sonnet-4-6", "Claude Sonnet 4.6").replace("claude-opus-4-6-thinking", "Claude Opus 4.6").replace("gpt-oss-120b-medium", "GPT-OSS 120B").strip()

    # Inject environment awareness, full personalization, memories, custom instructions, and prior history into prompt
    full_prompt = format_prompt_with_personalization(
        message_trimmed,
        memories=memories or [],
        custom_instructions=custom_instructions,
        personalization=personalization,
        is_auto_memory=is_auto_memory,
        is_temporary=is_temporary,
        history=history,
        model_name=model_display,
        effort_level=effort
    )

    cmd_args.extend([
        "--dangerously-skip-permissions",
        "--output-format", "stream-json",
        "-p", full_prompt
    ])

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
        sanitizer = MemoryStreamingSanitizer()

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
                        _save_conversation_map(conversation_map)
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
                            updates, stripped_chunk = sanitizer.feed(cleaned_chunk)
                            for u in updates:
                                logger.info(f"Autonomous memory detected: {u}")
                                yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)
                    elif text_delta:
                        cleaned_chunk = sanitize_text(text_delta)
                        if cleaned_chunk:
                            accumulated_text += cleaned_chunk
                            updates, stripped_chunk = sanitizer.feed(cleaned_chunk)
                            for u in updates:
                                logger.info(f"Autonomous memory detected: {u}")
                                yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)

                elif event_type == "result":
                    result = data.get("result", {})
                    final_response = result.get("response", accumulated_text)
                    final_updates, final_chunk = sanitizer.flush()
                    for u in final_updates:
                        logger.info(f"Final autonomous memory detected: {u}")
                        yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"])
                    if final_chunk:
                        yield _make_event("chunk", final_chunk)

                    _, cleaned_done = extract_and_strip_memory_tags(sanitize_text(final_response))
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

