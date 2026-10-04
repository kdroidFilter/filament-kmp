package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TextureViewController
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.nucleusEglImageTextureSource
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament
import io.github.erkko68.filament.InternalFilamentApi
import io.github.erkko68.filament.RenderTarget
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.View
import io.github.erkko68.filament.Viewport
import io.github.erkko68.filament.interop.NativePointer
import java.util.Collections
import java.util.WeakHashMap

// GPU path for Nucleus windows on Linux and Windows, whose Compose scene draws with GL (desktop
// GL on EGL for Linux, ANGLE's GLES on D3D11 for Windows). Filament cannot share that context
// directly, so c/filament/interop/FilaInterop.cpp gives it one it can share (FilaGpuShare) and textures
// the window imports without a copy (FilaGpuTexture):
//  - Linux: a GLES context on the window's EGLDisplay; textures go to Nucleus as EGLImages.
//    Needs the EGL build of Filament (the Linux source recipe in build-logic).
//  - Windows: a WGL context; textures alias D3D11 textures (WGL_NV_DX_interop2) that Nucleus
//    opens by their DXGI shared handle.
// Anything unavailable falls back to the readback path.

/** A Nucleus GL window's texture-sharing setup for Filament. */
internal class NucleusGlHost(val share: NativePointer, val eglImages: Boolean) : AutoCloseable {

    @OptIn(InternalFilamentApi::class)
    fun createEngine(): Engine? {
        val builder = FilaEngineBuilder_create()
        FilaEngineBuilder_gpuShare(builder, share)
        val handle = FilaEngineBuilder_build(builder)
        FilaEngineBuilder_destroy(builder)
        return if (handle == 0L) null else Engine(handle)
    }

    fun source(texture: NativePointer, size: IntSize): TextureViewSource {
        val handle = FilaGpuTexture_handle(texture)
        return if (eglImages) nucleusEglImageTextureSource(handle, size.width, size.height)
        else nucleusD3D11SharedTextureSource(handle, size.width, size.height)
    }

    override fun close() = FilaGpuShare_destroy(share)
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
    // Closed by rememberPlatformEngine, with the engine created on it.
    return remember(context) {
        runCatching { createHost(context) }
            .onFailure { logWarn("Nucleus GL share unavailable, falling back to readback: $it") }
            .getOrNull()
    }
}

private fun createHost(context: TaoOpenGlRenderContext): NucleusGlHost? {
    // The Fila* externals below live in the native library, which the engine has not loaded yet.
    Filament.init()
    val linux = System.getProperty("os.name").lowercase().contains("linux")
    // Linux shares the window's EGLDisplay, only readable while its context is current.
    val display = if (linux) context.withContextCurrent { FilaGpuShare_currentEglDisplay() } ?: 0L else 0L
    if (linux && display == 0L) {
        logWarn("Nucleus GL share unavailable: the window's EGLDisplay could not be read")
        return null
    }
    // Logs its own reason when it fails.
    val share = FilaGpuShare_create(display)
    return if (share == 0L) null else NucleusGlHost(share, eglImages = linux)
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
    renderingEnabled: Boolean,
    onResize: (aspect: Double) -> Unit,
) {
    var pxSize by remember { mutableStateOf(IntSize.Zero) }
    val onResizeRef = remember { Ref<(Double) -> Unit>() }
    SideEffect { onResizeRef.value = onResize }

    // ponytail: reallocates on every size change; debounce like the readback path if resizes stutter.
    val targets = remember(engine, host, pxSize) {
        if (pxSize.width > 0 && pxSize.height > 0) {
            runCatching { GlTarget(engine, host, pxSize) }.getOrNull()?.let { first ->
                runCatching { listOf(first, GlTarget(engine, host, pxSize)) }
                    .getOrElse { first.destroy(engine); emptyList() }
            } ?: emptyList()
        } else {
            emptyList()
        }
    }
    DisposableEffect(targets) {
        if (targets.isNotEmpty()) {
            view.viewport = Viewport(0, 0, pxSize.width, pxSize.height)
            onResizeRef.value?.invoke(pxSize.width.toDouble() / pxSize.height)
        }
        onDispose {
            view.renderTarget = null
            engine.flushAndWait()
            targets.forEach { it.destroy(engine) }
        }
    }

    NucleusTextureSurface(
        modifier = modifier.onSizeChanged { pxSize = it },
        engine = engine,
        renderer = renderer,
        view = view,
        targets = targets,
        renderingEnabled = renderingEnabled,
        kind = if (host.eglImages) "nucleus-egl" else "nucleus-dx",
        // Filament's GL rows are bottom-up; both hosts import the texture top-down.
        textureModifier = Modifier.graphicsLayer { scaleY = -1f },
    )
}

/** One shared texture imported into Filament, plus the window's view of it. */
private class GlTarget(engine: Engine, host: NucleusGlHost, size: IntSize) : GpuTarget {
    private val texture: NativePointer = FilaGpuTexture_create(host.share, size.width, size.height)
        .also { check(it != 0L) { "FilaGpuTexture_create failed (${size.width}x${size.height})" } }
    private val target = ImportedRenderTarget(engine, FilaGpuTexture_glName(texture).toUInt().toLong(), size)
    override val renderTarget: RenderTarget get() = target.renderTarget
    override val source: TextureViewSource = host.source(texture, size)
    override val controller = TextureViewController()

    override fun beforeRender(): Boolean = FilaGpuTexture_lock(texture)
    override fun afterRender() { FilaGpuTexture_unlock(texture) }
    override fun hidden() { FilaGpuTexture_release(texture) }

    fun destroy(engine: Engine) {
        target.destroy(engine)
        FilaGpuTexture_destroy(texture)
    }
}
