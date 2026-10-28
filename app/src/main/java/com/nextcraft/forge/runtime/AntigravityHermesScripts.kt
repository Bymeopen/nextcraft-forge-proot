package com.nextcraft.forge.runtime

/** Hermes-Lite helper scripts (the `.agents/scripts` Python files) provisioned by [AntigravityCustomizations]. */
internal object AntigravityHermesScripts {
    val SECURITY_GATE_PY: String = """
#!/usr/bin/env python3
import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
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
    (r"rm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+(/|/root|/workspace|~|\${'$'}HOME)(\s|${'$'})", "Blocked: Catastrophic recursive deletion outside project subdir."),
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

def check_command(args: dict):
    cmd = args.get("CommandLine", "") or args.get("command", "")
    for pattern, reason in DENY_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "deny", "reason": reason}
    for pattern, reason in ASK_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "ask", "reason": reason}
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

def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print(json.dumps({"decision": "allow"}))
        return

    tool_call = payload.get("toolCall", {})
    tool_name = tool_call.get("name", "") or payload.get("toolName", "")
    args = tool_call.get("args", {})

    if tool_name in ("write_file", "edit_file", "replace_file_content", "multi_replace_file_content") or any(k in args for k in ("Path", "path", "TargetFile")):
        result = check_file_mutation(args, tool_name)
    else:
        result = check_command(args)

    print(json.dumps(result))

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val SESSION_SEARCH_PY: String = """
#!/usr/bin/env python3
import argparse
import glob
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
DB_PATH = os.path.join(WORKSPACE_ROOT, ".agents/memory/history.db")
BRAIN_GLOB = "/root/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl"

def get_db() -> sqlite3.Connection:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(session_id, step_id, source, created_at, content, tokenize='porter unicode61')")
    conn.execute("CREATE TABLE IF NOT EXISTS indexed_steps (unique_key TEXT PRIMARY KEY, indexed_at TEXT)")
    return conn

def sync_transcripts(conn: sqlite3.Connection) -> int:
    added = 0
    now_str = datetime.now(timezone.utc).isoformat()
    for path in glob.glob(BRAIN_GLOB):
        parts = path.split(os.sep)
        try:
            session_id = parts[parts.index("brain") + 1]
        except (ValueError, IndexError):
            session_id = "unknown"
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if not line.strip():
                        continue
                    try:
                        obj = json.loads(line)
                    except Exception:
                        continue
                    step_idx = str(obj.get("step_index", ""))
                    source = obj.get("source", "UNKNOWN")
                    step_type = obj.get("type", "")
                    content = obj.get("content", "")
                    created_at = obj.get("created_at", "")
                    if not content or step_type not in ("USER_INPUT", "PLANNER_RESPONSE"):
                        continue
                    content_clean = content
                    if "<nextcraft_workspace>" in content_clean and "</nextcraft_workspace>" in content_clean:
                        content_clean = content_clean.split("</nextcraft_workspace>", 1)[-1].strip()
                    if len(content_clean) < 3:
                        continue
                    unique_key = f"{session_id}:{step_idx}:{step_type}"
                    if conn.execute("SELECT 1 FROM indexed_steps WHERE unique_key = ?", (unique_key,)).fetchone():
                        continue
                    conn.execute("INSERT INTO memory_fts (session_id, step_id, source, created_at, content) VALUES (?, ?, ?, ?, ?)", (session_id, step_idx, source, created_at, content_clean[:8000]))
                    conn.execute("INSERT INTO indexed_steps (unique_key, indexed_at) VALUES (?, ?)", (unique_key, now_str))
                    added += 1
        except OSError:
            continue
    conn.commit()
    return added

def log_note(conn: sqlite3.Connection, title: str, text: str) -> None:
    now_str = datetime.now(timezone.utc).isoformat()
    unique_key = f"note:{now_str}:{title}"
    conn.execute("INSERT INTO memory_fts (session_id, step_id, source, created_at, content) VALUES (?, ?, ?, ?, ?)", ("episodic-note", "note", "AGENT_NOTE", now_str, f"[{title}] {text}"))
    conn.execute("INSERT INTO indexed_steps (unique_key, indexed_at) VALUES (?, ?)", (unique_key, now_str))
    conn.commit()
    print(f"Logged episodic note: {title}")

def search(conn: sqlite3.Connection, query: str, limit: int = 5) -> None:
    sync_transcripts(conn)
    sql = "SELECT session_id, step_id, source, created_at, snippet(memory_fts, 4, '«', '»', ' ... ', 24), bm25(memory_fts) AS rank FROM memory_fts WHERE memory_fts MATCH ? ORDER BY rank LIMIT ?"
    try:
        rows = conn.execute(sql, (query, limit)).fetchall()
    except sqlite3.OperationalError:
        rows = conn.execute(sql, ('"' + query.replace('"', ' ') + '"', limit)).fetchall()
    if not rows:
        print(f"No matches found for query: {query}")
        return
    print(f"=== FTS5 Search Results for '{query}' (Top {len(rows)}) ===")
    for idx, (sess_id, step_id, src, ts, excerpt, rank) in enumerate(rows, 1):
        print(f"{idx}. [{src} | sess:{sess_id[:8]}#{step_id} | {ts[:19]}] (score: {rank:.2f})\n   {' '.join(excerpt.splitlines())}")

def stats(conn: sqlite3.Connection) -> None:
    added = sync_transcripts(conn)
    total = conn.execute("SELECT COUNT(*) FROM memory_fts").fetchone()[0]
    sessions = conn.execute("SELECT COUNT(DISTINCT session_id) FROM memory_fts").fetchone()[0]
    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    print(f"FTS5 DB: {DB_PATH} ({db_size} bytes) | Indexed Entries: {total} (+{added} new) | Sessions: {sessions}")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite SQLite FTS5 Session & Episodic Search")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sync")
    sub.add_parser("stats")
    p_search = sub.add_parser("search")
    p_search.add_argument("query")
    p_search.add_argument("-n", "--limit", type=int, default=5)
    p_log = sub.add_parser("log")
    p_log.add_argument("title")
    p_log.add_argument("text")
    args = parser.parse_args()
    conn = get_db()
    if args.cmd == "sync":
        print(f"Synced {sync_transcripts(conn)} new entries into FTS5.")
    elif args.cmd == "stats":
        stats(conn)
    elif args.cmd == "search":
        search(conn, args.query, args.limit)
    elif args.cmd == "log":
        log_note(conn, args.title, args.text)

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val CONTEXT_COMPRESSOR_PY: String = """
#!/usr/bin/env python3
import argparse
import glob
import json
import os
import sys
from datetime import datetime, timezone

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import session_search

BRAIN_GLOB = "/root/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl"

def get_latest_transcript():
    files = glob.glob(BRAIN_GLOB)
    if not files:
        return "", ""
    latest = max(files, key=os.path.getmtime)
    parts = latest.split(os.sep)
    try:
        sess_id = parts[parts.index("brain") + 1]
    except (ValueError, IndexError):
        sess_id = "unknown"
    return sess_id, latest

def summarize_session(sess_id: str, path: str, max_items: int = 8) -> str:
    user_goals, tools_used, key_outcomes = [], [], []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                step_type = obj.get("type", "")
                content = obj.get("content", "") or ""
                if "<nextcraft_workspace>" in content and "</nextcraft_workspace>" in content:
                    content = content.split("</nextcraft_workspace>", 1)[-1].strip()
                if step_type == "USER_INPUT" and content:
                    first_line = content.splitlines()[0].strip()
                    if first_line and first_line not in user_goals:
                        user_goals.append(first_line[:160])
                for tc in obj.get("tool_calls", []) or []:
                    tname = tc.get("name", "")
                    if tname and tname not in tools_used:
                        tools_used.append(tname)
                if step_type == "PLANNER_RESPONSE" and content:
                    first_line = content.splitlines()[0].strip()
                    if len(first_line) > 20:
                        key_outcomes.append(first_line[:180])
    except OSError as e:
        return f"Error reading transcript: {e}"
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    return f"Session {sess_id[:8]} ({now_str}) | Goals: {' | '.join(user_goals[-max_items:]) or 'N/A'} | Tools: {', '.join(tools_used) or 'none'} | Recent Outcomes: {' | '.join(key_outcomes[-3:]) or 'N/A'}"

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Pre-Compaction Context Compressor")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    conn = session_search.get_db()
    added = session_search.sync_transcripts(conn)
    sess_id, path = get_latest_transcript()
    if not path:
        print("No active transcript found.")
        return
    digest = summarize_session(sess_id, path)
    print(f"=== Session Digest ({sess_id[:8]}) ===\n{digest}")
    if not args.dry_run:
        session_search.log_note(conn, f"Session-Digest-{sess_id[:8]}", digest)
        print(f"Synced {added} raw steps + 1 compressed session digest into FTS5 history.db.")

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val SKILL_HUB_PY: String = """
#!/usr/bin/env python3
import argparse
import os
import re
import sys
import tarfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
sys.path.insert(0, SCRIPT_DIR)
from security_gate import SECRET_AND_INJECTION_PATTERNS

