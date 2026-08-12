/**
 * G3 组(文本解析与分段)回归测试 —— 对应 QA 修复 G3-01/02/03/05/06
 * 运行:npx tsx test/qa-g3.ts
 *
 * G3-04(跨段落合并片段的 data-start 未按段落钳制)根因在 ReaderView.vue 的渲染绑定,
 * 依赖浏览器布局与点击坐标,无法在 Node 机器测;locate.ts 侧仅收紧了契约注释,
 * 功能修复由负责视图层的组实施(绑定改为按段落钳制后的区间)。
 *
 * G3-05 的用例依赖『Node 无真实 IndexedDB』:用抛错计数桩替身 indexedDB.open,
 * 每次真实派生尝试必经 db() → openDB → indexedDB.open,open 调用数 = 派生次数。
 */
import assert from 'node:assert'
import { segmentChapter } from '../src/lib/segment'
import { alignSurrogateCut, boundChapters, CHAPTER_MAX_CHARS } from '../src/lib/text'
import { splitChapters } from '../src/lib/txt'

/** 是否不含孤立代理项(等价 String#isWellFormed,不依赖新 API) */
const wellFormed = (s: string): boolean => {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c >= 0xd800 && c <= 0xdbff) {
      const n = s.charCodeAt(i + 1)
      if (!(n >= 0xdc00 && n <= 0xdfff)) return false
      i++
    } else if (c >= 0xdc00 && c <= 0xdfff) return false
  }
  return true
}

// G3-01a. 超长 emoji 单句硬切:片段无孤立代理项,UTF-8 往返无损,首尾相接零丢字
{
  const text = 'a' + '😀'.repeat(200)
  const segs = segmentChapter(text, 120)
  for (const s of segs) {
    const frag = text.slice(s.start, s.end)
    assert.ok(wellFormed(frag), `片段 [${s.start},${s.end}) 含孤立代理项`)
    assert.equal(new TextDecoder().decode(new TextEncoder().encode(frag)), frag, 'UTF-8 往返应无损')
    assert.ok(s.end - s.start <= 120, '回退对齐不应加长片段')
  }
  for (let i = 1; i < segs.length; i++) assert.equal(segs[i].start, segs[i - 1].end, '硬切片段应首尾相接')
  assert.equal(segs[0].start, 0)
  assert.equal(segs[segs.length - 1].end, text.length)
  console.log('✓ G3-01 超长 emoji 句硬切不劈代理对 →', segs.length, '片段')
}

// G3-01b. 步长 1 极端:回退会原地踏步,须整对包住且循环推进
{
  const segs = segmentChapter('😀😀😀', 1)
  assert.deepEqual(segs, [
    { start: 0, end: 2 },
    { start: 2, end: 4 },
    { start: 4, end: 6 }
  ])
  console.log('✓ G3-01 步长 1 包住整个代理对(不卡死不劈切)')
}

// G3-01c. 源文本本身的孤立高代理不触发回退(只对完整代理对对齐)
{
  const text = 'x'.repeat(119) + '\ud83d' + 'y'.repeat(120)
  const segs = segmentChapter(text, 120)
  assert.equal(segs[0].end, 120, '孤立代理项处切点应保持原位')
  assert.equal(alignSurrogateCut(text, 120), 120)
  assert.equal(alignSurrogateCut('a😀b', 2), 1)
  assert.equal(alignSurrogateCut('a😀b', 1), 1)
  assert.equal(alignSurrogateCut('😀', 0), 0)
  console.log('✓ G3-01 对齐仅作用于完整代理对(孤立项/边界原样)')
}

// G3-01d. BMP 长句硬切行为不回归(与 sanity 用例 11 对齐)
{
  const segs = segmentChapter('一'.repeat(500) + '。', 100)
  assert.equal(segs.length, 6)
  console.log('✓ G3-01 常规硬切片段数不变')
}

// G3-02. 非法 maxChunkChars 入口钳制:0/负/NaN/±Infinity/<1 小数一律按默认 120
// 注:回退此修复会让 0/-3 两例死循环(用例卡死即失败),而非断言不过
{
  const text = '你好。世界。'
  const expect = segmentChapter(text, 120)
  for (const bad of [0, -3, NaN, Infinity, -Infinity, 0.4]) {
    assert.deepEqual(segmentChapter(text, bad), expect, `maxChunkChars=${bad} 应等效默认值`)
  }
  assert.deepEqual(segmentChapter(text, 3.9), segmentChapter(text, 3), '合法小数应向下取整')
  console.log('✓ G3-02 非法片段长度钳制(0/负/NaN 不再死循环)')
}

