# Lector · Android（Kotlin 原生）

> **0.2.0-exp** —— 实验版。UI 整体换用 Material 3 Expressive、新增可配置翻页手势、导入改为应用内全盘扫描。改动较激进，更新卡片可以直接叉掉（按版本永久忽略）。

网页版 Lector 的原生安卓实现：**导入 TXT / EPUB，轻点正文任意字，AI 便从那里开始朗读**。自填 API Key、自传书籍，无账号、无后端。

> 设计语言：**Material 3 Expressive**（material3 1.5.0-alpha18 的 `MaterialExpressiveTheme`），配色用 Google 的色彩算法生成而非手挑，动效则把 M3 的动效 token 与自研 CA 管线融合。布局骨架仍保留 One UI 式的大标题下沉与拇指可达。浅色/深色跟随系统，可选 Material You 动态取色。

## 功能

- **任意字点读**：渲染层（Compose `TextLayoutResult.getOffsetForPosition`）与朗读引擎共享同一套「章节纯文本字符偏移」坐标系，点击 → 字符偏移 → 二分定位合成片段 → 起播；句级跟随高亮 + 自动居中滚动。
- **AI TTS**：OpenAI 兼容语音接口（`POST {baseUrl}/audio/speech`），默认 OpenRouter；在线拉取语音模型、内置各模型音色目录（中文名/性别/风格、语言分组、每模型记忆音色）、OpenRouter 方言适配（`response_format` 收敛、Gemini PCM 自动封 WAV、开放音色 ID）、归因头；兼容 OpenAI 官方、SiliconFlow、FishAudio、自建服务（允许明文 HTTP 局域网地址）。
- **系统语音兜底**：Android TextToSpeech 合成到 WAV 后走同一条 ExoPlayer 管线——无 Key 也能听，且同样支持锁屏控制与倍速。
- **锁屏 / 通知栏 / 耳机线控**：Media3 `MediaSessionService` + 自定义 `SimpleBasePlayer` 把引擎状态映射为媒体会话（上一曲/下一曲 = 跳章，展示章节名与封面），播放中为前台服务，后台连播；焦点丢失/拔耳机自动暂停。
- **断网自愈**：指数退避重试（1s→30s，±20% 抖动，最多 8 次），单段穷尽自动跳段续播，连续 3 段失败才停播；401/404 等配置错误快速失败。
- **流畅播放**：句子级分段（ICU `BreakIterator` + 中文句末标点细分）合并为 ≤120 字片段（80–400 可调），当前片段播放时预取后续片段；音频按 `provider|模型|音色|格式|指令|文本` 哈希缓存（LRU 300MB / 800 条）。
- **阅读器**：五套主题、字号/行距/段距/衬线、目录、章末导航、睡眠定时（15–90 分钟或播完本章）、倍速轮换。
- **可配置翻页手势**（0.2.0）：滑动方向可选左右 / 上下 / 关闭；点击翻页热区可放在左右两侧或上下两端，两端占比各自可调（带实时预览），可交换方向、可关闭；「轻点朗读」可单独开关；滑动识别阈值可调，避免想点读却翻了页。默认值与 0.1.x 逐项等价，不动设置的用户手感不变。
  *上下滑还能绕开一个系统层面的冲突：手势导航会先吃掉屏幕左右边缘约 20~40dp 内起手的横滑，那一带只能返回、翻不了页。*
- **书架**：封面网格、继续阅读、书名/作者搜索、三种排序、长按删除。
- **应用内导入**（0.2.0）：自动扫描本机全部 TXT / EPUB，列出文件名/EPUB 书名、大小、日期、来源与路径，支持搜索、按类型筛选、多选批量入库，已在书架的会标出来。
  访问能力按系统给到的档位分三层，并**永远不强制**任何一种：整机权限（API 30+ 的「所有文件访问」，或 API ≤28 的读存储）→ 媒体库一条 SQL 查完 + 文件系统遍历补齐；SAF 目录授权 → `DocumentsContract` 批量游标遍历（比 `DocumentFile.listFiles()` 快一个数量级）；都没有 → 退回系统文件选择器。
