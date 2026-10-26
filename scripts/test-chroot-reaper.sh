#!/bin/sh
# Checks that the reap_group function of chroot-exec.sh ends every process of a finished command's session.
set -e
script="$(dirname "$0")/runtime-bundles/chroot-exec.sh"
command -v setsid >/dev/null 2>&1 || { echo "setsid missing, skipped"; exit 0; }

# Extract reap_group from the shipped script so the test cannot drift from it.
awk '/^reap_group\(\) \{/,/^}/' "$script" > "${TMPDIR:-/tmp}/reap_group.sh"
grep -q reap_group "${TMPDIR:-/tmp}/reap_group.sh"

marker="reaper-test-$$"
SETSID=setsid
. "${TMPDIR:-/tmp}/reap_group.sh"
setsid sh -c "exec -a $marker sleep 300 2>/dev/null || sleep 300 & sleep 300 & exec sleep 1" </dev/null &
child=$!
wait "$child"
reap_group
sleep 0.5
if pgrep -f "^sleep 300" >/dev/null 2>&1; then
    echo "reap_group left processes behind" >&2
    pkill -f "^sleep 300" || true
    exit 1
fi
echo "reaper ok"
