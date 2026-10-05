package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import io.github.erkko68.filament.Engine

/**
 * Creates and remembers an [Engine] that can present into this platform's [FilamentSurface], [Owned] so it's
 * destroyed only after everything created from it.
 */
@Composable
internal expect fun rememberPlatformEngine(backend: Engine.Backend, config: Engine.Config?): Engine

/** [rememberPlatformEngine], null until the engine is ready where the platform can create it off the UI thread. */
@Composable
internal expect fun rememberPlatformEngineAsync(backend: Engine.Backend, config: Engine.Config?): Engine?
