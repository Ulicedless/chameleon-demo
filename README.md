# Chameleon · 溶图

一个 Android 应用：把二次元角色图片自然地「溶」进现实照片。核心不是叠一张图，而是让角色接受照片的
光线、颜色、噪点与投影——让二次元立绘看起来真的站在那个场景里。

界面使用 Jetpack Compose + Material 3（Google 原生风格），主色玉青配暖砂与珊瑚点缀，支持 Android 12+
动态取色、亮暗主题、独立设置页与沉浸式预览。所有处理都在本机完成，不联网、不上传图片。

---

## 一、功能

**首页**：选择「角色图片」与「现实照片」，查看自动能力说明与最近作品，右上角进入设置。

**图标**：由项目根目录的 `icon.jpg` 生成（生成脚本 `tools/generate_icon.ps1`，输出在 `app/src/main/res/`）：

- 自适应图标：`drawable-nodpi/ic_launcher_background.png`（原图放大模糊 + 压暗作为底）+ 
  `drawable-nodpi/ic_launcher_foreground.png`（原图缩放到 70% 安全区、圆角、居中），由
  `mipmap-anydpi-v26/ic_launcher.xml` 与 `ic_launcher_round.xml` 组合；
- 单色层：`drawable/ic_launcher_monochrome.xml`（Android 13+ 主题图标，保留变色龙剪影）;
- 兼容位图：`mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png`（圆角方形）与 `ic_launcher_round.png`（圆形），
  角点透明，避免 Lint 的 `IconLauncherShape` 与 `IconDuplicates` 告警；
- 首页标题左侧也使用同一张图作为品牌标识。

替换图标：把新图覆盖到 `icon.jpg`（或指定别的路径），然后重跑生成脚本即可：

```powershell
powershell -ExecutionPolicy Bypass -File tools/generate_icon.ps1
powershell -ExecutionPolicy Bypass -File tools/generate_icon.ps1 -Source D:\pictures\new-icon.png
```

脚本会自己校验结果（背景层必须完全不透明、前景留白必须是透明、中央必须有画面），校验失败会直接报错，
避免生成"看起来是图标、实际上背景透明"的错误资源。

**编辑器**：

- 自动融合：分析照片后一次性给出光源方向、色彩、投影、噪点等参数；
- 预览区支持拖动、双指缩放与旋转（拖动时用低分辨率快速预览，松手后自动跑精细预览）；
- 四个控制页签：位置 / 光影 / 色彩 / 细节，共 30+ 个可调参数；
- 8 种风格预设：自然、影棚、日光、黄昏、夜景、逆光、插画、胶片；
- 「对比」按钮可切换查看「直接贴图（未融合）」的结果；
- 沉浸式预览：点击顶栏放大按钮进入全屏，双指缩放、拖动平移、双击复位、单击隐藏界面，可在预览里切换
  「看原图 / 看融合后」，退出时自动恢复系统栏。

**导出**：可选 1280 / 1920 / 2560 px 与 JPG / PNG，保存到相册（Pictures/Chameleon）或直接分享；
导出后会保留原图与参数到「最近作品」，可以重新打开继续调。

**设置页**：主题（跟随系统 / 浅色 / 深色）、动态取色、是否自动识别场景风格、默认风格、融合强度、
预览质量（流畅 720 / 均衡 1024 / 精细 1280）、导出默认分辨率与格式与质量、自动抠图与默认容差羽化、
是否保留作品、是否显示构图参考线、清理图片缓存与最近作品。

---

## 二、为什么「第一次导出成功、第二次失败」，以及怎么修的

先回答权限疑问：**Android 10 及以上保存到相册不需要任何存储权限**（系统媒体库 MediaStore 代为写入），
所以你在应用设置里找不到「存储权限」是正常的，不是权限被关掉了。

真正的原因在导出链路：

1. 选图返回的是一个系统 `content://` URI，它的读权限只在**本次应用会话**内有效。第一次导出时它还有效，
   之后应用被系统回收/重启，或权限过期，导出时重新解码这张图就会返回 null → 界面显示「导出失败」。
2. 导出要按所选分辨率**重新解码原图**。2560 px 的导出峰值内存接近手机上单个应用进程的上限，第二次导出
   时内存碎片更多，解码或渲染抛出的 `OutOfMemoryError` 会被 catch 变成同一个「导出失败」提示，看不出原因。

修复方式：

- **选图后立刻把原图复制到应用私有目录**（`SourceCache`，PNG，上限 2560 px）。导出、重新编辑都只读这份
  自己的文件，从此不再依赖系统 URI 权限；导出前还会等待复制任务结束。
