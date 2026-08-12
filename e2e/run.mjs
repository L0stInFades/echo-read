/**
 * EchoRead Playwright E2E（playwright-core + 本机 Chrome，无浏览器下载）。
 * 自起 vite preview，跑完自动清理。运行：node e2e/run.mjs
 */
import { spawn } from 'node:child_process'
import { chromium } from 'playwright-core'

const PORT = 4173
const BASE = `http://localhost:${PORT}/`
const isCI = !!process.env.CI

const preview = spawn('npx', ['vite', 'preview', '--port', String(PORT), '--strictPort'], {
  cwd: new URL('..', import.meta.url).pathname,
  stdio: 'ignore'
})
process.on('exit', () => preview.kill())

const sleep = ms => new Promise(r => setTimeout(r, ms))
async function waitServer() {
  for (let i = 0; i < 60; i++) {
    try {
      if ((await fetch(BASE)).ok) return
    } catch { /* retry */ }
    await sleep(500)
  }
  throw new Error('preview 服务未就绪')
}

/* ---------- 极简测试框架 ---------- */
const results = []
let page
async function test(name, fn) {
  try {
    await fn()
    results.push(['✓', name])
    console.log('✓', name)
  } catch (e) {
    results.push(['✗', name])
    console.error('✗', name, '\n  ', e.message)
    try {
      await page.screenshot({ path: `/tmp/echoread-shots/e2e-fail-${results.length}.png` })
    } catch { /* ignore */ }
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg ?? '断言失败')
}

/* ---------- 场景 ---------- */
let context
const pageErrors = []

