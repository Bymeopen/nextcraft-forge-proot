#!/usr/bin/env python3
import json
import re
import sys

# Hard-blocked catastrophic patterns (Denied immediately before bash runs)
DENY_PATTERNS = [
    (r"unprivileged_bpf_disabled", "Blocked: Modifying unprivileged_bpf_disabled crashes Android system_server."),
    (r"rm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+(/|/root|/workspace|~|\$HOME)(\s|$)", "Blocked: Catastrophic recursive deletion outside project subdir."),
    (r"\b(mkfs|fdisk|wipefs)\b", "Blocked: Disk formatting utilities are forbidden."),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "Blocked: Fork bomb detected."),
]

# High-risk patterns that require explicit user confirmation ("ask")
ASK_PATTERNS = [
    (r"curl\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"wget\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"\b(nc|ncat|netcat)\b.*-e", "Reverse shell pattern detected. Confirm execution?"),
]

def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print(json.dumps({"decision": "allow"}))
        return

    tool_call = payload.get("toolCall", {})
    args = tool_call.get("args", {})
    cmd = args.get("CommandLine", "") or args.get("command", "")

    for pattern, reason in DENY_PATTERNS:
        if re.search(pattern, cmd):
            print(json.dumps({"decision": "deny", "reason": reason}))
            return

    for pattern, reason in ASK_PATTERNS:
        if re.search(pattern, cmd):
            print(json.dumps({"decision": "ask", "reason": reason}))
            return

    print(json.dumps({"decision": "allow"}))

if __name__ == "__main__":
    main()
