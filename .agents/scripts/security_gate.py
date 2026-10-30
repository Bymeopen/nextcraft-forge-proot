#!/usr/bin/env python3
import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Claude Code exports CLAUDE_PROJECT_DIR to its hooks; a shared copy of this script (not inside the project) needs it to
# know which project it guards. Anything that is not an absolute path is ignored.
_PROJECT_DIR = os.environ.get("CLAUDE_PROJECT_DIR", "")
WORKSPACE_ROOT = os.path.abspath(_PROJECT_DIR) if os.path.isabs(_PROJECT_DIR) else os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
MEMORY_FILES = {
    os.path.join(WORKSPACE_ROOT, "AGENTS.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/USER.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/MEMORY.md"),
}
MAX_MEMORY_BYTES = 24 * 1024  # 24 KB total budget

# 1. Bash Command Hard-Blocked Patterns (Denied immediately before bash runs)
DENY_CMD_PATTERNS = [
    (r"\b(rm|unlink|shred|truncate)\b.*universal-agent-telemetry-protocol\.md", "Blocked by Immutable Protocol Guard: universal-agent-telemetry-protocol.md may only be upgraded, never deleted."),
    (r"unprivileged_bpf_disabled", "Blocked: Modifying unprivileged_bpf_disabled crashes Android system_server."),
    (r"rm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+(/|/root|/workspace|~|\$HOME)(\s|$)", "Blocked: Catastrophic recursive deletion outside project subdir."),
    (r"\b(mkfs|fdisk|wipefs)\b", "Blocked: Disk formatting utilities are forbidden."),
    (r"\bdd\b.*\bof=/dev/(sd|vd|nvme|mmcblk|loop|block|dm-)", "Blocked: Direct dd write to block device is forbidden."),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "Blocked: Fork bomb detected."),
    # Any name: f(){ f|f& };f
    (r"(\w+)\s*\(\)\s*\{[^}]*\1\s*\|\s*\1\s*&[^}]*\}\s*;?\s*\1", "Blocked: Fork bomb detected."),
]

# 2. Bash Command High-Risk Patterns (Require explicit user confirmation)
ASK_CMD_PATTERNS = [
    (r"curl\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"wget\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"(bash|sh|zsh)\s+<\(\s*(curl|wget)", "Remote script process-substitution-to-shell detected. Confirm execution?"),
    (r"\b(nc|ncat|netcat)\b.*-e", "Reverse shell pattern detected. Confirm execution?"),
]

# 3. Memory & Skill Anti-Poisoning Patterns (Secrets & Prompt Injection)
SECRET_AND_INJECTION_PATTERNS = [
    (r"AIza[0-9A-Za-z\-_]{35}", "Blocked by Memory Guard: Google API key detected in memory/skill file."),
    (r"gh[pousr]_[A-Za-z0-9_]{36,255}", "Blocked by Memory Guard: GitHub token detected in memory/skill file."),
    (r"sk-[A-Za-z0-9\-_]{20,}", "Blocked by Memory Guard: API secret key (sk-...) detected in memory/skill file."),
    (r"AKIA[0-9A-Z]{16}", "Blocked by Memory Guard: AWS access key ID detected."),
    (r"(?i)aws_secret_access_key\s*[=:]", "Blocked by Memory Guard: AWS secret access key assignment detected."),
    (r"xox[abp]-[0-9A-Za-z-]{10,}", "Blocked by Memory Guard: Slack token detected."),
    (r"-----BEGIN (RSA|EC|OPENSSH|PGP|DSA) PRIVATE KEY-----", "Blocked by Memory Guard: Private key block detected."),
    (r"-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?KEY-----", "Blocked by Memory Guard: Private cryptographic key detected."),
    (r"(?i)\b(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*\S{8,}", "Blocked by Memory Guard: Credential assignment (password/secret/api_key/token=...) detected."),
    (r"(?i)(ignore\s+(all\s+)?(previous|prior)\s+instructions|disregard\s+system\s+prompt|you\s+are\s+now\s+in\s+developer\s+mode)", "Blocked by Memory Guard: Prompt injection pattern detected in memory/skill file."),
]

