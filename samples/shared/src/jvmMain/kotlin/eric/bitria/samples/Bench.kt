package eric.bitria.samples

import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

/**
 * `FILAMENT_BENCH="<scene> <seconds> [warmup=20]"` (env): opens [Screen] `scene` directly, measures the
 * whole process's CPU time over `seconds` after `warmup` seconds (JIT compilation dominates the
 * first ~20 s and would drown the steady-state difference between surfaces), prints one `bench-result` line and exits. Pair with
 * `-Dfilament.compose.stats=true` for per-second delivered frames; see scripts/dev/bench-desktop.sh.
 */
class Bench(val screen: Screen, private val seconds: Long, private val warmupSeconds: Long) {

    fun start() {
        Thread({
            Thread.sleep(warmupSeconds * 1000)
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            val cpu0 = os.processCpuTime
            val t0 = System.nanoTime()
            Thread.sleep(seconds * 1000)
            val cpu = os.processCpuTime - cpu0
            val wall = System.nanoTime() - t0
            println("bench-result cpuPercent=${"%.1f".format(100.0 * cpu / wall)} seconds=$seconds")
            exitProcess(0)
        }, "bench").apply { isDaemon = true }.start()
    }

    companion object {
        /** From `FILAMENT_BENCH`, an env var so it passes through Gradle's `run` task untouched. */
        fun fromEnv(): Bench? {
            val parts = System.getenv("FILAMENT_BENCH")?.split(' ')?.filter { it.isNotEmpty() } ?: return null
            val screen = Screen.byName(parts.getOrElse(0) { "animation" }) ?: error("unknown scene ${parts[0]}")
            return Bench(screen, parts.getOrElse(1) { "10" }.toLong(), parts.getOrElse(2) { "20" }.toLong())
        }
    }
}
