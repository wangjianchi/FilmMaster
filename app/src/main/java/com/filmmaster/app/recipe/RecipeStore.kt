package com.filmmaster.app.recipe

import android.content.Context
import android.net.Uri
import java.io.File

/** 配方的本地持久化 + SAF 导入导出。 */
object RecipeStore {

    private const val FILE_NAME = "recipe.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(context: Context, recipe: Recipe) {
        file(context).writeText(recipe.toJson())
    }

    fun load(context: Context): Recipe? =
        file(context).takeIf { it.exists() }
            ?.let { runCatching { Recipe.fromJson(it.readText()) }.getOrNull() }

    fun export(context: Context, uri: Uri, recipe: Recipe) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(recipe.toJson().toByteArray()) }
            ?: throw IllegalStateException("无法写入导出文件")
    }

    fun import(context: Context, uri: Uri): Recipe {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("无法读取导入文件")
        return Recipe.fromJson(text)
    }
}
