/**
 * G4「状态与持久化」回归测试（node + tsx 运行，非正式单测）：
 *   1. settings load()：腐坏 localStorage 的类型/值域回退（G4-01）
 *   2. player 睡眠定时：到点落在引擎装载窗口不得失效（G4-02，真实计时，全文件约 6 秒）
 *   3. db()：blocking 让位后丢弃缓存连接、VersionError 一次性提示刷新（G4-03）
 * 浏览器依赖以最小桩替代：localStorage 用内存 Map；IndexedDB 只桩
 * open/close/versionchange 生命周期（idb 包装层真实参与，数据事务不在桩范围）。
 * 末尾 process.exit(0) 兜掉 toast 自清理的挂起定时器。
 * 运行：npx tsx test/qa-g4.ts
 */
import assert from 'node:assert'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

// ---- 浏览器桩（须在动态 import 业务模块前就位） ----
const mem = new Map<string, string>()
;(globalThis as any).localStorage = {
  getItem: (k: string) => mem.get(k) ?? null,
  setItem: (k: string, v: string) => void mem.set(k, String(v)),
  removeItem: (k: string) => void mem.delete(k),
  clear: () => mem.clear()
}

const { useSettingsStore } = await import('../src/store/settings')
const { toasts } = await import('../src/lib/toast')

const LS_TTS = 'echo-read:tts-settings'

function freshTTS(raw: string | null) {
  mem.clear()
  if (raw !== null) mem.set(LS_TTS, raw)
  setActivePinia(createPinia())
  return useSettingsStore().tts
}

// 1. G4-01：顶层非纯对象 → 整体回退且无数字键污染
{
  for (const raw of ['"hello"', '[9,8,7]', 'null', '123', 'true']) {
    const tts = freshTTS(raw)
    assert.equal(tts.maxChunkChars, 120, raw)
    assert.equal(tts.rate, 1, raw)
    assert.ok(Object.keys(tts).every(k => !/^\d+$/.test(k)), `${raw} 产生数字键`)
  }
  console.log('✓ G4-01 顶层非纯对象整体回退（无数字键污染）')
}

// 2. G4-01：顶层标量类型不符/非有限数 → 按键回退默认值
{
  const tts = freshTTS('{"maxChunkChars":null,"rate":"1.5","prefetch":[2],"provider":42}')
  assert.equal(tts.maxChunkChars, 120)
  assert.equal(tts.rate, 1)
  assert.equal(tts.rate.toFixed(2), '1.00') // 设置面板模板依赖 number
  assert.equal(tts.prefetch, 2)
  assert.equal(tts.provider, 'openai-speech')
  assert.equal(freshTTS('{"maxChunkChars":1e999}').maxChunkChars, 120) // JSON 可产出 Infinity
  console.log('✓ G4-01 顶层标量类型守卫（null/字符串/数组/Infinity 回退）')
}

// 3. G4-01：值域守卫 —— 类型正确但会挂死/抛错的值回退
{
  assert.equal(freshTTS('{"maxChunkChars":0}').maxChunkChars, 120) // 步长 0 → segmentChapter 死循环
  assert.equal(freshTTS('{"maxChunkChars":-10}').maxChunkChars, 120)
  assert.equal(freshTTS('{"maxChunkChars":false}').maxChunkChars, 120)
  assert.equal(freshTTS('{"rate":0}').rate, 1) // playbackRate 安全域外
  assert.equal(freshTTS('{"rate":-2}').rate, 1)
  assert.equal(freshTTS('{"rate":100}').rate, 1)
  assert.equal(freshTTS('{"prefetch":-3}').prefetch, 2)
  console.log('✓ G4-01 值域守卫（maxChunkChars≥40 / rate∈[0.25,4] / prefetch≥0）')
}

