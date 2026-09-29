package io.github.erkko68.filament.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament

/**
 * Creates and remembers an [Engine] for the lifetime of the composition.
 *
 * Each call creates its own engine — hoist one call and pass the value around to share an
 * engine across scenes, views, and loaders ([rememberFilamentScene], `rememberGltfAsset`,
 * `rememberKTXEnvironment`, …). If you don't pass an engine, [rememberFilamentScene] and
 * [FilamentSceneView] create a dedicated one scoped to that call site.
 */
@Composable
fun rememberFilamentEngine(backend: Engine.Backend = Engine.Backend.DEFAULT): Engine =
    rememberPlatformEngine(backend)

/** Platform hook behind [rememberFilamentEngine]; the JVM one can bind a host GPU context. */
@Composable
internal expect fun rememberPlatformEngine(backend: Engine.Backend): Engine

/** A plain engine for [backend], destroyed with the composition. */
@Composable
internal fun rememberDefaultEngine(backend: Engine.Backend): Engine {
    val engine = remember(backend) { Filament.init(); Engine.create(backend) }
    DisposableEffect(engine) {
        onDispose { EngineLifetimes.destroyWhenUnused(engine) }
    }
    return engine
}