# 2b. Protected surface: the permission hook itself, Claude's settings, SSH keys and stored credentials. A command or
# path that touches one is never auto-allowed (decision "ask": the app's approval flow decides), so an agent cannot
# rewrite the hook or the settings that constrain it.
PROTECTED_SURFACE = (
    "permission-hook",
    ".claude/settings",
    "/opt/nextcraft",
    ".ssh/",
    ".git-credentials",
    ".config/gh",
)


def touches_protected_surface(text: str) -> bool:
    lowered = (text or "").lower()
    return any(part in lowered for part in PROTECTED_SURFACE)


def check_command(args: dict):
    cmd = args.get("CommandLine", "") or args.get("command", "")
    for pattern, reason in DENY_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "deny", "reason": reason}
    for pattern, reason in ASK_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "ask", "reason": reason}
    if touches_protected_surface(cmd):
        return {"decision": "ask", "reason": "Touches a protected file (permission hook, Claude settings, SSH keys or credentials). Confirm?"}
    return {"decision": "allow"}

def is_protected_cognitive_file(path: str) -> bool:
    if not path:
        return False
    abs_path = os.path.abspath(path)
    if abs_path in MEMORY_FILES:
        return True
    # AGENTS.md section 1.C: no secrets in ANY .md file, not only memory/skill files.
    return abs_path.lower().endswith(".md")


def _allowed_write_roots() -> list:
    # Directories an agent may write to (AGENTS.md section 5.1): the workspace plus agent config/temp roots.
    home = os.path.expanduser("~")
    roots = [WORKSPACE_ROOT, "/workspace/nextcraft-forge", "/tmp", os.path.join(home, ".gemini"), os.path.join(home, ".claude")]
    for var in ("AGY_CONFIG_ROOT", "ANTIGRAVITY_CONFIG_ROOT", "CLAUDE_CONFIG_DIR", "TMPDIR"):
        value = os.environ.get(var)
        if value:
            roots.append(value)
    return [os.path.abspath(r) for r in roots]


def is_outside_workspace(path: str) -> bool:
    if not path or not os.path.isabs(path):
        return False  # relative paths resolve against the agent's cwd (the project); nothing to decide here
    abs_path = os.path.abspath(path)
    for root in _allowed_write_roots():
        if abs_path == root or abs_path.startswith(root.rstrip("/") + "/"):
            return False
    return True

def check_file_mutation(args: dict, tool_name: str = ""):
    path = args.get("Path", "") or args.get("path", "")
    content = (
        args.get("Content", "")
        or args.get("Replacement", "")
        or args.get("content", "")
        or ""
    )
    if is_outside_workspace(path):
        return {"decision": "deny", "reason": f"Blocked: writes are confined to {WORKSPACE_ROOT} (plus ~/.gemini, ~/.claude, /tmp, agent config root); got {path}"}
    if touches_protected_surface(path):
        return {"decision": "ask", "reason": f"Touches a protected file ({path}). Confirm?"}
    if not is_protected_cognitive_file(path):
        return {"decision": "allow"}

    for pattern, reason in SECRET_AND_INJECTION_PATTERNS:
        if re.search(pattern, content):
            return {"decision": "deny", "reason": reason}

    abs_path = os.path.abspath(path)
    REQUIRED_PROTOCOL_ANCHORS = ("CORE-EXEC-001", "CORE-EXEC-002", "CORE-EXEC-003", "CORE-EXEC-004", "CORE-EXEC-005")
    if os.path.exists(abs_path):
        try:
            existing_text = open(abs_path, "r", errors="replace").read()
        except OSError:
            existing_text = ""
        missing_anchors = [a for a in REQUIRED_PROTOCOL_ANCHORS if a in existing_text and a not in content]
        if missing_anchors and tool_name in ("write_file", "replace_file_content"):
            return {
                "decision": "deny",
                "reason": f"Blocked by Immutable Protocol Guard: {os.path.basename(abs_path)} contains protected execution notes ({', '.join(missing_anchors)}) which may only be upgraded, never deleted."
            }

    if abs_path in MEMORY_FILES:
        total_bytes = len(content.encode("utf-8"))
        for mem_file in MEMORY_FILES:
            if mem_file != abs_path and os.path.exists(mem_file):
                try:
                    total_bytes += os.path.getsize(mem_file)
                except OSError:
                    pass
        if total_bytes > MAX_MEMORY_BYTES:
            return {
                "decision": "deny",
                "reason": f"Blocked by Memory Guard: Combined memory size ({total_bytes} B) exceeds 24 KB limit ({MAX_MEMORY_BYTES} B). Compact memory first."
            }

    return {"decision": "allow"}

