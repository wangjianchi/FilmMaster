package com.filmmaster.app.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoSaver {

    /** 解码 JPEG 并按 EXIF Orientation 旋转/翻转，返回 ARGB_8888 位图。 */
    fun loadOriented(file: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("无法解码拍摄文件: ${file.name}")

        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }

        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { rotated ->
                if (rotated !== bitmap) bitmap.recycle()
            }
    }

    /** 保存到系统相册 Pictures/FilmMaster，返回 MediaStore Uri。水印为空时不拷贝原图。 */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        quality: Int = 95,
        watermark: String? = null
    ): Uri {
        val resolver = context.contentResolver
        val name = "FM_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        val output = watermark?.takeIf { it.isNotBlank() }
            ?.let { drawWatermark(bitmap, it) }
            ?: bitmap

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmMaster")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert 失败")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    throw IllegalStateException("JPEG 编码失败")
                }
            } ?: throw IllegalStateException("无法打开输出流")

            val publish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, publish, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        } finally {
            if (output !== bitmap) output.recycle()
        }
    }

    /** 右下角半透明水印，边距与字号按短边比例缩放。 */
    private fun drawWatermark(source: Bitmap, text: String): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val shortSide = minOf(copy.width, copy.height)
        val textSize = (shortSide / 24f).coerceAtLeast(14f)
        val margin = textSize * 0.8f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            alpha = 170
            this.textSize = textSize
            setShadowLayer(textSize / 8f, 0f, textSize / 12f, 0x99000000)
        }
        val canvas = Canvas(copy)
        canvas.drawText(
            text,
            copy.width - margin - paint.measureText(text),
            copy.height - margin,
            paint
        )
        return copy
    }
}
