package com.filmmaster.app.lut

/** 一个可被 UI 选择的胶片风格（LUT 在内存中程序化生成）。 */
data class FilmLook(
    val id: String,
    val label: String,
    val lut: Lut3D
)

/**
 * 程序化生成的示例 LUT。
 *
 * 这里不包含、也不推导任何厂商的私有 LUT：全部由公开的影调/色彩数学在 sRGB 空间
 * 逐点计算得到（S 曲线 + 阴影提升 + 高光压缩 + 分离色调 + 饱和度）。
 * 生成结果与 .cube 解析结果使用同一套 [Lut3D] 数据结构，M2 起会切到线性空间。
 */
object ProceduralLuts {

    const val SIZE = 17

    private data class Grade(
        val contrast: Float,
        val lift: Float,
        val rolloff: Float,
        val shadowTint: FloatArray,
        val highlightTint: FloatArray,
        val saturation: Float
    )

    private val specs: List<Triple<String, String, Grade>> = listOf(
        Triple(
            "amber400", "琥珀 400",
            Grade(
                contrast = 0.25f,
                lift = 0.05f,
                rolloff = 0.18f,
                shadowTint = floatArrayOf(0.020f, 0.000f, -0.020f),
                highlightTint = floatArrayOf(0.060f, 0.020f, -0.060f),
                saturation = -0.06f
            )
        ),
        Triple(
            "cine250", "冷调 250",
            Grade(
                contrast = 0.12f,
                lift = 0.03f,
                rolloff = 0.10f,
                shadowTint = floatArrayOf(-0.040f, 0.010f, 0.050f),
                highlightTint = floatArrayOf(0.030f, 0.000f, 0.000f),
                saturation = -0.02f
            )
        ),
        Triple(
            "faded100", "褪色 100",
            Grade(
                contrast = -0.12f,
                lift = 0.13f,
                rolloff = 0.30f,
                shadowTint = floatArrayOf(0.020f, 0.020f, 0.030f),
                highlightTint = floatArrayOf(0.020f, 0.010f, 0.000f),
                saturation = -0.22f
            )
        )
    )

    val looks: List<FilmLook> by lazy {
        specs.map { (id, label, grade) -> FilmLook(id, label, generate(grade, SIZE)) }
    }

    fun find(id: String): FilmLook? = looks.firstOrNull { it.id == id }

    private fun generate(grade: Grade, size: Int): Lut3D {
        val data = FloatArray(size * size * size * 3)
        var idx = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val srcR = r.toFloat() / (size - 1)
                    val srcG = g.toFloat() / (size - 1)
                    val srcB = b.toFloat() / (size - 1)

                    var cr = tone(srcR, grade)
                    var cg = tone(srcG, grade)
                    var cb = tone(srcB, grade)

                    // 分离色调：阴影与高光分别染色
                    val sr = 1f - cr
                    val sg = 1f - cg
                    val sb = 1f - cb
                    cr += grade.shadowTint[0] * sr * sr + grade.highlightTint[0] * cr * cr
                    cg += grade.shadowTint[1] * sg * sg + grade.highlightTint[1] * cg * cg
                    cb += grade.shadowTint[2] * sb * sb + grade.highlightTint[2] * cb * cb

                    // 饱和度（围绕亮度轴缩放）
                    val luma = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb
                    val sat = 1f + grade.saturation
                    cr = luma + (cr - luma) * sat
                    cg = luma + (cg - luma) * sat
                    cb = luma + (cb - luma) * sat

                    data[idx++] = cr.coerceIn(0f, 1f)
                    data[idx++] = cg.coerceIn(0f, 1f)
                    data[idx++] = cb.coerceIn(0f, 1f)
                }
            }
        }
        return Lut3D(size, data)
    }

    private fun tone(x: Float, g: Grade): Float {
        val smooth = x * x * (3f - 2f * x)
        var y = x + g.contrast * (smooth - x)          // S 曲线 / 反 S
        y = g.lift + (1f - g.lift) * y                 // 提黑
        y = 1f - (1f - y) * (1f + g.rolloff * y)       // 高光压缩
        return y.coerceIn(0f, 1f)
    }
}