async function main() {
  await waitServer()
  const browser = await chromium.launch({ channel: 'chrome', headless: true })
  context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN'
  })
  page = await context.newPage()
  page.on('pageerror', e => pageErrors.push(String(e)))

  await test('书架空态渲染（logo/导入/API Key 引导）', async () => {
    await page.goto(BASE)
    await page.waitForSelector('text=书架还是空的')
    await page.waitForSelector('text=配置 API Key')
    await page.waitForSelector('button:has-text("导入")')
  })

  await test('demo 模式自动导入示例书并进入阅读器', async () => {
    await page.goto(BASE + '#/?demo=1')
    await page.waitForSelector('.reader-body [data-start]', { timeout: 10000 })
    assert(page.url().includes('#/read/'), '未进入阅读器路由')
    const h1 = await page.textContent('.reader-body h1')
    assert(h1 === '开篇', `章节标题异常: ${h1}`)
    const paras = await page.locator('.reader-body p').count()
    assert(paras >= 1, '未渲染出正文段落')
  })

  await test('任意字点读：点按正文出现朗读高亮', async () => {
    const span = page.locator('.reader-body [data-start]').first()
    const box = await span.boundingBox()
    assert(box, 'span 不可见')
    // 偏移对齐不变式：每个 span 的 [data-start, data-end) 长度必须等于其渲染文本长度
    // （跨段落合并片段按段落钳制后绑定，否则点读偏移整体偏小）
    const badSpans = await page.evaluate(() => {
      let bad = 0
      for (const s of document.querySelectorAll('.reader-body [data-start]')) {
        if (Number(s.dataset.end) - Number(s.dataset.start) !== (s.textContent ?? '').length) bad++
      }
      return bad
    })
    assert(badSpans === 0, `data 区间与渲染文本不对齐的 span 数: ${badSpans}`)
    await page.mouse.click(box.x + Math.min(box.width * 0.6, box.width - 4), box.y + box.height / 2)
    await page.waitForSelector('.seg-active', { timeout: 5000 })
  })

  await test('无 API Key 点读 → 错误提示到达用户', async () => {
    const toast = page.locator('.fixed.top-0 .glass').first()
    await toast.waitFor({ state: 'visible', timeout: 15000 })
    const t = await toast.textContent()
    assert(/401|无效|失败|频繁|余额/.test(t ?? ''), `toast 内容异常: ${t}`)
  })

  await test('章末导航：下一章切换', async () => {
    await page.locator('.reader-body button:has-text("下一章")').click()
    await page.waitForFunction(
      () => document.querySelector('.reader-body h1')?.textContent === '第一章 雨夜来客',
      { timeout: 5000 }
    )
  })

  await test('目录抽屉：打开并跳转第三章', async () => {
    await page.getByRole('button', { name: '目录' }).click()
    await page.waitForSelector('text=第三章 第一位听众')
    await page.locator('button:has-text("第三章 第一位听众")').click()
    await page.waitForFunction(
      () => document.querySelector('.reader-body h1')?.textContent === '第三章 第一位听众',
      { timeout: 5000 }
    )
  })

  await test('阅读样式：切换明亮主题生效', async () => {
    await page.getByRole('button', { name: '阅读样式' }).click()
    await page.locator('button:has-text("明亮")').click()
    await page.waitForFunction(
      () => document.querySelector('.reader-theme-light') !== null,
      { timeout: 3000 }
    )
    // 换回暗夜并关闭弹层，避免影响后续交互
    await page.locator('button:has-text("暗夜")').click()
    await page.locator('button[aria-label="关闭"]').first().click()
    await page.waitForFunction(() => !document.querySelector('.fixed.inset-0.z-40'), { timeout: 3000 })
  })

  await test('TTS 设置：在线拉取 TTS 模型并选择（拦截 /models）', async () => {
    await page.route('**/api/v1/models**', route =>
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          data: [
            { id: 'openai/tts-1-hd', name: 'OpenAI: TTS-1 HD', architecture: { input_modalities: ['text'], output_modalities: ['speech'] }, supported_voices: ['nova', 'shimmer'] },
            { id: 'fish-audio/s2-pro', name: 'FishAudio: S2 Pro', architecture: { input_modalities: ['text'], output_modalities: ['speech'] }, supported_voices: null },
            { id: 'openai/gpt-4o', name: 'OpenAI: GPT-4o', architecture: { input_modalities: ['text'], output_modalities: ['text'] } }
          ]
        })
      })
    )
    await page.getByRole('button', { name: '朗读设置' }).click()
    await page.waitForSelector('text=AI 朗读设置')
    await page.getByRole('button', { name: '获取在线模型' }).click()
    const select = page.locator('select')
    await select.waitFor({ state: 'visible', timeout: 5000 })
    const opts = await select.locator('option').allTextContents()
    // 当前模型不在假列表中 → 1 个「手动」回退项 + 2 个 speech 模型；纯文本模型被过滤
    assert(opts.length === 3, `下拉项异常: ${JSON.stringify(opts)}`)
    assert(!opts.some(o => o.includes('GPT-4o')), `纯文本模型未被过滤: ${JSON.stringify(opts)}`)
    await select.selectOption('openai/tts-1-hd')
    await page.waitForFunction(
      () => {
        const s = JSON.parse(localStorage.getItem('echo-read:tts-settings') ?? '{}').openai ?? {}
        // 模型写回；音色自动回退到该模型 supported_voices 的第一项（默认 alloy 不在其中）
        return s.model === 'openai/tts-1-hd' && s.voice === 'nova'
          && JSON.parse(localStorage.getItem('echo-read:tts-models') ?? '[]').length === 2
      },
      { timeout: 3000 }
    )
    await page.unroute('**/api/v1/models**')
    await page.locator('button[aria-label="关闭"]').first().click()
    await page.waitForFunction(() => !document.querySelector('.fixed.inset-0.z-40'), { timeout: 3000 })
  })

  await test('TTS 设置：切换系统语音并持久化', async () => {
    await page.getByRole('button', { name: '朗读设置' }).click()
    await page.waitForSelector('text=AI 朗读设置')
    await page.locator('button:has-text("系统语音")').click()
    await page.waitForFunction(
      () => JSON.parse(localStorage.getItem('echo-read:tts-settings') ?? '{}').provider === 'webspeech',
      { timeout: 3000 }
    )
    await page.locator('button[aria-label="关闭"]').first().click()
    await page.waitForFunction(() => !document.querySelector('.fixed.inset-0.z-40'), { timeout: 3000 })
  })

  await test('播放坞：webspeech 路径起播并推进片段（stub 语音栈）', async () => {
    // headless Chrome 无语音栈：桩掉平台 API，确定性验证引擎 loop / 句柄 / 片段推进
    await page.evaluate(() => {
      const s = window.speechSynthesis
      s.getVoices = () => [{ lang: 'zh-CN', name: 'stub' }]
      s.speak = u => setTimeout(() => u.onend?.(new Event('end')), 120)
      s.pause = () => {}
      s.resume = () => {}
      s.cancel = () => {}
    })
    // 等上一用例的 401 错误 toast（5s 时长）自然消散，确保后面只统计本次播放新产生的提示
    await page.waitForFunction(() => !document.querySelector('.fixed.top-0 .glass'), { timeout: 8000 })
    const before = await page.locator('.seg-active').count()
    await page.getByRole('button', { name: '播放/暂停' }).click()
    await sleep(1500)
    // 每个 stub 片段 120ms：1.5s 后应已推进若干片段（进度条 > 0 或高亮移动）
    const progress = await page.evaluate(() => {
      const bar = document.querySelector('.glass .h-full.rounded-full')
      return bar ? bar.style.width : '0%'
    })
    const toastTexts = await page.locator('.fixed.top-0 .glass').allTextContents()
    assert(toastTexts.length === 0, `webspeech 播放中出现错误提示: ${toastTexts.join(' | ')}`)
    assert(progress !== '0%' && progress !== '0.0%', `片段未推进（进度=${progress}，前置高亮=${before}）`)
  })

  await test('睡眠定时：设定 15 分钟出现倒计时，关闭后复原', async () => {
    await page.getByRole('button', { name: '睡眠定时' }).click()
    await page.locator('button:has-text("15分")').click()
    // 激活后按钮内直接显示 mm:ss 倒计时
    await page.waitForFunction(() => {
      const b = document.querySelector('button[aria-label="睡眠定时"]')
      return /^1[45]:\d\d$/.test(b?.textContent?.trim() ?? '')
    }, { timeout: 3000 })
    await page.getByRole('button', { name: '睡眠定时' }).click()
    await page.locator('button:has-text("关闭")').click()
    // 复位后按钮回到月亮图标（无文本）
    await page.waitForFunction(() => {
      const b = document.querySelector('button[aria-label="睡眠定时"]')
      return (b?.textContent?.trim() ?? '') === ''
    }, { timeout: 3000 })
  })

  await test('持久化：刷新后书架保留书籍', async () => {
    await page.goto(BASE)
    await page.waitForSelector('text=深夜书屋（示例）', { timeout: 8000 })
  })

  await test('长按书籍卡片：操作菜单不被抬手合成 click 误关', async () => {
    // 真实触屏序列:长按开菜单后,浏览器按 touchend 坐标补发的合成 click 会命中新遮罩
    const box = await page.locator('.grid > div').first().boundingBox()
    const cdp = await context.newCDPSession(page)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.x + box.width / 2, y: box.y + box.height / 2 }] })
    await sleep(800)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    await cdp.detach()
    await sleep(400)
    assert(await page.locator('button:has-text("从书架删除")').isVisible(), '长按菜单被合成 click 瞬间关闭')
    assert(!page.url().includes('#/read/'), '长按误触发开书导航')
    await page.locator('button[aria-label="关闭"]').first().click()
    await page.waitForFunction(() => !document.querySelector('.fixed.inset-0.z-40'), { timeout: 3000 })
  })

  await test('从书架打开书籍并恢复阅读', async () => {
    await page.locator('.grid > div').first().click()
    await page.waitForSelector('.reader-body [data-start]', { timeout: 8000 })
    const h1 = await page.textContent('.reader-body h1')
    assert((h1 ?? '').length > 0, '章节未渲染')
  })

  await test('返回书架', async () => {
    await page.getByRole('button', { name: '返回' }).click()
    await page.waitForSelector('text=深夜书屋（示例）', { timeout: 8000 })
  })

  await test('全程无未捕获异常', async () => {
    assert(pageErrors.length === 0, `页面异常: ${pageErrors.join(' | ')}`)
  })

  await page.screenshot({ path: '/tmp/echoread-shots/e2e-final.png' })
  await browser.close()

  const failed = results.filter(r => r[0] === '✗').length
  console.log(`\n${results.length - failed}/${results.length} 通过`)
  process.exit(failed ? 1 : 0)
}

main().catch(e => {
  console.error('E2E 运行失败:', e)
  process.exit(1)
})

void isCI
