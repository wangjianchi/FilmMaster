package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 抓拍模式成片增强：USM 强边缘锐化 + Clarity 局部对比 + 胶片颗粒。
 *
 * 管线位置：ColorPipeline（LUT/曲线）之后、编码之前。
 * 在 LUT 之后做锐化，避免先锐化再被 LUT 的对比度放大成白边。
 *
 * 实现为纯 Kotlin（O(N) 滑动窗口盒式模糊），原因：抓拍合成已经在 CPU 侧，
 * 再走一次 EGL 往返的收益不大；后续可平移到 GLSL。
 */
class FrameEnhancer(
    private val usmRadius: Int = 2,
    private val maxGain: Float = 1.6f,
    private val minGain: Float = 0.85f
) {

    fun enhance(
        src: Bitmap,
        sharpness: Float,
        clarity: Float,
        grain: Float,
        seed: Int = 0
    ): Bitmap {
        if (sharpness <= 0f && clarity <= 0f && grain <= 0f) return src

        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val luma = FloatArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            luma[i] = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }

        val blurSmall = boxBlur(luma, width, height, usmRadius)
        val clarityRadius = max(8, min(width, height) / 120)
        val blurLarge = if (clarity > 0f) boxBlur(luma, width, height, clarityRadius) else null

        val out = IntArray(width * height)
        for (i in pixels.indices) {
            val base = max(luma[i], 0.5f)
            val edge = abs(luma[i] - blurSmall[i])
            // 噪声门限：只强化真正的边缘，避免把弱光噪点/JPEG 块效应一起放大
            val gate = edge / (edge + 3f)
            var detail = (luma[i] - blurSmall[i]) * sharpness * gate
            if (blurLarge != null) {
                detail += (luma[i] - blurLarge[i]) * clarity
            }
            val gain = ((luma[i] + detail) / base).coerceIn(minGain, maxGain)

            var r = ((pixels[i] shr 16) and 0xFF) * gain
            var g = ((pixels[i] shr 8) and 0xFF) * gain
            var b = (pixels[i] and 0xFF) * gain

            if (grain > 0f) {
                val n = noiseAt(i, seed) * grain * 12f
                r += n
                g += n
                b += n
            }

            out[i] = (0xFF shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }

        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 可分离滑动窗口盒式模糊：水平一遍 + 垂直一遍，复杂度 O(W*H)。 */
    private fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return src.copyOf()

        val horizontal = FloatArray(width * height)
        val result = FloatArray(width * height)
        val window = 2 * radius + 1

        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in -radius..radius) {
                sum += src[row + x.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / window
                val add = src[row + min(width - 1, x + radius + 1)]
                val remove = src[row + max(0, x - radius)]
                sum += add - remove
            }
        }

        for (x in 0 until width) {
            var sum = 0f
            for (y in -radius..radius) {
                sum += horizontal[y.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                result[y * width + x] = sum / window
                val add = horizontal[min(height - 1, y + radius + 1) * width + x]
                val remove = horizontal[max(0, y - radius) * width + x]
                sum += add - remove
            }
        }
        return result
    }

    private fun noiseAt(index: Int, seed: Int): Float {
        var x = index + seed * 0x9E3779B9.toInt()
        x = x xor (x ushr 16)
        x *= 0x7FEB352D
        x = x xor (x ushr 15)
        x *= 0x846CA68B.toInt()
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f * 2f - 1f
    }
}
