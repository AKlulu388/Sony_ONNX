# ARW Universal — 安卓原生工程

WebView 界面 + 原生处理引擎的安卓工程。网页在 `app/src/main/assets/`，原生层在 `java/` 与 `cpp/`，GitHub Actions 编出 debug APK。

## 目录结构

```
sony-sdm-android/
├── settings.gradle / build.gradle / gradle.properties
├── .github/workflows/build-apk.yml      # CI 打包（JDK17 + SDK34 + NDK26 + CMake）
└── app/
    ├── build.gradle                    # ORT AAR、NDK/CMake 配置
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/                     # WebView 加载的网页（iOS 风格）
        │   ├── index.html  css/  js/
        │   └── models/                 # 放入 *.onnx 模型
        ├── cpp/
        │   ├── CMakeLists.txt          # 源码编译 LibRaw 进 libsdm-raw.so
        │   └── raw_reader_jni.cpp       # LibRaw JNI 包装
        └── java/com/sony/sdm/
            ├── MainActivity.java       # WebView + 文件选择 + 桥接注册
            ├── bridge/NativeBridge.java # 网页 ↔ 原生协议
            ├── jni/RawReader.java       # native 方法声明
            ├── engine/
            │   ├── RawSource / PhaseSplitter / ImageMath
            │   ├── DemosaicNet          # ONNX 推理封装
            │   ├── TileProcessor        # 瓦片拼图流水线
            │   └── SdmEngine            # 编排：解码→推理→输出
            └── io/
                ├── TiffWriter           # 16-bit TIFF
                ├── PreviewWriter        # JPEG 预览
                ├── ArwMeta              # ARW/TIFF EXIF 解析
                └── DngWriter            # CFA DNG / LinearRaw DNG 写入 + 抽样校验
```

## 桥接协议

- 网页 → 原生：`Android.processFile(JSON{mode, options, files})`
- 原生 → 网页：`window.SdmBridge.onLog / onProgress / onResult / onError`
- 保存：`Android.saveFile(name, base64)`

## 已实现

- RAW 解码经 JNI 调 LibRaw：马赛克、CFA、黑/白电平、白平衡、机型/厂商、相机→XYZ 矩阵、可见区域。
- 四相位拆分、归一化、瓦片重叠拼接、余弦羽化、CAS 锐化、噪声 sigma 估计、2×2 降采样、显示 gamma。
- ONNX 瓦片推理封装（256→1024），有限性校验，CPU 推理。
- 三模式：
  - **超分 2×**：瓦片推理 → LinearRaw DNG / TIFF16 + JPEG 预览
  - **AI 降噪 1×**：噪声引导 → 降采样 → LinearRaw DNG / TIFF16 + 预览
  - **ARW → DNG**：原始马赛克逐字节回写 CFA DNG，带色彩档案、AsShotNeutral、黑/白电平、CFA 布局、EXIF（镜头/曝光/光圈/ISO/焦距/时间）与抽样行校验。
- 三模式界面（分段控件、滑杆、步进器、开关）、进度与日志、结果回写下载目录。

## 接缝：仍需补齐 / 注意

1. **ONNX 模型**：把解密好的 `*.onnx` 放进 `app/src/main/assets/models/`（目录内有说明）；首启拷到私有目录。模型缺失时推理模式会提示，`convert` 模式不依赖模型。
2. **LibRaw NDK 编译**：CI 克隆 LibRaw 源码后由 CMake 直接编译其 `src/`、`internal/`；个别版本在 NDK 下可能需要补编译宏/依赖，首次编译按报错微调即可。也可改用预编译静态库（`CMakeLists.txt` 内留了备用段）。
3. **NNAPI 加速**：当前走 CPU；可在 `DemosaicNet` 的 `SessionOptions` 上接入 NNAPI EP，保留异常瓦片回退 CPU。
4. **EXIF 继承**：只回写了镜头/曝光/光圈/ISO/焦距/时间等常用标签；整段 MakerNote 未原样透传（私签标签可能破坏渲染器）。
5. 本环境无 Android SDK/NDK，Java/C++ 未做编译验证；首次构建在 Android Studio 或 CI 上完成。

## 推送与出包

1. 新建仓库，把本目录内容（含隐藏的 `.github/`）提交到 `main`。
2. 推送后 Actions 自动构建，产物 `arw-universal-apk`（`app-debug.apk`）。
3. 本地打开：Android Studio 打开目录，配好 NDK 与 `models/` 后跑 `app`。

## 版权提示

该工程基于逆向复刻思路与解密的索尼模型，仅限个人研究；分发、上架涉及对应知识产权风险。
