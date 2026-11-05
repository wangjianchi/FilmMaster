package com.filmmaster.app.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX 封装：Preview + ImageCapture（JPEG）。
 * M1 锁定竖屏，targetRotation 固定为 0；M2 接入 RAW 时在此扩展 ImageCapture 输出格式。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null

    fun bind(previewView: PreviewView, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                imageCapture = capture

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
            } catch (t: Throwable) {
                onError(t)
            }
        }, mainExecutor)
    }

    fun unbind() {
        provider?.unbindAll()
        imageCapture = null
    }

    /** 拍摄到 cacheDir 下的临时 JPEG，供后续 LUT 处理。 */
    suspend fun capture(): File = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if (capture == null) {
            continuation.resumeWithException(IllegalStateException("相机尚未就绪"))
            return@suspendCancellableCoroutine
        }

        val file = File(context.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        )
    }
}
