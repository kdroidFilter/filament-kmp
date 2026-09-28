package eric.bitria.samples.nucleus

import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.TitleBar
import eric.bitria.samples.App
import eric.bitria.samples.Bench
import eric.bitria.samples.Screen

fun main() {
    val bench = Bench.fromEnv()?.also { it.start() }
    nucleusApplication {
        DecoratedWindow(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
            title = "Filament KMP Sample - Nucleus",
        ) {
            TitleBar { Text("Filament KMP Sample - Nucleus", Modifier.align(Alignment.CenterHorizontally)) }
            App(bench?.screen ?: Screen.Home)
        }
    }
}
