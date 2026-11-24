# 安卓工程落地指南（小白一步步版）

> 配套工程：`sony-sdm-android.zip`
> 目标：把这个解压后的文件夹上传到 GitHub，由 Actions 自动打出能装到安卓手机的 APK。
> 本指南按「文件放哪 → 准备什么 → 怎么上传 → 剩下的活怎么做 → 出问题怎么办」顺序写，照着做即可。

---

## 一、先搞清楚这套东西是什么

- **界面**是一个网页（`assets/` 里的 HTML/CSS/JS），装在 App 的 WebView 里，样式按 iOS 风格做。
- **干活的引擎**是安卓原生代码（Java + 一个 C++ 原生库）：NDK 编译 LibRaw 读 RAW，ONNX 模型跑超分/降噪，Java 层做拆分、拼图、写文件。
- **打包**：你不用在电脑上编译，把代码推到 GitHub，Actions 在云端装好 SDK/NDK 自动出 APK。
- **两个必须由你提供的外部件**：① 解密好的 ONNX 模型；② LibRaw 源码（CI 会自动从 GitHub 克隆，不用你手动下）。

> 法律提示：这套代码和模型是逆向/解密得来的，**只适合个人研究**，不要公开仓库、不要上架应用商店、不要分发给别人。建议把 GitHub 仓库设为 **Private（私有）**。

---

## 二、完整目录地图：每个文件放哪个目录

解压后根目录是 `sony-sdm-android/`。下面树形图里每一行都标了「这个文件是干什么的 / 要不要你动」。

```
sony-sdm-android/                         ← 整个文件夹就是仓库根目录，所有内容一起上传
│
├── settings.gradle                        工程设置（包名、包含 app 模块）——不用改
├── build.gradle                           根目录构建脚本，声明 AGP 8.7.3 ——不用改
├── gradle.properties                      Gradle/JVM 参数 ——不用改
├── .gitignore                             忽略编译产物 ——不用改
├── README.md                              工程说明 ——可看可改
│
├── .github/
│   └── workflows/
│       └── build-apk.yml                  ← CI 打包脚本：装 JDK17 / SDK34 / NDK26 / CMake，出 APK
│
└── app/
    ├── build.gradle                       App 模块配置：包名 com.sony.sdm、minSdk24、ORT 依赖、NDK/CMake 入口
    │
    └── src/main/
        ├── AndroidManifest.xml            App 清单：权限、启动页、标题「ARW Universal」
        │
        ├── assets/                        ← WebView 加载的网页资源（和 App 一起打包）
        │   ├── index.html                 页面骨架（标题、模式按钮、文件选择、参数、进度、结果）
        │   ├── css/
        │   │   └── style.css               iOS 风格样式（圆角、分段控件、滑杆、开关、配色）
        │   ├── js/
        │   │   ├── engine.js              桥接层：网页调 Android.processFile，接收 SdmBridge 回调
        │   │   └── ui.js                  界面逻辑：选文件、参数收集、进度/日志/结果渲染
        │   └── models/                    ★ 你自己的 ONNX 模型放这个目录（见第六节）
        │       ├── .gitkeep               占位文件，保证空目录能进 git，留着别删
        │       └── README.md               模型放置说明
        │
        ├── cpp/                           ← C++ 原生层（NDK 编译成 libsdm-raw.so）
        │   ├── CMakeLists.txt             LibRaw 源码编译配置 + JNI 库目标
        │   ├── raw_reader_jni.cpp         LibRaw 的 JNI 包装：读马赛克/CFA/黑白电平/WB/矩阵/机型
        │   └── libraw/                    ★ LibRaw 源码目录：CI 自动克隆到这里，本地编译时也是这个位置
        │
        └── java/com/sony/sdm/             ← Java 原生层（包名目录，层级不能改）
            ├── MainActivity.java           启动页：建 WebView、注册桥接、文件选择器、拷模型
            ├── bridge/
            │   └── NativeBridge.java       网页 ↔ 原生协议：收任务、后台执行、回写日志/进度/结果到网页
            ├── jni/
            │   └── RawReader.java          native 方法声明，对应 cpp 里的 C++ 函数
            ├── engine/
            │   ├── RawSource.java          一次解码得到的 RAW 数据源（含相位、WB、矩阵、机型等）
            │   ├── PhaseSplitter.java      Bayer 马赛克拆成 R/G1/G2/B 四个相位平面
            │   ├── ImageMath.java          数值工具：羽化窗、噪声 sigma 估计、CAS 锐化、WB、降采样、gamma
            │   ├── DemosaicNet.java        ONNX 会话封装：256 瓦片输入 → 1024 输出，有限性校验
            │   ├── TileProcessor.java      瓦片循环、重叠 32、余弦羽化加权拼图
            │   └── SdmEngine.java          总编排：解码→(推理/降噪)→写文件；三种模式分支
            └── io/
                ├── TiffWriter.java         16-bit RGB TIFF 写入
                ├── PreviewWriter.java      JPEG 预览写入
                ├── ArwMeta.java            ARW/TIFF EXIF 解析（镜头、快门、光圈、ISO、焦距、时间）
                └── DngWriter.java          CFA DNG / LinearRaw DNG 写入 + 抽样行校验
```

