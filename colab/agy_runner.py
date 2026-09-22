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

PRIMARY_MAP_PATHS = [
    "/content/drive/MyDrive/NextAI_Backup/conversation_map.json",
    "/root/.gemini/antigravity-cli/conversation_map.json",
    "/tmp/agy_conversation_map.json"
]

def _load_conversation_map() -> Dict[str, str]:
    for p in PRIMARY_MAP_PATHS:
        if os.path.exists(p):
            try:
                with open(p, "r") as f:
                    data = json.load(f)
                    if isinstance(data, dict) and data:
                        return data
            except Exception:
                pass
    return {}

def _save_conversation_map(m: Dict[str, str]):
    for p in PRIMARY_MAP_PATHS:
        try:
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as f:
                json.dump(m, f, indent=2)
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


def get_host_environment_summary(cwd: str = "/content") -> dict:
    """Collect real-time host environment telemetry for the client and model awareness."""
    import platform
    import subprocess
    import sys

    target_cwd = cwd if (cwd and os.path.exists(cwd)) else "/content"
    drive_mounted = os.path.exists("/content/drive/MyDrive")

    git_info = {}
    if os.path.exists(os.path.join(target_cwd, ".git")):
        try:
            branch = subprocess.check_output(["git", "-C", target_cwd, "rev-parse", "--abbrev-ref", "HEAD"], text=True, timeout=2).strip()
            sha = subprocess.check_output(["git", "-C", target_cwd, "rev-parse", "--short", "HEAD"], text=True, timeout=2).strip()
            commit_msg = subprocess.check_output(["git", "-C", target_cwd, "log", "-1", "--pretty=%B"], text=True, timeout=2).strip().split('\n')[0]
            git_info = {
                "repo": os.path.basename(target_cwd),
                "branch": branch,
                "commit": sha,
                "commit_msg": commit_msg[:60]
            }
        except Exception:
            pass

    skills = []
    skills_dir = "/root/.gemini/config/skills"
    if os.path.exists(skills_dir):
        try:
            for s in os.listdir(skills_dir):
                if os.path.isdir(os.path.join(skills_dir, s)):
                    skills.append(s)
        except Exception:
            pass

    mem_total_gb = 0
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if "MemTotal:" in line:
                    mem_kb = int(line.split()[1])
                    mem_total_gb = round(mem_kb / (1024 * 1024), 1)
                    break
    except Exception:
        pass

    has_gpu = False
    gpu_name = ""
    try:
        gpu_out = subprocess.check_output(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"], text=True, timeout=2).strip()
        if gpu_out:
            has_gpu = True
            gpu_name = gpu_out.split('\n')[0]
    except Exception:
        pass

    return {
        "host": "Google Colab",
        "os": f"Linux Ubuntu ({platform.machine()})",
        "python": sys.version.split()[0],
        "cpu_count": os.cpu_count() or 2,
        "ram_gb": mem_total_gb,
        "has_gpu": has_gpu,
        "gpu_name": gpu_name,
        "drive_mounted": drive_mounted,
        "drive_backup_path": "/content/drive/MyDrive/NextAI_Backup" if drive_mounted else None,
        "cwd": target_cwd,
        "git": git_info,
        "skills": skills,
        "active_models_count": len(AVAILABLE_MODELS) if 'AVAILABLE_MODELS' in globals() else 7,
        "websearch_available": os.path.exists("/usr/local/bin/websearch") or shutil.which("websearch") is not None
    }


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

# Autonomous Memory Patterns (ChatGPT Standard: XML Tags, JSON Blocks, and Reflex)
MEMORY_TAG_REGEX = re.compile(
    r'<(?:memory_update|memory|memory_save)\s+([^>]*?)(?:/>|>([\s\S]*?)</(?:memory_update|memory|memory_save)>)',
    re.DOTALL | re.IGNORECASE
)
JSON_MEMORY_BLOCK_REGEX = re.compile(
    r'```(?:memory|json:memory)\s*\n?(\{[\s\S]*?\})\s*\n?```',
    re.DOTALL | re.IGNORECASE
)
ATTR_REGEX = re.compile(r'(\w+)\s*=\s*["\']([^"\']*)["\']', re.IGNORECASE)
PARTIAL_TAG_REGEX = re.compile(r'<(?:memory_update|memory|memory_save)(?:\s+[^>]*)?$', re.DOTALL | re.IGNORECASE)
PARTIAL_JSON_BLOCK_REGEX = re.compile(r'```(?:memory|json:memory)(?:\s*[\s\S]*)?$', re.DOTALL | re.IGNORECASE)


def detect_semantic_memory_intent(user_msg: str, assistant_resp: str) -> list[dict]:
    """
    Autonomous Semantic Memory Reflex:
    If the model fails to emit a memory tag, but the conversation established a durable
    personal fact or user instruction, extract it reliably.
    """
    updates = []
    user_clean = (user_msg or "").strip()
    resp_clean = (assistant_resp or "").strip()

    # Case 1: Direct slash commands
    if user_clean.startswith("/remember "):
        fact = user_clean[10:].strip()
        if fact:
            updates.append({"action": "add", "category": "general", "content": fact, "importance": 8})
            return updates
    elif user_clean.startswith("/forget "):
        query = user_clean[8:].strip()
        if query:
            updates.append({"action": "delete", "category": "general", "content": query, "importance": 5})
            return updates

    # Case 2: User explicit "remember that / keep in mind that / remember: / note that"
    remember_patterns = [
        r"(?:please\s+)?remember\s+(?:that\s+)?(.+)",
        r"(?:please\s+)?keep\s+in\s+mind\s+(?:that\s+)?(.+)",
        r"(?:please\s+)?note\s+(?:that\s+)?(?:down[:\s]+)?(.+)",
        r"my\s+name\s+is\s+([A-Za-z0-9\s]+?)(?:\.|\,|$)",
        r"i\s+(?:am\s+living|live)\s+in\s+([A-Za-z0-9\s]+?)(?:\.|\,|$)",
        r"i\s+(?:work\s+as|am)\s+(?:an?\s+)?([A-Za-z0-9\s]+?)(?:\.|\,|$)",
    ]

    for p in remember_patterns:
        match = re.search(p, user_clean, re.IGNORECASE)
        if match:
            extracted = match.group(1).strip().rstrip(".")
            # Filter out non-durable queries like "remember this code?" or "remember what we discussed"
            if extracted and not extracted.lower().startswith(("what ", "how ", "when ", "where ", "who ", "why ", "this code", "this equation", "what we")):
                if 3 <= len(extracted) <= 140:
                    cat = "personal" if any(w in user_clean.lower() for w in ["my name", "i live", "i am", "my role", "my job"]) else "preferences"
                    updates.append({"action": "add", "category": cat, "content": extracted, "importance": 8})
                    break

    # Case 3: User explicit "forget that / forget about / clear memory about"
    forget_patterns = [
        r"(?:please\s+)?forget\s+(?:that\s+|about\s+)?(.+)",
        r"(?:please\s+)?clear\s+(?:my\s+)?memory\s+(?:about\s+)?(.+)",
        r"(?:please\s+)?don[\'\u2019]?t\s+remember\s+(.+)"
    ]
    for p in forget_patterns:
        match = re.search(p, user_clean, re.IGNORECASE)
        if match:
            q = match.group(1).strip().rstrip(".")
            if q and len(q) <= 60:
                updates.append({"action": "delete", "category": "general", "content": q, "importance": 5})
                break

    return updates


def extract_and_strip_memory_tags(text: str, is_streaming: bool = False):
    """
    Finds all memory update tags and JSON blocks regardless of quote style, multiline formatting,
    or attribute order. Returns (updates: list, cleaned_text: str).
    """
    updates = []

    # 1. Parse XML-style tags (<memory_update .../>, <memory .../>)
    def _repl_xml(match):
        attrs_str = match.group(1) or ""
        body_content = (match.group(2) or "").strip()
        attrs = dict(ATTR_REGEX.findall(attrs_str))
        action = (attrs.get("action") or "add").strip().lower()
        category = (attrs.get("category") or "general").strip().lower()
        fact = (attrs.get("fact") or attrs.get("query") or body_content or "").strip()
        importance_str = attrs.get("importance", "7").strip()
        try:
            importance = max(1, min(10, int(importance_str)))
        except ValueError:
            importance = 7
        if fact:
            updates.append({"action": action, "category": category, "content": fact, "importance": importance})
        return ""

    cleaned = MEMORY_TAG_REGEX.sub(_repl_xml, text)

    # 2. Parse Markdown JSON memory blocks (```memory { ... } ```)
    def _repl_json(match):
        raw_json = match.group(1) or "{}"
        try:
            parsed = json.loads(raw_json)
            if isinstance(parsed, dict):
                action = (parsed.get("action") or "add").strip().lower()
                category = (parsed.get("category") or "general").strip().lower()
                fact = (parsed.get("fact") or parsed.get("content") or parsed.get("query") or "").strip()
                imp = parsed.get("importance", 7)
                try:
                    imp_val = max(1, min(10, int(imp)))
                except (ValueError, TypeError):
                    imp_val = 7
                if fact:
                    updates.append({"action": action, "category": category, "content": fact, "importance": imp_val})
        except Exception:
            pass
        return ""

    cleaned = JSON_MEMORY_BLOCK_REGEX.sub(_repl_json, cleaned)

    if is_streaming:
        cleaned = PARTIAL_TAG_REGEX.sub("", cleaned)
        cleaned = PARTIAL_JSON_BLOCK_REGEX.sub("", cleaned)

    return updates, cleaned.strip()


class MemoryStreamingSanitizer:
    """
    Stateful stream buffer ensuring that memory tags or JSON blocks split across
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

        # Hold back potential incomplete tag or block at the tail of buffer
        for pattern in ("<memory_update", "<memory", "```memory", "```json:memory"):
            tag_idx = self.buffer.rfind(pattern)
            if tag_idx != -1 and tag_idx >= len(self.buffer) - 350:
                emit_chunk = self.buffer[:tag_idx]
                self.buffer = self.buffer[tag_idx:]
                return new_updates, emit_chunk

        last_bracket = self.buffer.rfind("<")
        if last_bracket != -1 and last_bracket >= len(self.buffer) - 20 and any(p.startswith(self.buffer[last_bracket:]) for p in ("<memory_update", "<memory")):
            emit_chunk = self.buffer[:last_bracket]
            self.buffer = self.buffer[last_bracket:]
            return new_updates, emit_chunk

        last_tick = self.buffer.rfind("```")
        if last_tick != -1 and last_tick >= len(self.buffer) - 25 and any(p.startswith(self.buffer[last_tick:]) for p in ("```memory", "```json:memory")):
            emit_chunk = self.buffer[:last_tick]
            self.buffer = self.buffer[last_tick:]
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
    if code_lang and code_lang.lower() not in ("auto", "none", "any", "default", ""):
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
    effort_level: str = "high",
    client_metadata: dict = None,
    cwd: str = "/content",
    search_results: list = None
) -> str:
    """
    Build the complete AI system context from environment awareness, user Personalization profile,
    memories, settings, and verbatim multi-turn conversation history for unbreakable conversational continuity.
    """
    sections = []

    # ── 0. Universal AI Assistant Core Identity (ChatGPT Standard) ─────────────
    sections.append(
        "<system_instruction>\n"
        "You are Next AI, a versatile, brilliant, and helpful general-purpose AI assistant like ChatGPT.\n\n"
        "CORE OPERATIONAL DIRECTIVES:\n"
        "1. FRESH CHAT SESSIONS: Treat every conversation session as completely fresh and focused entirely on the user's specific prompt.\n"
        "2. UNIVERSAL DOMAIN ASSISTANCE: Confidently assist across any field — creative writing, general knowledge, science, mathematics, reasoning, literature, philosophy, language learning, and coding across ANY programming language.\n"
        "3. ZERO CODEBASE / PROJECT BIAS: NEVER assume the user is asking about the Next AI app, Android mobile development, or any internal project unless the user explicitly and directly asks about it.\n"
        "4. NO INTERNAL METADATA LEAKAGE: Never mention internal repository details, git branches, commits, or host file paths unprompted.\n"
        "5. TOOL CAPABILITIES: You have real execution capabilities (file writing, shell execution, web search) in Google Colab. Use them ONLY when the user explicitly requests saving a file, executing code, or querying live web data.\n"
        "6. MATHEMATICS & FORMATTING: Format mathematical equations using standard LaTeX ($ for inline, $$ for block display). Format output using clean, structured Markdown.\n"
        "</system_instruction>"
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
            persona_text += (
                "USER PROFILE (Silent Background Context):\n"
                + identity_block + "\n"
                "Note: Treat user profile details as background context. Never artificially recite them or force them into answers unless directly relevant.\n\n"
            )
        persona_text += (
            "BEHAVIORAL DIRECTIVES — apply these to EVERY response without exception:\n"
            + "\n".join(f"  {i+1}. {d}" for i, d in enumerate(directives))
            + "\n</personalization>"
        )
        sections.append(persona_text)

    # ── 2. Custom Instructions (ChatGPT Standard: About User & Response Preferences)
    if custom_instructions and isinstance(custom_instructions, dict) and custom_instructions.get("is_enabled", True):
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
            instr_parts.append(f"• What to know about user:\n  {about_user}")
        if response_prefs:
            instr_parts.append(f"• How user wants AI to respond:\n  {response_prefs}")
        if tone_preset and tone_preset != "Default" and tone_preset != "Balanced":
            instr_parts.append(f"• Preferred Tone: {tone_preset}")
        if instr_parts:
            sections.append(
                "<custom_instructions>\n"
                "Standing user instructions (apply to every response):\n"
                + "\n".join(instr_parts) + "\n"
                "</custom_instructions>"
            )

    # ── 3. Relevant Contextual Memories (ChatGPT Silent Context Standard) ─────
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
                "<contextual_memory>\n"
                "Retrieved relevant memories from previous interactions:\n"
                + "\n".join(mem_lines) + "\n\n"
                "SILENT CONTEXT INSTRUCTIONS (ChatGPT Standard):\n"
                "1. Treat these memories as seamless, implicit background knowledge.\n"
                "2. NEVER say 'Based on my memory', 'As I remember', 'According to past chats', or similar phrases.\n"
                "3. If the user's prompt is a general question (e.g. math, syntax, concepts, general coding), do NOT inject or mention personal details.\n"
                "4. If current conversation conflicts with past memories, trust the user's current statements over past memories.\n"
                "</contextual_memory>"
            )

    # ── 4. Autonomous Memory Directive (Durable facts only) ───────────────────
    if is_auto_memory:
        cat_options = "personal|preferences|project|skills|facts|instructions|general"
        sections.append(
            "<autonomous_memory>\n"
            "You possess ChatGPT-grade persistent memory across conversations.\n"
            "When the user shares durable personal facts, enduring technical preferences, project architectures, or gives an explicit instruction to remember, you MUST record it.\n\n"
            "RULES FOR MEMORY CREATION:\n"
            "1. WHAT TO REMEMBER:\n"
            "   • Permanent identity, role, profession, location ('My name is Alex', 'I live in London', 'I am an iOS developer').\n"
            "   • Enduring technical stack, libraries, conventions ('I use Jetpack Compose and Kotlin coroutines', 'My backend is FastAPI').\n"
            "   • Explicit user instructions ('Remember that I prefer dark mode', 'Never write raw SQL queries').\n"
            "   • Durable personal preferences ('I am vegetarian', 'Keep code explanations brief').\n"
            "   • DO NOT remember one-off homework, temporary math calculations, ephemeral debugging sessions, or fleeting queries.\n\n"
            "2. HOW TO EMIT MEMORY UPDATES:\n"
            "   Emit a memory tag at the VERY END of your response:\n"
            f'   <memory_update action="add" category="{cat_options}" fact="concise atomic enduring fact" importance="1-10" />\n'
            '   Or if deleting/forgetting:\n'
            '   <memory_update action="delete" query="keywords of memory to remove" />\n\n'
            "   FEW-SHOT EXAMPLES:\n"
            "   User: 'I am building an Android assistant called Next AI with Jetpack Compose.'\n"
            "   Assistant: 'Sounds like a great project! ...'\n"
            '   <memory_update action="add" category="project" fact="Building Next AI Android assistant app with Jetpack Compose" importance="8" />\n\n'
            "   User: 'Remember that I live in New Delhi and work as an Android architect.'\n"
            "   Assistant: 'Noted! I have saved your location and role.'\n"
            '   <memory_update action="add" category="personal" fact="User lives in New Delhi and works as an Android architect" importance="9" />\n\n'
            "   User: 'Forget what I told you about Vue.js.'\n"
            "   Assistant: 'Understood, I have removed Vue.js from memory.'\n"
            '   <memory_update action="delete" query="Vue.js" />\n\n'
            "3. DIRECT INQUIRIES:\n"
            "   If the user asks 'What do you remember about me?' or 'What are my memories?', summarize your known memories conversationally by category without reciting internal tags.\n"
            "</autonomous_memory>"
        )

    # ── 4. Grounded Live Web Search Context (if available) ───────────────────
    if search_results and isinstance(search_results, list):
        search_lines = []
        for i, sr in enumerate(search_results, 1):
            t = sr.get("title", "").strip()
            u = sr.get("url", "").strip()
            s = sr.get("snippet", "").strip()
            search_lines.append(f"{i}. [{t}]({u})\n   Snippet: {s}")
        sections.append(
            "<grounded_live_web_search_results>\n"
            "The following are real-time, grounded web search results retrieved from live internet search for this turn:\n"
            + "\n".join(search_lines) + "\n\n"
            "INSTRUCTIONS FOR NEXT AI:\n"
            "- Answer the user's question accurately using the fresh information above.\n"
            "- Include clickable markdown links [Source Title](URL) to cite your sources directly.\n"
            "- If information is not in the search results, state so clearly.\n"
            "</grounded_live_web_search_results>"
        )

    # ── 5. Flash 3.8 High-Thinking SWE Protocol Discipline ────────────────────
    is_gemini_38 = "3.8" in model_name.lower() or "gemini-3.8-flash" in model_name.lower()
    has_coding_intent = any(kw in message.lower() for kw in [
        "code", "function", "class", "debug", "refactor", "bug", "algorithm",
        "implement", "build", "api", "kotlin", "python", "javascript", "test",
        "coding.md", "full stack", "architecture", "ui", "ux", "backend"
    ])
    swe_skill_path = "/root/.gemini/config/skills/flash38-swe-protocol/SKILL.md"
    if (is_gemini_38 or has_coding_intent) and os.path.exists(swe_skill_path):
        sections.append(
            "<flash38_swe_protocol>\n"
            "DISCIPLINE PROTOCOL ACTIVE (`flash38-swe-protocol`):\n"
            "You are running as Gemini 3.8 Flash High-Thinking with abundant token compute.\n"
            "1. Multi-candidate design: compare at least two distinct approaches before settling.\n"
            "2. Exhaustive edge-case thinking: cover boundaries, null/empty states, error paths, and concurrency.\n"
            "3. Strict verification: ensure code is syntactically complete, robust, and verified.\n"
            "4. Zero bluffing: never invent APIs or state unverified facts.\n"
            "</flash38_swe_protocol>"
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
    history: list = None,
    client_metadata: dict = None,
    cwd: str = "/content"
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

    # ── Real-time Web Search Execution Pipeline ──────────────────────────────
    search_results = []
    actual_prompt = message_trimmed
    is_search_intent = False
    search_query = ""

    if message_trimmed.startswith("/browser ") or message_trimmed.startswith("/search "):
        is_search_intent = True
        search_query = message_trimmed.split(" ", 1)[1].strip()
        actual_prompt = search_query if search_query else message_trimmed
    elif client_metadata and client_metadata.get("web_search") is True:
        is_search_intent = True
        search_query = message_trimmed

    if is_search_intent and search_query:
        yield _make_event("info", f"🌐 Searching the web for: \"{search_query}\"…")
        try:
            from websearch import search_ddg_lite, search_google_news
            search_results = await asyncio.to_thread(search_ddg_lite, search_query, 5)
            if not search_results:
                search_results = await asyncio.to_thread(search_google_news, search_query, 5)
        except Exception as s_err:
            logger.warning(f"Live web search failed: {s_err}")

        if search_results:
            yield _make_event("info", f"🌐 Found {len(search_results)} live web sources")

    target_cwd = cwd if (cwd and os.path.exists(cwd)) else "/content"

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
        actual_prompt,
        memories=memories or [],
        custom_instructions=custom_instructions,
        personalization=personalization,
        is_auto_memory=is_auto_memory,
        is_temporary=is_temporary,
        history=history,
        model_name=model_display,
        effort_level=effort,
        client_metadata=client_metadata,
        cwd=target_cwd,
        search_results=search_results
    )

    cmd_args.extend([
        "--dangerously-skip-permissions",
        "--output-format", "stream-json",
        "-p", full_prompt
    ])

    logger.info(f"Executing: {' '.join(cmd_args[:6])} ... [CWD: {target_cwd}] -p '{message_trimmed[:40]}'")
    yield _make_event("info", f"Starting {model_display}…")

    process = None
    try:
        process = await asyncio.create_subprocess_exec(
            *cmd_args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=target_cwd
        )
        if client_conv_id:
            active_processes[client_conv_id] = process

        accumulated_text = ""
        sanitizer = MemoryStreamingSanitizer()
        stream_start_time = time.time()

        while True:
            if process.stdout is None:
                break

            try:
                line_bytes = await asyncio.wait_for(process.stdout.readline(), timeout=300.0)
            except asyncio.TimeoutError:
                logger.warning(f"Process stdout readline timed out after 300s for conv {client_conv_id}")
                yield _make_event("info", "Process execution timed out.")
                break
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
                        raw_out_str = str(output or "")
                        clean_out = sanitize_text(raw_out_str)
                        # Safeguard: truncate massive tool output to prevent memory/buffer overflow
                        if len(clean_out) > 32_000:
                            clean_out = clean_out[:32_000] + f"\n... [Output truncated from {len(raw_out_str)} to 32,000 chars for performance]"

                        tool_duration = float(tool_info.get("duration") or step_update.get("duration") or 0.0)

                        yield _make_event(
                            "tool_event",
                            content=f"Tool {tool_name} ({state})",
                            tool_name=str(tool_name),
                            tool_state=str(state),
                            tool_params=params,
                            tool_output=clean_out,
                            duration=tool_duration
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
                                yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"], importance=u.get("importance", 7))
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)
                    elif text_delta:
                        cleaned_chunk = sanitize_text(text_delta)
                        if cleaned_chunk:
                            accumulated_text += cleaned_chunk
                            updates, stripped_chunk = sanitizer.feed(cleaned_chunk)
                            for u in updates:
                                logger.info(f"Autonomous memory detected: {u}")
                                yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"], importance=u.get("importance", 7))
                            if stripped_chunk:
                                yield _make_event("chunk", stripped_chunk)

                elif event_type == "result":
                    result = data.get("result", {})
                    final_response = result.get("response", accumulated_text)
                    final_updates, final_chunk = sanitizer.flush()
                    for u in final_updates:
                        logger.info(f"Final autonomous memory detected: {u}")
                        yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u["category"], importance=u.get("importance", 7))
                    if final_chunk:
                        yield _make_event("chunk", final_chunk)

                    _, cleaned_done = extract_and_strip_memory_tags(sanitize_text(final_response))

                    # Autonomous Semantic Memory Reflex (Fallback when model emits no tag)
                    if is_auto_memory and not is_temporary:
                        reflex_updates = detect_semantic_memory_intent(message_trimmed, cleaned_done)
                        for u in reflex_updates:
                            fact_key = f"{u['action']}:{u['content'].lower()}"
                            if fact_key not in sanitizer.emitted_facts:
                                sanitizer.emitted_facts.add(fact_key)
                                logger.info(f"Semantic reflex memory detected: {u}")
                                yield _make_event("memory_updated", content=u["content"], action=u["action"], category=u.get("category", "general"), importance=u.get("importance", 8))
                    elapsed_sec = max(0.05, time.time() - stream_start_time)
                    tokens_est = max(1, int(len(accumulated_text or cleaned_done) / 3.8))
                    tokens_per_sec = round(tokens_est / elapsed_sec, 1)

                    yield _make_event(
                        "done",
                        cleaned_done.strip(),
                        tokens_per_second=tokens_per_sec,
                        duration_sec=round(elapsed_sec, 2),
                        token_count=tokens_est
                    )

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
                elapsed_sec = max(0.05, time.time() - stream_start_time)
                tokens_est = max(1, int(len(accumulated_text) / 3.8))
                tokens_per_sec = round(tokens_est / elapsed_sec, 1)
                yield _make_event(
                    "done",
                    sanitize_text(accumulated_text).strip(),
                    tokens_per_second=tokens_per_sec,
                    duration_sec=round(elapsed_sec, 2),
                    token_count=tokens_est
                )
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
        if process and process.returncode is None:
            try:
                process.terminate()
            except Exception:
                pass
            try:
                await asyncio.wait_for(process.wait(), timeout=1.0)
            except Exception:
                try:
                    process.kill()
                except Exception:
                    pass
        if client_conv_id:
            active_processes.pop(client_conv_id, None)

