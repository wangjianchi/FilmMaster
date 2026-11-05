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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.filmmaster.app.camera.SnapshotCameraController
import com.filmmaster.app.gl.LutGlProcessor
import com.filmmaster.app.image.PhotoSaver
import com.filmmaster.app.lut.ProceduralLuts
import com.filmmaster.app.pipeline.MultiFrameProcessor
import com.filmmaster.app.pipeline.SnapshotColorPipeline
import java.io.File
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
    val processor = remember { LutGlProcessor() }
    val looks = remember { ProceduralLuts.looks }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var selectedIndex by remember { mutableStateOf(0) }
    var strength by remember { mutableStateOf(1f) }
    var processing by remember { mutableStateOf(false) }
    var snapshotOn by remember { mutableStateOf(false) }
    var lastShot by remember { mutableStateOf<Bitmap?>(null) }
    var lastInfo by remember { mutableStateOf("") }
    val exposure by controller.readout.collectAsState()

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

    val capture: () -> Unit = {
        if (!processing && hasPermission) {
            scope.launch {
                processing = true
                val tempFiles = mutableListOf<File>()
                val decodedFrames = mutableListOf<Bitmap>()
                var mergedBitmap: Bitmap? = null
                try {
                    val look = looks[selectedIndex]

                    if (snapshotOn) {
                        // 抓拍：4 张短曝光 → 对齐中值合成 → LUT → USM/Clarity
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
                            SnapshotColorPipeline().process(merged, look.lut, strength)
                        }
                        withContext(Dispatchers.IO) { PhotoSaver.saveToGallery(context, result) }

                        lastShot = result
                        lastInfo = "抓拍 · ${composite.usedFrames} 帧合成 · ${look.label} · " +
                            "强度 ${(strength * 100).toInt()}% · " +
                            "${result.width}×${result.height} · 已保存到相册"
                    } else {
                        val shotFile = controller.captureSingle()
                        tempFiles += shotFile

                        val loaded = withContext(Dispatchers.IO) {
                            PhotoSaver.loadOriented(shotFile)
                        }
                        decodedFrames += loaded

                        val result = withContext(Dispatchers.Default) {
                            processor.process(loaded, look.lut, strength)
                        }
                        withContext(Dispatchers.IO) { PhotoSaver.saveToGallery(context, result) }

                        lastShot = result
                        lastInfo = "${look.label} · 强度 ${(strength * 100).toInt()}% · " +
                            "${result.width}×${result.height} · 已保存到相册"
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
                .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 28.dp)
        ) {
            Text(
                text = "FILM MASTER · M1",
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = snapshotOn,
                    enabled = !processing,
                    onClick = {
                        val next = !snapshotOn
                        val applied = runCatching { controller.setSnapshotEnabled(next) }
                        if (applied.isSuccess) {
                            snapshotOn = next
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
                Spacer(Modifier.size(10.dp))
                Text(
                    text = exposureText(exposure),
                    color = if (exposure.degraded) Color(0xFFFFC66D) else Color(0xCCFFFFFF),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                looks.forEachIndexed { index, look ->
                    FilterChip(
                        selected = index == selectedIndex,
                        onClick = { selectedIndex = index },
                        label = { Text(look.label) },
                        enabled = !processing
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("强度", color = Color.White, fontSize = 13.sp)
                Slider(
                    value = strength,
                    onValueChange = { strength = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    enabled = !processing
                )
                Text("${(strength * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))

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
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable(enabled = !processing, onClick = capture),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(
                                if (processing) Color(0xFF666666) else Color.White,
                                CircleShape
                            )
                    )
                }

                Spacer(Modifier.size(52.dp))
            }

            if (lastInfo.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
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
