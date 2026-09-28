#!/usr/bin/env bash
#
# Benchmarks the JVM desktop surfaces on the same scene and window size (1280x800 dp):
#   nucleus-gpu        samples/nucleusApp, Filament renders on the Nucleus window's GPU
#   nucleus-offscreen  samples/nucleusApp with -Dfilament.compose.nucleus=false (readback path)
#   compose-awt        samples/desktopApp, stock Compose Desktop window (readback path)
# Append @N to a mode to cap its rendering at N fps (e.g. nucleus-gpu@30), to compare CPU at an
# equal delivered frame rate.
#
# Usage: scripts/dev/bench-desktop.sh [scene=animation] [seconds=20] [mode…]
# Env WARMUP (default 20 s) is skipped before sampling: JIT compilation dominates it.
# Prints per mode: delivered frames/s, whole-process CPU (% of one core) and CPU ms per frame.
set -euo pipefail

SCENE="${1:-animation}"
SECONDS_="${2:-20}"
shift 2 2>/dev/null || shift $#
MODES=("${@:-nucleus-gpu nucleus-offscreen compose-awt}")
read -r -a MODES <<< "${MODES[*]}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/build/bench"
mkdir -p "$OUT"

run_mode() {
    local mode=$1 app props="-Dfilament.compose.stats=true"
    [[ "$mode" == *@* ]] && props+=" -Dfilament.compose.maxFps=${mode#*@}"
    case "${mode%@*}" in
        nucleus-gpu) app=nucleusApp ;;
        nucleus-offscreen) app=nucleusApp; props+=" -Dfilament.compose.nucleus=false" ;;
        compose-awt) app=desktopApp ;;
        *) echo "unknown mode $mode" >&2; exit 1 ;;
    esac
    (cd "$ROOT/samples" && FILAMENT_BENCH="$SCENE $SECONDS_ ${WARMUP:-20}" JAVA_TOOL_OPTIONS="$props" \
        ./gradlew -q ":$app:run" --no-configuration-cache) > "$OUT/$mode.log" 2>&1 || true
    # Frames: only the seconds the process CPU was sampled over (after the warmup).
    awk -v mode="$mode" -v skip="${WARMUP:-20}" '
        /filament-stats/ { n++; if (n > skip + 1) { split($2, f, "="); fr += f[2]; k++ } }
        /bench-result/ { split($2, p, "="); proc = p[2]; gsub(",", ".", proc) }
        END {
            if (k == 0 || fr == 0) { printf "%-20s no samples (see build/bench/%s.log)\n", mode, mode; exit }
            fps = fr / k
            printf "%-20s %6.1f fps  %6.1f%% CPU  %6.2f ms CPU/frame\n", mode, fps, proc, proc * 10 / fps
        }' "$OUT/$mode.log"
}

echo "scene=$SCENE seconds=$SECONDS_ ($(uname -sm))"
for m in "${MODES[@]}"; do run_mode "$m"; done