// 4. G4-01：嵌套 openai 标量与枚举守卫
{
  const tts = freshTTS('{"openai":{"baseUrl":null,"model":42,"format":"wav","apiKey":"sk-keep"}}')
  assert.equal(tts.openai.baseUrl, 'https://openrouter.ai/api/v1')
  assert.equal(tts.openai.model, 'hexgrad/kokoro-82m')
  assert.equal(tts.openai.format, 'mp3')
  assert.equal(tts.openai.apiKey, 'sk-keep') // 合法字段原样保留
  console.log('✓ G4-01 嵌套标量守卫 + 合法值保留')
}

// 5. G4-01：合法配置零误伤（未知键前向兼容、voiceByModel、下架模型迁移）
{
  const good = freshTTS(JSON.stringify({
    provider: 'webspeech', rate: 1.75, maxChunkChars: 200, prefetch: 0,
    voiceByModel: { 'a/b': 'v1' }, openai: { format: 'opus' }, futureKey: 'kept'
  }))
  assert.equal(good.provider, 'webspeech')
  assert.equal(good.rate, 1.75)
  assert.equal(good.maxChunkChars, 200)
  assert.equal(good.prefetch, 0)
  assert.deepEqual({ ...good.voiceByModel }, { 'a/b': 'v1' })
  assert.equal(good.openai.format, 'opus')
  assert.equal((good as any).futureKey, 'kept')
  // voiceByModel 腐坏为数组 → 整体回退空表
  assert.deepEqual({ ...freshTTS('{"voiceByModel":["x"]}').voiceByModel }, {})
  // 下架模型迁移仍生效（sanitize 不破坏 migrate）
  assert.equal(freshTTS('{"openai":{"model":"openai/tts-1"}}').openai.model, 'hexgrad/kokoro-82m')
  console.log('✓ G4-01 合法配置零误伤 + 下架模型迁移不受影响')
}

// ---- G4-02：睡眠定时 × 引擎装载窗口（真实计时） ----
{
  mem.clear()
  setActivePinia(createPinia())
  const { usePlayerStore } = await import('../src/store/player')
  const player = usePlayerStore()
  const eng: any = player.engine
  const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
  const sleepToasts = () => toasts.filter(t => t.text.includes('睡眠定时')).length

  // 到点落在 loading 窗口（自动跨章/手动跳章装载中）：定时器必须保持武装、不得谎报
  const before = sleepToasts()
  eng.state = 'loading'
  player.setSleepTimer(0.02) // 1.2 秒到点
  await sleep(1400)
  assert.equal(player.sleepMode, 0.02, '装载窗口到点不得自我解除')
  assert.equal(eng.state, 'loading')
  assert.equal(sleepToasts(), before, '暂停未落地不得提示「已暂停」')

  // 装载完成引擎回到 playing：下一个 tick 补落暂停并解除
  let paused = false
  eng.handle = { pause: () => { paused = true }, resume() {}, stop() {}, setRate() {} }
  eng.state = 'playing'
  await sleep(1200)
  assert.equal(eng.state, 'paused', '装载完成后的续播必须被补暂停')
  assert.ok(paused)
  assert.equal(player.sleepMode, 'off')
  assert.equal(sleepToasts(), before + 1)
  eng.handle = null
  console.log('✓ G4-02 分钟定时落在装载窗口：保持武装，装载完成后补暂停')

  // 回归对照：playing 态正常到点即刻暂停（原路径不回归）
  let paused2 = false
  eng.state = 'playing'
  eng.handle = { pause: () => { paused2 = true }, resume() {}, stop() {}, setRate() {} }
  player.setSleepTimer(0.02)
  await sleep(1400)
  assert.equal(eng.state, 'paused')
  assert.ok(paused2)
  assert.equal(player.sleepMode, 'off')
  eng.handle = null

  // chapter 模式：换章后首个 playing 快照触发（fireSleep 共享路径不回归）
  eng.state = 'idle'
  player.setSleepTimer('chapter')
  const base = {
    state: 'paused' as const, bookId: 'A', chapterIndex: 2, chapterTitle: 't',
    segmentIndex: 0, segmentCount: 10, segmentStart: 0, segmentEnd: 5, synthesizing: false
  }
  player.snap = { ...base } // 首个有效章 → 捕获装订 A:2
  await nextTick()
  player.snap = { ...base, chapterIndex: 3, state: 'playing' } // 新章起播 → 触发
  await nextTick()
  assert.equal(player.sleepMode, 'off')
  console.log('✓ G4-02 原有路径不回归（playing 即刻暂停 / chapter 装订触发）')
}

