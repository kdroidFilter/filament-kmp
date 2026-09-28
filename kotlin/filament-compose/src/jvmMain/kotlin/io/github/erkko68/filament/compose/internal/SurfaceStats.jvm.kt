package io.github.erkko68.filament.compose.internal

import java.util.concurrent.atomic.AtomicLong

/**
 * Opt-in counters for benchmarking the JVM surfaces against each other: with
 * `-Dfilament.compose.stats=true`, prints one `filament-stats` line per second to stdout —
 * frames handed to the display, and CPU time spent in the render callbacks (render + readback
 * or fence handling). Off by default: then every call is a branch on a constant.
 */
internal object SurfaceStats {
    @JvmField val enabled: Boolean = System.getProperty("filament.compose.stats").toBoolean()

    private val frames = AtomicLong()
    private val renderNanos = AtomicLong()

    init {
        if (enabled) {
            Thread({
                while (true) {
                    Thread.sleep(1000)
                    val f = frames.getAndSet(0)
                    val ns = renderNanos.getAndSet(0)
                    println("filament-stats frames=$f renderCpuMs=${"%.2f".format(ns / 1e6)}")
                }
            }, "filament-stats").apply { isDaemon = true }.start()
        }
    }

    private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Prints which surface implementation rendered, once per kind: `filament-surface=<kind>`. */
    fun surface(kind: String) {
        if (enabled && reported.add(kind)) println("filament-surface=$kind")
    }

    // `-Dfilament.compose.maxFps=N` caps rendering, to compare CPU at an equal frame rate.
    // ponytail: one global clock, so it caps the sum of all views; per-surface if benches need more.
    private val minFrameNanos = System.getProperty("filament.compose.maxFps")?.toLongOrNull()?.let { 1_000_000_000L / it } ?: 0L
    private var lastFrameNanos = 0L

    /** False when rendering this frame would exceed the cap. Composition thread only. */
    fun frameDue(frameTimeNanos: Long): Boolean {
        if (minFrameNanos == 0L) return true
        // 2 ms of slack so a cap equal to a divisor of the display rate lands on every Nth vsync.
        if (frameTimeNanos - lastFrameNanos < minFrameNanos - 2_000_000L) return false
        lastFrameNanos = frameTimeNanos
        return true
    }

    fun frameDelivered() {
        if (enabled) frames.incrementAndGet()
    }

    inline fun measure(block: () -> Unit) {
        if (!enabled) return block()
        val start = System.nanoTime()
        try { block() } finally { renderNanos.addAndGet(System.nanoTime() - start) }
    }
}
