/**
 * QA-G1 回归：TTS 引擎并发与播放状态机（npx tsx test/qa-g1.ts）
 *
 * 浏览器依赖（Audio / speechSynthesis / indexedDB / fetch）以本文件内的最小桩替代，
 * 真实出声与自动播放策略无法在 node 断言，这里只验证引擎的纯逻辑时序与状态广播。
 * 执行顺序刻意为 G1-04 → G1-02 → G1-03 → G1-05 → G1-01：
 * G1-04 依赖「无 indexedDB」环境，必须在安装 fake IndexedDB 之前运行。
 */
import assert from 'node:assert'

const g = globalThis as any

/* ---------- 桩：Audio（blob 源起播后 autoEndMs 自动触发 onended） ---------- */

const audioEls: any[] = []
let autoEndMs = 120
class FakeAudio {
  loop = false
  volume = 1
  src = ''
  playbackRate = 1
  paused = true
  onended: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor() {
    audioEls.push(this)
  }
  play() {
    this.paused = false
    if (this.src.startsWith('blob:')) {
      const src = this.src
      setTimeout(() => {
        // 仍是同一段且未被换源/暂停才算自然播完
        if (this.src === src && !this.paused) this.onended?.()
      }, autoEndMs)
    }
    return Promise.resolve()
  }
  pause() {
    this.paused = true
  }
}
g.Audio = FakeAudio
let blobN = 0
g.URL.createObjectURL = () => `blob:qa-${++blobN}`
g.URL.revokeObjectURL = () => {}

/* ---------- 桩：speechSynthesis（记录 speak 过的 utterance） ---------- */

const spoken: any[] = []
class FakeUtterance {
  text: string
  rate = 1
  voice: any = null
  onend: (() => void) | null = null
  onerror: ((e: any) => void) | null = null
  constructor(text: string) {
    this.text = text
  }
}
g.SpeechSynthesisUtterance = FakeUtterance
g.speechSynthesis = {
  speak: (u: any) => spoken.push(u),
  cancel() {},
  pause() {},
  resume() {},
  getVoices: () => [],
  onvoiceschanged: null
}

/* ---------- 桩：可编程 fetch（按脚本逐次 ok / netfail / pending，缺省 ok） ---------- */

type FetchStep = { kind: 'ok' } | { kind: 'netfail' } | { kind: 'pending' }
let fetchScript: FetchStep[] = []
const fetchCalls: { body: any }[] = []
const fetchPending: (() => void)[] = []
const okRes = () => ({
  ok: true,
  status: 200,
  headers: { get: (h: string) => (h.toLowerCase() === 'content-type' ? 'audio/mpeg' : null) },
  blob: async () => new Blob([new Uint8Array(64)], { type: 'audio/mpeg' }),
  arrayBuffer: async () => new Uint8Array(64).buffer,
  text: async () => ''
})
g.fetch = (_url: string, init: any = {}) => {
  fetchCalls.push({ body: init.body ? JSON.parse(init.body) : null })
  const step = fetchScript[fetchCalls.length - 1] ?? ({ kind: 'ok' } as FetchStep)
  return new Promise((resolve, reject) => {
    init.signal?.addEventListener?.('abort', () => {
      const e: any = new Error('aborted')
      e.name = 'AbortError'
      reject(e)
    })
    if (step.kind === 'ok') setTimeout(() => resolve(okRes()), 2)
    else if (step.kind === 'netfail') setTimeout(() => reject(new TypeError('Failed to fetch')), 2)
    else fetchPending.push(() => resolve(okRes()))
  })
}

/* ---------- 桩：最小 fake IndexedDB（满足 idb v8 的 openDB + get/put/getAll/delete） ---------- */

