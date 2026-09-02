# Lector · AI 听书

Android 原生（Kotlin / Jetpack Compose）听书应用：导入 TXT / EPUB，轻点正文任意字，AI 便从那里开始朗读。自填 API Key，无账号、无后端。

> 当前 **0.3.0-exp**，实验版。应用由 EchoRead 更名为 Lector；更新卡片可按版本永久忽略。

## 功能

- **任意字点读**：点击位置换算为章节字符偏移，定位到合成片段起播；句级高亮跟随、自动翻页。
- **AI 朗读**：OpenAI 兼容语音接口，默认 OpenRouter；在线拉取模型、内置音色目录、按模型记忆音色。兼容 OpenAI、SiliconFlow、FishAudio 与自建服务。
- **系统语音兜底**：无 Key 时用 Android TextToSpeech，同样支持锁屏控制与倍速。
- **后台与锁屏**：Media3 媒体会话，通知栏 / 锁屏 / 耳机线控可播放、暂停、跳章。
- **断网自愈**：指数退避重试，单段失败自动跳段，连续失败才停播。
- **流畅播放**：句子级分段合并为片段，边播边预取，音频本地缓存（LRU 300MB）。
- **阅读器**：五套主题，字号 / 行距 / 段距 / 衬线，目录，睡眠定时，倍速；翻页手势、点击热区与灵敏度可配。
- **书架与导入**：全盘扫描本机 TXT / EPUB 批量入库；封面网格、继续阅读、搜索与排序。
- **解析**：TXT 自动探测编码与分章；EPUB 按 spine 抽取段落与封面。

## 仓库结构

```
android/             Android 工程，用 Android Studio 打开此目录
android/update.json  应用内更新清单，路径已写死在已发布客户端里，不能移动
android/release.sh   发布脚本
design/lector/       标志、字标与图标源文件
```

包名保留更名前的 `app.echoread`，数据库与本地存储的名字同样不变，否则老用户无法覆盖更新或会丢数据。

## 技术

Kotlin 2.2 · Jetpack Compose（material3 1.5.0-alpha18，Material 3 Expressive）· Room · Media3 · OkHttp · kotlinx.serialization · jsoup。minSdk 26，targetSdk 36。

模块单向依赖 `core ← data ← tts ← ui`，`core` 为纯 JVM，可单测。material3 与 compose-bom 版本钉死的原因见 `android/gradle/libs.versions.toml`。

配色由 material-color-utilities 按种子色 `#7C9BFF` 现算，支持动态取色与对比度档位；动效弹簧参数与 M3 token 一致，由 `MotionTest` 锁定。

## 构建

需要 JDK 17 到 24 与 Android SDK 36，`android/local.properties` 里写 `sdk.dir`。

```bash
cd android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 单元测试
```

## 发布

```bash
cd android
./release.sh 0.3.1-exp 15 "更新说明"
```

打正式签名包、写 `update.json`、提交推送、创建 GitHub Release 并上传 APK。签名读取 `android/keystore.properties`（已 gitignore）。客户端每天检查一次清单，有新版本时书架顶部出更新卡片，下载校验 SHA-256 后覆盖安装。

## 使用

1. 齿轮里填入 OpenRouter API Key，选择模型与音色；没有 Key 就切到「系统语音」。
2. 「导入书籍」扫描本机 TXT / EPUB 入库，或先听内置示例。
3. 打开书籍，轻点正文任意位置开始朗读。

## 致谢

章节数据模型与 TXT 分章思路参考了 [read-cat](https://github.com/read-cat/read-cat)，独立实现，未使用其代码。

## 许可

本项目采用 The JSON License（SPDX：JSON），全文见 [LICENSE](LICENSE)。