- **内存不足自动降级**：2560 失败就退到 1792、1254……并最终退到「用内存里已有的预览图导出」，而不是直接
  失败；同时在提示里说明「内存不足，已按 xxx px 导出」。
- **相册写不进去也有兜底**：MediaStore 插入失败时改写到应用外置目录 `Pictures/`，并提示文件位置。
- **导出一定结束**：整个流程包在 try/catch/finally 里，失败会给出具体原因，按钮不会卡在「导出中」。
- 导出期间保持屏幕常亮，避免长时间计算被系统挂起。

---

## 三、融合算法

1. **抠图**：透明底 PNG 直接使用 alpha；白底图用「边框像素 k-means 聚类背景色 → CIELAB 距离直方图 Otsu 阈值
   → 连通域保护（保留内部同色区域，例如白衬衫）→ 两次导向滤波（宽 + 紧）贴住画稿轮廓」，实测与真值
   IoU ≈ 0.96。
2. **边缘净化**：把半透明边缘里残留的背景色（白边 / 彩边）用从画稿内部向外松弛扩散得到的颜色替换，
   越透明替换越多。相比未处理，边缘颜色与理想抠图的误差下降约 30%。
3. **色彩融合**：把角色的 Lab 均值 / 标准差迁移到「角色所在区域」的背景统计量，并按**阴影 / 中间调 /
   高光三个亮度段分别匹配**（照片暗部与高光本来就不是同一种颜色），再做色度压缩与灰世界白平衡。
4. **高光与暗部配色**：主角自己的高光被染上场景主光的颜色，暗部被染上环境光的颜色，解决「角色像来自另一个
   光源」的问题（实测高光色度与主光的距离 6.63 → 2.96）。
5. **光影融合**：用背景低频亮度图作为入射光图（霓虹、窗户光会作用到角色身上），沿光照方向做受光一致性，
   在迎光侧生成轮廓光，在脚部生成环境反射光。
6. **地面投影**：按「点离地高度 h → 沿地面远离光源 h/tan(仰角)」投影，并用相机俯仰压缩系数把投影压回地面，
   因此影子是**贴在地上**向一侧延伸（而不是顺着屏幕往下掉），近处更实、远处更虚（半影随距离增长）。
7. **统一细节**：对齐清晰度、按背景噪声 σ 补噪、颗粒、景深虚化、暗角、轻微色散，最后统一曝光 / 对比 /
   饱和 / 色温色调。

---

## 四、工程结构

```
app/src/main/java/com/chameleon/blend/
├─ core/                     纯 Kotlin 算法层（不依赖 android.graphics，可在 JVM 上单测）
│  ├─ img/RasterImage.kt     浮点平面图像缓冲（R/G/B/A 四个 FloatArray）
│  ├─ img/ImageOps.kt        可分离 / 各向异性模糊、形态学、导向滤波、重采样、噪声与细节统计
│  ├─ img/ParallelRows.kt    按行分块的并行执行器
│  ├─ color/ColorMath.kt     sRGB↔线性、RGB↔CIELAB、分亮度段色度统计、灰世界白平衡、色度压缩
│  └─ blend/
│     ├─ BlendParams.kt      全部参数 + 8 种风格预设
│     ├─ Placement.kt        构图求解（大小 / 地面线 / 三分法落点）
│     ├─ SceneAnalysis.kt    背景分析（光照图、光源方向、主光与环境色及其色度、噪点、细节、地面线、时段）
│     ├─ Matting.kt          抠图（背景聚类 + Otsu 阈值 + 连通域 + 两次导向滤波）
│     ├─ BlendPipeline.kt    融合管线（边缘净化 → 清晰度 → 色彩 → 光影 → 地面投影 → 合成 → 颗粒与色调）
│     └─ BlendEngine.kt      自动调参、场景文案、融合强度缩放、对比用「直接贴图」参数
├─ data/
│  ├─ BitmapIo.kt            sRGB 解码（ImageDecoder，自动 EXIF）、Bitmap↔RasterImage、相册保存与兜底、分享
│  ├─ SourceCache.kt         选图后立即保存的私有原图副本（导出不再依赖系统 URI 权限）
│  ├─ ProjectStore.kt        最近作品（原图 + 结果 + 参数 JSON）
│  ├─ BlendParamsJson.kt     参数与抠图设置的全字段序列化
│  ├─ AppSettings.kt         用户偏好数据类
│  └─ SettingsStore.kt       SharedPreferences 持久化 + StateFlow
├─ ui/
│  ├─ theme/                 Material 3 主题（玉青主色 + 动态取色）
│  ├─ components/Controls.kt 滑块行、卡片、筛选芯片等基础组件
│  ├─ home/HomeScreen.kt     首页
│  ├─ settings/SettingsScreen.kt 设置页
│  └─ editor/
│     ├─ EditorScreen.kt     编辑器 + 预览手势 + 沉浸式预览入口
│     ├─ ImmersivePreview.kt 全屏预览（缩放 / 平移 / 对比 / 隐藏系统栏）
│     ├─ EditorPanels.kt     控制面板与导出弹窗
│     ├─ EditorState.kt      UI 状态
│     └─ EditorViewModel.kt  解码、抠图、分析、两级预览渲染、导出、项目与设置
└─ MainActivity.kt           单 Activity + Compose，支持系统「分享图片到 Chameleon」
```