- **解析**：TXT（BOM/严格 UTF-8 → juniversalchardet 探测 GBK/GB18030/Big5 → 候选严格解码；多套章节正则取匹配最多者，无结构时 8000 字兜底分块）；EPUB（`container.xml` → OPF → manifest/spine → nav/NCX 标题 → jsoup 块级元素抽段落，封面缩放为 ≤360px JPEG）。

## 工程

- Kotlin 2.2 · Jetpack Compose（compose-bom 2026.06.01 / **material3 1.5.0-alpha18** + androidx.graphics:graphics-shapes）· Room（KSP）· Media3 ExoPlayer/Session · OkHttp · kotlinx.serialization · jsoup · juniversalchardet

> material3 刻意固定在 BOM 之外：直到 2026.08.00，每一个 compose-bom 都仍把 material3 锁在 1.4.0，而 1.4.0 里 Expressive 的入口（`MaterialExpressiveTheme` / `MotionScheme` 工厂 / emphasized 字体）全是 `internal`，`MaterialShapes` 与全部 expressive 组件干脆不存在。
> 1.5.0-alpha18 是仍能在 AGP 8.x 上构建的最后一个 alpha：alpha19 起 `minCompileSdk=37 / minAGP=9.1.0`。同理 compose-bom 不能升到 2026.08.00（compose ui 1.12.0 有同样的要求）。升级前请先读 `gradle/libs.versions.toml` 里的注释。
- minSdk 26 · targetSdk/compileSdk 36 · AGP 8.13 · Gradle 8.14（wrapper 自带）

```
app/src/main/kotlin/app/echoread/
  core/   Types Models TextOps(区间/布局/限长) Segmenter(分句) TxtParser EpubParser Hash Sample   —— 纯 JVM，可单测
  data/   Db(Room) SettingsStore(SharedPreferences+JSON) ChapterCache(LRU 4 章) Library(导入归一化/进度)
  tts/    SpeechApi(OpenAI 兼容) Voices(音色目录) AudioCache SystemTts Playback(ExoPlayer+焦点)
          Engine(编排：队列/预取/跨章/退避) PlayerController(设置同步/进度/睡眠) EnginePlayer(SimpleBasePlayer) PlaybackService
  data/   BookScanner(全盘扫描：整机权限 / SAF 目录 / 媒体库 三条路径合并去重)
  ui/     Theme(M3 色板+形状+字体+动效入口) Icons Widgets Toast BookCover ShelfScreen ReaderScreen
          Sheets GestureSheet(翻页手势+实时预览) ImportSheet(应用内导入) App
  ui/motion/  MotionTokens(8 档弹簧，5 档等于 M3 token) MotionDriver(统一驱动器)
          EchoMotionScheme(把 M3 的 6 槽动效协议接到自研弹簧上) GestureBindings PressBounce Haptics(触觉)
  ui/reader/  ChapterLayout(整章排版/分页) ReaderPager(三槽位翻页器)
baselineprofile/   Baseline Profile 生成器 + Macrobenchmark（启动、翻页帧耗时）
```

模块方向严格单向：`core ← data ← tts ← ui`。任一章节在内存中只有一份规范纯文本，段落/片段/高亮皆为它的偏移区间 `Range(start, end)`，与网页版同构。

## 设计系统（0.2.0-exp）

### 配色：用 Google 的算法生成，而不是手挑

以 `#7C9BFF` 为 seed、`SchemeTonalSpot` 变体、contrast 0.0，跑 Google 自己的 material-color-utilities
（`com.google.android.material:material:1.14.0` 内附）产出完整 48 个角色的明暗两套调色板，再用独立实现交叉校验。
TonalSpot 只保留 seed 的**色相**（H=273.2）而丢弃其彩度与明度，所以品牌辨识度还在，每个具体色值却都是算法产物。