### 2.1 原始 Python 文件 → 安卓对应位置（方便你对照）

| 原来的 `.py` | 功能 | 安卓里对应到哪个目录/文件 |
|---|---|---|
| `pipeline.py` | 瓦片推理、相位拆分、数值计算 | `java/.../engine/`（PhaseSplitter / ImageMath / DemosaicNet / TileProcessor） |
| `worker.py` / `export.py` | 作业调度、结果汇总 | `java/.../engine/SdmEngine.java` + `bridge/NativeBridge.java` |
| `dng.py` | DNG/LinearRaw 写入、色彩矩阵 | `java/.../io/DngWriter.java` |
| `exif.py` | 读 EXIF 镜头/曝光信息 | `java/.../io/ArwMeta.java` |
| `writers.py` | TIFF / 预览写出 | `java/.../io/TiffWriter.java`、`PreviewWriter.java` |
| `convert.py` | ARW → DNG 转换流程 | `SdmEngine.java` 的 `convert` 分支 + `DngWriter.writeCfaDng` |
| `gui.py` | 桌面界面 | `assets/` 网页（index.html / style.css / ui.js） |
| `cli.py` | 命令行参数 | `engine.js` 提交的任务 JSON + `NativeBridge` 解析 |
| `models.py` | 模型解密/加载 | `MainActivity.java` 拷模型 + `DemosaicNet.java` 加载 |
| `runtime.py` / `history.py` / `sdmconfig.py` | 路径、历史、配置 | 安卓侧用私有目录替代，暂无对应文件 |

---

## 三、动手前要准备的东西

### 3.1 账号与软件
1. **GitHub 账号**：注册一个，仓库务必设私有。
2. **二选一**：
   - **路线 A（推荐）**：电脑只需要浏览器，代码推到 GitHub，Actions 云端编译，你只下载 APK。
   - **路线 B（排错用）**：电脑装 **Android Studio**（下载地址 developer.android.com/studio），安装时勾选 Android SDK、NDK、CMake；本地能编译、能连手机调试。

### 3.2 解压
把 `sony-sdm-android.zip` 解压，得到 `sony-sdm-android/` 文件夹。**注意不要把文件夹再套一层**——仓库根目录里应能直接看到 `settings.gradle`、`app/`、`.github/`。

### 3.3 手机要求
- 安卓 **7.0（API 24）或更高**；arm64 手机为主（NDK 同时编了 x86_64 模拟器）。

---

## 四、路线 A：用 GitHub Actions 出 APK（照着点）

### 第 1 步：新建私有仓库
1. 登录 GitHub，右上角 `+` → `New repository`。
2. 仓库名随便填，例如 `sony-sdm`。
3. 选中 **Private**。
4. 不要勾选「Add a README / .gitignore」（我们已有文件），点 `Create repository`。

### 第 2 步：上传工程文件
1. 在仓库页面点 `uploading an existing file` 链接（或 `Add file` → `Upload files`）。
2. 把解压出来的 `sony-sdm-android/` **里面的全部内容**拖进去。
   - 关键：`app/`、`.github/`、`settings.gradle`、`build.gradle` 这些要在仓库根目录。
   - **`.github` 是隐藏文件夹**，网页上传时一般能看到；若你的文件管理器看不到，先在系统里打开「显示隐藏文件」。