function installFakeIDB(): Map<string, Map<string, any>> {
  const stores = new Map<string, Map<string, any>>()
  for (const name of ['books', 'chapterIndex', 'chapters', 'audio']) stores.set(name, new Map())
  const keyPaths: Record<string, string> = { books: 'id', chapterIndex: 'bookId', chapters: 'key', audio: 'key' }

  class FIDBRequest extends EventTarget {
    result: any
    error: any = null
  }
  class FIDBObjectStore {
    name: string
    constructor(name: string) {
      this.name = name
    }
    private async(fill: (req: FIDBRequest) => void) {
      const req = new FIDBRequest()
      setTimeout(() => {
        fill(req)
        req.dispatchEvent(new Event('success'))
      }, 0)
      return req
    }
    get(key: string) {
      return this.async(req => (req.result = stores.get(this.name)!.get(key)))
    }
    put(value: any) {
      return this.async(req => {
        const key = value?.[keyPaths[this.name]]
        stores.get(this.name)!.set(key, value)
        req.result = key
      })
    }
    getAll() {
      return this.async(req => (req.result = [...stores.get(this.name)!.values()]))
    }
    delete(key: string) {
      return this.async(() => stores.get(this.name)!.delete(key))
    }
    createIndex() {}
  }
  class FIDBTransaction extends EventTarget {
    objectStoreNames: string[]
    error: any = null
    private cache = new Map<string, FIDBObjectStore>()
    constructor(names: string[]) {
      super()
      this.objectStoreNames = names
      // 请求在 t0 落地，事务在 t1 完成
      setTimeout(() => this.dispatchEvent(new Event('complete')), 1)
    }
    objectStore(name: string) {
      if (!this.cache.has(name)) this.cache.set(name, new FIDBObjectStore(name))
      return this.cache.get(name)!
    }
  }
  class FIDBDatabase extends EventTarget {
    objectStoreNames = { contains: (n: string) => stores.has(n) }
    transaction(names: string | string[]) {
      return new FIDBTransaction(Array.isArray(names) ? names : [names])
    }
    createObjectStore(name: string) {
      if (!stores.has(name)) stores.set(name, new Map())
      return new FIDBObjectStore(name)
    }
    deleteObjectStore(name: string) {
      stores.delete(name)
    }
    close() {}
  }
  class FIDBIndex {}
  class FIDBCursor {}
  const theDb = new FIDBDatabase()
  g.IDBRequest = FIDBRequest
  g.IDBObjectStore = FIDBObjectStore
  g.IDBTransaction = FIDBTransaction
  g.IDBDatabase = FIDBDatabase
  g.IDBIndex = FIDBIndex
  g.IDBCursor = FIDBCursor
  g.indexedDB = {
    open() {
      const req = new FIDBRequest()
      setTimeout(() => {
        req.result = theDb
        req.dispatchEvent(new Event('success'))
      }, 0)
      return req
    },
    deleteDatabase() {
      const req = new FIDBRequest()
      setTimeout(() => req.dispatchEvent(new Event('success')), 0)
      return req
    }
  }
  return stores
}

/* ---------- 工具 ---------- */

const tick = (ms: number) => new Promise(r => setTimeout(r, ms))

async function until(label: string, cond: () => boolean, ms = 3000) {
  const t0 = Date.now()
  while (!cond() && Date.now() - t0 < ms) await tick(10)
  assert.ok(cond(), `等待超时：${label}`)
}

const makeCfg = (over: Record<string, any> = {}) => ({
  baseUrl: 'https://api.example.com/v1',
  apiKey: 'sk-test',
  model: 'tts-1',
  voice: 'alloy',
  format: 'mp3',
  ...over
})
const makeSettings = (provider: 'openai-speech' | 'webspeech') => ({
  provider,
  rate: 1,
  maxChunkChars: 50,
  prefetch: 0
})

/** 直接注入章节状态绕过 load()（TS private 仅类型层，运行时可写） */
function inject(eng: any, text: string, segments: { start: number; end: number }[]) {
  eng.derived = { title: 'QA 章', text, paras: [], segments }
  eng.chapterIndex = 0
  eng.chapterCount = 1
  eng.bookId = 'qa'
  eng.segmentIndex = 0
  eng.state = 'paused'
}

const { TTSEngine } = await import('../src/tts/engine')
const newEngine = (provider: 'openai-speech' | 'webspeech', cfg: any = makeCfg()) =>
  new TTSEngine(makeSettings(provider) as any, cfg) as any

