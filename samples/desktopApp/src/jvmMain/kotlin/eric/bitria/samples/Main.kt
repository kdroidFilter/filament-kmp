package eric.bitria.samples

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import io.github.erkko68.filament.compose.ExperimentalGpuToGpuFrameSharing
import io.github.erkko68.filament.compose.FilamentComposeDesktop

@OptIn(ExperimentalGpuToGpuFrameSharing::class)
fun main() {
    FilamentComposeDesktop.isGpuToGpuFrameSharingEnabled = true
    // Compose picks its GPU when the first window opens, and Filament renders on the same one.
    // Prefer the discrete GPU on hybrid laptops; -Dskiko.gpu.priority=auto|integrated overrides it.
    if (System.getProperty("skiko.gpu.priority") == null) {
        System.setProperty("skiko.gpu.priority", "discrete")
    }
    val bench = Bench.fromEnv()?.also { it.start() }
    singleWindowApplication(
        title = "Filament KMP Sample - Desktop",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App(bench?.screen ?: Screen.Home)
    }
}
