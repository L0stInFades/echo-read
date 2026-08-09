/**
 * 解析与分段核心逻辑的冒烟测试（node + tsx 运行，非正式单测）
 * 运行：npx tsx test/sanity.ts
 */
import assert from 'node:assert'
import iconv from 'iconv-lite'
import { decodeText, splitChapters } from '../src/lib/txt'
import { segmentChapter, segmentIndexAt } from '../src/lib/segment'
import { paraRanges, layoutBlocks, fragText, boundChapters, joinParagraphs, CHAPTER_MAX_CHARS } from '../src/lib/text'
import { pickTtsModels } from '../src/tts/providers/openai-speech'

// 1. UTF-8 解码
{
  const buf = new TextEncoder().encode('第一章 开始\n内容')
  const { text, encoding } = decodeText(buf)
  assert.equal(encoding, 'utf-8')
  assert.ok(text.includes('第一章'))
  console.log('✓ UTF-8 解码')
}

// 2. GBK 解码（中文老书常见编码）
{
  const buf = iconv.encode('第二章 风云\n正文内容', 'gbk')
  const { text, encoding } = decodeText(new Uint8Array(buf))
  assert.ok(['gb18030', 'gbk', 'gb2312'].includes(encoding), encoding)
  assert.ok(text.includes('风云'), text)
  console.log('✓ GBK 解码 →', encoding)
}

// 3. 章节切分（数字章节）
{
  const text = '简介：这是一本测试书，讲述了一段漫长而曲折的故事，供单元测试使用。\n\n第一章 起源\n这是第一章的正文。\n第二段。\n\n第二章 发展\n第二章正文。\n\n第三章 高潮\n第三章正文。'
  const chapters = splitChapters(text)
  assert.equal(chapters.length, 4, JSON.stringify(chapters.map(c => c.title)))
  assert.equal(chapters[0].title, '开篇')
  assert.equal(chapters[1].title, '第一章 起源')
  assert.equal(chapters[3].title, '第三章 高潮')
  assert.deepEqual(chapters[1].paragraphs, ['这是第一章的正文。', '第二段。'])
  console.log('✓ 数字章节切分（含开篇前言）')
}

// 4. 章节切分（中文章节数）
{
  const text = '第一章 雪夜\n正文一。\n第二章 黎明\n正文二。'
  const chapters = splitChapters(text)
  assert.equal(chapters.length, 2)
  assert.equal(chapters[0].title, '第一章 雪夜')
  console.log('✓ 中文数字章节切分')
}

// 5. 无章节结构 → 按字数兜底切块
{
  const long = Array.from({ length: 600 }, (_, i) => `第${i}段内容内容内容内容内容内容内容内容内容内容。`).join('\n')
  const chapters = splitChapters(long)
  assert.ok(chapters.length >= 2, `兜底切块数=${chapters.length}`)
  console.log('✓ 无章节兜底切块 →', chapters.length, '块')
}

// 6. 幻影编号匹配不丢正文（列举/对白短行被误判为标题时，内容必须完整保留）
{
  const text = '1. 他来了\n2. 她走了\n3. 狗趴在门口\n这一段才是真正的正文，包含了完整的故事内容。'
  const chapters = splitChapters(text)
  const allText = chapters.flatMap(c => [c.title, ...c.paragraphs]).join('\n')
  for (const line of ['他来了', '她走了', '狗趴在门口', '这一段才是真正的正文']) {
    assert.ok(allText.includes(line), `内容丢失: ${line}`)
  }
  console.log('✓ 幻影编号匹配零丢字')
}

// 7. 单标题书（全书仅一个章节标题）+ 极短前言保留
{
  const text = '题记：献给远方。\n第一章 唯一\n' + '正文内容。'.repeat(50)
  const chapters = splitChapters(text)
  assert.ok(chapters.length >= 2, JSON.stringify(chapters.map(c => c.title)))
  assert.equal(chapters[0].title, '开篇')
  assert.ok(chapters[0].paragraphs[0].includes('题记'))
  assert.equal(chapters[1].title, '第一章 唯一')
  console.log('✓ 单标题采纳 + 短前言保留')
}

