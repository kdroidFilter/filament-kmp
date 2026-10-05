package io.github.erkko68.filament.compose.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Filament

@Composable
internal actual fun rememberPlatformEngine(backend: Engine.Backend, config: Engine.Config?): Engine =
    remember(backend) { Filament.init(); Owned(checkNotNull(Engine.create(backend, config = config)) { "Failed to create a $backend Engine" }, emptyList()) { Engine.destroy(it) } }.value

// Created right away, as rememberPlatformEngine does.
@Composable
internal actual fun rememberPlatformEngineAsync(backend: Engine.Backend, config: Engine.Config?): Engine? =
    rememberPlatformEngine(backend, config)