副产品：TonalSpot 把 tertiary 放在 hue+60（H=333.2，玫红）。旧的「靛→紫→粉」极光渐变，由 `primary → tertiary`
天然复现，无需任何手挑色。顺带修掉一个真实缺陷 —— 旧渐变三个色标在浅色底上只有 2.15~2.61:1，大标题实际不满足 WCAG；
新渐变两端同色调（浅色 T40 / 深色 T80），全程 6.1:1 / 10.9:1 且亮度恒定。

界面的十个语义色（`EchoColors`）由色板派生，因此约 160 处调用点一行未改就整体换到了 M3 角色上，并顺带获得动态取色支持。
层级遵循 M3 的「色调化表面」：页面 = `surface`，卡片 = `surfaceContainer`，卡片内的行 = `surfaceContainerHigh`。
**浅色模式因此发生方向性变化**：M3 里容器比页面更暗，而旧设计是灰底白卡片。

阅读器的五套主题**刻意不接入 M3**：长文阅读的目标对比度（8~12:1）本就低于 UI（14~16:1），
而且正文是内容、不该跟着壁纸变色；「纸墨」「护眼」更是纸张模拟，任何调色板都生不出来。
但顺手修了它们的两个实测缺陷：五套主题的 `dim` 次要文字全部不满足 AA（2.03~3.25:1，已提到 4.75~4.95:1），
「纸墨」的朗读高亮文字 3.93:1 不达标（已改到 6.75:1）。

### 动效：M3 的标准 × 自研 CA 管线

`EchoMotion` 从 6 档扩到 8 档，取值全部来自 material3 1.5.0-alpha18 里
`StandardMotionTokens` / `ExpressiveMotionTokens` 的实际字节码常量，用 `response = 2π/√k` 换算到我们的
`(response, damping)` 参数化。八档里**五档与 Google 的 token 数值完全相等**：

| Token | ζ | k | 对应 |
|---|---|---|---|
| `Flash` | 1.00 | 3800 | = M3 `fastEffects` |
| `Instant` | 1.00 | 1600 | = M3 `defaultEffects` |
| `Standard` | 0.90 | 700 | = M3 standard `defaultSpatial` |
| `Playful` | 0.60 | 800 | = M3 expressive `fastSpatial` |
| `Emphasized` | 0.80 | 380 | = M3 expressive `defaultSpatial` |
| `Expand` | 0.80 | 200 | = M3 expressive `slowSpatial` |
| `Track` | 0.95 | 816 | **融合**：刚度取自 M3，阻尼保留我们的 |
| `Gentle` | 1.00 | 130 | **我们独有**：两套体系里唯一「又慢又临界阻尼」的弹簧 |

两处刻意不跟随：`Track` 是手势 settle 专用，不能带上 expressive `fastSpatial` 的 ζ=0.6 —— 9.5% 过冲落在翻页上
意味着接近一页宽的正文滑出页边再弹回来；`Gentle` 用于颜色/进度条，颜色一旦过冲会溢出色域，观感上是一次闪烁。
`MotionTest` 把这张表钉成了断言，改动取值会直接红。

组件侧的接缝是 `EchoMotionScheme`：实现 M3 的 6 槽 `MotionScheme` 协议、转发到上面的档位，
再装进 `MaterialExpressiveTheme(motionScheme = …)`。落进来的 M3 组件因此跑在我们的曲线上，全应用只有一套动效词汇。
**反过来，自研 CA 管线驱动的表面（翻页、弹层拖拽、预测性返回、按压回弹）绝不经过它** ——
`MotionScheme` 只能交出一条裸的 `FiniteAnimationSpec`，没有 MutatorMutex 仲裁、速度继承、rebase/snapTo 与橡皮筋。

### 图标

