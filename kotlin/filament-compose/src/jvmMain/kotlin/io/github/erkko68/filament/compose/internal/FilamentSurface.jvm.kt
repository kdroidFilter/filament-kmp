package io.github.erkko68.filament.compose.internal

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.Ref
import androidx.compose.ui.unit.IntSize
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Renderer
import io.github.erkko68.filament.View
import io.github.erkko68.filament.Viewport
import io.github.erkko68.filament.compose.internal.target.OffscreenTarget
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

private const val RESIZE_DEBOUNCE_MS = 150L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun FilamentSurface(
    modifier: Modifier,
    engine: Engine,
    renderer: Renderer,
    view: View,
    transparent: Boolean,
    renderingEnabled: Boolean,
    onResize: (aspect: Double) -> Unit,
) {
    // Inside a Nucleus (Tao) window, render on the window's GPU instead of reading back.
    if (nucleusGpuEnabled) {
        val glHost = NucleusGl.hostOf(engine)
        if (glHost != null) {
            NucleusGlFilamentSurface(modifier, engine, renderer, view, glHost, renderingEnabled, onResize)
            return
        }
        if (engine.backend == Engine.Backend.METAL) {
            val metalDevice = rememberNucleusMetalDevice()
            if (metalDevice != 0L) {
                NucleusMetalFilamentSurface(modifier, engine, renderer, view, metalDevice, renderingEnabled, onResize)
                return
            }
        }
    }

    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var textureSize by remember { mutableStateOf(IntSize.Zero) }
    var displayedImage by remember { mutableStateOf<Image?>(null) }
    // Whether displayedImage's rows run bottom-up; set with it, as it may outlive its target.
    var displayedBottomUp by remember { mutableStateOf(false) }
    // The replaced frame stays alive one more frame, until Compose has replayed its last draw.
    val previousImage = remember { Ref<Image>() }
    var target by remember { mutableStateOf<OffscreenTarget?>(null) }
    val window = LocalAwtWindow.current

    // Keep a mutable ref so DisposableEffect(textureSize) always dispatches to the latest lambda.
    val onResizeRef = remember { Ref<(Double) -> Unit>() }
    SideEffect { onResizeRef.value = onResize }

    DisposableEffect(Unit) {
        onDispose {
            displayedImage?.close()
            displayedImage = null
            previousImage.value?.close()
            previousImage.value = null
        }
    }

    LaunchedEffect(layoutSize) {
        val w = layoutSize.width
        val h = layoutSize.height
        if (w <= 0 || h <= 0) return@LaunchedEffect
        if (textureSize.width <= 0) {
            textureSize = IntSize(w, h)
        } else {
            delay(RESIZE_DEBOUNCE_MS)
            textureSize = IntSize(w, h)
        }
    }

    DisposableEffect(textureSize, transparent) {
        val w = textureSize.width
        val h = textureSize.height

        if (w > 0 && h > 0) {
            view.viewport = Viewport(0, 0, w, h)
            onResizeRef.value?.invoke(w.toDouble() / h.toDouble())
            target = OffscreenTarget(engine, window, w, h, transparent)
        }

        onDispose {
            // The displayed image is a GPU copy, so it keeps showing through the resize.
            target?.close()
            target = null
        }
    }

    FilamentRenderLoop(renderingEnabled) { frameTime ->
        val current = target ?: return@FilamentRenderLoop
        if (!SurfaceStats.frameDue(frameTime)) return@FilamentRenderLoop
        var image: Image? = null
        SurfaceStats.measure { image = current.renderFrame(renderer, view, frameTime) }
        val frame = image ?: return@FilamentRenderLoop
        SurfaceStats.surface("readback")
        SurfaceStats.frameDelivered()
        previousImage.value?.close()
        previousImage.value = displayedImage
        displayedImage = frame
        displayedBottomUp = current.bottomUp
    }

    Spacer(
        modifier = modifier
            .onSizeChanged { layoutSize = it }
            .drawBehind {
                val image = displayedImage ?: return@drawBehind
                drawIntoCanvas { canvas ->
                    val skia = canvas.skiaCanvas
                    skia.save()
                    if (displayedBottomUp) {
                        skia.translate(0f, size.height)
                        skia.scale(1f, -1f)
                    }
                    skia.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeWH(size.width, size.height),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                    skia.restore()
                }
            }
    )
}