CLAUDE_FILE_TOOLS = {"Write": "write_file", "Edit": "edit_file", "MultiEdit": "edit_file", "NotebookEdit": "edit_file"}


def evaluate_claude(payload: dict) -> dict:
    """PreToolUse / PermissionRequest payload from Claude Code: tool_name + tool_input."""
    tool = payload.get("tool_name", "") or ""
    tool_input = payload.get("tool_input") or {}
    if tool == "Bash":
        return check_command({"CommandLine": tool_input.get("command", "")})
    if tool in CLAUDE_FILE_TOOLS:
        path = tool_input.get("file_path", "") or tool_input.get("notebook_path", "")
        if tool == "Write":
            content = tool_input.get("content", "")
        elif tool == "MultiEdit":
            content = "\n".join(str(e.get("new_string", "")) for e in tool_input.get("edits") or [] if isinstance(e, dict))
        elif tool == "NotebookEdit":
            content = tool_input.get("new_source", "")
        else:
            content = tool_input.get("new_string", "")
        return check_file_mutation({"Path": path, "Replacement": content}, CLAUDE_FILE_TOOLS[tool])
    return {"decision": "allow", "no_opinion": True}  # Read, Grep, web tools, ...: nothing for this gate to decide


def evaluate_antigravity(payload: dict) -> dict:
    tool_call = payload.get("toolCall", {})
    tool_name = tool_call.get("name", "") or payload.get("toolName", "")
    args = tool_call.get("args", {})
    if tool_name in ("write_file", "edit_file", "replace_file_content", "multi_replace_file_content") or any(k in args for k in ("Path", "path", "TargetFile")):
        return check_file_mutation(args, tool_name)
    return check_command(args)


def claude_output(payload: dict, result: dict) -> dict:
    if result.get("no_opinion"):
        return {}  # an explicit "allow" would skip Claude's own permission prompt for a tool this gate never judged
    decision = result.get("decision", "allow")
    if payload.get("hook_event_name") == "PermissionRequest":
        if decision in ("allow", "deny"):
            return {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": decision}}}
        return {}  # "ask": no verdict here, the next handler (the app's approval flow) decides
    output = {"hookEventName": "PreToolUse", "permissionDecision": decision}
    if result.get("reason"):
        output["permissionDecisionReason"] = result["reason"]
    return {"hookSpecificOutput": output}


def main():
    # `--decision` prints only allow|deny|ask for the permission hook and never fails open: unreadable input is "ask".
    decision_only = "--decision" in sys.argv[1:]
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError("payload is not an object")
    except Exception:
        print("ask" if decision_only else json.dumps({"decision": "allow"}))
        return

    is_claude = "tool_name" in payload or "tool_input" in payload
    try:
        result = evaluate_claude(payload) if is_claude else evaluate_antigravity(payload)
    except Exception as error:  # a bug in the gate must not silently allow in --decision mode
        if decision_only:
            print("ask")
            return
        result = {"decision": "allow", "reason": f"gate error: {error}"}

    if decision_only:
        print("ask" if result.get("no_opinion") else result.get("decision", "ask"))
    elif is_claude:
        print(json.dumps(claude_output(payload, result)))
    else:
        print(json.dumps(result))

if __name__ == "__main__":
    main()
