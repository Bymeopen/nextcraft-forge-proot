#!/usr/bin/env python3
import json
import os
import re
import shlex
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
MEMORY_FILES = {
    os.path.join(WORKSPACE_ROOT, "AGENTS.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/USER.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/MEMORY.md"),
}
MAX_MEMORY_BYTES = 24 * 1024  # 24 KB total budget

# Tool names of both harnesses: Gemini/Antigravity (run_command, write_file, ...) and Claude Code (Bash, Write, ...).
COMMAND_TOOLS = {"run_command", "Bash"}
FILE_TOOLS = {
    "write_file", "edit_file", "replace_file_content", "multi_replace_file_content",
    "Write", "Edit", "MultiEdit", "NotebookEdit",
}
PATH_KEYS = ("Path", "path", "TargetFile", "file_path", "filepath", "notebook_path")
FULL_CONTENT_KEYS = ("Content", "content", "CodeContent")
NEW_TEXT_KEYS = ("Replacement", "ReplacementContent", "new_string", "new_source")
OLD_TEXT_KEYS = ("TargetContent", "old_string")

# 1. Bash Command Hard-Blocked Patterns (Denied immediately before bash runs)
DENY_CMD_PATTERNS = [
    (r"unprivileged_bpf_disabled", "Blocked: Modifying unprivileged_bpf_disabled crashes Android system_server."),
    (r"\b(mkfs|fdisk|wipefs)\b", "Blocked: Disk formatting utilities are forbidden."),
    (r"\bdd\b.*\bof=/dev/(sd|vd|nvme|mmcblk|loop|block|dm-)", "Blocked: Direct dd write to block device is forbidden."),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "Blocked: Fork bomb detected."),
    # Any name: f(){ f|f& };f
    (r"(\w+)\s*\(\)\s*\{[^}]*\1\s*\|\s*\1\s*&[^}]*\}\s*;?\s*\1", "Blocked: Fork bomb detected."),
]

# 2. Bash Command High-Risk Patterns (Require explicit user confirmation)
_SHELLS = r"(\S*/)?(ba|z|da|k|a)?sh|(\S*/)?(python[0-9.]*|perl|ruby|node)"
_SUDO = r"(sudo\s+(-\S+\s+)*)?(env\s+(\S+=\S+\s+)*)?"
ASK_CMD_PATTERNS = [
    (r"\b(curl|wget)\b[^\n]*\|\s*" + _SUDO + r"(" + _SHELLS + r")\b", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"(\bbash|\bsh|\bzsh)\s+<\(\s*(curl|wget)", "Remote script process-substitution-to-shell detected. Confirm execution?"),
    (r"\b(nc|ncat|netcat)\b.*-e", "Reverse shell pattern detected. Confirm execution?"),
]

# Directories whose recursive deletion is never legitimate for an agent.
PROTECTED_DIRS = {
    "/", "/root", "/workspace", "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/var", "/boot", "/dev",
    "/proc", "/sys", "/opt", "/home", "/data", "/system", "/vendor", "/storage", "/sdcard",
}
COMMAND_PREFIXES = {"sudo", "doas", "env", "command", "nohup", "time", "nice", "exec"}

# 3. Memory & Skill Anti-Poisoning Patterns (Secrets & Prompt Injection)
def _looks_like_secret(value: str) -> bool:
    # Prose such as "token: refresh_endpoint_name" is not a credential: real ones mix letters and digits.
    value = value.strip("'\"")
    return len(value) >= 8 and any(c.isdigit() for c in value) and any(c.isalpha() for c in value)


