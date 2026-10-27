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
# A per-run sleep duration (300.<pid> seconds) makes every sleep of this test unique on the process list, so the
# pgrep/pkill below never match another sleep 300 running on the machine.
duration="300.$$"
setsid sh -c "exec -a $marker sleep $duration 2>/dev/null || sleep $duration & sleep $duration & exec sleep 1" </dev/null &
child=$!
wait "$child"
reap_group
sleep 0.5
if pgrep -f "^($marker|sleep) $duration\$" >/dev/null 2>&1; then
    echo "reap_group left processes behind" >&2
    pkill -f "^($marker|sleep) $duration\$" || true
    exit 1
fi
echo "reaper ok"
