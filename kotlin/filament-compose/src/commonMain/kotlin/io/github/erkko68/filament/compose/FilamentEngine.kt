package io.github.erkko68.filament.compose

import androidx.compose.runtime.Composable
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.compose.internal.rememberPlatformEngine
import io.github.erkko68.filament.compose.internal.rememberPlatformEngineAsync

/**
 * Creates and remembers an [Engine] for the lifetime of the composition.
 *
 * Hoist one call and pass the value around to share an engine across scenes, views, and loaders
 * ([rememberFilamentScene], `rememberGltfAsset`, `rememberKTXEnvironment`, …). If you don't pass an engine,
 * [rememberFilamentScene] and [FilamentSceneView] create a dedicated one scoped to that call site. In a Nucleus GL
 * window, the calls of a window share one engine.
 *
 * Creating an engine initializes its GPU driver, tens of milliseconds on the calling thread; see
 * [rememberFilamentEngineAsync] to keep that off the UI.
 */
@Composable
fun rememberFilamentEngine(backend: Engine.Backend = Engine.Backend.DEFAULT): Engine =
    rememberPlatformEngine(backend)

/**
 * Like [rememberFilamentEngine], without blocking the UI while the engine is created: null until it is ready, so the
 * caller shows a placeholder meanwhile. The GPU driver is initialized on Filament's own thread where the platform
 * allows it (Nucleus GL windows); elsewhere the engine is created right away, as [rememberFilamentEngine] does.
 */
@Composable
fun rememberFilamentEngineAsync(backend: Engine.Backend = Engine.Backend.DEFAULT): Engine? =
    rememberPlatformEngineAsync(backend)