/* ---------- G1-04：audioGet 失败降级为 miss（须在安装 fake IndexedDB 之前跑） ---------- */
{
  assert.equal(typeof g.indexedDB, 'undefined', '本用例必须在无 indexedDB 环境下运行')
  fetchScript = [{ kind: 'ok' }, { kind: 'ok' }]
  fetchCalls.length = 0
  const eng = newEngine('openai-speech')
  inject(eng, '一句。二句。', [
    { start: 0, end: 3 },
    { start: 3, end: 6 }
  ])
  await eng.play()
  await until('引擎到达终态', () => ['paused', 'error'].includes(eng.snapshot().state))
  const snap = eng.snapshot()
  assert.equal(snap.state, 'paused', `IDB 读失败不应停播报错（实际 error=${snap.error}）`)
  assert.equal(snap.error, undefined)
  assert.equal(fetchCalls.length, 2, '两个片段都应真正发起网络合成')
  console.log('✓ G1-04 缓存读失败只当 miss：无 IDB 环境正常合成并播完全章')
}

const idbStores = installFakeIDB()

/* ---------- G1-02：退避重试期间改配置，缓存键与合成配置须锚定同一快照 ---------- */
{
  fetchScript = [{ kind: 'netfail' }, { kind: 'ok' }, { kind: 'ok' }]
  fetchCalls.length = 0
  const eng = newEngine('openai-speech') // voice=alloy
  inject(eng, '一句。二句。', [
    { start: 0, end: 3 },
    { start: 3, end: 6 }
  ])
  const keyAlloy = eng.cacheKey('一句。')
  await eng.play()
  await until('首次合成失败进入退避', () => eng.snapshot().retryNote !== undefined)
  // 退避窗口内用户换音色：updateConfig 不换代、不打断在途片段
  eng.updateConfig(makeSettings('openai-speech'), makeCfg({ voice: 'nova' }))
  const keyNova = eng.cacheKey('一句。')
  const keyNovaSeg2 = eng.cacheKey('二句。')
  assert.notEqual(keyAlloy, keyNova)
  await until('退避重试发出', () => fetchCalls.length >= 2)
  assert.equal(fetchCalls[0].body.voice, 'alloy')
  assert.equal(fetchCalls[1].body.voice, 'alloy', '在途片段的重试必须沿用入口配置快照')
  await until('第二段以新配置合成', () => fetchCalls.length >= 3)
  assert.equal(fetchCalls[2].body.voice, 'nova', '新配置应从下一片段起生效')
  await until('播完全章', () => eng.snapshot().state === 'paused')
  const keys = [...idbStores.get('audio')!.keys()]
  assert.ok(keys.includes(keyAlloy), 'alloy 合成的音频应落 alloy 键')
  assert.ok(keys.includes(keyNovaSeg2), 'nova 合成的音频应落 nova 键')
  assert.ok(!keys.includes(keyNova), '不得把跨配置音频写入未参与合成的键（缓存投毒）')
  console.log('✓ G1-02 退避期间改配置：键与合成配置同快照，无缓存投毒')
}

/* ---------- G1-03：句柄落地必须广播 synthesizing=false ---------- */
{
  fetchScript = [{ kind: 'ok' }, { kind: 'ok' }]
  fetchCalls.length = 0
  autoEndMs = 150
  const eng = newEngine('openai-speech')
  inject(eng, '甲。乙。', [
    { start: 0, end: 2 },
    { start: 2, end: 4 }
  ])
  let last: any = null
  eng.on((s: any) => (last = s))
  await eng.play()
  assert.equal(eng.snapshot().synthesizing, true)
  assert.equal(last.synthesizing, true, '合成窗口开启应已广播')
  await until('句柄落地', () => eng.handle !== null)
  const shared = audioEls[0]
  assert.ok(shared && !shared.paused && shared.src.startsWith('blob:'), '共享元素应正在播 blob 音频')
  assert.equal(eng.snapshot().synthesizing, false)
  assert.equal(last.synthesizing, false, '句柄落地必须广播 synthesizing=false，出声期间不得停留在「合成中」')
  await until('播完全章', () => eng.snapshot().state === 'paused')
  console.log('✓ G1-03 句柄落地即广播 synthesizing=false（出声期间指示正确）')
}

