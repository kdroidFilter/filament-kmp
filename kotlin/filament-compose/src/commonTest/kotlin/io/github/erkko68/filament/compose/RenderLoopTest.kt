package io.github.erkko68.filament.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import io.github.erkko68.filament.compose.internal.FilamentRenderLoop
import io.github.erkko68.filament.compose.internal.rememberPausedFrameGate
import io.github.erkko68.filament.compose.testutils.ComposeTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `renderingEnabled` gates the per-view render loop: no frame callbacks while it is false. */
class RenderLoopTest : ComposeTestFixture() {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun disabledLoopSchedulesNoFramesAndResumes() = runComposeUiTest {
        mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        var frames = 0
        setContent { FilamentRenderLoop(enabled) { frames++ } }

        repeat(3) { mainClock.advanceTimeByFrame() }
        assertTrue(frames > 0, "enabled loop should render")

        enabled = false
        mainClock.advanceTimeByFrame() // let the effect restart with enabled = false
        val paused = frames
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertEquals(paused, frames, "disabled loop must not render")

        enabled = true
        repeat(3) { mainClock.advanceTimeByFrame() }
        assertTrue(frames > paused, "re-enabled loop should resume")
    }

    // A surface whose frames reach the screen one frame late, like the Nucleus GPU surfaces' fences.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pausedLoopRunsUntilAPausedFrameIsOnScreen() = runComposeUiTest {
        mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        var targets by mutableStateOf(0)
        var frames = 0
        var gpuBusy = true
        var inFlight: Boolean? = null // whether the frame on the GPU was rendered paused
        setContent {
            val gate = rememberPausedFrameGate(enabled, targets)
            FilamentRenderLoop(gate.loopEnabled(enabled)) {
                frames++
                if (gpuBusy) return@FilamentRenderLoop
                inFlight?.let { gate.delivered(pausedFrame = it) }
                inFlight = !enabled
            }
        }
        repeat(3) { mainClock.advanceTimeByFrame() }

        // Paused while the GPU is busy: the last change is not on screen yet, so frames go on
        enabled = false
        repeat(5) { mainClock.advanceTimeByFrame() }
        val whileBusy = frames
        assertTrue(whileBusy > 3, "a paused surface must keep rendering until its scene is on screen")

        // Rendered paused, then delivered: the loop stops
        gpuBusy = false
        repeat(4) { mainClock.advanceTimeByFrame() }
        val settled = frames
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertEquals(settled, frames, "once a paused frame is on screen the loop must stop")

        // New targets (a resize) have shown nothing: one more round, then stop again
        targets++
        repeat(4) { mainClock.advanceTimeByFrame() }
        assertTrue(frames > settled, "new targets must get the paused scene")
        val resized = frames
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertEquals(resized, frames)
    }
}
