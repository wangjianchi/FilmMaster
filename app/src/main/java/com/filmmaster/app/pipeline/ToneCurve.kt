package com.filmmaster.app.pipeline

import kotlin.math.abs
import kotlin.math.pow

/**
 * M3 参数化胶片曲线：对比度、伽马、黑位抬升、高光压缩。
 *
 * 曲线在线性光下计算（见 [com.filmmaster.app.color.ColorMath]）：
 * 1. 伽马调整中间调；
 * 2. 以 18% 中性灰为轴做对比度幂变换；
 * 3. 抬黑位（lift）模拟胶片片基；
 * 4. 高光压缩（rolloff）避免亮部硬切。
 *
 * 预烘焙成 1024 点的一维表，单像素只需一次查表。
 */
data class ToneCurve(
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val lift: Float = 0f,
    val rolloff: Float = 0f
) {

    val isIdentity: Boolean
        get() = abs(contrast) < 1e-3f &&
            abs(gamma - 1f) < 1e-3f &&
            lift < 1e-3f &&
            rolloff < 1e-3f

    fun bake(size: Int = 1024): FloatArray {
        val table = FloatArray(size)
        val safeGamma = gamma.coerceIn(0.5f, 2f)
        val safeContrast = contrast.coerceIn(-0.8f, 0.8f)
        val safeLift = lift.coerceIn(0f, 0.3f)
        val safeRolloff = rolloff.coerceIn(0f, 1f)

        for (i in 0 until size) {
            var y = i.toFloat() / (size - 1)
            y = y.pow(1f / safeGamma)
            if (y > 0f) {
                y = PIVOT * (y / PIVOT).pow(1f + safeContrast)
            }
            y = safeLift + (1f - safeLift) * y
            y = y.pow(1f + safeRolloff * 0.6f)
            table[i] = y.coerceIn(0f, 1f)
        }
        return table
    }

    companion object {
        const val PIVOT = 0.18f
        val NEUTRAL = ToneCurve()
    }
}