// 8. GBK 文件前 64KB 为纯 ASCII 时不错判（jschardet ascii 误判防护）
{
  const asciiHead = 'Project Gutenberg License\n'.repeat(200)
  const buf = iconv.encode(asciiHead + '\n第三章 归来\n中文正文。', 'gbk')
  const { text } = decodeText(new Uint8Array(buf))
  assert.ok(text.includes('归来') && text.includes('中文正文'), text.slice(0, 60))
  console.log('✓ ASCII 头部 + GBK 正文正确解码')
}

// 9. 段落区间与规范文本（零副本区间模型）
{
  const paragraphs = ['小明走进了屋子。他环顾四周。', '信上写着：欢迎回来。']
  const text = joinParagraphs(paragraphs)
  const paras = paraRanges(text)
  assert.equal(paras.length, 2)
  assert.equal(text.slice(paras[0].start, paras[0].end), paragraphs[0])
  assert.equal(text.slice(paras[1].start, paras[1].end), paragraphs[1])
  console.log('✓ 段落区间往返一致')
}

// 10. 句子分段与偏移定位（任意字点读的根基，纯偏移片段）
{
  const text = '小明走进了屋子。他环顾四周，看到桌上有一封信。\n信上写着：欢迎回来。'
  const segs = segmentChapter(text, 20)
  assert.ok(segs.length >= 2)
  // 片段区间合法且单调递增
  for (let i = 0; i < segs.length; i++) {
    assert.ok(segs[i].start < segs[i].end)
    if (i > 0) assert.ok(segs[i].start >= segs[i - 1].start)
  }
  // 任意偏移都能定位到正确片段
  const probe = text.indexOf('信上')
  const seg = segs[segmentIndexAt(segs, probe)]
  assert.ok(probe >= seg.start && probe < seg.end, `${probe} 应落在 [${seg.start},${seg.end})`)
  assert.ok(text.slice(seg.start, seg.end).includes('信上写着'))
  // 边界
  assert.equal(segmentIndexAt(segs, 0), 0)
  assert.equal(segmentIndexAt(segs, text.length + 100), segs.length - 1)
  console.log('✓ 句子分段与任意偏移定位 →', segs.length, '片段')
}

// 11. 超长句硬切（含纯空白片段剔除）
{
  const longSentence = '一'.repeat(500) + '。'
  const segs = segmentChapter(longSentence, 100)
  assert.ok(segs.length === 6, `硬切片段数=${segs.length}`)
  const withBlanks = '甲。' + ' '.repeat(300) + '乙。'
  const segs2 = segmentChapter(withBlanks, 50)
  assert.ok(segs2.every(s => withBlanks.slice(s.start, s.end).trim().length > 0), '不应有纯空白片段')
  console.log('✓ 超长句硬切 + 空白片段剔除 →', segs.length, '片段')
}

// 12. 双指针布局合并（渲染层 O(P+S)）
{
  const text = joinParagraphs(['第一句。第二句。', '第三句。第四句。第五句。'])
  const paras = paraRanges(text)
  const segs = segmentChapter(text, 12) // 小片段强制跨段
  const blocks = layoutBlocks(paras, segs)
  assert.equal(blocks.length, paras.length)
  // 每个片段恰好被归属到与之重叠的段落；fragText 拼接应还原段落文本
  for (let pi = 0; pi < paras.length; pi++) {
    const joined = blocks[pi].map(sp => fragText(text, sp, paras[pi])).join('')
    assert.equal(joined, text.slice(paras[pi].start, paras[pi].end), `段落 ${pi} 还原失败`)
  }
  // 所有片段都被分配且无遗漏
  assert.ok(blocks.flat().length >= segs.length)
  console.log('✓ 双指针布局合并（fragText 无损还原段落）')
}

