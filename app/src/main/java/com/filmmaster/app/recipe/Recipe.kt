package com.filmmaster.app.recipe

import com.filmmaster.app.pipeline.ToneCurve
import org.json.JSONObject

/**
 * M4 配方：一次拍摄的全部可调参数，可序列化为 JSON 并导入导出。
 *
 * 只保存“创作参数”，不保存相机硬件状态（ISO/快门），避免配方在不同机型上产生意外曝光。
 */
data class Recipe(
    val name: String = "默认配方",
    val lookId: String = "amber400",
    val strength: Float = 1f,
    val contrast: Float = 0f,
    val gamma: Float = 1f,
    val lift: Float = 0f,
    val rolloff: Float = 0f,
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val sharpness: Float = 0.30f,
    val clarity: Float = 0.18f,
    val watermark: String = ""
) {

    fun curve(): ToneCurve = ToneCurve(
        contrast = contrast,
        gamma = gamma,
        lift = lift,
        rolloff = rolloff
    )

    fun toJson(): String = JSONObject().apply {
        put("version", 1)
        put("name", name)
        put("lookId", lookId)
        put("strength", strength.toDouble())
        put("contrast", contrast.toDouble())
        put("gamma", gamma.toDouble())
        put("lift", lift.toDouble())
        put("rolloff", rolloff.toDouble())
        put("grain", grain.toDouble())
        put("vignette", vignette.toDouble())
        put("sharpness", sharpness.toDouble())
        put("clarity", clarity.toDouble())
        put("watermark", watermark)
    }.toString(2)

    companion object {

        fun fromJson(text: String): Recipe {
            val json = JSONObject(text)
            return Recipe(
                name = json.optString("name", "导入配方"),
                lookId = json.optString("lookId", "amber400"),
                strength = json.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1f),
                contrast = json.optDouble("contrast", 0.0).toFloat().coerceIn(-0.8f, 0.8f),
                gamma = json.optDouble("gamma", 1.0).toFloat().coerceIn(0.5f, 2f),
                lift = json.optDouble("lift", 0.0).toFloat().coerceIn(0f, 0.3f),
                rolloff = json.optDouble("rolloff", 0.0).toFloat().coerceIn(0f, 1f),
                grain = json.optDouble("grain", 0.0).toFloat().coerceIn(0f, 1f),
                vignette = json.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 1f),
                sharpness = json.optDouble("sharpness", 0.30).toFloat().coerceIn(0f, 1f),
                clarity = json.optDouble("clarity", 0.18).toFloat().coerceIn(0f, 1f),
                watermark = json.optString("watermark", "")
            )
        }
    }
}
