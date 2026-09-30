package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive

/** Calls [onFrame] once per display refresh while [enabled]; no frame is scheduled otherwise. */
@Composable
internal fun FilamentRenderLoop(enabled: Boolean = true, onFrame: (Long) -> Unit) {
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                currentOnFrame(frameTimeNanos)
            }
        }
    }
}

/**
 * Keeps a paused surface rendering until a frame rendered while paused is on screen. Pausing right after a change
 * would otherwise strand it: its frame still on the GPU when the loop stops, skipped (frame pacing, busy GPU), or
 * never rendered into targets (re)allocated after the pause, leaving a stale or empty view until the next change.
 *
 * The loop runs while [loopEnabled]; a frame remembers whether it was rendered paused and says so to [delivered]
 * once it is on screen.
 */
internal class PausedFrameGate {
    private var pausedFrameShown by mutableStateOf(false)

    fun loopEnabled(renderingEnabled: Boolean): Boolean = renderingEnabled || !pausedFrameShown

    fun delivered(pausedFrame: Boolean) {
        if (pausedFrame) pausedFrameShown = true
    }

    fun resume() {
        pausedFrameShown = false
    }
}

/** A [PausedFrameGate] for one set of render targets: new [targets] have shown nothing yet. */
@Composable
internal fun rememberPausedFrameGate(renderingEnabled: Boolean, targets: Any?): PausedFrameGate {
    val gate = remember(targets) { PausedFrameGate() }
    SideEffect { if (renderingEnabled) gate.resume() }
    return gate
}
