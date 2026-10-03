# `kotlin/` — Filament KMP modules

This directory contains the Kotlin Multiplatform modules published to Maven Central. Each subdirectory is an independent Gradle module.

| Module | Artifact | Description |
| :--- | :--- | :--- |
| [`filament/`](filament) | `dev.nucleusframework.filament:filament` | Core renderer wrapper. |
| [`filament-compose/`](filament-compose) | `dev.nucleusframework.filament:filament-compose` | Compose Multiplatform integration. |
| [`gltfio/`](gltfio) | `dev.nucleusframework.filament:gltfio` | glTF / GLB asset loading. |
| [`filamat/`](filamat) | `dev.nucleusframework.filament:filamat` | Runtime material compilation. |
| [`filament-utils/`](filament-utils) | `dev.nucleusframework.filament:filament-utils` | Math, manipulators, HDR/KTX loaders. |

See [`docs/guide/modules.md`](../docs/guide/modules.md) for the full coordinates list, dependency graph, and per-module usage notes.

## Targets

Each module publishes the following Kotlin Multiplatform targets:

- `android`
- `iosArm64`, `iosSimulatorArm64`
- `jvm` (Desktop — Windows, Linux, macOS)
- `js`, `wasmJs` (Web)

## Building locally

From the repository root:

```bash
./gradlew :kotlin:filament-compose:build
./gradlew :kotlin:gltfio:build
# …or build everything:
./gradlew build
```

The first build downloads Filament prebuilts via the `prebuilts_<id>` Gradle tasks — expect a few minutes.

## Contributing

See [`docs/internals/repo-structure.md`](../docs/internals/repo-structure.md) for how the Kotlin modules tie into the C wrapper (`c/`) and the native runtimes (`jni/`, `desktop/`, `android/`, `web/`).
