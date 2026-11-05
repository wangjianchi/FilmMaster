package com.filmmaster.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * M2 RAW 采集：CameraX 的 ImageCapture 尚不支持 RAW 输出，因此这里直接走 Camera2，
 * 抓一张 RAW_SENSOR 帧并用 [DngCreator] 封装成 DNG，写入相册 `Pictures/FilmMaster/RAW`。
 *
 * 使用约束：Camera2 与 CameraX 不能同时占用相机，调用前必须先解绑 CameraX 会话，
 * 采集结束后由调用方重绑（见 [SnapshotCameraController.captureRawThenRebind]）。
 * DNG 的解码/去马赛克留给 M5，本阶段只保证“真实 RAW 落盘”。
 */
class RawDngCapture(private val context: Context) {

    fun isSupported(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return runCatching {
            manager.cameraIdList.any { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            }
        }.getOrDefault(false)
    }

    suspend fun capture(): android.net.Uri = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: throw IllegalStateException("CameraManager 不可用")
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK &&
                chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        } ?: throw IllegalStateException("设备不支持 RAW")

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val rawSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("无可用 RAW 尺寸")

        val thread = HandlerThread("raw-dng").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        try {
            device = openDevice(manager, cameraId, handler)
            reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)

            val sessionLatch = CountDownLatch(1)
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessionLatch.countDown()
                    }
                },
                handler
            )
            check(sessionLatch.await(5, TimeUnit.SECONDS) && session != null) {
                "创建 RAW 会话失败"
            }

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()

            val resultLatch = CountDownLatch(1)
            var result: TotalCaptureResult? = null
            var frames = 0
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    req: CaptureRequest,
                    r: TotalCaptureResult
                ) {
                    frames++
                    if (frames >= WARMUP_FRAMES && result == null) {
                        runCatching { s.stopRepeating() }
                        s.capture(req, this, handler)
                    } else if (frames > WARMUP_FRAMES && result == null) {
                        result = r
                        resultLatch.countDown()
                    }
                }
            }
            session!!.setRepeatingRequest(request, callback, handler)
            check(resultLatch.await(8, TimeUnit.SECONDS) && result != null) {
                "等待 RAW 曝光超时"
            }

            val image = reader.acquireNextImage()
                ?: throw IllegalStateException("未获取到 RAW 帧")
            val finalResult = result ?: throw IllegalStateException("缺少 RAW 曝光结果")
            image.use { raw ->
                val dng = DngCreator(characteristics, finalResult)
                val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                dng.setOrientation(
                    if (orientation == 90) ExifInterface.ORIENTATION_ROTATE_90 else orientation
                )
                val temp = File(context.cacheDir, "raw_${System.currentTimeMillis()}.dng")
                FileOutputStream(temp).use { out -> dng.writeImage(out, raw) }
                val uri = publishDng(temp)
                temp.delete()
                uri
            }
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    private fun openDevice(manager: CameraManager, id: String, handler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: Throwable? = null
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                latch.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                error = IllegalStateException("相机被占用")
                latch.countDown()
            }

            override fun onError(camera: CameraDevice, code: Int) {
                camera.close()
                error = IllegalStateException("打开相机失败: $code")
                latch.countDown()
            }
        }, handler)
        check(latch.await(5, TimeUnit.SECONDS)) { "打开相机超时" }
        return device ?: throw (error ?: IllegalStateException("打开相机失败"))
    }

    private fun publishDng(file: File): android.net.Uri {
        val name = "FM_RAW_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.dng"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmMaster/RAW")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert 失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法写入 DNG")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private companion object {
        const val WARMUP_FRAMES = 4
    }
}
