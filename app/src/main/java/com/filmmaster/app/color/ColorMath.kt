package com.filmmaster.app.color

/**
 * sRGB（显示参考）与线性光之间的转换。
 *
 * M2 起的管线约定：
 *  - 输入的 JPEG 字节是 sRGB 编码值；
 *  - 影调曲线在线性光下计算（高位深、无断层）；
 *  - 创意 LUT 仍然是“显示参考”的（按 .cube 通用语义），因此放在线性化之后。
 */
object ColorMath {

    fun srgbToLinear(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.04045f) {
            v / 12.92f
        } else {
            Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }

    fun linearToSrgb(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) {
            v * 12.92f
        } else {
            (1.055f * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055f).toFloat()
        }
    }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
}
