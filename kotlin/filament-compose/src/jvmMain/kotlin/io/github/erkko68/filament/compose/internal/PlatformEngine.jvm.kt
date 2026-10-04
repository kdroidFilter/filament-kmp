package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament
import io.github.erkko68.filament.InternalFilamentApi
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
            val lease = remember(key) { SharedEngines.Lease(key) { it.complete(nucleusGlEngine(context, backend)) } }
            return checkNotNull(lease.entry.engine) { "the shared $backend engine was released" }
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
 * In a Nucleus GL window, the engine's driver is initialized on Filament's own thread (Engine.Builder.build with a
 * callback), so the UI keeps running meanwhile; the window's GL share is still set up here, as reading the window's
 * EGLDisplay needs its context current. Shared like [rememberPlatformEngine]'s, but apart from them: an engine still
 * being created can't be handed to a caller that needs one at once. Elsewhere, created right away.
 */
@Composable
internal actual fun rememberPlatformEngineAsync(backend: Engine.Backend): Engine? {
    if (nucleusGpuEnabled && (backend == Engine.Backend.DEFAULT || backend == Engine.Backend.OPENGL)) {
        val context = rememberTaoGpuRenderContext() as? TaoOpenGlRenderContext
        if (context != null) {
            val key = AsyncKey(context, backend)
            return remember(key) { SharedEngines.Lease(key) { nucleusGlEngineAsync(context, backend, it) } }.entry.engine
        }
    }
    return rememberPlatformEngine(backend)
}

private data class AsyncKey(val context: TaoOpenGlRenderContext, val backend: Engine.Backend)

/**
 * One engine per Nucleus GPU context and backend, for every view in that window: creating and destroying an engine
 * costs tens of milliseconds on the UI thread, which a screen bringing back its 3D views (a tab switch) paid once per
 * view. The engine outlives its last user by [KEEP_ALIVE_MS], so views coming back in another composition a few
 * frames later find it; it is then released like any [Owned], after the objects created on it. Composition thread
 * only.
 */
private object SharedEngines {
    private const val KEEP_ALIVE_MS = 10_000L

    /** A shared engine, [engine] null while it is still being created. */
    class Entry {
        var engine: Engine? by mutableStateOf(null)
            private set
        private var owned: Owned<Engine>? = null
        private var dropped = false
        var users = 0
        var release: Job? = null

        fun complete(owned: Owned<Engine>) {
            // Let go of before it was ready: nobody will use it.
            if (dropped) return owned.release()
            this.owned = owned
            engine = owned.value
        }

        fun drop() {
            dropped = true
            engine = null
            owned?.release()
            owned = null
        }
    }

    private val engines = HashMap<Any, Entry>()

    /** The composition thread, to come back on; null without a main dispatcher. */
    val scope by lazy { runCatching { CoroutineScope(SupervisorJob() + Dispatchers.Main) }.getOrNull() }

    /** A call site's hold on the shared engine, for as long as it stays in the composition. */
    class Lease(private val key: Any, start: (Entry) -> Unit) : RememberObserver {
        val entry: Entry = acquire(key, start)
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

    private fun acquire(key: Any, start: (Entry) -> Unit): Entry {
        val entry = engines.getOrPut(key) { Entry().also(start) }
        entry.release?.cancel()
        entry.release = null
        entry.users++
        return entry
    }

    private fun release(key: Any) {
        val entry = engines[key] ?: return
        if (--entry.users > 0) return
        val drop = {
            engines.remove(key)
            entry.drop()
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
    return owned(host, host?.createEngine(), backend)
}

/** [nucleusGlEngine], its driver initialized on Filament's thread; [entry] completes on the composition thread. */
@OptIn(InternalFilamentApi::class)
private fun nucleusGlEngineAsync(context: TaoOpenGlRenderContext, backend: Engine.Backend, entry: SharedEngines.Entry) {
    Filament.init()
    val host = createNucleusGlHost(context)
    val scope = SharedEngines.scope
    if (host == null || scope == null) return entry.complete(owned(host, host?.createEngine(), backend))
    val builder = Engine.Builder()
    FilaEngineBuilder_gpuShare(builder.nativeObject, host.share)
    builder.build { token ->
        // getEngine must run on the thread that started the build.
        scope.launch { entry.complete(owned(host, Engine.getEngine(token), backend)) }
    }
}

/** [engine] (bound to [host]), or a plain one when there is none; the window's GL share goes with it, not before. */
private fun owned(host: NucleusGlHost?, engine: Engine?, backend: Engine.Backend): Owned<Engine> {
    val bound = engine?.also { NucleusGl.bind(it, host!!) } ?: run {
        if (host != null) logWarn("no engine on the Nucleus GL share, falling back to a $backend engine")
        checkNotNull(Engine.create(backend)) { "Failed to create a $backend Engine" }
    }
    return Owned(bound, emptyList()) {
        NucleusGl.unbind(it)
        Engine.destroy(it)
        host?.close()
    }
}
