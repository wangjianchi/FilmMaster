package com.filmmaster.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.filmmaster.app.camera.RawDngCapture
import com.filmmaster.app.camera.SnapshotCameraController
import com.filmmaster.app.image.PhotoSaver
import com.filmmaster.app.lut.ProceduralLuts
import com.filmmaster.app.pipeline.FrameEnhancer
import com.filmmaster.app.pipeline.LinearColorPipeline
import com.filmmaster.app.pipeline.MultiFrameProcessor
import com.filmmaster.app.pipeline.PreviewGrader
import com.filmmaster.app.pipeline.SnapshotColorPipeline
import com.filmmaster.app.recipe.Recipe
import com.filmmaster.app.recipe.RecipeStore
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val controller = remember { SnapshotCameraController(context, lifecycleOwner) }
    val looks = remember { ProceduralLuts.looks }
    val rawDngCapture = remember { RawDngCapture(context) }

    var recipe by remember { mutableStateOf(RecipeStore.load(context) ?: Recipe()) }
    var proMode by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var processing by remember { mutableStateOf(false) }
    var snapshotOn by remember { mutableStateOf(false) }
    var previewGradingOn by remember { mutableStateOf(false) }
    var rawOn by remember { mutableStateOf(false) }
    var shutterDen by remember { mutableStateOf(125f) }
    var isoManual by remember { mutableStateOf(3200f) }
    var lastShot by remember { mutableStateOf<Bitmap?>(null) }
    var lastInfo by remember { mutableStateOf("") }

    val exposure by controller.readout.collectAsState()
    val gradedPreview by controller.gradedPreview.collectAsState()

    val selectedLook = looks.firstOrNull { it.id == recipe.lookId } ?: looks.first()

    // 预览分级与成片共用同一套参数：每次配方变化只重建轻量 grader，不重绑相机会话
    val graderRef = remember { AtomicReference<PreviewGrader?>(null) }
    SideEffect {
        graderRef.set(
            PreviewGrader(
                lut = selectedLook.lut,
                strength = recipe.strength,
                curve = recipe.curve(),
                grain = recipe.grain,
                vignette = recipe.vignette
            )
        )
    }

    LaunchedEffect(previewGradingOn) {
        controller.setPreviewGrading(previewGradingOn) { bitmap ->
            graderRef.get()?.grade(bitmap) ?: bitmap
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "未授予相机权限，无法预览", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) {
            controller.bind(previewView) { throwable ->
                Toast.makeText(context, "相机启动失败: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
        }
        onDispose { controller.unbind() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching { RecipeStore.export(context, uri, recipe) }
                .onSuccess { Toast.makeText(context, "配方已导出", Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(context, "导出失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { RecipeStore.import(context, uri) }
                .onSuccess {
                    recipe = it
                    Toast.makeText(context, "已导入配方「${it.name}」", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(context, "导入失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    val capture: () -> Unit = {
        if (!processing && hasPermission) {
            scope.launch {
                processing = true
                val tempFiles = mutableListOf<File>()
                val decodedFrames = mutableListOf<Bitmap>()
                var mergedBitmap: Bitmap? = null
                try {
                    val look = selectedLook
                    val seed = (System.nanoTime() and 0xFFFF).toInt()
                    val curve = recipe.curve()

                    if (snapshotOn) {
                        // 抓拍：短曝光连拍 → 对齐中值合成 → 线性曲线 + LUT → USM/Clarity
                        val shots = controller.captureBurst(SNAPSHOT_BURST)
                        tempFiles += shots

                        val frames = withContext(Dispatchers.IO) {
                            shots.map { PhotoSaver.loadOriented(it) }
                        }
                        decodedFrames += frames

                        val composite = withContext(Dispatchers.Default) {
                            MultiFrameProcessor().process(frames)
                        }
                        val merged = composite.bitmap
                        mergedBitmap = merged

                        val result = withContext(Dispatchers.Default) {
                            SnapshotColorPipeline(
                                pipelineFactory = { lut, strength ->
                                    LinearColorPipeline(
                                        lut = lut,
                                        strength = strength,
                                        curve = curve,
                                        grain = recipe.grain,
                                        vignette = recipe.vignette,
                                        seed = seed
                                    )
                                },
                                enhancer = FrameEnhancer()
                            ).process(
                                merged = merged,
                                lut = look.lut,
                                strength = recipe.strength,
                                sharpness = recipe.sharpness,
                                clarity = recipe.clarity,
                                grain = 0f
                            )
                        }
                        withContext(Dispatchers.IO) {
                            PhotoSaver.saveToGallery(context, result, watermark = recipe.watermark)
                        }

                        lastShot = result
                        lastInfo = "抓拍 · ${composite.usedFrames} 帧合成 · ${look.label} · " +
                            "${result.width}×${result.height} · 已保存到相册"
                    } else {
                        if (rawOn) {
                            val dng = controller.captureRawThenRebind(rawDngCapture)
                            if (dng == null) {
                                Toast.makeText(
                                    context,
                                    "RAW 采集失败，仅保存 JPEG",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        val shotFile = controller.captureSingle()
                        tempFiles += shotFile

                        val loaded = withContext(Dispatchers.IO) {
                            PhotoSaver.loadOriented(shotFile)
                        }
                        decodedFrames += loaded

                        val result = withContext(Dispatchers.Default) {
                            LinearColorPipeline(
                                lut = look.lut,
                                strength = recipe.strength,
                                curve = curve,
                                grain = recipe.grain,
                                vignette = recipe.vignette,
                                seed = seed
                            ).process(loaded)
                        }
                        withContext(Dispatchers.IO) {
                            PhotoSaver.saveToGallery(context, result, watermark = recipe.watermark)
                        }

                        lastShot = result
                        lastInfo = "${look.label} · ${result.width}×${result.height}" +
                            (if (rawOn) " · JPEG+RAW" else "") + " · 已保存到相册"
                    }
                    Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(context, "处理失败: ${t.message}", Toast.LENGTH_LONG).show()
                } finally {
                    withContext(Dispatchers.IO) {
                        decodedFrames.forEach { if (!it.isRecycled) it.recycle() }
                        mergedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        tempFiles.forEach { it.delete() }
                    }
                    processing = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            if (previewGradingOn) {
                gradedPreview?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "分级预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            PermissionPanel(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FILM MASTER · M4",
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !proMode,
                    onClick = { proMode = false },
                    label = { Text("Auto") }
                )
                Spacer(Modifier.size(6.dp))
                FilterChip(
                    selected = proMode,
                    onClick = { proMode = true },
                    label = { Text("Pro") }
                )
            }
            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = exposureText(exposure),
                    color = if (exposure.degraded) Color(0xFFFFC66D) else Color(0xCCFFFFFF),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = snapshotOn,
                    enabled = !processing,
                    onClick = {
                        val next = !snapshotOn
                        val applied = runCatching { controller.setSnapshotEnabled(next) }
                        if (applied.isSuccess) {
                            snapshotOn = next
                            if (next) rawOn = false
                        } else {
                            Toast.makeText(
                                context,
                                "抓拍模式不可用，已回到普通模式",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    label = { Text("抓拍") }
                )
                FilterChip(
                    selected = previewGradingOn,
                    enabled = !processing,
                    onClick = { previewGradingOn = !previewGradingOn },
                    label = { Text("分级预览") }
                )
                if (controller.supportsRaw) {
                    FilterChip(
                        selected = rawOn,
                        enabled = !processing && !snapshotOn,
                        onClick = { rawOn = !rawOn },
                        label = { Text("RAW") }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                looks.forEach { look ->
                    FilterChip(
                        selected = look.id == selectedLook.id,
                        onClick = { recipe = recipe.copy(lookId = look.id) },
                        label = { Text(look.label) },
                        enabled = !processing
                    )
                }
            }

            if (proMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 236.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    LabeledSlider(
                        label = "强度",
                        value = recipe.strength,
                        range = 0f..1f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(strength = it) }
                    )

                    LabeledSlider(
                        label = "对比",
                        value = recipe.contrast,
                        range = -0.6f..0.6f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(contrast = it) },
                        formatter = { String.format("%+.2f", it) }
                    )
                    LabeledSlider(
                        label = "伽马",
                        value = recipe.gamma,
                        range = 0.7f..1.6f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(gamma = it) },
                        formatter = { String.format("%.2f", it) }
                    )
                    LabeledSlider(
                        label = "黑位",
                        value = recipe.lift,
                        range = 0f..0.2f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(lift = it) },
                        formatter = { String.format("%.3f", it) }
                    )
                    LabeledSlider(
                        label = "高光",
                        value = recipe.rolloff,
                        range = 0f..0.8f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(rolloff = it) }
                    )
                    LabeledSlider(
                        label = "颗粒",
                        value = recipe.grain,
                        range = 0f..1f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(grain = it) }
                    )
                    LabeledSlider(
                        label = "暗角",
                        value = recipe.vignette,
                        range = 0f..1f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(vignette = it) }
                    )
                    LabeledSlider(
                        label = "锐化",
                        value = recipe.sharpness,
                        range = 0f..1f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(sharpness = it) }
                    )
                    LabeledSlider(
                        label = "清晰",
                        value = recipe.clarity,
                        range = 0f..1f,
                        enabled = !processing,
                        onChange = { recipe = recipe.copy(clarity = it) }
                    )

                    if (snapshotOn) {
                        LabeledSlider(
                            label = "快门",
                            value = shutterDen,
                            range = 30f..500f,
                            enabled = !processing,
                            onChange = { shutterDen = it },
                            onFinish = {
                                controller.setShutterDenominator(shutterDen.toInt())
                            },
                            formatter = { "1/${it.toInt()}" }
                        )
                        LabeledSlider(
                            label = "ISO",
                            value = isoManual,
                            range = 100f..6400f,
                            enabled = !processing,
                            onChange = { isoManual = it },
                            onFinish = { controller.setIsoOverride(isoManual.toInt()) },
                            formatter = { it.toInt().toString() }
                        )
                    }

                    OutlinedTextField(
                        value = recipe.watermark,
                        onValueChange = { recipe = recipe.copy(watermark = it.take(40)) },
                        label = { Text("水印（留空不加水印）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = !processing,
                            onClick = {
                                RecipeStore.save(context, recipe)
                                Toast.makeText(context, "配方已保存", Toast.LENGTH_SHORT).show()
                            }
                        ) { Text("保存") }
                        TextButton(
                            enabled = !processing,
                            onClick = { exportLauncher.launch("film-recipe.json") }
                        ) { Text("导出") }
                        TextButton(
                            enabled = !processing,
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }
                        ) { Text("导入") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("强度", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = recipe.strength,
                        onValueChange = { recipe = recipe.copy(strength = it) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        enabled = !processing
                    )
                    Text("${(recipe.strength * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val shot = lastShot
                if (shot != null) {
                    Image(
                        bitmap = shot.asImageBitmap(),
                        contentDescription = "最近一张",
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Spacer(Modifier.size(52.dp))
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable(enabled = !processing, onClick = capture),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(
                                if (processing) Color(0xFF666666) else Color.White,
                                CircleShape
                            )
                    )
                }

                Spacer(Modifier.size(52.dp))
            }

            if (lastInfo.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(lastInfo, color = Color(0xB3FFFFFF), fontSize = 11.sp)
            }
        }

        if (processing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFFE0A458))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (snapshotOn) "正在对齐合成 $SNAPSHOT_BURST 帧…"
                        else "正在处理全分辨率照片…",
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionPanel(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要相机权限才能开始拍摄", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequest) {
                Text("授予权限")
            }
        }
    }
}

private const val SNAPSHOT_BURST = 4

private fun exposureText(readout: SnapshotCameraController.ExposureReadout): String {
    if (readout.shutterNs <= 0L) return "快门 / ISO 读取中"
    val denominator = (1_000_000_000.0 / readout.shutterNs).roundToInt()
    val shutter = if (denominator >= 1) "1/$denominator" else "${readout.shutterNs / 1_000_000}ms"
    val base = "$shutter · ISO ${readout.iso}"
    return if (readout.degraded) "$base · 降级" else base
}