SECRET_AND_INJECTION_PATTERNS = [
    (r"AIza[0-9A-Za-z\-_]{35}", "Blocked by Memory Guard: Google API key detected in memory/skill file.", None),
    (r"gh[pousr]_[A-Za-z0-9_]{36,255}", "Blocked by Memory Guard: GitHub token detected in memory/skill file.", None),
    (r"sk-[A-Za-z0-9\-_]{20,}", "Blocked by Memory Guard: API secret key (sk-...) detected in memory/skill file.", None),
    (r"AKIA[0-9A-Z]{16}", "Blocked by Memory Guard: AWS access key ID detected.", None),
    (r"(?i)aws_secret_access_key\s*[=:]", "Blocked by Memory Guard: AWS secret access key assignment detected.", None),
    (r"xox[abp]-[0-9A-Za-z-]{10,}", "Blocked by Memory Guard: Slack token detected.", None),
    (r"-----BEGIN (RSA|EC|OPENSSH|PGP|DSA) PRIVATE KEY-----", "Blocked by Memory Guard: Private key block detected.", None),
    (r"-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?KEY-----", "Blocked by Memory Guard: Private cryptographic key detected.", None),
    (r"(?i)\b(?:password|passwd|secret|api[_-]?key|token)\s*[=:]\s*(\S{8,})", "Blocked by Memory Guard: Credential assignment (password/secret/api_key/token=...) detected.", _looks_like_secret),
    (r"(?i)(ignore\s+(all\s+)?(previous|prior)\s+instructions|disregard\s+system\s+prompt|you\s+are\s+now\s+in\s+developer\s+mode)", "Blocked by Memory Guard: Prompt injection pattern detected in memory/skill file.", None),
]


def _expand(token: str, cwd: str) -> str:
    token = os.path.expandvars(os.path.expanduser(token))
    if not os.path.isabs(token):
        token = os.path.join(cwd, token)
    return os.path.normpath(token)


def _is_protected_target(token: str, cwd: str) -> bool:
    if token.rstrip("/").endswith(("/*", "/.", "/..")) or token in ("*", ".", ".."):
        # Glob or dot targets expand to the whole directory: judge the directory itself.
        token = os.path.dirname(token.rstrip("/")) or "."
        if token == ".":
            token = cwd
    path = _expand(token, cwd)
    home = os.path.expanduser("~")
    return path in PROTECTED_DIRS or path == os.path.normpath(home) or path == WORKSPACE_ROOT


def _rm_is_catastrophic(argv: list, cwd: str) -> bool:
    while argv and (argv[0] in COMMAND_PREFIXES or re.fullmatch(r"\w+=\S*", argv[0])):
        argv = argv[1:]
    if not argv or os.path.basename(argv[0]) != "rm":
        return False
    recursive = False
    targets = []
    options_done = False
    for arg in argv[1:]:
        if not options_done and arg == "--":
            options_done = True
        elif not options_done and arg.startswith("--"):
            recursive = recursive or arg == "--recursive"
        elif not options_done and arg.startswith("-") and len(arg) > 1:
            recursive = recursive or "r" in arg or "R" in arg
        else:
            targets.append(arg)
    return recursive and any(_is_protected_target(t, cwd) for t in targets)


def _command_segments(cmd: str):
    # Split on shell separators so `cd x && rm -rf /` is judged per command.
    for segment in re.split(r"&&|\|\||[;|\n&]", cmd):
        segment = segment.strip()
        if not segment:
            continue
        try:
            yield shlex.split(segment)
        except ValueError:
            yield segment.split()


def check_command(args: dict, cwd: str):
    cmd = args.get("CommandLine", "") or args.get("command", "")
    for argv in _command_segments(cmd):
        if _rm_is_catastrophic(argv, cwd):
            return {"decision": "deny", "reason": "Blocked: Catastrophic recursive deletion outside project subdir."}
    for pattern, reason in DENY_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "deny", "reason": reason}
    for pattern, reason in ASK_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "ask", "reason": reason}
    return {"decision": "allow"}


def _resolve(path: str, cwd: str) -> str:
    # realpath follows symlinks, so a link inside the workspace that points outside is judged by its target.
    if not os.path.isabs(path):
        path = os.path.join(cwd, path)
    return os.path.realpath(path)


def is_protected_cognitive_file(path: str, cwd: str) -> bool:
    if not path:
        return False
    abs_path = _resolve(path, cwd)
    if abs_path in {os.path.realpath(m) for m in MEMORY_FILES}:
        return True
    # AGENTS.md section 1.C: no secrets in ANY .md file, not only memory/skill files.
    return abs_path.lower().endswith(".md")


