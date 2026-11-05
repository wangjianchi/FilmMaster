package com.filmmaster.app.pipeline

import android.graphics.Bitmap
import com.filmmaster.app.gl.LutGlProcessor
import com.filmmaster.app.lut.Lut3D

/**
 * 色彩引擎接缝。
 *
 * 当前工程（M1）只有 3D LUT；后续 M2~M5 的胶片曲线、线性空间、颗粒/暗角
 * 可以在实现类内部扩展，而不需要改动抓拍模式与相机层。
 */
interface ColorPipeline {
    fun process(src: Bitmap): Bitmap
}

/** 用现有 GPU LUT 处理器实现的 ColorPipeline。 */
class LutColorPipeline(
    private val lut: Lut3D,
    private val strength: Float,
    private val processor: LutGlProcessor = LutGlProcessor()
) : ColorPipeline {

    override fun process(src: Bitmap): Bitmap =
        processor.process(src, lut, strength)
}
