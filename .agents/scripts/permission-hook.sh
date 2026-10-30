#!/bin/sh
# NextCraft permission hook for Claude Code. One script for two events:
#
#   PreToolUse         (project settings): security_gate.py judges the tool call; its verdict (allow / deny / ask, with a
#                      reason) is passed to Claude. If the gate cannot run, the answer is "ask".
#   PermissionRequest  (host settings): "allow" and "deny" from the gate are answered at once, without waiting for the app.
#                      "ask" goes to the app through the shared bridge directory, as before: the app classifies the
#                      request and prompts if needed; no answer within the timeout means deny.
#
# security_gate.py needs python3, which the Core bundle does not ship (only the optional Python stack does). Without
# python3, a missing gate or any error the result is "ask", never "allow": an install without Python keeps the app's
# own approval flow instead of running commands unchecked.
BRIDGE="${NEXTCRAFT_BRIDGE_DIR:-/nextcraft-bridge}"
RESP="${NEXTCRAFT_RESPONSE_DIR:-/nextcraft-bridge-responses}"
# Parallel chats share this directory: the session id (set by the app) tags the request so only its owner answers it.
SID="${NEXTCRAFT_SESSION_ID:-}"
INPUT="$(cat)"

json_field() {
    printf '%s' "$INPUT" | sed -n 's/.*"'"$1"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
}
emit_permission_request() {
    printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "$1"
}
emit_pre_tool_use_ask() {
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"%s"}}\n' "$1"
}

# The project the request belongs to: Claude exports CLAUDE_PROJECT_DIR to hooks; the payload's cwd is the fallback.
PROJECT="${CLAUDE_PROJECT_DIR:-}"
[ -n "$PROJECT" ] || PROJECT="$(json_field cwd)"
GATE="$PROJECT/.agents/scripts/security_gate.py"
[ -f "$GATE" ] || GATE="${HOME:-/root}/.gemini/config/scripts/security_gate.py"
GATE_USABLE=no
case "$PROJECT" in
    /*) if command -v python3 >/dev/null 2>&1 && [ -f "$GATE" ]; then GATE_USABLE=yes; fi ;;
esac

if [ "$(json_field hook_event_name)" = "PreToolUse" ]; then
    if [ "$GATE_USABLE" = yes ]; then
        VERDICT="$(printf '%s' "$INPUT" | CLAUDE_PROJECT_DIR="$PROJECT" python3 "$GATE" 2>/dev/null)"
        case "$VERDICT" in
            '{'*) printf '%s\n' "$VERDICT"; exit 0 ;;
        esac
    fi
    emit_pre_tool_use_ask "security_gate.py is not available here, so the app's approval flow decides"
    exit 0
fi

DECISION=ask
if [ "$GATE_USABLE" = yes ]; then
    DECISION="$(printf '%s' "$INPUT" | CLAUDE_PROJECT_DIR="$PROJECT" python3 "$GATE" --decision 2>/dev/null)" || DECISION=ask
fi
case "$DECISION" in
    allow | deny)
        emit_permission_request "$DECISION"
        exit 0
        ;;
esac

ID="${SID:+${SID}_}$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "$$-$(date +%s)")"
DECISION=deny
if [ -d "$BRIDGE" ] && printf '%s' "$INPUT" > "$BRIDGE/$ID.request.tmp" && mv "$BRIDGE/$ID.request.tmp" "$BRIDGE/$ID.request"; then
    i=0
    while [ "$i" -lt 600 ]; do
        if [ -f "$RESP/$ID.response" ]; then
            [ "$(cat "$RESP/$ID.response")" = "allow" ] && DECISION=allow
            break
        fi
        sleep 0.1
        i=$((i + 1))
    done
    rm -f "$BRIDGE/$ID.request" "$BRIDGE/$ID.request.tmp"
fi
emit_permission_request "$DECISION"
