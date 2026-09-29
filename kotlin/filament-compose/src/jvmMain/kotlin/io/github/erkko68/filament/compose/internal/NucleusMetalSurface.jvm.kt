package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusMetalTextureSource
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import dev.nucleusframework.window.tao.rememberTextureViewController
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Fence
import io.github.erkko68.filament.RenderTarget
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.View
import io.github.erkko68.filament.Viewport

// GPU path for apps running on Nucleus (https://nucleusframework.dev) with its Tao backend:
// Filament renders straight into an MTLTexture on the window's own Metal device and Nucleus's
// TextureView composites it, instead of the default swapchain → readPixels → Skia Image copy
// through the CPU. Nucleus is a compileOnly dependency, so nothing here may be touched unless
// [nucleusGpuEnabled] is true.

/**
 * True when Nucleus's Tao backend is on the runtime classpath, unless opted out with
 * `-Dfilament.compose.nucleus=false` (forces the readback path, e.g. to compare the two).
 */
internal val nucleusGpuEnabled: Boolean by lazy {
    System.getProperty("filament.compose.nucleus") != "false" &&
        runCatching { Class.forName("dev.nucleusframework.window.tao.TaoGpuRenderContextKt") }.isSuccess
}

/**
 * The window's `id<MTLDevice>` when this composable sits in a Nucleus Metal surface, else 0
 * (another backend, or the GPU context not up yet).
 */
@Composable
internal fun rememberNucleusMetalDevice(): Long =
    (rememberTaoGpuRenderContext() as? TaoMetalRenderContext)?.metalDevicePtr ?: 0L

/**
 * Filament → shared MTLTexture → Nucleus TextureView. Filament and Nucleus both take
 * `MTLCreateSystemDefaultDevice()`, so the texture lives on the window's device and Nucleus
 * samples it in place. Two targets ping-pong: Filament renders into one while the other is on
 * screen, and a target is only handed over once its Filament fence (a GPU-side MTLSharedEvent
 * signal) has fired — TextureView's contract is that the producer finished writing.
 */
@Composable
internal fun NucleusMetalFilamentSurface(
    modifier: Modifier,
    engine: Engine,
    renderer: Renderer,
    view: View,
    metalDevice: Long,
    renderingEnabled: Boolean,
    onResize: (aspect: Double) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val controller = rememberTextureViewController()
    val onResizeRef = remember { Ref<(Double) -> Unit>() }
    SideEffect { onResizeRef.value = onResize }

    // ponytail: reallocates on every size change; debounce like the readback path if resizes stutter.
    val targets = remember(engine, metalDevice, size) {
        if (size.width > 0 && size.height > 0) List(2) { MetalTarget(engine, metalDevice, size) } else emptyList()
    }
    var shown by remember(targets) { mutableStateOf<MetalTarget?>(null) }
    val inFlight = remember(targets) { Ref<Pair<MetalTarget, Fence>>() }

    DisposableEffect(targets) {
        if (targets.isNotEmpty()) {
            view.viewport = Viewport(0, 0, size.width, size.height)
            onResizeRef.value?.invoke(size.width.toDouble() / size.height)
        }
        onDispose {
            inFlight.value?.let { (_, fence) -> engine.destroyFence(fence) }
            inFlight.value = null
            view.renderTarget = null
            // Drain Filament before the MTLTextures it imported go away.
            engine.flushAndWait()
            targets.forEach { it.destroy(engine) }
        }
    }

    FilamentRenderLoop(renderingEnabled) { frameTime ->
        if (targets.isEmpty() || !SurfaceStats.frameDue(frameTime)) return@FilamentRenderLoop
        SurfaceStats.measure {
            val pending = inFlight.value
            // GPU still busy with the previous frame: skip rather than stall the UI thread.
            if (pending != null && pending.second.wait(Fence.Mode.FLUSH, 0) == Fence.FenceStatus.TIMEOUT_EXPIRED) {
                return@measure
            }
            if (pending != null) {
                engine.destroyFence(pending.second)
                inFlight.value = null
                shown = pending.first
                controller.markFrameAvailable()
                SurfaceStats.surface("nucleus-metal")
                SurfaceStats.frameDelivered()
            }
            val next = targets.first { it !== shown }
            view.renderTarget = next.renderTarget
            renderer.renderStandaloneView(view)
            inFlight.value = next to engine.createFence()
            engine.flush()
        }
    }

    TextureView(
        source = shown?.source,
        modifier = modifier.onSizeChanged { size = it },
        controller = controller,
    )
}

/** One MTLTexture shared with Nucleus, imported into Filament as the color attachment. */
private class MetalTarget(engine: Engine, device: Long, size: IntSize) {
    val mtlTexture: Long = FilaMetalTexture_create(device, size.width, size.height)
        .also { check(it != 0L) { "MTLDevice newTextureWithDescriptor failed (${size.width}x${size.height})" } }
    val source: TextureViewSource = nucleusMetalTextureSource(mtlTexture, size.width, size.height)
    // Filament adopts a +1 reference on import (CFBridgingRelease); ours stays for Nucleus.
    private val target = ImportedRenderTarget(engine, mtlTexture.also(::FilaMetalTexture_retain), size)
    val renderTarget: RenderTarget get() = target.renderTarget

    fun destroy(engine: Engine) {
        target.destroy(engine)
        FilaMetalTexture_release(mtlTexture)
    }
}