/* ---------- G1-05：僵尸 loop 不得回写新代的 synthesizing ---------- */
{
  fetchScript = [{ kind: 'netfail' }, { kind: 'pending' }, { kind: 'ok' }]
  fetchCalls.length = 0
  fetchPending.length = 0
  const eng = newEngine('openai-speech')
  inject(eng, '丙。丁。', [
    { start: 0, end: 2 },
    { start: 2, end: 4 }
  ])
  await eng.play()
  await until('首次合成失败进入退避', () => eng.snapshot().retryNote !== undefined)
  assert.equal(eng.snapshot().synthesizing, true)
  eng.pause() // 合成窗口期暂停：换代，旧 loop 仍睡在 ≤500ms 的退避分片里
  assert.equal(eng.snapshot().synthesizing, false)
  await eng.play() // 新代 loop：synthesizing=true，fetch2 悬挂在途
  await until('新代请求发出', () => fetchCalls.length >= 2)
  assert.equal(eng.snapshot().synthesizing, true)
  await tick(700) // 旧 loop 最迟 ~500ms 醒来退出，此窗口内不得回写新代状态
  assert.equal(eng.snapshot().state, 'playing')
  assert.equal(fetchPending.length, 1, 'fetch2 应仍在途')
  assert.equal(eng.snapshot().synthesizing, true, '僵尸 loop 不得把新代的 synthesizing 清成 false')
  assert.equal(audioEls[0].paused, false, '新代合成在途，静音保活不得被僵尸 loop 关停')
  fetchPending.shift()!() // 放行 fetch2：新代正常落地
  await until('新代句柄落地', () => eng.handle !== null)
  assert.equal(eng.snapshot().synthesizing, false)
  await until('播完全章', () => eng.snapshot().state === 'paused')
  console.log('✓ G1-05 僵尸 loop 代际守卫：不回写新代 synthesizing，保活配对无恙')
}

/* ---------- G1-01：句柄 ended 被 reject 后死句柄必须摘除 ---------- */
{
  const eng = newEngine('webspeech')
  inject(eng, '第一句。第二句。第三句。', [
    { start: 0, end: 4 },
    { start: 4, end: 8 },
    { start: 8, end: 12 }
  ])
  spoken.length = 0
  await eng.play()
  await until('首段开讲', () => spoken.length === 1)
  spoken[0].onerror({ error: 'synthesis-failed' }) // 播放中句柄出错（如音频被其它 App 抢占）
  await until('进入错误态', () => eng.snapshot().state === 'error')
  assert.equal(eng.snapshot().error, '播放中断')
  assert.equal(eng.handle, null, '出错的死句柄必须摘除，否则 play() 会误走 resume 分支')
  await eng.play() // 错误态点播放：必须走全新 loop 真正重新朗读
  await until('重新开讲', () => spoken.length === 2)
  assert.equal(spoken[1].text, '第一句。', '应从出错片段的开头重读')
  assert.equal(eng.snapshot().state, 'playing')
  assert.equal(eng.snapshot().error, undefined, '重新播放应清除错误提示')
  // 静默变体：暂停期间句柄出错，同样不得残留死句柄
  eng.pause()
  spoken[1].onerror({ error: 'synthesis-failed' })
  await until('暂停态死句柄被摘除', () => eng.handle === null)
  assert.equal(eng.snapshot().state, 'paused')
  assert.equal(eng.snapshot().error, undefined)
  await eng.play()
  await until('恢复后重新开讲', () => spoken.length === 3)
  assert.equal(eng.snapshot().state, 'playing')
  eng.pause() // 收尾：句柄级暂停，驻留的 loop 无定时器，不阻塞进程退出
  console.log('✓ G1-01 死句柄摘除：错误态/暂停态出错后 play 均能真正恢复朗读')
}

console.log('\nQA-G1 回归全部通过')
