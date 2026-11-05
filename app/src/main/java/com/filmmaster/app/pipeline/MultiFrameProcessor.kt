package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 多帧对齐 + 合成（MFNR 的前置处理器）。
 *
 * 管线位置：连拍 JPEG × N → 解码 →（此处）全局平移对齐 + 逐像素中值合成 → 干净单帧
 * → 现有 ColorPipeline。
 *
 * 对齐：在 1/8 缩略图上做 ±6 像素的穷举 SAD 搜索，得到全局平移量后回投到工作分辨率。
 * 合成：逐像素逐通道取中值，但只把中值与参考帧的差值限制在 ±[DETAIL_KEEP] 内——
 * 保留参考帧的高频（文字/纹理边缘），同时压掉各帧不一致的噪点，避免四点中值把细节抹平。
 * 按行分块并行。
 *
 * 限制：只补偿平移（不处理旋转/视差）；工作分辨率默认限制在 2048px 长边以控制内存。
 */
class MultiFrameProcessor(
    private val maxDimension: Int = 2048,
    private val searchRadius: Int = 6,
    private val thumbDivisor: Int = 8
) {

    data class Result(
        val bitmap: Bitmap,
        val usedFrames: Int,
        val shifts: List<Pair<Int, Int>>
    )

    suspend fun process(frames: List<Bitmap>): Result {
        require(frames.isNotEmpty()) { "没有可合成的帧" }

        val owned = ArrayList<Bitmap>(frames.size * 2)
        val working = frames.map { frame ->
            val scaled = scaleDown(frame)
            if (scaled !== frame) owned += scaled
            scaled
        }

        if (working.size == 1) {
            val single = working.first().copy(Bitmap.Config.ARGB_8888, false)
            return Result(single, 1, listOf(0 to 0))
        }

        val reference = working.first()
        val shifts = estimateShifts(reference, working)

        val aligned = working.mapIndexed { index, bitmap ->
            if (index == 0) bitmap else translate(bitmap, shifts[index]).also { owned += it }
        }

        val merged = medianMerge(aligned)

        owned.forEach { if (!it.isRecycled) it.recycle() }
        return Result(merged, aligned.size, shifts.map { it.first to it.second })
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDimension) return src
        val scale = maxDimension.toFloat() / longest
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 返回每帧相对参考帧的平移（工作分辨率像素）。 */
    private fun estimateShifts(reference: Bitmap, frames: List<Bitmap>): List<Pair<Int, Int>> {
        val refThumb = luminanceThumb(reference)
        val radius = searchRadius
        val scaleX = reference.width.toFloat() / refThumb.width
        val scaleY = reference.height.toFloat() / refThumb.height

        return frames.map { frame ->
            if (frame === reference) return@map Pair(0, 0)
            val thumb = luminanceThumb(frame)
            if (thumb.width != refThumb.width || thumb.height != refThumb.height) {
                return@map Pair(0, 0)
            }

            var bestDx = 0
            var bestDy = 0
            var bestCost = Long.MAX_VALUE

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    var cost = 0L
                    var count = 0
                    var y = radius
                    while (y < thumb.height - radius) {
                        val refRow = y * refThumb.width
                        val curRow = (y + dy) * thumb.width
                        var x = radius
                        while (x < thumb.width - radius) {
                            val diff = refThumb.pixels[refRow + x] - thumb.pixels[curRow + x + dx]
                            cost += abs(diff)
                            count++
                            x += 2
                        }
                        y += 2
                    }
                    if (count > 0 && cost < bestCost) {
                        bestCost = cost
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }

            (bestDx * scaleX).toInt() to (bestDy * scaleY).toInt()
        }
    }

    private class Thumb(val width: Int, val height: Int, val pixels: IntArray)

    private fun luminanceThumb(src: Bitmap): Thumb {
        val w = max(32, src.width / thumbDivisor)
        val h = max(24, src.height / thumbDivisor)
        val scaled = if (src.width != w || src.height != h) {
            Bitmap.createScaledBitmap(src, w, h, true)
        } else {
            src
        }
        val argb = IntArray(w * h)
        scaled.getPixels(argb, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()

        val luma = IntArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            luma[i] = (((c shr 16) and 0xFF) * 299 +
                ((c shr 8) and 0xFF) * 587 +
                (c and 0xFF) * 114) / 1000
        }
        return Thumb(w, h, luma)
    }

    private fun translate(src: Bitmap, shift: Pair<Int, Int>): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix().apply { setTranslate(-shift.first.toFloat(), -shift.second.toFloat()) }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private suspend fun medianMerge(frames: List<Bitmap>): Bitmap = coroutineScope {
        val width = frames.first().width
        val height = frames.first().height
        val count = frames.size

        val buffers = frames.map { frame ->
            IntArray(width * height).also {
                frame.getPixels(it, 0, width, 0, 0, width, height)
            }
        }
        // 参考帧（对齐后的第一帧）用于细节保留
        val reference = buffers.first()
        val output = IntArray(width * height)
        val chunkRows = 64

        (0 until height step chunkRows).map { startY ->
            async(Dispatchers.Default) {
                val endY = min(height, startY + chunkRows)
                val r = IntArray(count)
                val g = IntArray(count)
                val b = IntArray(count)

                for (y in startY until endY) {
                    val rowBase = y * width
                    for (x in 0 until width) {
                        val p = rowBase + x
                        for (k in 0 until count) {
                            val c = buffers[k][p]
                            r[k] = (c shr 16) and 0xFF
                            g[k] = (c shr 8) and 0xFF
                            b[k] = c and 0xFF
                        }
                        sortSmall(r)
                        sortSmall(g)
                        sortSmall(b)
                        val mid = count / 2
                        var rr = if (count % 2 == 0) (r[mid - 1] + r[mid]) / 2 else r[mid]
                        var gg = if (count % 2 == 0) (g[mid - 1] + g[mid]) / 2 else g[mid]
                        var bb = if (count % 2 == 0) (b[mid - 1] + b[mid]) / 2 else b[mid]

                        // 细节保留：中值只允许修正参考帧 ±DETAIL_KEEP 的偏差
                        val ref = reference[p]
                        rr += (((ref shr 16) and 0xFF) - rr)
                            .coerceIn(-DETAIL_KEEP, DETAIL_KEEP)
                        gg += (((ref shr 8) and 0xFF) - gg)
                            .coerceIn(-DETAIL_KEEP, DETAIL_KEEP)
                        bb += ((ref and 0xFF) - bb)
                            .coerceIn(-DETAIL_KEEP, DETAIL_KEEP)

                        output[p] = (0xFF shl 24) or
                            (rr.coerceIn(0, 255) shl 16) or
                            (gg.coerceIn(0, 255) shl 8) or
                            bb.coerceIn(0, 255)
                    }
                }
            }
        }.awaitAll()

        Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    /** 3~5 个元素插入排序，避免每个像素分配数组。 */
    private fun sortSmall(values: IntArray) {
        for (i in 1 until values.size) {
            val v = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > v) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = v
        }
    }

    private companion object {
        /** 允许中值修正参考帧的最大码值差，超过即认为是真实细节而非噪点。 */
        const val DETAIL_KEEP = 12
    }
}
