package eric.bitria.samples

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication

fun main() {
    val bench = Bench.fromEnv()?.also { it.start() }
    singleWindowApplication(
        title = "Filament KMP Sample - Desktop",
        state = WindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        App(bench?.screen ?: Screen.Home)
    }
}
