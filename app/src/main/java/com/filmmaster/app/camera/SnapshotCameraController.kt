package com.filmmaster.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 抓拍模式相机控制器（CameraX + Camera2Interop）。
 *
 * 关键约束：Camera2 只有在 CONTROL_AE_MODE == OFF 时才会采用 SENSOR_EXPOSURE_TIME，
 * 因此抓拍模式的 ImageCapture 固定 AE 关闭、手动曝光时间与 ISO（快门优先）。
 * ISO 由预览 AE 的最新结果按“保持曝光量”换算得到：ISO_new = ISO_ae * T_ae / T_target。
 *
 * AE/AF 锁定：曝光由固定值天然锁定；AF 在连拍前触发一次中心区域对焦，
 * 连拍期间使用 CONTINUOUS_PICTURE，不再反复触发对焦。
 *
 * 说明：这是 MVP 的顺序连拍实现（每次 takePicture 等 JPEG 落盘），
 * 真正的 60fps 环形缓冲需要 ImageReader burst，将在后续迭代加入。
 */
class SnapshotCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    data class ExposureReadout(
        val shutterNs: Long = 0L,
        val iso: Int = 0,
        val locked: Boolean = false,
        val degraded: Boolean = false
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val _readout = MutableStateFlow(ExposureReadout())
    val readout: StateFlow<ExposureReadout> = _readout.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var analysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var snapshotEnabled = false

    /** 设备是否支持 RAW（决定 UI 是否显示 RAW 开关）。 */
    @Volatile var supportsRaw: Boolean = false
        private set

    private var previewGradingEnabled = false
    @Volatile private var previewGrader: ((Bitmap) -> Bitmap)? = null
    private var frameCounter = 0

    private val _gradedPreview = MutableStateFlow<Bitmap?>(null)
    val gradedPreview: StateFlow<Bitmap?> = _gradedPreview.asStateFlow()

    /** 快门候选（分母），从快到慢；在保证不欠曝的前提下选最快的一个。 */
    private val shutterCandidates = intArrayOf(125, 100, 80, 60, 50, 40, 30, 25, 20)

    /** 期望快门上界（纳秒），默认 1/125s；实际会按场景亮度自动放慢。 */
    private var targetExposureNs = 1_000_000_000L / 125

    /** 已应用到 capture use case 的曝光，用于判断是否需要重绑。 */
    @Volatile private var appliedShutterNs = 0L
    @Volatile private var appliedIso = 0

    /** 用户手动指定 ISO；null 表示自动换算。 */
    private var isoOverride: Int? = null

    private var exposureRange: android.util.Range<Long>? = null
    private var isoRange: android.util.Range<Int>? = null

    @Volatile private var latestAeExposureNs: Long? = null
    @Volatile private var latestAeIso: Int? = null

    /** 预览会话回调：持续读取 AE 的实际曝光时间与 ISO，用于实时显示与 ISO 换算。 */
    private val aeCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            if (exp != null && iso != null && exp > 0 && iso > 0) {
                latestAeExposureNs = exp
                latestAeIso = iso
                if (!snapshotEnabled) {
                    _readout.value = ExposureReadout(exp, iso, locked = false, degraded = false)
                }
            }
        }
    }

    fun bind(previewView: PreviewView, onError: (Throwable) -> Unit) {
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(aeCallback)
                val previewUseCase = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                preview = previewUseCase

                bindUseCases(cameraProvider)
            } catch (t: Throwable) {
                onError(t)
            }
        }, mainExecutor)
    }

    fun unbind() {
        analysis?.clearAnalyzer()
        analysis = null
        provider?.unbindAll()
        provider = null
        camera = null
        preview = null
        imageCapture = null
        _gradedPreview.value = null
    }

    /** 开关半分辨率预览分级（会重建 ImageCapture + ImageAnalysis 并重绑）。 */
    fun setPreviewGrading(enabled: Boolean, grader: ((Bitmap) -> Bitmap)?) {
        previewGrader = grader
        if (previewGradingEnabled == enabled) return
        previewGradingEnabled = enabled
        if (!enabled) _gradedPreview.value = null
        provider?.let { bindUseCases(it) }
    }

    /** 开关 RAW（仅设备支持且非抓拍模式时生效）。 */
    suspend fun captureRawThenRebind(rawCapture: RawDngCapture): android.net.Uri? {
        val view = previewView ?: return null
        unbind()
        val uri = runCatching { rawCapture.capture() }.getOrNull()
        bind(view) { }
        // 等待 CameraX 重新就绪，避免紧接着的 JPEG 拍摄落空
        withTimeoutOrNull(3000L) {
            while (imageCapture == null) delay(30)
        }
        return uri
    }

    /** 切换抓拍模式；必须在主线程调用（会重建 ImageCapture 并重绑）。 */
    fun setSnapshotEnabled(enabled: Boolean) {
        if (snapshotEnabled == enabled) return
        val previous = snapshotEnabled
        snapshotEnabled = enabled
        try {
            provider?.let { bindUseCases(it) }
        } catch (t: Throwable) {
            // 重绑失败（例如设备拒绝 AE_MODE_OFF）→ 回退到普通模式
            snapshotEnabled = previous
            runCatching { provider?.let { bindUseCases(it) } }
            throw t
        }
    }

    /** 设置快门优先的曝光时间，例如 denominator=125 表示 1/125s。 */
    fun setShutterDenominator(denominator: Int) {
        if (denominator <= 0) return
        targetExposureNs = 1_000_000_000L / denominator
        if (snapshotEnabled) provider?.let { bindUseCases(it) }
    }

    /** 手动指定 ISO；传 null 恢复自动换算。 */
    fun setIsoOverride(iso: Int?) {
        isoOverride = iso
        if (snapshotEnabled) provider?.let { bindUseCases(it) }
    }

    /** 顺序连拍 [count] 张，返回实际落盘的临时 JPEG 列表（至少 1 张）。 */
    suspend fun captureBurst(count: Int = 4): List<File> {
        val capture = imageCapture ?: throw IllegalStateException("相机尚未就绪")
        // 按当前场景重新测光并调整快门/ISO，避免用启用模式那一刻的旧值拍暗
        applyExposureIfNeeded()
        val activeCapture = imageCapture ?: capture
        focusAndWait()

        val files = ArrayList<File>(count)
        var lastError: Throwable? = null
        repeat(count) { index ->
            try {
                files += takePictureOnce(activeCapture, index)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        if (files.isEmpty()) {
            throw lastError ?: IllegalStateException("连拍失败")
        }
        return files
    }

    /** 普通模式单张拍摄（不触发对焦，保持 M1 的低延迟行为）。 */
    suspend fun captureSingle(): File {
        val capture = imageCapture ?: throw IllegalStateException("相机尚未就绪")
        return takePictureOnce(capture, 0)
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider) {
        readCharacteristics(cameraProvider)
        val (exposureNs, iso, degraded) = computeTargetExposure()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(100)
            .setTargetRotation(Surface.ROTATION_0)

        if (snapshotEnabled) {
            // 限制单帧尺寸：4 张全分辨率 ARGB 解码在手机上容易 OOM，且合成上限就是 2048
            captureBuilder.setTargetResolution(android.util.Size(2048, 1536))
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                // 连拍期间不主动触发 AF 扫描，避免焦点拉扯
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
        }

        val capture = captureBuilder.build()

        val previewUseCase = preview ?: return
        analysis?.clearAnalyzer()
        analysis = null

        val useCases = mutableListOf<androidx.camera.core.UseCase>(previewUseCase, capture)
        if (previewGradingEnabled) {
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
            analysis = analysisUseCase
            useCases += analysisUseCase
        }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
        imageCapture = capture
        appliedShutterNs = exposureNs
        appliedIso = iso

        _readout.value = ExposureReadout(
            shutterNs = if (snapshotEnabled) exposureNs else _readout.value.shutterNs,
            iso = if (snapshotEnabled) iso else _readout.value.iso,
            locked = snapshotEnabled,
            degraded = snapshotEnabled && degraded
        )
    }

    /**
     * 在“不欠曝”的前提下选最快的快门：用预览 AE 的 EV（曝光量）反推各快门所需 ISO，
     * 取第一个 ISO 不触顶的档位。全都不够时才牺牲曝光（标记 degraded）。
     */
    private fun computeTargetExposure(): Triple<Long, Int, Boolean> {
        val minIso = maxOf(MIN_ISO, isoRange?.lower ?: MIN_ISO)
        val maxIso = minOf(MAX_AUTO_ISO, isoRange?.upper ?: MAX_AUTO_ISO)

        isoOverride?.let {
            return Triple(clampExposure(targetExposureNs), it.coerceIn(minIso, maxIso), false)
        }

        val aeIso = latestAeIso
        val aeExp = latestAeExposureNs
        if (aeIso == null || aeExp == null || aeExp <= 0) {
            // 还没拿到预览 AE 结果：用保守值，标记降级
            return Triple(clampExposure(1_000_000_000L / 60), 3200.coerceIn(minIso, maxIso), true)
        }

        val ev = aeIso.toDouble() * aeExp
        for (denominator in shutterCandidates) {
            val ns = clampExposure(1_000_000_000L / denominator)
            val neededIso = (ev / ns).toInt()
            if (neededIso <= maxIso) {
                return Triple(ns, neededIso.coerceIn(minIso, maxIso), neededIso >= maxIso)
            }
        }

        // 即使最慢快门也要超过 ISO 上限 → 欠曝，取最慢快门 + 最高 ISO
        val slowest = clampExposure(1_000_000_000L / shutterCandidates.last())
        return Triple(slowest, maxIso, true)
    }

    private fun clampExposure(ns: Long): Long {
        val range = exposureRange ?: return ns
        return ns.coerceIn(range.lower, range.upper)
    }

    /** 拍摄前按当前场景刷新曝光；只有值变化时才重绑，代价约一帧。 */
    private suspend fun applyExposureIfNeeded() {
        if (!snapshotEnabled) return
        val cameraProvider = provider ?: return
        val (ns, iso, _) = computeTargetExposure()
        if (ns == appliedShutterNs && iso == appliedIso) return
        withContext(Dispatchers.Main) {
            runCatching { bindUseCases(cameraProvider) }
        }
        delay(80)
    }

    private fun readCharacteristics(cameraProvider: ProcessCameraProvider) {
        runCatching {
            val info = cameraProvider.availableCameraInfos.firstOrNull {
                Camera2CameraInfo.from(it).getCameraCharacteristic(
                    CameraCharacteristics.LENS_FACING
                ) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            val camera2Info = Camera2CameraInfo.from(info)
            exposureRange = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            isoRange = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val capabilities = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            )
            supportsRaw = capabilities?.any {
                it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            } == true
        }
    }

    /** 预览帧 → 半分辨率分级。抽帧到约 10fps，避免 CPU 分级拖慢取景。 */
    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            val grader = previewGrader
            if (!previewGradingEnabled || grader == null) return
            frameCounter++
            if (frameCounter % PREVIEW_FRAME_STRIDE != 0) return

            val raw = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            val rotated = if (rotation == 0) {
                raw
            } else {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true
                ).also { raw.recycle() }
            }
            val graded = grader(rotated)
            if (graded !== rotated && !rotated.isRecycled) rotated.recycle()
            _gradedPreview.value = graded
        } catch (_: Throwable) {
            // 预览分级失败不影响拍摄，静默丢弃该帧
        } finally {
            proxy.close()
        }
    }

    private suspend fun focusAndWait() {
        val cam = camera ?: return
        val view = previewView ?: return
        if (view.width <= 0 || view.height <= 0) return
        val point = view.meteringPointFactory.createPoint(
            view.width / 2f, view.height / 2f
        )
        val action = FocusMeteringAction
            .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(300, TimeUnit.MILLISECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
        delay(120)
    }

    private suspend fun takePictureOnce(capture: ImageCapture, index: Int): File =
        suspendCancellableCoroutine { continuation ->
            val stamp = System.currentTimeMillis()
            val file = File(context.cacheDir, "snapshot_${stamp}_$index.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()

            continuation.invokeOnCancellation { file.delete() }

            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        if (continuation.isActive) {
                            continuation.resumeWithException(exception)
                        }
                    }
                }
            )
        }

    private companion object {
        const val MIN_ISO = 100
        const val MAX_AUTO_ISO = 6400
        const val PREVIEW_FRAME_STRIDE = 3
    }
}
