package io.github.erkko68.filament.compose.internal

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import dev.nucleusframework.window.tao.rememberTextureViewController
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Fence
import io.github.erkko68.filament.InternalFilamentApi
import io.github.erkko68.filament.RenderTarget
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.View
import io.github.erkko68.filament.Viewport
import io.github.erkko68.filament.ffm.FilamentC
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceOrigin
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.util.Collections
import java.util.WeakHashMap

// GPU path for Nucleus windows on Linux and Windows, whose Compose scene draws with GL:
//  - Linux: Nucleus renders with EGL + desktop GL. A source-built, EGL-enabled Filament
//    (scripts/dev/build-host-libs.sh) creates its context on the window's EGLDisplay, shared
//    with the window's context; Filament renders into a GL texture that Skia adopts as is.
//  - Windows: Nucleus renders with ANGLE (GLES on D3D11), which a desktop-GL Filament cannot
//    share with. Filament renders into a GL texture aliasing a shared D3D11 texture
//    (WGL_NV_DX_interop2, see c/filament/cpp/Interop.cpp), which Nucleus's TextureView imports.
// Anything unavailable (upstream GLX libs, no interop extension, …) falls back to readback.

private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA8 = 0x8058

/** How a Nucleus GL window and a Filament engine share textures. */
internal sealed class NucleusGlHost(val context: TaoOpenGlRenderContext) : AutoCloseable {
    /** An engine able to render into this host's textures, or null to fall back. */
    abstract fun createEngine(): Engine?

    class Egl(context: TaoOpenGlRenderContext, private val display: MemorySegment, private val eglContext: MemorySegment) :
        NucleusGlHost(context) {
        override fun createEngine(): Engine? = buildEngine { builder ->
            FilamentC.FilaEngineBuilder_eglDisplay(builder, display)
            FilamentC.FilaEngineBuilder_sharedContext(builder, eglContext)
        }

        override fun close() {}
    }

    class Dx(context: TaoOpenGlRenderContext, val share: MemorySegment) : NucleusGlHost(context) {
        override fun createEngine(): Engine? = buildEngine { builder ->
            FilamentC.FilaEngineBuilder_backend(builder, FilamentC.FILA_ENGINE_BACKEND_OPENGL())
            FilamentC.FilaEngineBuilder_sharedContext(builder, FilamentC.FilaDxShare_glContext(share))
        }

        override fun close() = FilamentC.FilaDxShare_destroy(share)
    }

    @OptIn(InternalFilamentApi::class)
    protected fun buildEngine(configure: (MemorySegment) -> Unit): Engine? {
        val builder = FilamentC.FilaEngineBuilder_create()
        configure(builder)
        val handle = FilamentC.FilaEngineBuilder_build(builder)
        FilamentC.FilaEngineBuilder_destroy(builder)
        return if (handle.address() == 0L) null else Engine(handle)
    }
}

/** Engines created on a [NucleusGlHost], so their surfaces know to render there. */
internal object NucleusGl {
    private val hosts = Collections.synchronizedMap(WeakHashMap<Engine, NucleusGlHost>())
    fun bind(engine: Engine, host: NucleusGlHost) { hosts[engine] = host }
    fun unbind(engine: Engine) { hosts.remove(engine) }
    fun hostOf(engine: Engine): NucleusGlHost? = hosts[engine]
}

/** The window's GL sharing setup when this composable sits in a Nucleus GL surface, else null. */
@Composable
internal fun rememberNucleusGlHost(): NucleusGlHost? {
    val context = rememberTaoGpuRenderContext() as? TaoOpenGlRenderContext ?: return null
    val host = remember(context) { runCatching { createHost(context) }.getOrNull() }
    DisposableEffect(host) {
        onDispose { host?.close() }
    }
    return host
}

private fun createHost(context: TaoOpenGlRenderContext): NucleusGlHost? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") ->
            FilamentC.FilaDxShare_create().takeIf { it.address() != 0L }?.let { NucleusGlHost.Dx(context, it) }
        os.contains("linux") && FilamentC.FilaInterop_hasEglPlatform() ->
            context.withContextCurrent { Egl.currentDisplay() to Egl.currentContext() }
                ?.takeIf { (d, c) -> d.address() != 0L && c.address() != 0L }
                ?.let { (d, c) -> NucleusGlHost.Egl(context, d, c) }
        else -> null
    }
}

/**
 * Filament → shared GL texture → the Nucleus window, ping-ponging two targets like the Metal
 * surface: a target is only shown once its Filament fence (glFenceSync, i.e. GPU completion)
 * has fired, and Filament only ever renders into the one not on screen.
 */
