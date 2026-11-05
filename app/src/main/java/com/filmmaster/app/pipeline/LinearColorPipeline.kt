package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import com.filmmaster.app.color.ColorMath
import com.filmmaster.app.gl.LutGlProcessor
import com.filmmaster.app.lut.Lut3D

/**
 * M2 线性高位深成片管线。
 *
 * 顺序：sRGB 解码 → 线性化 → 参数化胶片曲线（线性光）→ 编码回 sRGB → 创意 3D LUT
 * → 颗粒 / 暗角（GPU，见 [LutGlProcessor]）。
 *
 * 曲线在 float 线性空间完成，避免 M1 直接在 8bit sRGB 上压曲线的断层；
 * 3D LUT 保持“显示参考”语义，放在曲线之后，与现有 .cube 资源兼容。
 */
class LinearColorPipeline(
    private val lut: Lut3D,
    private val strength: Float,
    private val curve: ToneCurve = ToneCurve.NEUTRAL,
    private val grain: Float = 0f,
    private val vignette: Float = 0f,
    private val seed: Int = 0,
    private val processor: LutGlProcessor = LutGlProcessor()
) : ColorPipeline {

    override fun process(src: Bitmap): Bitmap {
        val curved = if (curve.isIdentity) src else applyCurve(src, curve)
        val out = processor.process(curved, lut, strength, grain, vignette, seed)
        if (curved !== src && !curved.isRecycled) curved.recycle()
        return out
    }

    private fun applyCurve(src: Bitmap, curve: ToneCurve): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val table = curve.bake(TABLE_SIZE)
        val out = IntArray(pixels.size)
        val last = TABLE_SIZE - 1

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ColorMath.srgbToLinear(((p shr 16) and 0xFF) / 255f)
            val g = ColorMath.srgbToLinear(((p shr 8) and 0xFF) / 255f)
            val b = ColorMath.srgbToLinear((p and 0xFF) / 255f)

            val cr = ColorMath.linearToSrgb(table[(r * last).toInt().coerceIn(0, last)])
            val cg = ColorMath.linearToSrgb(table[(g * last).toInt().coerceIn(0, last)])
            val cb = ColorMath.linearToSrgb(table[(b * last).toInt().coerceIn(0, last)])

            out[i] = (p and 0xFF000000.toInt()) or
                ((cr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((cg * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (cb * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TABLE_SIZE = 1024
    }
}
