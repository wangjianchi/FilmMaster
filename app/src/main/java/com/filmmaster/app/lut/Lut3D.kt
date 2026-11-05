package com.filmmaster.app.lut

import kotlin.math.floor
import kotlin.math.min

/**
 * 3D LUT（.cube 语义）：R 通道变化最快，其次 G，最后 B。
 *
 * data 索引：((b * size + g) * size + r) * 3 + channel
 * 与 OpenGL 3D 纹理的 x=r / y=g / z=b 排布一致，可直接上传。
 */
class Lut3D(val size: Int, private val data: FloatArray) {

    init {
        require(size >= 2) { "LUT size must be >= 2, got $size" }
        require(data.size == size * size * size * 3) {
            "LUT data length ${data.size} != size^3*3 (${size * size * size * 3})"
        }
    }

    fun copyData(): FloatArray = data.copyOf()

    private fun at(r: Int, g: Int, b: Int, channel: Int): Float {
        return data[((b * size + g) * size + r) * 3 + channel]
    }

    /**
     * 三线性插值采样。输入为 [0,1] 的 sRGB/显示参考值，输出写入 out（长度 3）。
     */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray = FloatArray(3)): FloatArray {
        require(out.size >= 3) { "out must hold 3 floats" }
        val s = size - 1
        val fr = r.coerceIn(0f, 1f) * s
        val fg = g.coerceIn(0f, 1f) * s
        val fb = b.coerceIn(0f, 1f) * s

        val r0 = floor(fr).toInt()
        val g0 = floor(fg).toInt()
        val b0 = floor(fb).toInt()
        val r1 = min(r0 + 1, s)
        val g1 = min(g0 + 1, s)
        val b1 = min(b0 + 1, s)

        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0

        for (c in 0 until 3) {
            val c000 = at(r0, g0, b0, c)
            val c100 = at(r1, g0, b0, c)
            val c010 = at(r0, g1, b0, c)
            val c110 = at(r1, g1, b0, c)
            val c001 = at(r0, g0, b1, c)
            val c101 = at(r1, g0, b1, c)
            val c011 = at(r0, g1, b1, c)
            val c111 = at(r1, g1, b1, c)

            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg

            out[c] = c0 + (c1 - c0) * db
        }
        return out
    }

    fun sample(r: Float, g: Float, b: Float): FloatArray = sample(r, g, b, FloatArray(3))

    /**
     * CPU 路径：对 ARGB_8888 像素数组做 LUT + 强度混合，alpha 原样保留。
     * 用于无 GLES3 设备的降级，也用于 JVM 单元测试。
     */
    fun applyArgb(pixels: IntArray, strength: Float): IntArray {
        val k = strength.coerceIn(0f, 1f)
        val out = IntArray(pixels.size)
        val lutRgb = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            sample(r, g, b, lutRgb)

            val nr = r + (lutRgb[0].coerceIn(0f, 1f) - r) * k
            val ng = g + (lutRgb[1].coerceIn(0f, 1f) - g) * k
            val nb = b + (lutRgb[2].coerceIn(0f, 1f) - b) * k

            out[i] = (p and 0xFF000000.toInt()) or
                (quantize(nr) shl 16) or
                (quantize(ng) shl 8) or
                quantize(nb)
        }
        return out
    }

    private fun quantize(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    companion object {
        /**
         * 解析 Adobe .cube（3D）文本。1D LUT、非 [0,1] domain 暂不支持（M1 明确抛错）。
         */
        fun parseCube(text: String): Lut3D {
            var size = 0
            var is1D = false
            val values = ArrayList<Float>()

            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE: $line")
                    "LUT_1D_SIZE" -> {
                        is1D = true
                        size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    }
                    "TITLE", "DOMAIN_MIN", "DOMAIN_MAX", "LUT_3D_INPUT_RANGE" -> Unit
                    else -> parts.forEach { token ->
                        values.add(token.toFloatOrNull()
                            ?: throw IllegalArgumentException("Invalid LUT value: $line"))
                    }
                }
            }

            require(!is1D) { "1D LUT is not supported, use a 3D .cube file" }
            require(size >= 2) { "Missing or invalid LUT_3D_SIZE" }
            val expected = size * size * size * 3
            require(values.size == expected) {
                "LUT data mismatch: expected $expected floats for size $size, got ${values.size}"
            }
            return Lut3D(size, values.toFloatArray())
        }
    }
}
