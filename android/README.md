# EchoRead · Android（Kotlin 原生）

网页版 EchoRead 的原生安卓实现：**导入 TXT / EPUB，轻点正文任意字，AI 便从那里开始朗读**。自填 API Key、自传书籍，无账号、无后端。

> 设计语言：HarmonyOS（ArkUI）的大圆角卡片分组 · One UI 的大标题下沉与拇指可达 · ColorOS 式弹簧物理动效；品牌色沿用网页版「暗夜极光」渐变，浅色/深色跟随系统。

## 功能

- **任意字点读**：渲染层（Compose `TextLayoutResult.getOffsetForPosition`）与朗读引擎共享同一套「章节纯文本字符偏移」坐标系，点击 → 字符偏移 → 二分定位合成片段 → 起播；句级跟随高亮 + 自动居中滚动。
- **AI TTS**：OpenAI 兼容语音接口（`POST {baseUrl}/audio/speech`），默认 OpenRouter；在线拉取语音模型、内置各模型音色目录（中文名/性别/风格、语言分组、每模型记忆音色）、OpenRouter 方言适配（`response_format` 收敛、Gemini PCM 自动封 WAV、开放音色 ID）、归因头；兼容 OpenAI 官方、SiliconFlow、FishAudio、自建服务（允许明文 HTTP 局域网地址）。
- **系统语音兜底**：Android TextToSpeech 合成到 WAV 后走同一条 ExoPlayer 管线——无 Key 也能听，且同样支持锁屏控制与倍速。
- **锁屏 / 通知栏 / 耳机线控**：Media3 `MediaSessionService` + 自定义 `SimpleBasePlayer` 把引擎状态映射为媒体会话（上一曲/下一曲 = 跳章，展示章节名与封面），播放中为前台服务，后台连播；焦点丢失/拔耳机自动暂停。
- **断网自愈**：指数退避重试（1s→30s，±20% 抖动，最多 8 次），单段穷尽自动跳段续播，连续 3 段失败才停播；401/404 等配置错误快速失败。
- **流畅播放**：句子级分段（ICU `BreakIterator` + 中文句末标点细分）合并为 ≤120 字片段（80–400 可调），当前片段播放时预取后续片段；音频按 `provider|模型|音色|格式|指令|文本` 哈希缓存（LRU 300MB / 800 条）。
- **阅读器**：五套主题、字号/行距/段距/衬线、目录、章末导航、睡眠定时（15–90 分钟或播完本章）、倍速轮换。
- **书架**：封面网格、继续阅读、书名/作者搜索、三种排序、长按删除、多选导入；支持从文件管理器「打开方式」/ 分享导入。
- **解析**：TXT（BOM/严格 UTF-8 → juniversalchardet 探测 GBK/GB18030/Big5 → 候选严格解码；多套章节正则取匹配最多者，无结构时 8000 字兜底分块）；EPUB（`container.xml` → OPF → manifest/spine → nav/NCX 标题 → jsoup 块级元素抽段落，封面缩放为 ≤360px JPEG）。

## 工程

- Kotlin 2.2 · Jetpack Compose（Material3）· Room（KSP）· Media3 ExoPlayer/Session · OkHttp · kotlinx.serialization · jsoup · juniversalchardet
- minSdk 26 · targetSdk/compileSdk 36 · AGP 8.13 · Gradle 8.14（wrapper 自带）

```
app/src/main/kotlin/app/echoread/
  core/   Types Models TextOps(区间/布局/限长) Segmenter(分句) TxtParser EpubParser Hash Sample   —— 纯 JVM，可单测
  data/   Db(Room) SettingsStore(SharedPreferences+JSON) ChapterCache(LRU 4 章) Library(导入归一化/进度)
  tts/    SpeechApi(OpenAI 兼容) Voices(音色目录) AudioCache SystemTts Playback(ExoPlayer+焦点)
          Engine(编排：队列/预取/跨章/退避) PlayerController(设置同步/进度/睡眠) EnginePlayer(SimpleBasePlayer) PlaybackService
  ui/     Theme Motion Icons Widgets Toast BookCover ShelfScreen ReaderScreen Sheets App
```

模块方向严格单向：`core ← data ← tts ← ui`。任一章节在内存中只有一份规范纯文本，段落/片段/高亮皆为它的偏移区间 `Range(start, end)`，与网页版同构。

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