def _allowed_write_roots(cwd: str) -> list:
    # Directories an agent may write to (AGENTS.md section 5.1): the workspace, the project being worked on (cwd)
    # plus agent config/temp roots.
    home = os.path.expanduser("~")
    roots = [WORKSPACE_ROOT, cwd, "/workspace/nextcraft-forge", "/tmp", os.path.join(home, ".gemini"), os.path.join(home, ".claude")]
    for var in ("AGY_CONFIG_ROOT", "ANTIGRAVITY_CONFIG_ROOT", "CLAUDE_CONFIG_DIR", "TMPDIR"):
        value = os.environ.get(var)
        if value:
            roots.append(value)
    return [os.path.realpath(r) for r in roots]


def is_outside_workspace(path: str, cwd: str) -> bool:
    if not path:
        return False
    resolved = _resolve(path, cwd)
    for root in _allowed_write_roots(cwd):
        if resolved == root or resolved.startswith(root.rstrip("/") + "/"):
            return False
    return True


def _first(args: dict, keys) -> str:
    for key in keys:
        value = args.get(key)
        if isinstance(value, str) and value:
            return value
    return ""


def _new_text(args: dict) -> str:
    text = _first(args, FULL_CONTENT_KEYS) or _first(args, NEW_TEXT_KEYS)
    edits = args.get("edits")
    if isinstance(edits, list):  # Claude MultiEdit
        text += "\n".join(e.get("new_string", "") for e in edits if isinstance(e, dict))
    chunks = args.get("ReplacementChunks")
    if isinstance(chunks, list):  # Antigravity multi_replace_file_content
        text += "\n".join(c.get("ReplacementContent", "") for c in chunks if isinstance(c, dict))
    return text


def _projected_size(abs_path: str, args: dict) -> int:
    # Size the file will have after the call, not the size of the written fragment.
    full = _first(args, FULL_CONTENT_KEYS)
    if full or not os.path.exists(abs_path):
        return len(_new_text(args).encode("utf-8"))
    try:
        with open(abs_path, "r", encoding="utf-8", errors="replace") as fh:
            current = fh.read()
    except OSError:
        return len(_new_text(args).encode("utf-8"))
    old = _first(args, OLD_TEXT_KEYS)
    new = _first(args, NEW_TEXT_KEYS)
    if old and old in current:
        current = current.replace(old, new, 1)
        return len(current.encode("utf-8"))
    return len(current.encode("utf-8")) + len(_new_text(args).encode("utf-8"))


def check_file_mutation(args: dict, cwd: str):
    path = _first(args, PATH_KEYS)
    content = _new_text(args)
    if not path:
        return {"decision": "deny", "reason": "Blocked: file-mutation call without a recognisable target path."}
    if is_outside_workspace(path, cwd):
        return {"decision": "deny", "reason": f"Blocked: writes are confined to {WORKSPACE_ROOT} (plus the project directory, ~/.gemini, ~/.claude, /tmp, agent config root); got {path}"}
    if not is_protected_cognitive_file(path, cwd):
        return {"decision": "allow"}

    for pattern, reason, validator in SECRET_AND_INJECTION_PATTERNS:
        for match in re.finditer(pattern, content):
            if validator is None or validator(match.group(1)):
                return {"decision": "deny", "reason": reason}

    abs_path = _resolve(path, cwd)
    memory = {os.path.realpath(m) for m in MEMORY_FILES}
    if abs_path in memory:
        total_bytes = _projected_size(abs_path, args)
        for mem_file in memory:
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


def decide(payload: dict) -> dict:
    tool_call = payload.get("toolCall") or {}
    tool_name = tool_call.get("name", "") or payload.get("toolName", "") or payload.get("tool_name", "")
    args = tool_call.get("args") or payload.get("tool_input") or {}
    cwd = os.path.realpath(payload.get("cwd") or os.getcwd())

    if tool_name in FILE_TOOLS or (tool_name not in COMMAND_TOOLS and any(k in args for k in PATH_KEYS)):
        return check_file_mutation(args, cwd)
    return check_command(args, cwd)


def main():
    claude_style = False
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError("payload is not an object")
        claude_style = "tool_name" in payload
        result = decide(payload)
    except Exception as exc:
        # Fail closed: a gate that cannot read the call must not let it through.
        result = {"decision": "deny", "reason": f"Blocked: security gate could not read the tool call ({exc})."}

    if claude_style:
        result = {"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": result["decision"],
            "permissionDecisionReason": result.get("reason", ""),
        }}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