渲染分两级：拖动时用预览分辨率的一半快速预览，松手 260 ms 后跑精细预览；导出时按所选分辨率重新解码、
重新分析、重新渲染。

---

## 五、构建与运行

环境（本机已验证）：Android Studio 2026.1.1、JBR 21、Android SDK 36.1、AGP 9.2.1、Kotlin 2.2.10、
Compose BOM 2026.02.01、Gradle 9.6.1，minSdk 29。

```powershell
$env:JAVA_HOME="D:\Arsenal\Android\Android Studio\jbr"
$env:ANDROID_HOME="D:\Home\Android-Studio\Android\Sdk"
.\gradlew.bat :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:testDebugUnitTest    # 28 个测试
```

或直接用 Android Studio 打开 `D:\Home\chameleon-demo`。

### 本机已知环境问题（与项目无关）

某些终端里 `%TEMP%` 被解析成 8.3 短路径（`C:\Users\TOBIIC~1\...`），JDK 17+ 用 AF_UNIX 做 Selector
唤醒管道时连接失败，Gradle 会报 `Unable to establish loopback connection`：

```powershell
New-Item -ItemType Directory -Force C:\TempCodex
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\TempCodex"
```

Android Studio 内部构建通常不会触发（IDE 使用正常的长路径 TEMP）。

---

## 六、质量验证

算法层不依赖 Android，可在 JVM 上跑完整数值指标；UI 与平台层用 Robolectric 真实渲染。共 **28 个测试**，
全部通过：

| 测试类 | 覆盖内容 |
| --- | --- |
| `BlendPipelineTest` (7) | 场景分析、自动调参、融合正确性、色彩迁移、确定性、空前景、抠图基础 |
| `BlendQualityTest` (7) | 投影、轮廓光、光照梯度、背景保护、颗粒、预览性能、2560 导出内存 |
| `BlendUpgradeTest` (5) | 边缘净化、分亮度段配色、高光配色、抠图 IoU、投影半影 |
| `HomeScreenRenderTest` (4) | 首页 / 编辑器 / 设置页真实渲染与回调 |
| `AndroidBridgeTest` (5) | PNG 往返与 alpha、项目读写、参数 JSON、私有原图副本、保存兜底 |

关键实测数字：

- 地面投影：脚前光向带内 p15 亮度 0.324 → 0.188
- 轮廓光：迎光侧边缘 +0.29，背光侧 +0.07
- 光照梯度：背景亮区内角色 +0.051，暗区 +0.001
- 背景保护：脚印影响区外 0 像素漂移（合成严格局部化）
- 高光配色：与主光色度距离 6.63 → 2.96；分亮度段色度误差 8.39 → 6.33
- 边缘净化：与理想抠图的误差 0.119 → 0.084
- 抠图：与真值 IoU 0.963
- 投影半影：近处宽度 74 px / 远处 76 px，核心深度 0.43 → 0.24（更柔和但仍结实）
- 性能：1280×900 快速预览 ~0.26 s、精细 ~0.30 s；2560×1920 导出 2.3 s，136 MB 堆内完成

测试也会把可视化结果写到 `app/build/engine-preview/`（`01-naive-paste.png` 与 `02-blended.png` 便于对比）。

---

## 七、使用建议

- 角色图优先用**透明底 PNG 立绘**；白底图会自动抠图，可在「位置 / 细节」里调容差与羽化，在「细节」里调
  边缘净化消除白边。
- 先确认脚部是否落在地面线上（真实感第一要素），再调「投影强度 / 接触阴影 / 投影长度」。
- 夜景、霓虹场景把「轮廓光」和「噪点匹配」拉高；想保留插画感就用「插画」风格并把「色彩融合」降到 0.3 左右。
- 手机较慢时在设置里把预览质量改为「流畅」，导出仍可选 2560。

## 八、后续可扩展

- 用 AGSL `RuntimeShader`（API 33+）把几何与光照搬到 GPU，导出速度可再提升一个量级；
- 手绘蒙版笔刷，处理抠图困难的复杂背景；
- 参考图驱动的风格迁移（把某张照片的调色直接映射到成片）。
