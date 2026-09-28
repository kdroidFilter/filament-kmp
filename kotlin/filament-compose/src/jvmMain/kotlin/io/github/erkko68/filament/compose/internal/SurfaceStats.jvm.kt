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

    fun frameDelivered() {
        if (enabled) frames.incrementAndGet()
    }

    inline fun measure(block: () -> Unit) {
        if (!enabled) return block()
        val start = System.nanoTime()
        try { block() } finally { renderNanos.addAndGet(System.nanoTime() - start) }
    }
}
