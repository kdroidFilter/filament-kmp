package io.github.erkko68.filament.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament
import io.github.erkko68.filament.compose.internal.NucleusGl
import io.github.erkko68.filament.compose.internal.nucleusGpuEnabled
import io.github.erkko68.filament.compose.internal.rememberNucleusGlHost

/**
 * Inside a Nucleus window on Linux/Windows, the engine is created on (a context shared with)
 * the window's GL context so its surfaces can render there directly; see NucleusGlSurface.
 * An explicitly requested non-GL backend keeps the plain engine.
 */
@Composable
internal actual fun rememberPlatformEngine(backend: Engine.Backend): Engine {
    val host = if (nucleusGpuEnabled && (backend == Engine.Backend.DEFAULT || backend == Engine.Backend.OPENGL)) {
        rememberNucleusGlHost()
    } else {
        null
    }
    if (host == null) return rememberDefaultEngine(backend)

    val engine = remember(host) {
        Filament.init()
        host.createEngine()?.also { NucleusGl.bind(it, host) } ?: Engine.create(backend)
    }
    DisposableEffect(engine) {
        onDispose {
            NucleusGl.unbind(engine)
            engine.destroy()
        }
    }
    return engine
}
