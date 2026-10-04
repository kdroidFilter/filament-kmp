package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament
import io.github.erkko68.filament.compose.internal.target.DesktopOs
import io.github.erkko68.filament.compose.internal.target.GpuFrameSharing
import io.github.erkko68.filament.compose.internal.target.d3d.D3DEngines
import io.github.erkko68.filament.compose.internal.target.glx.GlxEngines
import io.github.erkko68.filament.compose.internal.target.unavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inside a Nucleus window on Linux/Windows, the engine is created on (a context shared with) the window's GL
 * context so its surfaces render there directly; see NucleusGlSurface. An explicit non-GL backend skips it.
 *
 * Otherwise, with GPU-to-GPU frame sharing on, the engine is built to reach the window's GPU context: on Linux
 * it shares skiko's GL context, on Windows it runs on skiko's D3D12 GPU. Otherwise (or if that
 * fails) it's a plain engine, and frames go through CPU readback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun rememberPlatformEngine(backend: Engine.Backend): Engine {
    if (nucleusGpuEnabled && (backend == Engine.Backend.DEFAULT || backend == Engine.Backend.OPENGL)) {
        val context = rememberTaoGpuRenderContext() as? TaoOpenGlRenderContext
        if (context != null) {
            // One context instance per surface (Nucleus' identity-stable contract), so one engine per surface.
            val key = context to backend
            return remember(key) { SharedEngines.Lease(key) { nucleusGlEngine(context, backend) } }.engine
        }
    }

    val window = LocalAwtWindow.current
    return remember(backend) {
        Filament.init()
        val shared = if (!GpuFrameSharing.enabled) null else {
            GpuFrameSharing.guard("creating the Filament engine", window, null, {
                when (DesktopOs.current) {
                    // skiko's MTLTextures are sampleable by any engine on the same (default) GPU.
                    DesktopOs.MACOS -> checkNotNull(Engine.create(backend)) { "Failed to create a $backend Engine" }
                    DesktopOs.WINDOWS -> D3DEngines.create(backend, window)
                    DesktopOs.LINUX -> GlxEngines.create(backend, window)
                    DesktopOs.OTHER -> unavailable("no GPU-to-GPU path on ${System.getProperty("os.name")}")
                }
            }, { null })
        }
        // D3DEngines.destroy also frees the Windows engine's platform, which Filament doesn't own.
        Owned(shared?.also(GpuFrameSharing::optIn) ?: checkNotNull(Engine.create(backend)) { "Failed to create a $backend Engine" }, emptyList(), D3DEngines::destroy)
    }.value
}

/**
 * One engine per Nucleus GPU context and backend, for every view in that window: creating and destroying an engine
 * costs tens of milliseconds on the UI thread, which a screen bringing back its 3D views (a tab switch) paid once per
 * view. The engine outlives its last user by [KEEP_ALIVE_MS], so views coming back in another composition a few
 * frames later find it; it is then released like any [Owned], after the objects created on it. Composition thread
 * only.
 */
private object SharedEngines {
    private const val KEEP_ALIVE_MS = 10_000L

    private class Entry(val owned: Owned<Engine>) {
        var users = 0
        var release: Job? = null
    }

    private val engines = HashMap<Any, Entry>()
    private val scope by lazy { runCatching { CoroutineScope(SupervisorJob() + Dispatchers.Main) }.getOrNull() }

    /** A call site's hold on the shared engine, for as long as it stays in the composition. */
    class Lease(private val key: Any, create: () -> Owned<Engine>) : RememberObserver {
        val engine: Engine = acquire(key, create)
        private var released = false

        override fun onRemembered() {}

        override fun onForgotten() = release()

        override fun onAbandoned() = release()

        private fun release() {
            if (released) return
            released = true
            release(key)
        }
    }

    private fun acquire(key: Any, create: () -> Owned<Engine>): Engine {
        val entry = engines.getOrPut(key) { Entry(create()) }
        entry.release?.cancel()
        entry.release = null
        entry.users++
        return entry.owned.value
    }

    private fun release(key: Any) {
        val entry = engines[key] ?: return
        if (--entry.users > 0) return
        val drop = {
            engines.remove(key)
            entry.owned.release()
        }
        // Without a main dispatcher to come back on, it goes at once, as an unshared engine would.
        entry.release = scope?.launch {
            delay(KEEP_ALIVE_MS)
            drop()
        } ?: run {
            drop()
            null
        }
    }
}

/** An engine on (a context shared with) the window's GL context, or a plain one when that is unavailable. */
private fun nucleusGlEngine(context: TaoOpenGlRenderContext, backend: Engine.Backend): Owned<Engine> {
    Filament.init()
    val host = createNucleusGlHost(context)
    val engine = host?.createEngine()?.also { NucleusGl.bind(it, host) } ?: run {
        if (host != null) logWarn("no engine on the Nucleus GL share, falling back to a $backend engine")
        checkNotNull(Engine.create(backend)) { "Failed to create a $backend Engine" }
    }
    // The window's GL share goes with the engine created on it, not before.
    return Owned(engine, emptyList()) {
        NucleusGl.unbind(it)
        Engine.destroy(it)
        host?.close()
    }
}