// G3-03. boundChapters 巨段硬切:切点避开代理对,入库文本不畸形且字数守恒
{
  const para = 'x'.repeat(CHAPTER_MAX_CHARS - 1) + '😀' + 'y'.repeat(100)
  const out = boundChapters([{ title: '巨', paragraphs: [para] }])
  assert.equal(out.length, 2)
  for (const c of out) {
    assert.ok(wellFormed(c.text), `${c.title} 含孤立代理项`)
    assert.ok(c.text.length <= CHAPTER_MAX_CHARS)
  }
  assert.equal(out.map(c => c.text).join(''), para, '硬切不得丢字')
  console.log('✓ G3-03 巨段硬切不劈代理对(边界 emoji 归入后章)')
}

// G3-03b. 连续 emoji 巨段:多个切点全部对齐,限长与守恒同时成立
{
  const para = 'x' + '😀'.repeat(CHAPTER_MAX_CHARS)
  const out = boundChapters([{ title: 'E', paragraphs: [para] }])
  assert.ok(out.length >= 3)
  assert.ok(out.every(c => wellFormed(c.text) && c.text.length <= CHAPTER_MAX_CHARS))
  assert.equal(out.map(c => c.text).join(''), para)
  console.log('✓ G3-03 连续 emoji 巨段多切点全对齐 →', out.length, '章')
}

// G3-03c. 恰好 8000 / 8001 的既有边界行为不回归
{
  const exact = boundChapters([{ title: 'A', paragraphs: ['z'.repeat(CHAPTER_MAX_CHARS)] }])
  assert.equal(exact.length, 1)
  assert.equal(exact[0].text.length, CHAPTER_MAX_CHARS)
  const over = boundChapters([{ title: 'B', paragraphs: ['z'.repeat(CHAPTER_MAX_CHARS + 1)] }])
  assert.equal(over.length, 2)
  assert.equal(over.map(c => c.text).join(''), 'z'.repeat(CHAPTER_MAX_CHARS + 1))
  console.log('✓ G3-03 恰 8000 不拆 / 8001 拆二 不回归')
}

// G3-06. Chapter 标题单行约束:孤行 'Chapter' + 数字行不再并成带换行标题
{
  const cross = splitChapters('Chapter\n12\nbody text here.\n\nChapter\n13\nmore body.')
  assert.ok(cross.every(c => !c.title.includes('\n')), '章节标题不得含换行')
  const all = cross.flatMap(c => [c.title, ...c.paragraphs]).join('\n')
  for (const line of ['12', 'body text here.', '13', 'more body.']) {
    assert.ok(all.includes(line), `内容丢失: ${line}`)
  }
  // 同行形式(有无空格、大小写)照常识别
  const same = splitChapters('Chapter 1 Start\nbody one.\nChapter 2 End\nbody two.')
  assert.equal(same.length, 2)
  assert.equal(same[0].title, 'Chapter 1 Start')
  const tight = splitChapters('CHAPTER1\nalpha.\nchap2\nbeta.')
  assert.equal(tight.length, 2)
  console.log('✓ G3-06 Chapter 标题单行(跨行不吞并、同行照常)')
}

// G3-05. getDerivedChapter 并发去重 + invalidateDerived 摘除在途登记
{
  let openCount = 0
  ;(globalThis as any).indexedDB = {
    open() {
      openCount++
      throw new Error('Node 无 IndexedDB(计数桩)')
    }
  }
  const { getDerivedChapter, invalidateDerived } = await import('../src/lib/chapters')

  // 同 key 并发未命中:共享同一在途派生(修复前各自派生 → open 2 次)
  const p1 = getDerivedChapter('bk', 0, 100)
  const p2 = getDerivedChapter('bk', 0, 100)
  const [r1, r2] = await Promise.allSettled([p1, p2])
  assert.equal(openCount, 1, `并发未命中应只派生一次,实际 ${openCount}`)
  assert.equal(r1.status, 'rejected')
  assert.equal(r2.status, 'rejected')
  assert.ok(
    (r1 as PromiseRejectedResult).reason === (r2 as PromiseRejectedResult).reason,
    '两个调用方应共享同一次派生'
  )

  // 不同 key 互不影响
  await getDerivedChapter('bk', 1, 100).catch(() => {})
  assert.equal(openCount, 2)

  // 派生失败后在途登记已清理:同 key 重试触发新派生(失败不驻留)
  await getDerivedChapter('bk', 0, 100).catch(() => {})
  assert.equal(openCount, 3)

  // 删书竞态:invalidateDerived 立即摘除在途登记,其后同 key 请求不得沿用旧在途
  const stale = getDerivedChapter('bk', 0, 100) // open #4
  invalidateDerived('bk')
  const fresh = getDerivedChapter('bk', 0, 100) // 摘除生效 → open #5
  await Promise.allSettled([stale, fresh])
  assert.equal(openCount, 5, `invalidate 后应重新派生,实际 ${openCount}`)
  console.log('✓ G3-05 并发派生去重 + invalidateDerived 摘除在途登记')
}

console.log('\nG3 组回归全部通过')
