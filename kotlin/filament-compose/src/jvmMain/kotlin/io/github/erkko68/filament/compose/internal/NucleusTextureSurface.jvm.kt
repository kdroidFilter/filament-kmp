package io.github.erkko68.filament.compose.internal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.node.Ref
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewController
import dev.nucleusframework.window.tao.TextureViewSource
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Fence
import io.github.erkko68.filament.RenderTarget
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.View

/** One of a Nucleus GPU surface's two targets: a texture Filament renders into and the window composites. */
internal interface GpuTarget {
    val renderTarget: RenderTarget
    val source: TextureViewSource
    val controller: TextureViewController

    /** Windows: GL may only write while the D3D11 texture is locked for it. */
    fun beforeRender(): Boolean = true

    fun afterRender() {}
}

/**
 * Longest the draw pass waits for the GPU to finish a frame. Past it (a first frame compiling its shaders, a busy
 * GPU) the frame is shown once done, a frame or more late, rather than stalling the UI thread.
 */
private const val SYNC_WAIT_NANOS = 4_000_000L

private class InFlight(val target: GpuTarget, val fence: Fence, val paused: Boolean)

/**
 * Presents Filament frames through Nucleus [TextureView]s, in step with the rest of the Compose scene: a frame is
 * rendered in the draw pass, after the composition that changed the scene has been applied, and shown in that same
 * draw once the GPU is done. 2D overlays drawn from the same state therefore line up with the 3D image with no
 * frame delay of their own.
 *
 * Two targets ping-pong: Filament renders into one while the other is on screen, and a target is only shown once
 * its fence has fired — TextureView's contract is that the producer finished writing. Each target has its own
 * TextureView, only the shown one drawing, so switching needs no recomposition.
 *
 * The caller owns [targets] (allocation, viewport, destruction) and disposes them after this.
 */
@Composable
internal fun NucleusTextureSurface(
    modifier: Modifier,
    engine: Engine,
    renderer: Renderer,
    view: View,
    targets: List<GpuTarget>,
    renderingEnabled: Boolean,
    kind: String,
    textureModifier: Modifier = Modifier,
) {
    val gate = rememberPausedFrameGate(renderingEnabled, targets)
    // Read in draw only: a change redraws, nothing recomposes
    var shown by remember(targets) { mutableIntStateOf(-1) }
    var requested by remember { mutableIntStateOf(0) }
    val rendered = remember(targets) { Ref<Int>() }
    val inFlight = remember(targets) { Ref<InFlight>() }

    DisposableEffect(targets) {
        onDispose {
            inFlight.value?.let { frame ->
                engine.destroyFence(frame.fence)
                frame.target.afterRender()
            }
            inFlight.value = null
        }
    }

    /** Shows the frame on the GPU if it is done within [timeoutNanos]; false while the GPU still works on it. */
    fun settle(timeoutNanos: Long): Boolean {
        val frame = inFlight.value ?: return true
        if (frame.fence.wait(Fence.Mode.FLUSH, timeoutNanos) == Fence.FenceStatus.TIMEOUT_EXPIRED) return false
        inFlight.value = null
        engine.destroyFence(frame.fence)
        frame.target.afterRender()
        shown = targets.indexOf(frame.target)
        frame.target.controller.markFrameAvailable()
        SurfaceStats.surface(kind)
        SurfaceStats.frameDelivered()
        gate.delivered(pausedFrame = frame.paused)
        return true
    }

    fun render(paused: Boolean) {
        SurfaceStats.measure {
            // GPU still busy with the previous frame: skip rather than stall the UI thread
            if (!settle(0)) return@measure
            val next = targets.filterIndexed { i, _ -> i != shown }.first()
            if (!next.beforeRender()) return@measure
            view.renderTarget = next.renderTarget
            renderer.renderStandaloneView(view)
            inFlight.value = InFlight(next, engine.createFence(), paused)
            engine.flush()
            settle(SYNC_WAIT_NANOS)
        }
    }

    // The loop only asks for a draw: rendering there sees this frame's composition, not the previous one's
    FilamentRenderLoop(gate.loopEnabled(renderingEnabled)) { frameTime ->
        if (targets.isNotEmpty() && SurfaceStats.frameDue(frameTime)) requested++
    }

    Box(
        modifier.drawWithContent {
            // Once per request: showing a frame redraws this pass too
            if (targets.isNotEmpty() && rendered.value != requested) {
                rendered.value = requested
                render(paused = !renderingEnabled)
            }
            drawContent()
        },
    ) {
        targets.forEachIndexed { i, target ->
            Box(Modifier.matchParentSize().drawWithContent { if (shown == i) drawContent() }) {
                TextureView(source = target.source, modifier = textureModifier.fillMaxSize(), controller = target.controller)
            }
        }
    }
}
