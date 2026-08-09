/**
 * 无依赖 CDP 端到端检查：驱动本机 Chrome(headless) 验证核心交互。
 * 用法：node scripts/cdp-test.mjs [url] [截图输出路径]
 */
import { spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'

const BASE = process.argv[2] ?? 'http://localhost:4173/'
const SHOT = process.argv[3] ?? '/tmp/echoread-shots/e2e.png'
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const PORT = 9333

const chrome = spawn(CHROME, [
  '--headless=new', '--disable-gpu', '--no-first-run',
  `--remote-debugging-port=${PORT}`,
  '--window-size=390,844', '--hide-scrollbars',
  '--user-data-dir=/tmp/echoread-cdp-profile',
  'about:blank'
], { stdio: 'ignore' })

const sleep = ms => new Promise(r => setTimeout(r, ms))

async function waitPort() {
  for (let i = 0; i < 40; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (res.ok) return
    } catch { /* retry */ }
    await sleep(250)
  }
  throw new Error('Chrome CDP 端口未就绪')
}

let msgId = 0
const pending = new Map()
const events = []
let ws

function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++msgId
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面内执行出错: ' + JSON.stringify(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text))
  return r.result?.value
}

async function main() {
  await waitPort()
  const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
  const page = targets.find(t => t.type === 'page')
  ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise(r => (ws.onopen = r))
  ws.onmessage = e => {
    const m = JSON.parse(e.data)
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id)
      pending.delete(m.id)
      m.error ? reject(new Error(m.error.message)) : resolve(m.result)
    } else if (m.method) {
      events.push(m)
    }
  }

  await send('Runtime.enable')
  await send('Page.enable')
  await send('Log.enable')

  console.log('== 打开书架（demo 模式自动导入示例书并进入阅读器） ==')
  await send('Page.navigate', { url: `${BASE}#/?demo=1` })
  await sleep(5000)

  const hash = await evaluate('location.hash')
  console.log('当前 hash:', hash)

  const state1 = await evaluate(`({
    title: document.querySelector('header')?.textContent?.slice(0, 60),
    paras: document.querySelectorAll('.reader-body p').length,
    spans: document.querySelectorAll('.reader-body [data-start]').length,
    chapterTitle: document.querySelector('.reader-body h1')?.textContent
  })`)
  console.log('阅读器状态:', JSON.stringify(state1, null, 2))

  if (!state1.spans) throw new Error('阅读器未渲染出片段 span')

  // 模拟在正文中间位置点按（任意字点读）
  const probe = await evaluate(`(() => {
    const spans = [...document.querySelectorAll('.reader-body [data-start]')]
    const el = spans[Math.min(8, spans.length - 1)]
    const r = el.getBoundingClientRect()
    return { x: r.left + Math.min(r.width * 0.6, r.width - 4), y: r.top + r.height / 2, start: el.dataset.start, text: el.textContent.slice(0, 20) }
  })()`)
  console.log('== 模拟点按文字 ==', JSON.stringify(probe))

  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: probe.x, y: probe.y, button: 'left', clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: probe.x, y: probe.y, button: 'left', clickCount: 1 })
  await sleep(2500)

  const state2 = await evaluate(`({
    active: document.querySelector('.seg-active')?.textContent?.slice(0, 30) ?? null,
    activeStart: document.querySelector('.seg-active')?.dataset?.start ?? null,
    toast: document.querySelector('.fixed.top-0 .glass')?.textContent ?? null
  })`)
  console.log('点按后状态:', JSON.stringify(state2, null, 2))

  if (state2.activeStart == null) throw new Error('点按后没有出现朗读高亮（.seg-active）')

  // 截图
  const shot = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(SHOT, Buffer.from(shot.data, 'base64'))
  console.log('截图已保存:', SHOT)

  const errors = events
    .filter(e => (e.method === 'Log.entryAdded' && e.params.entry.level === 'error') || e.method === 'Runtime.exceptionThrown')
    .map(e => e.params.entry?.text ?? e.params.exceptionDetails?.exception?.description)
  console.log('控制台错误:', errors.length ? errors : '无')
}

main()
  .then(() => { chrome.kill(); process.exit(0) })
  .catch(e => { console.error('E2E 失败:', e.message); chrome.kill(); process.exit(1) })
