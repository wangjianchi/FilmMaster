package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import com.filmmaster.app.color.ColorMath
import com.filmmaster.app.lut.Lut3D
import kotlin.math.sqrt

/**
 * M3 半分辨率预览分级：与成片同一套曲线 / LUT / 颗粒 / 暗角，只是跑在缩小的预览帧上。
 *
 * 预览只有取景参考意义，因此用纯 CPU 路径（避免每帧重建 EGL 上下文），
 * 帧率由 [com.filmmaster.app.camera.SnapshotCameraController] 抽帧控制。
 */
class PreviewGrader(
    private val lut: Lut3D,
    private val strength: Float,
    private val curve: ToneCurve = ToneCurve.NEUTRAL,
    private val grain: Float = 0f,
    private val vignette: Float = 0f
) {

    private val table: FloatArray = curve.bake(1024)
    private val identityCurve = curve.isIdentity

    fun grade(src: Bitmap, maxWidth: Int = PREVIEW_WIDTH): Bitmap {
        val scaled = if (src.width > maxWidth) {
            val ratio = maxWidth.toFloat() / src.width
            Bitmap.createScaledBitmap(
                src,
                maxWidth,
                (src.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            src
        }

        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== src) scaled.recycle()

        if (identityCurve && vignette <= 0f && grain <= 0f) {
            // 仅 LUT 时也走完整路径，避免跳过创意分级
            val graded = lut.applyArgb(pixels, strength)
            return Bitmap.createBitmap(graded, width, height, Bitmap.Config.ARGB_8888)
        }

        val last = table.size - 1
        if (!identityCurve) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = curveMap(((p shr 16) and 0xFF) / 255f, last)
                val g = curveMap(((p shr 8) and 0xFF) / 255f, last)
                val b = curveMap((p and 0xFF) / 255f, last)
                pixels[i] = (p and 0xFF000000.toInt()) or
                    (r shl 16) or (g shl 8) or b
            }
        }

        val graded = lut.applyArgb(pixels, strength)

        val cx = width / 2f
        val cy = height / 2f
        val maxRadius = sqrt(cx * cx + cy * cy)
        for (i in graded.indices) {
            val p = graded[i]
            var r = (p shr 16) and 0xFF
            var g = (p shr 8) and 0xFF
            var b = p and 0xFF

            if (vignette > 0f) {
                val x = (i % width) - cx
                val y = (i / width) - cy
                val d = sqrt(x * x + y * y) / maxRadius
                val fall = 1f - vignette * smoothstep(0.25f, 1.15f, d)
                r = (r * fall).toInt().coerceIn(0, 255)
                g = (g * fall).toInt().coerceIn(0, 255)
                b = (b * fall).toInt().coerceIn(0, 255)
            }
            if (grain > 0f) {
                val n = (noiseAt(i) * grain * GRAIN_SCALE).toInt()
                r = (r + n).coerceIn(0, 255)
                g = (g + n).coerceIn(0, 255)
                b = (b + n).coerceIn(0, 255)
            }
            graded[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(graded, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun curveMap(srgb: Float, last: Int): Int {
        val linear = ColorMath.srgbToLinear(srgb)
        val mapped = table[(linear * last).toInt().coerceIn(0, last)]
        return (ColorMath.linearToSrgb(mapped) * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun noiseAt(index: Int): Float {
        var x = index * 0x9E3779B9.toInt()
        x = x xor (x ushr 16)
        x *= 0x7FEB352D
        x = x xor (x ushr 15)
        x *= 0x846CA68B.toInt()
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f * 2f - 1f
    }

    companion object {
        const val PREVIEW_WIDTH = 360
        private const val GRAIN_SCALE = 14f
    }
}
