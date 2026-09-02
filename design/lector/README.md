# Lector · 品牌资产

新名字 **Lector**（/ˈlɛktər/，朗读者）与新标志。0.3.0-exp 起已接入应用：`android/app/src/main/res/drawable/` 下的四个 `ic_*.xml` 与本目录同源，改标志时两边一起改。

## 标志

「 引号 + 一个方块字 —— 正在被念出来的那个字。两块几何在 200 单位的设计盒内：

- `「`：L 形，粗 40，外角 r20、端头 r8、内角 r6
- `■`：128×128 圆角方，r30，位于 (72,72)

## 色彩

| 名称 | 值 | 用途 |
|---|---|---|
| Lector 蓝 | `#7C9BFF` | 品牌主色，也是应用 M3 配色的种子色（`ReaderSettings.DEFAULT_SEED`） |
| 墨 | `#111F55` | 字标与图形的墨色 |
| 纸 | `#FAF8FF` | 浅色底（= M3 light surface，已是启动窗口背景） |
| 夜 | `#121318` | 深色底（= M3 dark surface） |

## 文件

| 文件 | 说明 |
|---|---|
| `mark.svg` / `mark-mono.svg` | 图形标志，双色 / 单色，200×200 |
| `wordmark.svg` | 字标 Lector（Outfit Bold，已转轮廓） |
| `logo-horizontal-{light,dark}.svg` | 横版组合，浅底 / 深底 |
| `logo-vertical-{light,dark}.svg` | 竖版组合 |
| `app-icon-{light,solid,dark}.svg` | 512 应用图标三种配色；`light` 为采用方案 |
| `ic_launcher_foreground.xml` | Android 自适应图标前景层（视口 512，圆形遮罩安全） |
| `ic_launcher_background.xml` | 背景层，平铺 Lector 蓝 |
| `ic_launcher_monochrome.xml` | 主题图标（API 33+）单色层 |
| `ic_notification.xml` | 通知栏小图标，24dp |
| `png/` | 预览用位图导出 |

字体：字标用 [Outfit](https://fonts.google.com/specimen/Outfit)（OFL），中文备注用 Noto Sans SC（OFL）；两者都已转成路径，资产不依赖字体安装。

## 同步到 Android 工程

1. 四个 `ic_*.xml` 覆盖 `android/app/src/main/res/drawable/` 下的同名文件。
2. 品牌色若变，同时改 `android/app/src/main/res/values/colors.xml` 的 `ic_launcher_background`（旧启动器的纯色回退）与 `values-v31/themes.xml` 的 `windowSplashScreenIconBackgroundColor`。
3. 应用名在 `android/app/src/main/res/values/strings.xml` 的 `app_name`。
