# FilmMaster — Android 影像 App（M4）

一个独立实现的 Android 相机 + 胶片风格影像应用，对标"大师模式"的核心体验：
实时取景、胶片风格 Look、强度控制、线性高位深管线、抓拍多帧合成、Auto / Pro 与配方系统。

> 本项目不逆向、不提取任何厂商（OPPO / 哈苏等）的闭源代码、私有 LUT 或 ISP 算法。
> 内置风格 LUT 全部由公开的影调/色彩数学**程序化生成**（`ProceduralLuts.kt`）。

## 已完成（M1–M4）

- CameraX 实时预览（`PreviewView` + `Preview`）
- JPEG 拍照（`ImageCapture`，全分辨率）
- 成片后 GPU 3D LUT（离屏 EGL + GLES3 `sampler3D`，硬件三线性插值）
- 强度滑杆（`mix(原图, LUT 结果, strength)`）
- 3 个程序化胶片风格：琥珀 400 / 冷调 250 / 褪色 100
- EXIF 方向校正、保存到系统相册 `Pictures/FilmMaster`
- 无 GLES3 / EGL 失败时自动降级到 CPU 三线性 LUT 路径
- 单元测试：`.cube` 解析、三线性采样、强度/Alpha、程序化 LUT 合法性
- **M2**：线性高位深成片管线（sRGB → 线性 → 曲线 → 编码 → LUT），RAW 能力探测与 JPEG+RAW 采集
- **M3**：参数化胶片曲线（对比 / 伽马 / 黑位 / 高光压缩）、GPU 颗粒与暗角、半分辨率分级预览
- **M4**：Auto / Pro 双模式、配方 JSON 本地保存与 SAF 导入导出、成片水印
- **抓拍模式**：固定短曝光连拍 4 帧 → 平移对齐 + 中值合成 → 线性曲线 / LUT → USM / Clarity

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

## CI 自动构建与发布

工作流：`.github/workflows/android.yml`

- push / PR 到 `master`：运行单元测试 + 构建 debug APK，产物作为 Actions Artifact 保留 30 天
- push `v*` 标签：构建并自动创建 GitHub Release，附带 APK
- Actions 页面可手动触发（`workflow_dispatch`）

发布新版本：

```bash
git tag v1.0-snapshot
git push origin v1.0-snapshot
```

Release 页面即可下载 APK。注意：CI 每次运行生成的 debug keystore 不同，跨 run 覆盖安装会因签名不一致失败，升级前先卸载旧版；若要固定签名，需要额外配置 release keystore secrets 并改用 `assembleRelease`。

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
| `camera/SnapshotCameraController.kt` | CameraX 绑定、拍照、抓拍连拍、RAW、分级预览帧 |
| `camera/CameraController.kt` | M1 遗留的普通相机封装（未接入 UI） |
| `lut/Lut3D.kt` | LUT 数据结构、`.cube` 解析、三线性采样、CPU 应用 |
| `lut/ProceduralLuts.kt` | 3 个程序化胶片风格 |
| `color/ColorMath.kt` | sRGB ↔ 线性光转换 |
| `pipeline/ToneCurve.kt` | 参数化胶片曲线（线性光，预烘焙 1D 表） |
| `pipeline/LinearColorPipeline.kt` | M2 线性高位深成片管线 |
| `pipeline/PreviewGrader.kt` | M3 半分辨率预览分级 |
| `pipeline/MultiFrameProcessor.kt` | 抓拍对齐 + 中值合成 |
| `pipeline/FrameEnhancer.kt` | USM / Clarity / 颗粒 |
| `recipe/Recipe.kt` | M4 配方模型与 JSON 序列化 |
| `recipe/RecipeStore.kt` | 配方持久化与 SAF 导入导出 |
| `gl/EglCore.kt` | 离屏 EGL（GLES3 优先，ES2 降级） |
| `gl/LutGlProcessor.kt` | 3D LUT + 颗粒 / 暗角 shader + FBO 回读 |
| `image/PhotoSaver.kt` | EXIF 方向、水印、MediaStore 保存 |
| `ui/CameraScreen.kt` | Auto / Pro UI、风格、抓拍、配方操作 |
| `ui/Controls.kt` | Pro 面板滑杆组件 |

M2 起，影调曲线在**线性光**下计算（float，预烘焙 1D 表），3D LUT 仍保持
“显示参考”语义并放在曲线之后，因此现有 `.cube` 资源无需改写即可继续使用。

## 已知限制（M1–M4）

- 普通取景仍是相机直通；“分级预览”是 360px 半分辨率、约 10fps 的近似预览，仅供构图参考。
- RAW 只做**采集**：CameraX 的 ImageCapture 不支持 RAW 输出，因此开启 RAW 时会临时释放 CameraX、用 Camera2 + `DngCreator` 拍一张 DNG 写入 `Pictures/FilmMaster/RAW`，再重绑并拍摄 JPEG。DNG 解码 / 去马赛克留到 M5，线性管线当前处理 JPEG。
- 配方只保存创作参数，不含 ISO / 快门等硬件状态。
- 全分辨率 GPU 处理受 `GL_MAX_TEXTURE_SIZE` 限制，超出时自动降采样（M5 计划改为分块处理）。
- 锁定竖屏，`targetRotation` 固定 0。
- 未做人脸/场景识别；抓拍为顺序连拍 + 平移对齐，非 60fps 环形缓冲。
- `minSdk = 29`：依赖 MediaStore `RELATIVE_PATH / IS_PENDING`，暂不提供旧系统兼容分支。

## 路线图

- ~~M2：线性空间 ColorPipeline + RAW 能力 / 采集~~
- ~~M3：参数化胶片曲线 + 颗粒/暗角 shader + 半分辨率分级预览~~
- ~~M4：Auto / Pro UI + 配方 JSON 导入导出 + 水印~~
- **M5**：多风格 LUT（`.cube` 资源 + 生成脚本）、DNG 解码、完整测试、分块 GPU 处理。

## 许可与声明

示例代码仅用于学习与工程演示。所有胶片风格为原创程序化参数，命名不代表任何厂商的官方风格。
