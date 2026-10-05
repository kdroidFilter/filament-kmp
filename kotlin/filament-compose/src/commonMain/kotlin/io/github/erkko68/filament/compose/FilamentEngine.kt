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
 *
 * [config] sizes the engine's memory arenas, job threads and caches (the defaults suit a full-screen scene; a few small
 * views get by with much less). Where calls share an engine (a Nucleus GL window), the first call's applies; the
 * Windows engine of Compose Desktop's GPU-to-GPU frame sharing keeps the defaults.
 */
@Composable
fun rememberFilamentEngine(
    backend: Engine.Backend = Engine.Backend.DEFAULT,
    config: Engine.Config? = null,
): Engine = rememberPlatformEngine(backend, config)

/**
 * Like [rememberFilamentEngine], without blocking the UI while the engine is created: null until it is ready, so the
 * caller shows a placeholder meanwhile. The GPU driver is initialized on Filament's own thread where the platform
 * allows it (Nucleus GL windows); elsewhere the engine is created right away, as [rememberFilamentEngine] does.
 * [config] as for [rememberFilamentEngine].
 */
@Composable
fun rememberFilamentEngineAsync(
    backend: Engine.Backend = Engine.Backend.DEFAULT,
    config: Engine.Config? = null,
): Engine? = rememberPlatformEngineAsync(backend, config)
