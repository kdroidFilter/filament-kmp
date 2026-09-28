#!/usr/bin/env bash
#
# Runs samples/nucleusApp on the duck scene for a few seconds with surface stats on and checks
# that frames reach the screen through the expected surface path (see SurfaceStats.jvm.kt):
#   nucleus-metal | nucleus-egl | nucleus-dx | readback
#
# Usage: scripts/dev/smoke-nucleus.sh <expected-surface> [seconds=8]
# SMOKE_GRADLE_ARGS is passed to Gradle (e.g. -Pfilament.debug=true for symbolized crash stacks).
# On Linux without a display, run under xvfb-run (CI does).
set -euo pipefail

EXPECTED="${1:?usage: $0 nucleus-metal|nucleus-egl|nucleus-dx|readback [seconds]}"
SECS="${2:-8}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG="$ROOT/build/smoke-nucleus.log"
mkdir -p "$(dirname "$LOG")"

(cd "$ROOT/samples" && FILAMENT_BENCH="duck $SECS" JAVA_TOOL_OPTIONS="-Dfilament.compose.stats=true" \
    ./gradlew -q :nucleusApp:run --no-configuration-cache ${SMOKE_GRADLE_ARGS:-}) > "$LOG" 2>&1 || true

surfaces=$(sed -n 's/^filament-surface=//p' "$LOG" | sort -u | tr '\n' ' ')
frames=$(awk '/^filament-stats/ { split($2, f, "="); n += f[2] } END { print n + 0 }' "$LOG")
echo "surfaces: ${surfaces:-none}  frames delivered: $frames  (log: $LOG)"
if [[ " $surfaces " != *" $EXPECTED "* || "$frames" -eq 0 ]]; then
    echo "expected frames through '$EXPECTED'" >&2
    tail -40 "$LOG" >&2
    # A native crash leaves its stacks in hs_err_pid*.log next to the app.
    for f in "$ROOT"/samples/nucleusApp/hs_err_pid*.log; do
        [[ -f "$f" ]] && sed -n '1,/^---------------  T H R E A D/p; /^siginfo/p; /^Native frames/,/^$/p' "$f" >&2
    done
    exit 1
fi
