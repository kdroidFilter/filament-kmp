#!/usr/bin/env bash
#
# Benchmarks the JVM desktop surfaces on the same scene and window size (1280x800 dp):
#   nucleus-gpu        samples/nucleusApp, Filament renders on the Nucleus window's GPU
#   nucleus-offscreen  samples/nucleusApp with -Dfilament.compose.nucleus=false (readback path)
#   compose-awt        samples/desktopApp, stock Compose Desktop window (readback path)
#
# Usage: scripts/dev/bench-desktop.sh [scene=animation] [seconds=20] [mode…]
# Prints per mode: delivered frames/s, render-callback CPU ms/s, whole-process CPU %.
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
    case "$mode" in
        nucleus-gpu) app=nucleusApp ;;
        nucleus-offscreen) app=nucleusApp; props+=" -Dfilament.compose.nucleus=false" ;;
        compose-awt) app=desktopApp ;;
        *) echo "unknown mode $mode" >&2; exit 1 ;;
    esac
    (cd "$ROOT/samples" && FILAMENT_BENCH="$SCENE $SECONDS_" JAVA_TOOL_OPTIONS="$props" \
        ./gradlew -q ":$app:run" --no-configuration-cache) > "$OUT/$mode.log" 2>&1 || true
    # Drop the warmup seconds (the process starts sampling after 3 s) and the partial last line.
    awk -v mode="$mode" '
        /filament-stats/ { n++; if (n > 4) { split($2, f, "="); split($3, c, "="); fr += f[2]; cpu += c[2]; k++ } }
        /bench-result/ { split($2, p, "="); proc = p[2] }
        END {
            if (k == 0) { printf "%-18s no samples (see build/bench/%s.log)\n", mode, mode; exit }
            printf "%-18s %6.1f fps  %7.2f ms/s render CPU  %6s%% process CPU\n", mode, fr / k, cpu / k, proc
        }' "$OUT/$mode.log"
}

echo "scene=$SCENE seconds=$SECONDS_ ($(uname -sm))"
for m in "${MODES[@]}"; do run_mode "$m"; done
