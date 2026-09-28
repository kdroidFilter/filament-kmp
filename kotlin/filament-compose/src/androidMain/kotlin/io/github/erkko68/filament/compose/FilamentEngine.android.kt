package io.github.erkko68.filament.compose

import androidx.compose.runtime.Composable
import io.github.erkko68.filament.Engine

@Composable
internal actual fun rememberPlatformEngine(backend: Engine.Backend): Engine = rememberDefaultEngine(backend)
