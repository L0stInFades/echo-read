# Lector · 品牌资产

新名字 **Lector**（/ˈlɛktər/，朗读者）与新标志。本目录只是资产，尚未接入应用；接入步骤见文末。

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
| `app-icon-{light,solid,dark}.svg` | 512 应用图标三种配色；`light` 为推荐 |
| `favicon.svg` | 网页版站标（圆角 112） |
| `pwa-logo.svg` | 网页版 PWA 图标源（替换 `public/logo.svg` 后跑 `npm run icons`） |
| `ic_launcher_foreground.xml` | Android 自适应图标前景层（视口 512，圆形遮罩安全） |
| `ic_launcher_background.xml` | 背景层，平铺 Lector 蓝 |
| `ic_launcher_monochrome.xml` | 主题图标（API 33+）单色层 |
| `ic_notification.xml` | 通知栏小图标，24dp |
| `png/` | 预览用位图导出 |

字体：字标用 [Outfit](https://fonts.google.com/specimen/Outfit)（OFL），中文备注用 Noto Sans SC（OFL）；两者都已转成路径，资产不依赖字体安装。

## 接入 Android 版

1. 用本目录的四个 `ic_*.xml` 覆盖 `android/app/src/main/res/drawable/` 下的同名文件。
2. `android/app/src/main/res/values/strings.xml`：`app_name` 改为 `Lector`。
3. `android/app/src/main/res/values/colors.xml`：`ic_launcher_background` 改为 `#7C9BFF`（旧启动器的纯色回退）。
4. 包名若要一并改成 `app.lector`，需要同时改 `applicationId`、`namespace`、源码包路径与 `update.json` 的更新链路，属独立改动，建议单独提交。

## 接入网页版

1. `pwa-logo.svg` 覆盖 `public/logo.svg`，`favicon.svg` 覆盖 `public/favicon.svg`，然后 `npm run icons` 重新生成 PWA 图标。
2. `index.html` 与 `vite.config.ts` 里的 `EchoRead` 改为 `Lector`；`theme-color` 可保持 `#0b0e14` 或改为 `#121318`。