SKILLS_DIR = os.path.join(WORKSPACE_ROOT, ".agents/skills")

def audit_skills() -> bool:
    if not os.path.exists(SKILLS_DIR):
        print("No skills directory found.")
        return True
    ok, count = True, 0
    for root, _, files in os.walk(SKILLS_DIR):
        for f in files:
            if f.endswith(".md"):
                count += 1
                p = os.path.join(root, f)
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
                for pattern, reason in SECRET_AND_INJECTION_PATTERNS:
                    if re.search(pattern, text):
                        print(f"[FAIL] {p}: {reason}")
                        ok = False
    if ok:
        print(f"[PASS] Audited {count} skill file(s): 0 secrets or injection patterns found.")
    return ok

def export_bundle(out_path: str) -> None:
    if not audit_skills():
        sys.exit(1)
    with tarfile.open(out_path, "w:gz") as tar:
        tar.add(SKILLS_DIR, arcname="skills")
    print(f"Exported skills bundle to: {out_path} ({os.path.getsize(out_path)} bytes)")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Skill Auditor & Portable Bundler")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("audit")
    p_exp = sub.add_parser("export")
    p_exp.add_argument("output")
    args = parser.parse_args()
    if args.cmd == "audit":
        sys.exit(0 if audit_skills() else 1)
    elif args.cmd == "export":
        export_bundle(args.output)

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val HERMES_INIT_PY: String = """
#!/usr/bin/env python3
import argparse
import os
import shutil
import stat
import sys

SOURCE_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_WORKSPACE = os.path.abspath(os.path.join(SOURCE_SCRIPTS_DIR, "../.."))

def make_executable(path: str) -> None:
    st = os.stat(path)
    os.chmod(path, st.st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

def bootstrap_workspace(target_dir: str) -> None:
    target_dir = os.path.abspath(target_dir)
    if not target_dir.startswith("/workspace"):
        print(f"Error: Target directory must be inside /workspace (got {target_dir})")
        sys.exit(1)
    for sub in (".agents/memory", ".agents/scripts", ".agents/skills"):
        os.makedirs(os.path.join(target_dir, sub), exist_ok=True)
    if os.path.abspath(SOURCE_WORKSPACE) != target_dir:
        for script_name in os.listdir(SOURCE_SCRIPTS_DIR):
            if not script_name.endswith(".py"):
                continue
            src_file = os.path.join(SOURCE_SCRIPTS_DIR, script_name)
            dst_file = os.path.join(target_dir, ".agents/scripts", script_name)
            with open(src_file, "r", encoding="utf-8") as fh:
                content = fh.read().replace(SOURCE_WORKSPACE, target_dir)
            with open(dst_file, "w", encoding="utf-8") as fh:
                fh.write(content)
            make_executable(dst_file)
    else:
        for script_name in os.listdir(SOURCE_SCRIPTS_DIR):
            if script_name.endswith(".py"):
                make_executable(os.path.join(SOURCE_SCRIPTS_DIR, script_name))
    src_hooks = os.path.join(SOURCE_WORKSPACE, ".agents/hooks.json")
    dst_hooks = os.path.join(target_dir, ".agents/hooks.json")
    if os.path.exists(src_hooks) and os.path.abspath(src_hooks) != os.path.abspath(dst_hooks):
        with open(src_hooks, "r", encoding="utf-8") as fh:
            hooks_content = fh.read().replace(SOURCE_WORKSPACE, target_dir)
        with open(dst_hooks, "w", encoding="utf-8") as fh:
            fh.write(hooks_content)
    src_skills = os.path.join(SOURCE_WORKSPACE, ".agents/skills")
    dst_skills = os.path.join(target_dir, ".agents/skills")
    if os.path.exists(src_skills):
        for item in os.listdir(src_skills):
            s_item = os.path.join(src_skills, item)
            d_item = os.path.join(dst_skills, item)
            if os.path.isdir(s_item) and not os.path.exists(d_item):
                shutil.copytree(s_item, d_item)
    for rel_path in ["AGENTS.md", ".agents/memory/USER.md", ".agents/memory/MEMORY.md"]:
        src_p = os.path.join(SOURCE_WORKSPACE, rel_path)
        dst_p = os.path.join(target_dir, rel_path)
        if os.path.exists(src_p) and not os.path.exists(dst_p):
            with open(src_p, "r", encoding="utf-8") as fh:
                text = fh.read().replace(SOURCE_WORKSPACE, target_dir)
            with open(dst_p, "w", encoding="utf-8") as fh:
                fh.write(text)
    print(f"[OK] Hermes-Lite v2 initialized in: {target_dir}")

def main():
    parser = argparse.ArgumentParser(description="Bootstrap Hermes-Lite v2 into a workspace directory")
    parser.add_argument("target", nargs="?", default=SOURCE_WORKSPACE)
    args = parser.parse_args()
    bootstrap_workspace(args.target)

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val WEB_AND_UI_TOOL_PY: String = """
#!/usr/bin/env python3
import argparse
import html.parser
import os
import re
import shutil
import subprocess
import sys
import urllib.request

