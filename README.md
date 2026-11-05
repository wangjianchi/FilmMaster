# FilmMaster — Android 影像 App（M1）

一个独立实现的 Android 相机 + 3D LUT 影像应用，对标"大师模式"的核心体验：
实时取景、胶片风格 Look、强度控制、RAW/高位深管线（M2 起）。

> 本项目不逆向、不提取任何厂商（OPPO / 哈苏等）的闭源代码、私有 LUT 或 ISP 算法。
> 内置风格 LUT 全部由公开的影调/色彩数学**程序化生成**（`ProceduralLuts.kt`）。

## M1 已完成

- CameraX 实时预览（`PreviewView` + `Preview`）
- JPEG 拍照（`ImageCapture`，全分辨率）
- 成片后 GPU 3D LUT（离屏 EGL + GLES3 `sampler3D`，双线性/三线性硬件插值）
- 强度滑杆（`mix(原图, LUT 结果, strength)`）
- 3 个程序化胶片风格：琥珀 400 / 冷调 250 / 褪色 100
- EXIF 方向校正、保存到系统相册 `Pictures/FilmMaster`
- 无 GLES3 / EGL 失败时自动降级到 CPU 三线性 LUT 路径
- 单元测试：`.cube` 解析、三线性采样、强度/Alpha、程序化 LUT 合法性

## 构建

环境要求：Android Studio（Ladybug 及以上）或本地 Android SDK；JDK 17；Android SDK 34。

```bash
# 1) 配置 SDK 路径（首次）
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2) 构建 Debug APK
./gradlew :app:assembleDebug

# 3) 安装到已连接设备
./gradlew :app:installDebug

# 4) 运行单元测试
./gradlew :app:testDebugUnitTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

首次启动会请求 `CAMERA` 权限；授予后取景生效。拍摄后照片写入相册，底部会出现最近一张缩略图。

## 架构简述（M1）

```
CameraX Preview ──► PreviewView（直通取景）
CameraX ImageCapture ──► cacheDir/*.jpg
        │
        ├─ PhotoSaver.loadOriented：BitmapFactory + EXIF 旋转
        ├─ LutGlProcessor.process：EGL(FBO) → LUT shader → glReadPixels → Bitmap
        │      └─ 失败降级 Lut3D.applyArgb（CPU 三线性）
        └─ PhotoSaver.saveToGallery：MediaStore
```

关键文件：

| 文件 | 职责 |
| --- | --- |
| `camera/CameraController.kt` | CameraX 绑定、拍照 |
| `lut/Lut3D.kt` | LUT 数据结构、`.cube` 解析、三线性采样、CPU 应用 |
| `lut/ProceduralLuts.kt` | 3 个程序化胶片风格 |
| `gl/EglCore.kt` | 离屏 EGL（GLES3 优先，ES2 降级） |
| `gl/LutGlProcessor.kt` | 3D LUT shader + FBO + 回读 |
| `image/PhotoSaver.kt` | EXIF 方向、MediaStore 保存 |
| `ui/CameraScreen.kt` | 取景 UI、风格/强度、拍摄流程 |

M1 中 LUT 在**显示参考 sRGB 空间**查表（成片后处理）；M2 会改为线性高位深管线，
把 LUT 放到正确的管线位置（线性化 → 色彩引擎 → 编码）。

## 已知限制（M1）

- 预览为相机直通，尚未套用 LUT；预览分级（半分辨率 LUT+曲线）在 M2/M3 实现。
- 仅 JPEG 输入；RAW/DNG 与线性化在 M2。
- 全分辨率 GPU 处理受 `GL_MAX_TEXTURE_SIZE` 限制，超出时自动降采样（会在 M5 改为分块处理）。
- 锁定竖屏，`targetRotation` 固定 0，简化 M1 的方向处理。
- 不做人脸/场景识别，不做多帧合成。
- `minSdk = 29`：依赖 MediaStore `RELATIVE_PATH / IS_PENDING`，暂不提供旧系统兼容分支。

## 路线图

- **M2**：Camera2/CameraX RAW 能力 + DNG 线性化（`DngCreator` 读取/解码），线性空间 ColorPipeline 接口。
- **M3**：参数化胶片曲线 + 颗粒/暗角 shader，预览半分辨率分级管线。
- **M4**：Auto / Pro UI，配方 JSON 序列化 + 导入导出 + 水印。
- **M5**：多风格 LUT（`.cube` 资源 + 生成脚本）、完整测试、README 完善。

## 许可与声明

示例代码仅用于学习与工程演示。所有胶片风格为原创程序化参数，命名不代表任何厂商的官方风格。
