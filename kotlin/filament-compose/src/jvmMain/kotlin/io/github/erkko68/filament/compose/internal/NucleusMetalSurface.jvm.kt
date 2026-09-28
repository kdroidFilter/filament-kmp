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
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_LONG

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

    FilamentRenderLoop { frameTime ->
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
    val mtlTexture: Long = MetalTextures.create(device, size.width, size.height)
    val source: TextureViewSource = nucleusMetalTextureSource(mtlTexture, size.width, size.height)
    // Filament adopts a +1 reference on import (CFBridgingRelease); ours stays for Nucleus.
    private val target = ImportedRenderTarget(engine, MetalTextures.retain(mtlTexture), size)
    val renderTarget: RenderTarget get() = target.renderTarget

    fun destroy(engine: Engine) {
        target.destroy(engine)
        MetalTextures.release(mtlTexture)
    }
}

/**
 * `id<MTLTexture>` allocation through the Objective-C runtime via FFM, so this needs no native
 * code of its own. Metal is already loaded in-process (by Filament and Nucleus).
 */
private object MetalTextures {
    private const val PIXEL_FORMAT_RGBA8_UNORM = 70L
    private const val USAGE_SHADER_READ_RENDER_TARGET = 0x1L or 0x4L
    private const val STORAGE_MODE_PRIVATE = 2L

    private val linker = Linker.nativeLinker()
    private val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global())
    private val msgSend = objc.find("objc_msgSend").orElseThrow()

    private val getClass = downcall(objc.find("objc_getClass").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val registerSel = downcall(objc.find("sel_registerName").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    // objc_msgSend must be called through the exact prototype of each method.
    private val sendDescriptor = downcall(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_BYTE))
    private val sendSetLong = downcall(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))
    private val sendObject = downcall(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    private val sendVoid = downcall(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))

    private val descriptorClass = Arena.ofConfined().use { getClass.invoke(it.allocateFrom("MTLTextureDescriptor")) as MemorySegment }
    private val selDescriptor = sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:")
    private val selSetUsage = sel("setUsage:")
    private val selSetStorageMode = sel("setStorageMode:")
    private val selNewTexture = sel("newTextureWithDescriptor:")
    private val selRetain = sel("retain")
    private val selRelease = sel("release")

    /** A +1 retained RGBA8 render-target texture on [device]; balance with [release]. */
    fun create(device: Long, width: Int, height: Int): Long {
        val desc = sendDescriptor.invoke(
            descriptorClass, selDescriptor, PIXEL_FORMAT_RGBA8_UNORM, width.toLong(), height.toLong(), 0.toByte(),
        ) as MemorySegment
        sendSetLong.invoke(desc, selSetUsage, USAGE_SHADER_READ_RENDER_TARGET)
        sendSetLong.invoke(desc, selSetStorageMode, STORAGE_MODE_PRIVATE)
        val texture = sendObject.invoke(MemorySegment.ofAddress(device), selNewTexture, desc) as MemorySegment
        check(texture.address() != 0L) { "MTLDevice newTextureWithDescriptor failed (${width}x$height)" }
        return texture.address()
    }

    fun retain(texture: Long): Long {
        sendVoid.invoke(MemorySegment.ofAddress(texture), selRetain)
        return texture
    }

    fun release(texture: Long) {
        sendVoid.invoke(MemorySegment.ofAddress(texture), selRelease)
    }

    private fun sel(name: String): MemorySegment =
        Arena.ofConfined().use { registerSel.invoke(it.allocateFrom(name)) as MemorySegment }

    private fun downcall(symbol: MemorySegment, descriptor: FunctionDescriptor) =
        linker.downcallHandle(symbol, descriptor)
}