3. 页面底部点 `Commit changes`。

> 怎么确认传对了：仓库根目录能看到 `app` 文件夹，点进去是 `build.gradle` 和 `src`；而不是根目录又套一个 `sony-sdm-android` 文件夹。

### 第 3 步：放入 ONNX 模型（见第六节第 1 项）
把模型文件传到 `app/src/main/assets/models/` 目录。可以在网页上进入该文件夹后 `Add file` → `Upload files`。

### 第 4 步：等 Actions 打包
1. 仓库顶部点 `Actions` 标签。
2. 会看到 `Build APK` 工作流在跑（黄色圆点）。
3. 跑完变绿勾：点进去 → 最下面 `Artifacts` 区域下载 `arw-universal-apk`，解压得到 `app-debug.apk`。
4. 若是红叉：点开失败的步骤，把红色报错对照第七节排查。

### 第 5 步：装到手机
1. 把 APK 发到手机（微信/QQ/数据线/网盘均可）。
2. 手机上点 APK 安装，首次会提示「未知来源应用」，允许本次安装即可。
3. 打开 App：选 ARW 文件 → 选模式 → 开始处理；结果在手机「下载」目录里。

---

## 五、路线 B：本地 Android Studio 编译（用于排错）

1. 打开 Android Studio → `Open` → 选解压后的 `sony-sdm-android/` 文件夹。
2. 首次同步会提示装 SDK 34、NDK 26、CMake 3.22.1，按提示接受安装。
3. LibRaw 源码：
   - 让 CMake 自动拉取，或手动执行：
     ```bash
     git clone --depth 1 https://github.com/LibRaw/LibRaw.git app/src/main/cpp/libraw
     ```
4. 模型放进 `app/src/main/assets/models/`（见第六节）。
5. 顶部菜单 `Build` → `Build APK`；产物在 `app/build/outputs/apk/debug/app-debug.apk`。
6. 连手机（打开开发者选项 → USB 调试），点绿色三角运行。

---

## 六、剩下的活：每项怎么做

### 6.1 ★ 放入 ONNX 模型（必须，否则推理模式不能用）

**放哪**：`app/src/main/assets/models/` 目录，文件名以 `.onnx` 结尾，例如：

```
app/src/main/assets/models/9981FFE2.onnx
```

**规则**：
- App 启动时会自动拷贝 `models/` 下**第一个** `.onnx` 到私有目录加载，文件名不强制，放一个即可。
- 单个模型约 92MB；GitHub 单文件上限 100MB，能传，但仓库会变大，所以务必私有。
- 推理（超分 2× / AI 降噪 1×）必须有模型；**ARW→DNG 转换模式不依赖模型**，没模型也能跑转换。
- 如果以后模型太大不想进仓库：把 `MainActivity.java` 的拷贝逻辑改成「首次启动从你自己的下载地址拉取到私有目录」，这是可选改造，不是必须。

### 6.2 LibRaw 源码（CI 自动处理，一般不用你动手）

- 位置：`app/src/main/cpp/libraw/`。
- CI 脚本 `build-apk.yml` 里有一步 `Prepare LibRaw sources`，会自动 `git clone` 到这个目录；`CMakeLists.txt` 直接把其 `src/`、`internal/` 编进原生库。
- 你**不需要**手动编译 LibRaw 静态库。
- 万一 CI 在 LibRaw 编译步骤报错：常见原因是某个源文件需要编译宏或平台依赖。把报错贴出来，按缺什么补什么；`CMakeLists.txt` 里也留了「改用预编译静态库」的备用注释段。

### 6.3 DNG 与 EXIF（代码已就位，说明现状）

- **已完成**：CFA DNG、LinearRaw DNG 写入；色彩矩阵取逆写 ColorMatrix1；白平衡换算 AsShotNeutral；黑白电平、CFA 布局、ActiveArea；镜头/快门/光圈/ISO/焦距/时间写进 ExifIFD；写回后抽样三行校验。
- **没做的部分**：整段 MakerNote 原样透传（私签标签可能让渲染器崩溃，当前只回写常用标签）。日常出 DNG 给 Lightroom / Camera Raw 用，现有标签足够。