@Composable
internal fun NucleusGlFilamentSurface(
    modifier: Modifier,
    engine: Engine,
    renderer: Renderer,
    view: View,
    host: NucleusGlHost,
    onResize: (aspect: Double) -> Unit,
) {
    var pxSize by remember { mutableStateOf(IntSize.Zero) }
    val controller = rememberTextureViewController()
    val onResizeRef = remember { Ref<(Double) -> Unit>() }
    SideEffect { onResizeRef.value = onResize }

    // ponytail: reallocates on every size change; debounce like the readback path if resizes stutter.
    val targets = remember(engine, host, pxSize) {
        if (pxSize.width > 0 && pxSize.height > 0) {
            runCatching { List(2) { GlTarget.create(engine, host, pxSize) } }.getOrNull()?.filterNotNull()
                ?.takeIf { it.size == 2 } ?: emptyList()
        } else {
            emptyList()
        }
    }
    var shown by remember(targets) { mutableStateOf<GlTarget?>(null) }
    val inFlight = remember(targets) { Ref<Pair<GlTarget, Fence>>() }

    DisposableEffect(targets) {
        if (targets.isNotEmpty()) {
            view.viewport = Viewport(0, 0, pxSize.width, pxSize.height)
            onResizeRef.value?.invoke(pxSize.width.toDouble() / pxSize.height)
        }
        onDispose {
            inFlight.value?.let { (target, fence) ->
                engine.destroyFence(fence)
                target.afterRender()
            }
            inFlight.value = null
            view.renderTarget = null
            engine.flushAndWait()
            targets.forEach { it.destroy(engine) }
        }
    }

    FilamentRenderLoop {
        if (targets.isEmpty()) return@FilamentRenderLoop
        SurfaceStats.measure {
            val pending = inFlight.value
            if (pending != null && pending.second.wait(Fence.Mode.FLUSH, 0) == Fence.FenceStatus.TIMEOUT_EXPIRED) {
                return@measure
            }
            if (pending != null) {
                engine.destroyFence(pending.second)
                inFlight.value = null
                pending.first.afterRender()
                shown = pending.first
                controller.markFrameAvailable()
                SurfaceStats.surface(if (host is NucleusGlHost.Dx) "nucleus-dx" else "nucleus-egl")
                SurfaceStats.frameDelivered()
            }
            val next = targets.first { it !== shown }
            if (!next.beforeRender()) return@measure
            view.renderTarget = next.renderTarget
            renderer.renderStandaloneView(view)
            inFlight.value = next to engine.createFence()
            engine.flush()
        }
    }

    val sized = modifier.onSizeChanged { pxSize = it }
    when (host) {
        is NucleusGlHost.Dx -> TextureView(
            source = shown?.source,
            // GL writes rows bottom-up into memory D3D reads top-down.
            modifier = sized.graphicsLayer { scaleY = -1f },
            controller = controller,
        )
        is NucleusGlHost.Egl -> Spacer(
            sized.drawBehind {
                val image = shown?.image ?: return@drawBehind
                drawIntoCanvas {
                    it.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeWH(size.width, size.height),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
            },
        )
    }
}

/** One shared GL texture imported into Filament, plus how the host displays it. */
private class GlTarget private constructor(
    private val host: NucleusGlHost,
    private val glName: Int,
    private val dxTexture: MemorySegment?,
    val size: IntSize,
    engine: Engine,
) {
    private val target = ImportedRenderTarget(engine, glName.toLong(), size)
    val renderTarget: RenderTarget get() = target.renderTarget
    val source: TextureViewSource? = dxTexture?.let {
        nucleusD3D11SharedTextureSource(FilamentC.FilaDxTexture_sharedHandle(it).address(), size.width, size.height)
    }

    /** Linux: Skia's view of the texture, adopted once it first holds a frame. */
    var image: Image? = null
        private set

    /** Windows: GL may only write while the D3D11 texture is locked for it. */
    fun beforeRender(): Boolean = dxTexture == null || FilamentC.FilaDxTexture_lock(dxTexture)

    fun afterRender() {
        if (dxTexture != null) {
            FilamentC.FilaDxTexture_unlock(dxTexture)
        } else if (image == null) {
            image = host.context.withContextCurrent {
                Image.adoptTextureFrom(
                    host.context.skiaContext,
                    BackendTexture.makeGL(size.width, size.height, false, glName, GL_TEXTURE_2D, GL_RGBA8),
                    SurfaceOrigin.BOTTOM_LEFT,
                    ColorType.RGBA_8888,
                )
            }
        }
    }

    fun destroy(engine: Engine) {
        target.destroy(engine)
        when {
            dxTexture != null -> FilamentC.FilaDxTexture_destroy(dxTexture)
            // Skia owns an adopted texture and deletes it with the image.
            else -> host.context.withContextCurrent { image?.close() ?: FilamentC.FilaGl_deleteTexture(glName) }
        }
    }

    companion object {
        fun create(engine: Engine, host: NucleusGlHost, size: IntSize): GlTarget? = when (host) {
            is NucleusGlHost.Dx -> {
                val dx = FilamentC.FilaDxTexture_create(host.share, size.width, size.height)
                if (dx.address() == 0L) null
                else GlTarget(host, FilamentC.FilaDxTexture_glName(dx), dx, size, engine)
            }
            is NucleusGlHost.Egl -> host.context.withContextCurrent {
                FilamentC.FilaGl_createTexture(size.width, size.height).also { FilamentC.FilaGl_flush() }
            }?.takeIf { it != 0 }?.let { GlTarget(host, it, null, size, engine) }
        }
    }
}

/** The two EGL entry points needed to find the host's display and context, via FFM. */
private object Egl {
    private val lib = SymbolLookup.libraryLookup("libEGL.so.1", Arena.global())
    private val getDisplay = Linker.nativeLinker().downcallHandle(
        lib.find("eglGetCurrentDisplay").orElseThrow(), FunctionDescriptor.of(ADDRESS),
    )
    private val getContext = Linker.nativeLinker().downcallHandle(
        lib.find("eglGetCurrentContext").orElseThrow(), FunctionDescriptor.of(ADDRESS),
    )

    fun currentDisplay(): MemorySegment = getDisplay.invoke() as MemorySegment
    fun currentContext(): MemorySegment = getContext.invoke() as MemorySegment
}
