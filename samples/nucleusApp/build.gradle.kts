plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Same app as desktopApp, hosted in a Nucleus (Tao backend) window: filament-compose then renders
// on the window's GPU (see FilamentSurface.jvm.kt) instead of reading frames back through the CPU.
kotlin {
    jvm {}

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.nucleus.application)
        }
    }
}

compose.desktop {
    application {
        mainClass = "eric.bitria.samples.nucleus.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