// 13. 章节限长归一化
{
  const bigPara = '长'.repeat(CHAPTER_MAX_CHARS + 500) // 无换行巨型段落 → 硬切
  const chapters = boundChapters([
    { title: '短章', paragraphs: ['很短。'] },
    { title: '巨章', paragraphs: [bigPara] },
    { title: '长章', paragraphs: Array.from({ length: 100 }, () => '内容内容内容内容内容内容内容内容内容。') }
  ])
  assert.equal(chapters[0].title, '短章')
  assert.ok(chapters.every(c => c.text.length <= CHAPTER_MAX_CHARS))
  const giant = chapters.filter(c => c.title.startsWith('巨章'))
  assert.ok(giant.length === 2 && giant[1].title === '巨章（2）', JSON.stringify(giant.map(c => c.title)))
  assert.ok(giant.join('').length === 0 || giant.map(c => c.text).join('').length === bigPara.length)
  console.log('✓ 章节限长归一化（含巨型段落硬切）→', chapters.length, '章')
}

// N. 在线模型筛选：模态元数据（speech/audio）∪ id 启发式，supported_voices 提取
{
  const models = pickTtsModels({
    data: [
      { id: 'openai/gpt-4o-mini-tts', name: 'OpenAI: GPT-4o mini TTS', architecture: { input_modalities: ['text'], output_modalities: ['audio'] } },
      { id: 'fish-audio/s2-pro', name: 'FishAudio: S2 Pro', architecture: { input_modalities: ['text'], output_modalities: ['speech'] }, supported_voices: null },
      { id: 'google/gemini-3.1-flash-tts-preview', architecture: { input_modalities: ['text'], output_modalities: ['speech'] }, supported_voices: ['Zephyr', 'Puck', 42] },
      { id: 'openai/gpt-4o', name: 'OpenAI: GPT-4o', architecture: { input_modalities: ['text', 'image'], output_modalities: ['text'] } },
      { id: 'some/audio-chat', architecture: { input_modalities: ['audio'], output_modalities: ['audio'] } },
      { id: 'custom/my-tts', architecture: { input_modalities: ['text'], output_modalities: ['text'] } }, // 元数据不全但 id 命中
      { name: '无 id 垃圾条目', architecture: { input_modalities: ['text'], output_modalities: ['speech'] } }
    ]
  })
  assert.deepEqual(models.map(m => m.id), ['custom/my-tts', 'fish-audio/s2-pro', 'google/gemini-3.1-flash-tts-preview', 'openai/gpt-4o-mini-tts'])
  assert.equal(models[3].name, 'OpenAI: GPT-4o mini TTS') // 有 name 用 name
  assert.equal(models[2].name, 'google/gemini-3.1-flash-tts-preview') // 缺 name 回退 id
  assert.deepEqual(models[2].voices, ['Zephyr', 'Puck']) // supported_voices 提取并剔除非字符串
  assert.equal(models[1].voices, undefined) // supported_voices: null → 不带音色字段
  console.log('✓ 在线模型筛选（speech/audio 模态 ∪ id 启发式 + supported_voices）')
}

// N+1. 在线模型筛选：无元数据服务退化为 id 启发式
{
  const models = pickTtsModels({
    data: [{ id: 'tts-1' }, { id: 'gpt-4o' }, { id: 'fish-speech-1.5' }, { id: 'whisper-1' }]
  })
  assert.deepEqual(models.map(m => m.id), ['fish-speech-1.5', 'tts-1'])
  assert.deepEqual(pickTtsModels(null), [])
  assert.deepEqual(pickTtsModels({ data: 'junk' }), [])
  console.log('✓ 在线模型筛选（id 启发式 + 腐坏响应兜底）')
}

console.log('\n全部冒烟测试通过')
