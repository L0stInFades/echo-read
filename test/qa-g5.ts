/**
 * QA G5 组回归：UI 与端到端（ShelfView.vue / TtsSettingsSheet.vue）
 * 运行：npx tsx test/qa-g5.ts
 *
 * 三个 bug 都在 .vue 单文件组件里，tsx 无法直接 import。此处从 SFC 源码
 * 提取真实函数文本（esbuild 剥离类型后求值），跑的是仓库里的实际逻辑而非复述实现；
 * 提取失败会立刻断言报错，提示同步维护。真实浏览器行为（合成 click 命中测试、
 * BottomSheet 遮罩交互）由 e2e/run.mjs 的「长按书籍卡片」用例覆盖，此处只测纯逻辑。
 */
import assert from 'node:assert'
import { readFileSync } from 'node:fs'
import { transformSync } from 'esbuild'
import { catalogVoices, groupVoices } from '../src/tts/voices'

const read = (p: string) => readFileSync(new URL(p, import.meta.url), 'utf8')
const scriptOf = (sfc: string) => {
  const m = sfc.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)
  assert.ok(m, 'SFC 中未找到 <script setup> 块')
  return m![1]
}
/** 提取 TS 源片段 → 剥离类型 → 可求值 JS */
const toJs = (src: string) => transformSync(src, { loader: 'ts' }).code

const shelfScript = scriptOf(read('../src/views/ShelfView.vue'))
const sheetScript = scriptOf(read('../src/components/TtsSettingsSheet.vue'))

// G5-01. 长按弹出菜单后,须吞掉抬手时浏览器补发的合成 click(否则命中刚出现的遮罩即关菜单)
{
  const m = shelfScript.match(/\/\* 长按弹出操作菜单 \*\/[\s\S]*?function pressEnd\(\) \{[\s\S]*?\n\}/)
  assert.ok(m, 'ShelfView 中未找到长按处理块')
  const listeners: { type: string; fn: (e: any) => void; opts: any }[] = []
  let timerCb: (() => void) | null = null
  let timerDelay = 0
  let cleared = 0
  let now = 1_000_000
  const actionBook = { value: null as any }
  const api = new Function(
    'document', 'setTimeout', 'clearTimeout', 'Date', 'actionBook',
    `${toJs(m![0])}\nreturn { pressStart, pressEnd }`
  )(
    { addEventListener: (type: string, fn: any, opts: any) => listeners.push({ type, fn, opts }) },
    (fn: () => void, ms: number) => { timerCb = fn; timerDelay = ms; return 1 },
    () => { cleared++; timerCb = null },
    { now: () => now },
    actionBook
  )
  const mkClick = () => {
    const ev = { prevented: false, stopped: false, preventDefault() { ev.prevented = true }, stopPropagation() { ev.stopped = true } }
    return ev
  }

  // 长按触发:菜单打开,且在 document 捕获阶段挂上一次性吞点击守卫
  api.pressStart({ id: 'b1' })
  assert.equal(timerDelay, 550)
  timerCb!()
  assert.equal(actionBook.value?.id, 'b1')
  assert.equal(listeners.length, 1)
  assert.equal(listeners[0].type, 'click')
  assert.equal(listeners[0].opts?.capture, true, '守卫须在捕获阶段(先于遮罩收到 click)')
  assert.equal(listeners[0].opts?.once, true, '守卫只允许吞一次(不得影响后续正常点击)')

  // 紧随其后的合成 click 被吞(preventDefault + stopPropagation)
  now += 100
  const ghost = mkClick()
  listeners[0].fn(ghost)
  assert.ok(ghost.prevented && ghost.stopped, '长按后的合成 click 未被吞掉')

  // 超时后到达的 click 放行(如 Android 抑制了合成 click,不得误吞用户后续真实点击)
  api.pressStart({ id: 'b2' })
  timerCb!()
  now += 800
  const late = mkClick()
  listeners[1].fn(late)
  assert.ok(!late.prevented && !late.stopped, '时效外的真实点击被误吞')

  // 慢松手:定时器触发后按住超过时效窗才抬手;合成 click 跟随抬手派发,时效窗须随抬手重启
  api.pressStart({ id: 'b2b' })
  timerCb!()
  now += 900
  api.pressEnd()
  now += 10
  const slow = mkClick()
  listeners[2].fn(slow)
  assert.ok(slow.prevented && slow.stopped, '慢松手(按住超时效窗)后的合成 click 未被吞掉')

  // 快速点按(未达 550ms 抬手):定时器取消,不开菜单、不挂守卫
  actionBook.value = null
  const before = listeners.length
  api.pressStart({ id: 'b3' })
  api.pressEnd()
  assert.equal(cleared, 1)
  assert.equal(actionBook.value, null)
  assert.equal(listeners.length, before)
  console.log('✓ G5-01 长按合成 click 捕获阶段一次性吞除(时效外放行、快速点按不受影响)')
}

