package io.github.erkko68.filament.compose.internal

import androidx.compose.ui.unit.IntSize
import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.RenderTarget
import io.github.erkko68.filament.Texture

/**
 * A Filament render target whose color attachment is a host GPU texture ([nativeTexture]: an
 * `id<MTLTexture>` or a GL texture name, per backend) imported as RGBA8, plus Filament's own
 * depth buffer. Shared by the Nucleus GPU surfaces; destroying it leaves [nativeTexture] alone.
 */
internal class ImportedRenderTarget(engine: Engine, nativeTexture: Long, size: IntSize) {
    private val color: Texture = Texture.Builder()
        .width(size.width).height(size.height).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
        .import(nativeTexture)
        .build(engine)
    private val depth: Texture = Texture.Builder()
        .width(size.width).height(size.height).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.DEPTH24)
        .usage(Texture.Usage.DEPTH_ATTACHMENT)
        .build(engine)
    val renderTarget: RenderTarget = RenderTarget.Builder()
        .texture(RenderTarget.AttachmentPoint.COLOR, color)
        .texture(RenderTarget.AttachmentPoint.DEPTH, depth)
        .build(engine)

    fun destroy(engine: Engine) {
        engine.destroy(renderTarget)
        engine.destroy(color)
        engine.destroy(depth)
    }
}