// ---- G4-03：db() blocking 让位后的连接缓存 ----
{
  class FakeIDBRequest extends EventTarget {
    result: any
    error: any
  }
  class FakeIDBDatabase extends EventTarget {
    closed = false
    close() { this.closed = true }
  }
  class FakeIDBObjectStore {}
  class FakeIDBIndex {}
  class FakeIDBCursor { advance() {} continue() {} continuePrimaryKey() {} }
  class FakeIDBTransaction extends EventTarget {}
  Object.assign(globalThis as any, {
    IDBRequest: FakeIDBRequest,
    IDBDatabase: FakeIDBDatabase,
    IDBObjectStore: FakeIDBObjectStore,
    IDBIndex: FakeIDBIndex,
    IDBCursor: FakeIDBCursor,
    IDBTransaction: FakeIDBTransaction
  })
  // 每次 open 依脚本决定：成功交出新连接，或以 VersionError 拒绝（库已被升到更高版本）
  const opened: InstanceType<typeof FakeIDBDatabase>[] = []
  let failWithVersionError = false
  ;(globalThis as any).indexedDB = {
    open() {
      const req = new FakeIDBRequest()
      queueMicrotask(() => {
        if (failWithVersionError) {
          req.error = new DOMException('version too low', 'VersionError')
          req.dispatchEvent(new Event('error'))
        } else {
          const d = new FakeIDBDatabase()
          opened.push(d)
          req.result = d
          req.dispatchEvent(new Event('success'))
        }
      })
      return req
    }
  }

  const dbMod = await import('../src/lib/db')
  const tick = () => new Promise(r => setTimeout(r, 0))
  // 数据事务不在桩范围：getBook 拿到连接后允许因缺事务方法而失败，返回错误供检视
  const touch = () => dbMod.getBook('x').then(() => null, (e: any) => e)

  await touch()
  assert.equal(opened.length, 1, '首次调用建立连接')
  await touch()
  assert.equal(opened.length, 1, '连接被缓存复用')

  // 其它标签页请求升级 → versionchange → blocking：关闭连接并丢弃缓存
  await tick() // 等 idb 异步挂上 versionchange 监听
  opened[0].dispatchEvent(new Event('versionchange'))
  await tick()
  assert.equal(opened[0].closed, true, 'blocking 应关闭旧连接')
  await touch()
  assert.equal(opened.length, 2, '让位后必须重新 openDB，而非复用已关闭连接')
  assert.equal(opened[1].closed, false)

  // 让位后库已升到更高版本：重开报 VersionError → 不缓存失败 + 一次性刷新提示
  await tick()
  opened[1].dispatchEvent(new Event('versionchange'))
  await tick()
  failWithVersionError = true
  const err1 = await touch()
  assert.equal(err1?.name, 'VersionError', '应以 VersionError 拒绝而非 InvalidStateError')
  const warns = () => toasts.filter(t => t.text.includes('刷新')).length
  assert.equal(warns(), 1, 'VersionError 应提示刷新页面')
  const err2 = await touch()
  assert.equal(err2?.name, 'VersionError', 'rejected Promise 不得被缓存，应重试再失败')
  assert.equal(warns(), 1, '刷新提示只发一次')
  console.log('✓ G4-03 让位后丢弃缓存连接并重开；VersionError 一次性提示刷新')
}

console.log('\nG4 回归测试全部通过')
process.exit(0)