class SimpleHTMLToText(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip_tags = {"script", "style", "noscript", "svg", "head"}
        self.skip_depth = 0
        self.chunks = []

    def handle_starttag(self, tag, attrs):
        if tag in self.skip_tags:
            self.skip_depth += 1
        elif tag in ("p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr"):
            self.chunks.append("\n")

    def handle_endtag(self, tag):
        if tag in self.skip_tags and self.skip_depth > 0:
            self.skip_depth -= 1

    def handle_data(self, data):
        if self.skip_depth == 0:
            text = data.strip()
            if text:
                self.chunks.append(text + " ")

    def get_text(self) -> str:
        raw = "".join(self.chunks)
        lines = [re.sub(r"\s+", " ", line).strip() for line in raw.splitlines()]
        return "\n".join(line for line in lines if line)

def fetch_web(url: str, max_chars: int = 6000) -> None:
    if not url.lower().startswith(("http://", "https://")):
        print(f"Error fetching {url}: Only http:// and https:// URLs are allowed.")
        sys.exit(1)
    req = urllib.request.Request(url, headers={"User-Agent": "HermesLite-Reader/2.0 (+NextCraftForge)"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw_html = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        sys.exit(1)
    parser = SimpleHTMLToText()
    parser.feed(raw_html)
    text = parser.get_text()
    print(f"=== Extracted Content from {url} ({len(text)} chars) ===\n{text[:max_chars]}")

def android_ui_status() -> None:
    dumpsys_bin = shutil.which("android-dumpsys") or "/opt/nextcraft/bin/android-dumpsys"
    if not os.path.exists(dumpsys_bin):
        print("android-dumpsys not found.")
        return
    try:
        out = subprocess.check_output(["sh", dumpsys_bin, "window", "displays"], stderr=subprocess.DEVNULL, timeout=5).decode("utf-8", errors="replace")
        for line in out.splitlines():
            if any(k in line for k in ("init=", "mCurrentFocus=", "mFocusedApp=")):
                print(line.strip())
    except Exception as e:
        print(f"UI status check failed: {e}")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Web Reader & Android UI Bridge")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_web = sub.add_parser("fetch")
    p_web.add_argument("url")
    p_web.add_argument("-m", "--max-chars", type=int, default=6000)
    sub.add_parser("ui-focus")
    args = parser.parse_args()
    if args.cmd == "fetch":
        fetch_web(args.url, args.max_chars)
    elif args.cmd == "ui-focus":
        android_ui_status()

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val FORGE_REFERENCE_INDEX_PY: String = """
#!/usr/bin/env python3
import argparse
import json
import os
import re
import sqlite3
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
DEFAULT_DB_PATH = os.path.join(WORKSPACE_ROOT, ".agents/memory/forge_reference_index.db")

SKIP_DIRS = {
    ".git", ".forge", "build", ".gradle", "node_modules", "__pycache__", ".next",
    "dist", "target", ".idea", ".vscode", "captures", ".cxx", "out", "attachments",
}

INDEXABLE_EXTS = {
    ".kt", ".kts", ".java", ".py", ".js", ".ts", ".tsx", ".jsx",
    ".rs", ".go", ".c", ".cpp", ".h", ".hpp", ".cs", ".swift",
    ".dart", ".rb", ".php", ".sh", ".sql", ".md", ".json", ".yaml",
    ".yml", ".toml", ".xml", ".html", ".css",
}

SYMBOL_PATTERNS = [
    ("class", re.compile(r"^\s*(?:(?:public|private|protected|internal|abstract|final|open|data|sealed|value|inline| static)\s+)*(?:class|interface|object|enum\s+class|struct|trait|record)\s+([A-Za-z_][A-Za-z0-9_]*)")),
    ("function", re.compile(r"^\s*(?:(?:public|private|protected|internal|override|suspend|inline|operator|static|async|pub|extern)\s+)*(?:fun|def|fn|func|function)\s+(?:[A-Za-z0-9_<>,.?*\s]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[(\[<]")),
    ("const", re.compile(r"^\s*(?:(?:public|private|protected|internal|const|static|final)\s+)*(?:val|var|const|let)\s+([A-Za-z_][A-Za-z0-9_]*)\s*[:=]")),
]

def _resolve_db_path(target_dir: str = "") -> str:
    if target_dir:
        real = os.path.realpath(os.path.abspath(target_dir))
        if real.startswith("/workspace/"):
            parts = real.split(os.sep)
            proj_root = "/".join(parts[:3]) if len(parts) >= 3 else real
            return os.path.join(proj_root, ".agents/memory/forge_reference_index.db")
    return DEFAULT_DB_PATH

def get_db(db_path: str = "") -> sqlite3.Connection:
    path = db_path or DEFAULT_DB_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path)
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_passages_fts USING fts5(file_path, start_line, end_line, symbol_context, content, tokenize='porter unicode61')")
    conn.execute("CREATE TABLE IF NOT EXISTS symbol_defs (symbol_name TEXT, kind TEXT, file_path TEXT, line_num INTEGER, signature TEXT)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_symbol_name ON symbol_defs(symbol_name)")
    conn.execute("CREATE TABLE IF NOT EXISTS indexed_files (file_path TEXT PRIMARY KEY, mtime REAL, size_bytes INTEGER, total_lines INTEGER)")
    conn.execute("CREATE TABLE IF NOT EXISTS gain_metrics (id INTEGER PRIMARY KEY AUTOINCREMENT, query_type TEXT, query_text TEXT, full_bytes INTEGER, returned_bytes INTEGER, saved_tokens INTEGER, created_at REAL)")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn

def _extract_symbols(lines: list) -> list:
    found = []
    for idx, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith(("//", "#", "/*", "*")):
            continue
        for kind, regex in SYMBOL_PATTERNS:
            m = regex.match(raw_line)
            if m:
                found.append((m.group(1), kind, idx, line[:180]))
                break
    return found

def autoindex(root_dir: str, force: bool = False) -> dict:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    if not (real_root.startswith("/workspace") or real_root.startswith("/tmp")):
        raise ValueError(f"Indexing restricted to /workspace or /tmp (got {real_root})")
    db_path = _resolve_db_path(real_root)
    conn = get_db(db_path)
    t0 = time.time()
    indexed_files, skipped_unchanged = 0, 0
    existing = {row[0]: (row[1], row[2]) for row in conn.execute("SELECT file_path, mtime, size_bytes FROM indexed_files").fetchall()}
    for dirpath, dirnames, filenames in os.walk(real_root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".git") and d != "memory"]
        for fname in filenames:
            _, ext = os.path.splitext(fname)
            if ext.lower() not in INDEXABLE_EXTS:
                continue
            fpath = os.path.realpath(os.path.join(dirpath, fname))
            if not fpath.startswith(real_root):
                continue
            try:
                st = os.stat(fpath)
            except OSError:
                continue
            if st.st_size <= 0 or st.st_size > 1024 * 1024:
                continue
            rel_path = os.path.relpath(fpath, real_root)
            if not force and rel_path in existing:
                old_mtime, old_size = existing[rel_path]
                if abs(st.st_mtime - old_mtime) < 0.001 and st.st_size == old_size:
                    skipped_unchanged += 1
                    continue
            try:
                with open(fpath, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            lines = text.splitlines()
            if not lines:
                continue
            conn.execute("DELETE FROM code_passages_fts WHERE file_path = ?", (rel_path,))
            conn.execute("DELETE FROM symbol_defs WHERE file_path = ?", (rel_path,))
            symbols = _extract_symbols(lines)
            for sym, kind, lnum, sig in symbols:
                conn.execute("INSERT INTO symbol_defs (symbol_name, kind, file_path, line_num, signature) VALUES (?, ?, ?, ?, ?)", (sym, kind, rel_path, lnum, sig))
            chunk_size, overlap = 45, 5
            step = max(1, chunk_size - overlap)
            for start_idx in range(0, len(lines), step):
                chunk_lines = lines[start_idx:start_idx + chunk_size]
                if not chunk_lines:
                    break
                s_line, e_line = start_idx + 1, start_idx + len(chunk_lines)
                sym_ctx = ", ".join([s[0] for s in symbols if s_line <= s[2] <= e_line][:6])
                numbered = "\n".join(f"{s_line + i}: {l}" for i, l in enumerate(chunk_lines))
                conn.execute("INSERT INTO code_passages_fts (file_path, start_line, end_line, symbol_context, content) VALUES (?, ?, ?, ?, ?)", (rel_path, str(s_line), str(e_line), sym_ctx, numbered[:6000]))
                if e_line >= len(lines):
                    break
            conn.execute("INSERT OR REPLACE INTO indexed_files (file_path, mtime, size_bytes, total_lines) VALUES (?, ?, ?, ?)", (rel_path, st.st_mtime, st.st_size, len(lines)))
            indexed_files += 1
    conn.commit()
    elapsed = round(time.time() - t0, 2)
    return {
        "root": real_root,
        "dbPath": db_path,
        "updatedFiles": indexed_files,
        "unchangedFiles": skipped_unchanged,
        "totalFiles": conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0],
        "totalPassages": conn.execute("SELECT COUNT(*) FROM code_passages_fts").fetchone()[0],
        "totalSymbols": conn.execute("SELECT COUNT(*) FROM symbol_defs").fetchone()[0],
        "elapsedSec": elapsed,
    }

def _record_gain(conn: sqlite3.Connection, qtype: str, qtext: str, matched_files: set, returned_text: str) -> int:
    if not matched_files:
        return 0
    placeholders = ",".join("?" for _ in matched_files)
    row = conn.execute(f"SELECT COALESCE(SUM(size_bytes), 0) FROM indexed_files WHERE file_path IN ({placeholders})", tuple(matched_files)).fetchone()
    full_bytes = int(row[0] or 0)
    ret_bytes = len(returned_text.encode("utf-8"))
    saved_tokens = max(0, full_bytes - ret_bytes) // 4
    conn.execute("INSERT INTO gain_metrics (query_type, query_text, full_bytes, returned_bytes, saved_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?)", (qtype, qtext[:200], full_bytes, ret_bytes, saved_tokens, time.time()))
    conn.commit()
    return saved_tokens

def search_code(query: str, root_dir: str = "", limit: int = 5) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    sql = "SELECT file_path, start_line, end_line, symbol_context, content, bm25(code_passages_fts) AS rank FROM code_passages_fts WHERE code_passages_fts MATCH ? ORDER BY rank LIMIT ?"
    try:
        rows = conn.execute(sql, (query, limit)).fetchall()
    except sqlite3.OperationalError:
        safe_q = " OR ".join(f'"{w}"' for w in re.findall(r"[A-Za-z0-9_]+", query) if len(w) > 1) or f'"{query}"'
        rows = conn.execute(sql, (safe_q, limit)).fetchall()
    if not rows:
        return f"No code passages matched query: `{query}`"
    out, matched_files = [f"### Forge Reference Index — BM25 Code Search: `{query}` (Top {len(rows)})"], set()
    for idx, (fpath, s_line, e_line, sym_ctx, content, rank) in enumerate(rows, 1):
        matched_files.add(fpath)
        sym_label = f" | symbols: `{sym_ctx}`" if sym_ctx else ""
        out.append(f"\n#### {idx}. `{fpath}:{s_line}-{e_line}` (BM25: `{rank:.2f}`{sym_label})\n```\n{content}\n```")
    result_text = "\n".join(out)
    saved = _record_gain(conn, "search", query, matched_files, result_text)
    if saved > 0:
        result_text += f"\n\n> **Token Gain**: Saved ~`{saved:,}` tokens vs reading {len(matched_files)} full file(s)."
    return result_text

def lookup_symbol(symbol: str, root_dir: str = "", limit: int = 10) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    rows = conn.execute("SELECT symbol_name, kind, file_path, line_num, signature FROM symbol_defs WHERE symbol_name = ? OR symbol_name LIKE ? ORDER BY (symbol_name = ?) DESC, symbol_name ASC LIMIT ?", (symbol, f"%{symbol}%", symbol, limit)).fetchall()
    if not rows:
        return f"No symbol definition found for `{symbol}`."
    out, matched_files = [f"### Forge Reference Index — Symbol Definitions for `{symbol}` ({len(rows)} found)"], set()
    for sym_name, kind, fpath, lnum, sig in rows:
        matched_files.add(fpath)
        out.append(f"- **`{sym_name}`** ({kind}) at `{fpath}:{lnum}`\n  `{sig}`")
    result_text = "\n".join(out)
    saved = _record_gain(conn, "def", symbol, matched_files, result_text)
    if saved > 0:
        result_text += f"\n\n> **Token Gain**: Saved ~`{saved:,}` tokens vs full-file scans."
    return result_text

def batch_search(queries: list, symbols: list = None, root_dir: str = "", limit_per_query: int = 3) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    sections = ["## Forge Reference Index — Batch Search Results"]
    for s in (symbols or [])[:10]:
        sections.append(lookup_symbol(str(s), real_root, limit=5))
    for q in (queries or [])[:10]:
        sections.append(search_code(str(q), real_root, limit=limit_per_query))
    return "\n\n---\n\n".join(sections)

def gain_report(root_dir: str = "") -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    tot_files = conn.execute("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0), COALESCE(SUM(total_lines), 0) FROM indexed_files").fetchone()
    tot_passages = conn.execute("SELECT COUNT(*) FROM code_passages_fts").fetchone()[0]
    tot_syms = conn.execute("SELECT COUNT(*) FROM symbol_defs").fetchone()[0]
    g_row = conn.execute("SELECT COUNT(*), COALESCE(SUM(full_bytes), 0), COALESCE(SUM(returned_bytes), 0), COALESCE(SUM(saved_tokens), 0) FROM gain_metrics").fetchone()
    queries_run, full_b, ret_b, saved_tok = int(g_row[0]), int(g_row[1]), int(g_row[2]), int(g_row[3])
    ratio = f"{(full_b / max(1, ret_b)):.1f}x" if ret_b > 0 else "1.0x"
    return (
        f"### Forge Reference Index — Telemetry & Token Gain Report\n"
        f"- **Indexed Root**: `{real_root}`\n"
        f"- **Corpus Size**: `{tot_files[0]}` files (`{tot_files[2]:,}` lines, `{tot_files[1] // 1024:,} KB`)\n"
        f"- **Indexed Chunks & Symbols**: `{tot_passages:,}` BM25 passages | `{tot_syms:,}` symbol definitions\n"
        f"- **Queries Executed**: `{queries_run}` queries\n"
        f"- **Context Reduction Ratio**: **`{ratio}` fewer bytes** (`{ret_b // 1024:,} KB` returned vs `{full_b // 1024:,} KB` full files)\n"
        f"- **Estimated Tokens Saved**: **`~{saved_tok:,}` tokens**"
    )

def main():
    parser = argparse.ArgumentParser(description="Forge Reference Index — Local Code, Symbol & Reference Search Engine")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_idx = sub.add_parser("autoindex")
    p_idx.add_argument("path", nargs="?", default=WORKSPACE_ROOT)
    p_idx.add_argument("--force", action="store_true")
    p_srch = sub.add_parser("search")
    p_srch.add_argument("query")
    p_srch.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_srch.add_argument("-n", "--limit", type=int, default=5)
    p_def = sub.add_parser("def")
    p_def.add_argument("symbol")
    p_def.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_def.add_argument("-n", "--limit", type=int, default=10)
    p_batch = sub.add_parser("batch")
    p_batch.add_argument("-q", "--query", action="append", default=[])
    p_batch.add_argument("-s", "--symbol", action="append", default=[])
    p_batch.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_gain = sub.add_parser("gain")
    p_gain.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    args = parser.parse_args()
    if args.cmd == "autoindex":
        print(json.dumps(autoindex(args.path, force=args.force), indent=2))
    elif args.cmd == "search":
        print(search_code(args.query, args.path, args.limit))
    elif args.cmd == "def":
        print(lookup_symbol(args.symbol, args.path, args.limit))
    elif args.cmd == "batch":
        print(batch_search(args.query, args.symbol, args.path))
    elif args.cmd == "gain":
        print(gain_report(args.path))

if __name__ == "__main__":
    main()
""".trimIndent() + "\n"

    val HERMES_SCRIPTS: Map<String, String> = linkedMapOf(
        "security_gate.py" to SECURITY_GATE_PY,
        "session_search.py" to SESSION_SEARCH_PY,
        "context_compressor.py" to CONTEXT_COMPRESSOR_PY,
        "skill_hub.py" to SKILL_HUB_PY,
        "hermes_init.py" to HERMES_INIT_PY,
        "web_and_ui_tool.py" to WEB_AND_UI_TOOL_PY,
        "forge_reference_index.py" to FORGE_REFERENCE_INDEX_PY,
    )
}