启动器图标随更名重做（资产与说明见 `design/lector/`）：**中文引号「 + 一个方块字**，即正文里那句被高亮、正在朗读的字；
不用书本、声波、麦克风。背景层平铺品牌种子色 `#7C9BFF`，前景为墨色 `#111F55` 引号与白色方块，另配 `monochrome` 层支持 Android 13+ 的主题图标，
通知栏小图标同一套几何。两块几何在 200 单位设计盒内按 1.08 缩放居中于 512 视口，最远着墨点距中心约 147，圆形 / 方圆 / 圆角方遮罩下均无裁切。
启动画面（API 31+）给图标垫了同色圆底，否则白色方块会消失在浅色启动底上。

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk 36`）。用 Android Studio 直接打开 `android/` 目录，或命令行：

```bash
cd android
./gradlew :app:assembleDebug          # 调试包 → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 单元测试（解析/分段/音色目录/请求体/退避/EPUB，33 例）
./gradlew :app:assembleRelease        # R8 精简的发布包（默认 debug 签名，正式发布请配置签名）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

命令行构建需在 `android/local.properties` 写 `sdk.dir=<你的 Android SDK 路径>`（已被 .gitignore 忽略）。

### 性能：Baseline Profile 与 Macrobenchmark

> **0.2.0-exp 待办**：本次改版引入了新的启动/导入路径与 M3 组件，仓库里的 Baseline Profile 尚未重新生成
> （需要连一台 API 33+ 真机或模拟器跑 `:app:generateReleaseBaselineProfile`）。现有 profile 仍覆盖未变的启动主路径，
> 只是新代码暂时享受不到预编译，属性能优化而非正确性问题。

- `app/src/release/generated/baselineProfiles/` 是提交在库里的 Baseline/Startup Profile，随 release 包一起打进 APK（`profileinstaller` 安装即生效，冷启动与首屏免 JIT）。
- 改了启动/阅读路径的代码后重新生成（需连一台 API 33+ 的设备或模拟器）：`ANDROID_SERIAL=<serial> ./gradlew :app:generateReleaseBaselineProfile`
- 压测：`ANDROID_SERIAL=<serial> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`（`StartupBenchmark` 对比有无 profile 的冷启动；`ReaderScrollBenchmark` 翻页 `FrameTimingMetric`）。结果在 `baselineprofile/build/outputs/connected_android_test_additional_output/`。
- UiAutomator 通过 testTag 定位：`shelf.sample` `shelf.book` `reader.page` `reader.back`。

### 渲染与动画

- 正文文字层：每页的整章 `drawText` 只录制一次进 `GraphicsLayer`（RenderNode 显示列表），换段高亮、拖动翻页只重放显示列表（`ReaderScreen.PageCanvas`）。
- 根导航与预测性返回（API 33+）共用一个 `MotionDriver`：打开、返回、手势拖拽都是同一个值的不同驱动方式，可中途放弃。
- 触觉反馈（`ui/motion/Haptics.kt`）只在手势跨越语义边界时触发：翻页越过半页、弹层吸附、返回提交、书首/书尾；引擎自动翻页不震。阅读样式面板可关闭。

## 发布与应用内更新

应用内置无后端的更新机制：启动后每天静默检查一次仓库里的 `android/update.json`（走 jsDelivr CDN，GitHub raw 兜底），有新版本时书架顶部出卡片，点击下载（校验 SHA-256）后拉起系统安装器覆盖安装；「怎么用」面板里可手动检查。

发布一个新版本：

```bash
cd android
./release.sh 0.1.2 3 "更新说明"   # 打正式签名包 → 写 update.json → 提交推送 → gh release 上传 APK
```

正式签名读取 `android/keystore.properties`（`storeFile / storePassword / keyAlias / keyPassword`，已 gitignore）；没有该文件时回退 debug 签名，仅供本地验证。

## 使用

1. 打开应用 → 右上角齿轮 → 填入 OpenRouter API Key（[在此创建](https://openrouter.ai/settings/keys)），点「获取在线模型」选择模型与音色，点「试听测试」验证；没有 Key 时切到「系统语音」。
2. 底部「导入书籍」选择 TXT / EPUB（可多选）；没有书时点「先听示例」。
3. 打开书籍后轻点正文任意位置开始朗读；底栏可暂停、切章、调倍速、睡眠定时；锁屏与通知栏可控制播放。

## 许可

与仓库根目录 [LICENSE](../LICENSE) 相同。
