# EchoRead · AI 听书

一款真正顶级的 **PWA 网页 TTS 听书应用** —— 导入 TXT / EPUB，**轻点正文任意字**，AI 便从那里开始为你朗读。移动优先，可安装到主屏，支持离线书架。

> 章节数据模型与 TXT 分章的部分思路参考了开源阅读器 [read-cat](https://github.com/read-cat/read-cat),面向 Web 与 AI TTS 场景独立全新实现,经逐行比对未使用其任何代码,特此致谢其思路启发。

## 核心特性

- **任意字开始读**：渲染层与朗读引擎共享同一套「章节纯文本字符偏移」坐标系。点击位置经 `caretRangeFromPoint` 换算成精确字符偏移 → 二分定位到最近合成片段 → 从该处起播。
- **真 AI TTS · 深度适配 OpenRouter**：对接 OpenAI 兼容语音接口（`POST {baseUrl}/audio/speech`），默认指向 **OpenRouter**（`https://openrouter.ai/api/v1`）：在线拉取全部语音模型（`/models?output_modalities=speech`）、内置各模型音色目录（中文名/性别/风格标注、语言分组、每模型记忆音色）、单价与能力展示、请求方言自动适配（`response_format` 收敛 mp3/pcm、Gemini PCM 裸流自动封 WAV、开放音色 ID 自由输入）、应用归因头；亦兼容 OpenAI 官方、SiliconFlow、FishAudio 等同格式服务。
- **离线兜底**：一键切换浏览器系统语音（Web Speech API），无 Key 也能听。
- **锁屏可听 · 断网自愈**：所有片段复用同一被用户手势授权的音频元素，段间以静音循环保活占住后台音频权（iOS 锁屏连播的架构基础）；网络抖动/限流按指数退避自动重试（1s→30s 封顶 ±20% 抖动，最多 8 次、暂停即中止），单段穷尽自动跳段续播，连续 3 段失败才停播提示，401 等配置错误则快速失败。
- **流畅播放**：句子级分段（`Intl.Segmenter`）合并为 ≤120 字片段（80–400 可调），当前片段播放时后台预取后续片段；音频按 `模型|音色|格式|指令|文本` 哈希缓存进 IndexedDB（LRU 300MB），断句重听零流量。
- **移动优先阅读器**：句级跟随高亮 + 自动居中滚动、锁屏媒体控制（Media Session）、睡眠定时器（15–90 分钟或播完本章）、五套阅读主题、字号/行距/衬线调节、章末导航、目录抽屉。
- **PWA**：Service Worker 预缓存 + 可安装 manifest（含 maskable 图标），书架与已缓存音频完全离线可用。

## 书籍导入与解析

| 格式 | 说明 |
| --- | --- |
| TXT | BOM/UTF-8 严格解码 → jschardet 探测（GBK/GB18030/Big5 等，浏览器原生 `TextDecoder` 解码）；多套章节正则取匹配数最多者分章（排除「第二章正文。」类误匹配），无章节结构时按 8000 字段落边界兜底切块 |
| EPUB | JSZip 解包 → `container.xml` 定位 OPF → 元数据（`dc:` 命名空间兼容）/ manifest / spine → nav 或 NCX 章节标题映射 → 块级元素序列化抽取纯文本段落（剔除 script/style/img），封面缩放为缩略 dataURL |

书籍元数据、章节目录、章节正文、音频缓存分仓存储于 IndexedDB（`idb`），大书不卡顿。

## 技术栈

Vite 6 · Vue 3 · TypeScript · Pinia · Vue Router（hash 模式，静态托管友好）· Tailwind CSS 4 · vite-plugin-pwa（Workbox）· JSZip · jschardet · idb

## 开发

```bash
npm install
npm run dev        # 本地开发（手机调试可用局域网 IP 访问）
npm run build      # 类型检查 + 生产构建（输出 dist/，含 SW）
npm run preview    # 预览生产构建
npx tsx test/sanity.ts       # TXT 解析 / 分段 / 偏移定位冒烟测试
npx tsx test/epub-sanity.ts  # EPUB 解析冒烟测试（内存构造最小 EPUB）
npm run e2e        # Playwright 端到端（16 用例，需本机 Chrome，自动起 preview）
```

## 使用

1. 部署 `dist/` 到任意静态托管（HTTPS 或 localhost 才能启用 PWA 与音频自动播放）。
2. 打开应用 → 右上角齿轮 → 填入 OpenRouter API Key（[在此创建](https://openrouter.ai/settings/keys)），点「获取在线模型」后选择模型与音色（默认 Kokoro 超低价中文音色，Fish S2.1 有免费档可先试听），点「试听测试」验证。
3. 点「导入」选择 TXT / EPUB 文件。
4. 打开书籍，**轻点正文任意位置**，AI 即从该字开始朗读。

## 架构设计：一切皆偏移量

高内聚、低耦合的核心是一条不变式——**任一章节在内存中只有一份规范纯文本，其余一切皆是它的偏移区间 `Range {start, end}`**：

- **段落** = 区间（`paraRanges` O(n) 单趟切分，零字符串副本）
- **合成片段** = 区间（`segmentChapter` 句子合并，不持有文本）
- **高亮/点读** = 区间比对（`data-start` 二分定位，渲染时才切片）
- **渲染布局** = 段落区间 × 片段区间的双指针归并 `layoutBlocks`，O(P+S)

由此推导出三层内存约束：

| 层 | 单元 | 上限 |
| --- | --- | --- |
| 持久层 | 章节纯文本（IndexedDB 分仓） | 单章 ≤ 8000 字（`boundChapters` 导入时归一化，巨型段落硬切） |
| 驻留层 | 派生章节（文本+区间，`getDerivedChapter`） | LRU 4 章，引擎与视图**共享同一实例** |
| 媒体层 | 音频 Blob（IndexedDB） | LRU 300MB / 800 条，播放句柄用毕即释放 objectURL |

模块方向严格单向：`types ← lib(text/segment/chapters) ← tts(engine) ← store ← views`，解析器（txt/epub）只产出 `ParsedBook`，入库归一化只发生在 `library` 一处。

## 目录结构

```
src/
  lib/        text(区间/布局/限长) segment(分句) chapters(派生缓存) locate(DOM点读)
              db(IndexedDB) txt/epub(解析) hash toast sample
  tts/        engine(编排：队列/预取/跨章) handles(播放原语：Blob/WebSpeech) providers(OpenAI兼容)
  store/      settings(localStorage) library(书架+导入归一化) player(引擎桥接+MediaSession)
  views/      ShelfView(书架) ReaderView(阅读+点读)
  components/ BottomSheet / 章节列表 / 阅读样式 / TTS 设置 / 封面
```

## 许可

本项目采用完全定制的 **《伪娘许可证》The Femboy License v3.0(强制·决绝版)**,全文见 [LICENSE](LICENSE)。要点:

- **对一切「非适用对象」使用者**(包括所有女性、未成年人与法人):**无条件授权**,宽松程度不低于 MIT-0——无署名义务、无任何约束。
- **对成年男性使用者**:权限以履行「伪娘义务」为前提——必须真诚立志、积极践行伪娘化,这不是邀请而是强制条件;拒绝、拖延或嘲讽者不获任何许可,且无宽限期、无复活、无协商余地(许可证 § 3.1–3.5)。公开背弃者许可自动且不可逆地丧失(§ 3.3)。
- 安全与合法性边界、商标保留、免责声明见许可证第四、五条;v2.0 的自愿志向条款已被彻底废除(§ 6.3)。

> 这是一份 source-available 许可证,因按使用者类别区分授权条款,不属于 OSI 定义的开源许可证。
