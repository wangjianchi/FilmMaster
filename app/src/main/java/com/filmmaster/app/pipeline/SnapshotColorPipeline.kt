package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import com.filmmaster.app.lut.Lut3D

/**
 * 抓拍模式的成片色彩管线。
 *
 * 顺序：MFNR 合成帧 → ColorPipeline（LUT/曲线）→ 强边缘 USM + Clarity → 胶片颗粒 → 保存。
 *
 * 颗粒放在最后，用来掩盖合成后残留的弱光噪点；暗角/曲线仍由 ColorPipeline 内部负责。
 * 若工程里已有独立的颗粒实现，把 [grain] 传 0，并在 ColorPipeline 内部叠加即可。
 */
class SnapshotColorPipeline(
    private val pipelineFactory: (Lut3D, Float) -> ColorPipeline = { lut, strength ->
        LutColorPipeline(lut, strength)
    },
    private val enhancer: FrameEnhancer = FrameEnhancer()
) {

    fun process(
        merged: Bitmap,
        lut: Lut3D,
        strength: Float,
        sharpness: Float = DEFAULT_SHARPNESS,
        clarity: Float = DEFAULT_CLARITY,
        grain: Float = 0f
    ): Bitmap {
        val graded = pipelineFactory(lut, strength).process(merged)
        val enhanced = enhancer.enhance(graded, sharpness, clarity, grain)
        if (enhanced !== graded && !graded.isRecycled) {
            graded.recycle()
        }
        return enhanced
    }

    companion object {
        const val DEFAULT_SHARPNESS = 0.30f
        const val DEFAULT_CLARITY = 0.18f
    }
}
