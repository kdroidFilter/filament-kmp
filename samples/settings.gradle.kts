rootProject.name = "Samples"

// This block includes the main `filament-kmp` build, allowing the samples
// to use the local source code of the modules directly instead of fetching
// them from a repository.
//
// To run the samples as a standalone project:
// 1. Remove this `includeBuild` block.
// 2. The project will then resolve Filament dependencies from Maven Central.
//    Ensure the desired version is specified in your `gradle/libs.versions.toml`.
// 3. For the `webApp` sample, download `filament-kmp.{js,wasm}` and `filamat-kmp.{js,wasm}` from
//    the matching GitHub release into webApp/src/webMain/resources, and drop the umbrella
//    `resources.srcDir`/`dependsOn` lines from webApp/build.gradle.kts.
includeBuild("../") {
    name = "filament-umbrella"
    dependencySubstitution {
        substitute(module("dev.nucleusframework.filament:filament")).using(project(":kotlin:filament"))
        substitute(module("dev.nucleusframework.filament:filamat")).using(project(":kotlin:filamat"))
        substitute(module("dev.nucleusframework.filament:gltfio")).using(project(":kotlin:gltfio"))
        substitute(module("dev.nucleusframework.filament:filament-utils")).using(project(":kotlin:filament-utils"))
        substitute(module("dev.nucleusframework.filament:filament-compose")).using(project(":kotlin:filament-compose"))
    }
}

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