// G5-02. 目录收敛后失效的语言筛选须自动复位(否则音色区清空且筛选行隐藏、无 UI 可解)
{
  const m = sheetScript.match(/watch\(voiceGroups, (gs => \{[\s\S]*?\})\)/)
  assert.ok(m, 'TtsSettingsSheet 中未找到 voiceGroups 失效筛选复位 watcher')
  const voiceLang = { value: '' }
  const cb = new Function('voiceLang', `return ${m![1]}`)(voiceLang) as (gs: { lang: string }[]) => void

  // 静态目录含日语组;在线刷新后仅剩 1 个英文音色 → 'ja' 组消失,筛选须归零
  const full = groupVoices(catalogVoices('hexgrad/kokoro-82m'))
  assert.ok(full.some(g => g.lang === 'ja'), '前置:kokoro 静态目录应含日语组')
  const shrunk = groupVoices(catalogVoices('hexgrad/kokoro-82m', ['af_bella']))
  assert.ok(!shrunk.some(g => g.lang === 'ja'), '前置:收敛目录不应再含日语组')
  voiceLang.value = 'ja'
  cb(shrunk)
  assert.equal(voiceLang.value, '', '失效语言筛选未被复位')

  // 语言仍存在时保留用户筛选
  voiceLang.value = 'zh'
  cb(full)
  assert.equal(voiceLang.value, 'zh', '有效筛选被误清')

  // 「全部」(空筛选)恒定不动
  voiceLang.value = ''
  cb(shrunk)
  assert.equal(voiceLang.value, '')
  console.log('✓ G5-02 音色目录收敛时失效语言筛选自动复位(有效筛选不误清)')
}

// G5-03. 单章书进度按章内偏移展示,不再恒显示「未开始」
{
  const m = shelfScript.match(/function progressText\([\s\S]*?\n\}/)
  assert.ok(m, 'ShelfView 中未找到 progressText')
  const progressText = new Function(`${toJs(m![0])}\nreturn progressText`)() as (b: {
    chapterCount: number
    totalChars: number
    progress: { chapterIndex: number; offset: number }
  }) => string

  const single = (offset: number, totalChars = 6000) =>
    progressText({ chapterCount: 1, totalChars, progress: { chapterIndex: 0, offset } })
  assert.equal(single(0), '未开始')
  assert.equal(single(3000), '已读 50%')
  assert.equal(single(6000), '已读 100%')
  assert.equal(single(9999), '已读 100%') // 越界 offset(陈旧元数据)封顶
  assert.equal(single(0, 0), '未开始') // totalChars=0 防御除零

  // 多章路径行为不变
  const multi = (chapterIndex: number, offset: number) =>
    progressText({ chapterCount: 10, totalChars: 80000, progress: { chapterIndex, offset } })
  assert.equal(multi(0, 0), '未开始')
  assert.equal(multi(0, 500), '已读 0%')
  assert.equal(multi(5, 0), '已读 56%')
  assert.equal(multi(9, 0), '已读 100%')
  console.log('✓ G5-03 单章书进度按 offset/totalChars 展示(多章路径不变)')
}

console.log('\nG5 组回归全部通过')