### 6.4 可选：接 NNAPI 加速器（不做也能跑）

- 现状：推理走 CPU（`DemosaicNet.java` 的 `SessionOptions`）。
- 想加速：在 `DemosaicNet` 里给 SessionOptions 加 NNAPI EP，并保留「加速器输出异常就回退 CPU」的逻辑；这是优化项，不影响出包。

### 6.5 出图后自检清单

- [ ] 能选到 ARW 文件并打开（解码无报错）。
- [ ] 超分/降噪模式出图尺寸正确（2× 模式约为原图两倍边长）。
- [ ] 预览图色彩不偏绿、不偏紫；DNG/TIFF 在 Lightroom 里能打开。
- [ ] ARW→DNG 转换出的 DNG 在相机 Raw 软件里能看到完整画面和镜头信息。
- [ ] 结果文件出现在手机「下载」目录。

---

## 七、常见报错对照表

| 现象 | 可能原因 | 怎么办 |
|---|---|---|
| Actions 在 `Prepare LibRaw sources` 失败 | 网络没拉到 LibRaw，或源码版本不兼容 | 看日志；重跑一次；仍失败就按 6.2 手动处理 |
| Actions 在 `Build Debug APK` 失败 | Java/C++ 编译错误、依赖下载失败 | 点开失败步骤看红线；先确认 SDK34/NDK26/CMake 装好；把报错信息记录下来 |
| App 打开后提示「缺少 ONNX 模型」 | `assets/models/` 里没有 `.onnx` | 按 6.1 放模型，再重新打包 |
| 网页提示「引擎未接入」 | 原生桥没挂上 / `engine.js` 路径不对 | 确认 `assets/js/engine.js` 随包打进；WebView 控制台无 404 |
| APK 装不上 | 手机系统低于安卓 7.0；或安装包架构不匹配 | 换 Android 7.0+ 手机；arm64 手机用 arm64-v8a 包 |
| 转换 DNG 在软件里打不开 | DNG 标签/矩阵问题；或源文件已是线性 DNG 无马赛克 | 先用预览 JPEG 看是否正常；检查日志里的黑/白电平与 CFA 图案 |
| 推理结果发灰/偏色 | 色彩矩阵、白平衡或模型不匹配 | 确认模型与机型对应；检查日志里 WB 增益是否为正 |
| 下载的 Artifact 里没有 APK | 构建没成功，或产物路径变了 | 回到 Actions 确认构建绿勾；路径是 `app/build/outputs/apk/debug/app-debug.apk` |

---

## 八、关键配置速查（不用改，了解即可）

| 项 | 值 | 位置 |
|---|---|---|
| 包名 / applicationId | `com.sony.sdm` | `app/build.gradle` |
| 最低系统 | Android 7.0（API 24） | `app/build.gradle` |
| 目标 / 编译 SDK | 34 | `app/build.gradle` |
| ABI | `arm64-v8a`、`x86_64` | `app/build.gradle` |
| ORT 版本 | `onnxruntime-android:1.17.0` | `app/build.gradle` |
| AGP / Gradle / JDK | 8.7.3 / 8.9 / 17 | 根 `build.gradle`、CI 脚本 |
| NDK / CMake | 26.1.10909125 / 3.22.1 | CI 脚本、`app/build.gradle` |
| 桥接对象名 | `Android`；回调命名空间 `window.SdmBridge` | `NativeBridge.java`、`engine.js` |

---

## 九、重要提醒

1. **本环境没有 Android SDK/NDK**，工程里的 Java/C++ 只做了静态检查，**没有实际编译通过过**；第一次在 CI 或 Android Studio 构建时，大概率要按报错做少量修正，这是正常的。
2. 模型与代码涉及对索尼专有资产的逆向和解密，**仅限个人技术研究**；公开仓库、分发、上架都有知识产权风险。
3. 若要继续完善，优先级建议：先让 CI 能编出 APK → 放模型验证出图 → 再补 NNAPI 加速和 MakerNote 透传。
