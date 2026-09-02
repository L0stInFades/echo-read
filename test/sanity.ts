/**
 * 解析与分段核心逻辑的冒烟测试（node + tsx 运行，非正式单测）
 * 运行：npx tsx test/sanity.ts
 */
import assert from 'node:assert'
import iconv from 'iconv-lite'
import { decodeText, splitChapters } from '../src/lib/txt'
import { segmentChapter, segmentIndexAt } from '../src/lib/segment'
import { paraRanges, layoutBlocks, fragText, boundChapters, joinParagraphs, CHAPTER_MAX_CHARS } from '../src/lib/text'
import { pickTtsModels, buildSpeechBody, parsePcmParams, pcmToWav, buildHeaders, isFatalSpeechError } from '../src/tts/providers/openai-speech'
import { backoffDelay } from '../src/tts/engine'
import {
  canonicalModelId,
  describeVoice,
  catalogVoices,
  groupVoices,
  defaultVoiceFor,
  modelHints
} from '../src/tts/voices'

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

// N+2. 在线模型筛选：简介与单价随行（OpenRouter TTS 按字符计价，Gemini 另有 token 价）
{
  const models = pickTtsModels({
    data: [
      {
        id: 'hexgrad/kokoro-82m',
        name: 'Kokoro',
        architecture: { input_modalities: ['text'], output_modalities: ['speech'] },
        description: ' 开源轻量 TTS ',
        pricing: { prompt: '0.00000062', completion: '0' }
      },
      {
        id: 'google/gemini-3.1-flash-tts-preview',
        name: 'Gemini TTS',
        architecture: { input_modalities: ['text'], output_modalities: ['speech'] },
        pricing: { prompt: '0.000001', completion: '0.00002' }
      }
    ]
  })
  assert.equal(models[1].description, '开源轻量 TTS')
  assert.equal(models[1].promptPrice, 0.00000062)
  assert.equal(models[1].completionPrice, undefined) // '0' 不算有效单价
  assert.equal(models[0].completionPrice, 0.00002)
  console.log('✓ 在线模型筛选（简介/单价提取）')
}

// N+3. 音色目录：结构化音色 ID 解析（语言/性别/风格）
{
  assert.equal(canonicalModelId('fish-audio/s2.1-pro-free:free'), 'fish-audio/s2.1-pro-free')
  // Kokoro：前缀 = 语言 + 性别；中文音色有通行中文名
  const xiaoxiao = describeVoice('hexgrad/kokoro-82m', 'zf_xiaoxiao')
  assert.deepEqual([xiaoxiao.label, xiaoxiao.lang, xiaoxiao.gender], ['晓晓', 'zh', 'f'])
  const emma = describeVoice('hexgrad/kokoro-82m', 'bf_emma')
  assert.deepEqual([emma.label, emma.lang, emma.gender, emma.note], ['Emma', 'en', 'f', '英音'])
  // Deepgram：aura-2-{name}-{lang}
  const thalia = describeVoice('deepgram/aura-2', 'aura-2-thalia-en')
  assert.deepEqual([thalia.label, thalia.lang, thalia.gender], ['Thalia', 'en', 'f'])
  // Voxtral：{地区}_{人名}_{情绪}
  const jane = describeVoice('mistralai/voxtral-mini-tts-2603', 'gb_jane_confident')
  assert.deepEqual([jane.label, jane.lang, jane.gender, jane.note], ['Jane·自信', 'en', 'f', '英音'])
  // MAI：{locale}-{Name}:MAI-Voice-2
  const harper = describeVoice('microsoft/mai-voice-2', 'en-US-Harper:MAI-Voice-2')
  assert.deepEqual([harper.label, harper.lang, harper.gender], ['Harper', 'en', 'f'])
  // Gemini 具名表
  const kore = describeVoice('google/gemini-3.1-flash-tts-preview', 'Kore')
  assert.deepEqual([kore.lang, kore.gender, kore.note], ['multi', 'f', '坚定'])
  // 未知音色退化为原样标签
  const raw = describeVoice('unknown/model', 'nova')
  assert.deepEqual([raw.label, raw.lang], ['nova', undefined])
  console.log('✓ 音色 ID 解析（Kokoro/Deepgram/Voxtral/MAI/Gemini + 兜底）')
}

// N+4. 音色目录：兜底表完整性与语言分组排序
{
  assert.equal(catalogVoices('hexgrad/kokoro-82m').length, 54)
  assert.equal(catalogVoices('deepgram/aura-2').length, 90)
  assert.equal(catalogVoices('mistralai/voxtral-mini-tts-2603').length, 30)
  assert.equal(catalogVoices('google/gemini-3.1-flash-tts-preview').length, 30)
  // 开放音色模型不给目录（由 freeVoice 建议接管）
  assert.equal(catalogVoices('fish-audio/s2.1-pro-free:free').length, 0)
  assert.equal(catalogVoices('minimax/speech-2.8-turbo').length, 0)
  // 在线列表优先于兜底表
  assert.deepEqual(catalogVoices('hexgrad/kokoro-82m', ['zm_yunxi']).map(v => v.id), ['zm_yunxi'])
  // 分组：中文最前，组内保序
  const groups = groupVoices(catalogVoices('hexgrad/kokoro-82m'))
  assert.equal(groups[0].lang, 'zh')
  assert.equal(groups[0].voices.length, 8)
  assert.ok(groups.every(g => g.voices.length > 0))
  console.log('✓ 音色目录（兜底表 54/90/30/30 + 中文分组置顶）')
}

// N+5. 默认音色：模型偏好 → 中文优先 → 首个；开放音色模型给建议/留空
{
  assert.equal(defaultVoiceFor('hexgrad/kokoro-82m'), 'zf_xiaoxiao') // preferred
  assert.equal(defaultVoiceFor('openai/tts-1-hd', ['nova', 'shimmer']), 'nova') // 无中文 → 首个
  assert.equal(defaultVoiceFor('fish-audio/s2.1-pro-free:free'), '') // voice 可省略
  assert.equal(defaultVoiceFor('minimax/speech-2.8-turbo'), 'audiobook_female_1') // 建议首项
  assert.equal(defaultVoiceFor('google/gemini-3.1-flash-tts-preview'), 'Kore')
  assert.ok(modelHints('fish-audio/s9-future')?.voiceOptional, '厂商前缀兜底应命中 fish-audio')
  console.log('✓ 默认音色选择（偏好/中文优先/开放音色）')
}

// N+6. 请求体组装：OpenRouter 方言（format 收敛、instructions 经 provider.options、voice 省略）
{
  const orCfg = {
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: 'k',
    model: 'hexgrad/kokoro-82m',
    voice: 'zf_xiaoxiao',
    instructions: '温柔一点',
    format: 'opus' as const
  }
  const body = buildSpeechBody(orCfg, '你好')
  assert.equal(body.response_format, 'mp3') // OpenRouter 不支持 opus → 收敛 mp3
  assert.deepEqual(body.provider, { options: { openai: { instructions: '温柔一点' } } })
  assert.equal((body as any).instructions, undefined)
  // Gemini 仅支持 pcm → 强制 pcm
  const gemini = buildSpeechBody({ ...orCfg, model: 'google/gemini-3.1-flash-tts-preview', voice: 'Kore' }, 'hi')
  assert.equal(gemini.response_format, 'pcm')
  // voice 留空 → 省略参数（fish 默认音色场景）
  const fish = buildSpeechBody({ ...orCfg, model: 'fish-audio/s1', voice: ' ' }, 'hi')
  assert.equal('voice' in fish, false)
  // 非 OpenRouter（OpenAI 官方等）：保留顶层 instructions 与原始 format
  const oa = buildSpeechBody({ ...orCfg, baseUrl: 'https://api.openai.com/v1' }, 'hi')
  assert.equal(oa.response_format, 'opus')
  assert.equal(oa.instructions, '温柔一点')
  assert.equal((oa as any).provider, undefined)
  console.log('✓ 请求体组装（OpenRouter 方言 vs OpenAI 官方）')
}

// N+7. PCM 封 WAV：Content-Type 参数解析 + RIFF 头
{
  assert.deepEqual(parsePcmParams('audio/pcm;rate=24000;channels=1'), { rate: 24000, channels: 1 })
  assert.deepEqual(parsePcmParams('audio/pcm'), { rate: 24000, channels: 1 }) // 缺省值
  const pcm = new Uint8Array([1, 2, 3, 4]).buffer
  const wav = pcmToWav(pcm, 24000, 1)
  assert.equal(wav.type, 'audio/wav')
  assert.equal(wav.size, 44 + 4)
  const head = new DataView(await wav.arrayBuffer())
  assert.equal(String.fromCharCode(head.getUint8(0), head.getUint8(1), head.getUint8(2), head.getUint8(3)), 'RIFF')
  assert.equal(head.getUint32(24, true), 24000) // 采样率
  assert.equal(head.getUint16(22, true), 1) // 声道
  assert.equal(head.getUint32(40, true), 4) // data 长度
  console.log('✓ PCM 封 WAV（参数解析 + RIFF 头字段）')
}

// N+8. 请求头组装：归因头只对 OpenRouter 附加，其他服务保持纯净
{
  const cfg = {
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: 'sk-or-test',
    model: 'hexgrad/kokoro-82m',
    voice: 'zf_xiaoxiao',
    format: 'mp3' as const
  }
  const or = buildHeaders(cfg)
  assert.equal(or.Authorization, 'Bearer sk-or-test')
  assert.equal(or['X-OpenRouter-Title'], 'Lector')
  assert.ok(or['HTTP-Referer']?.startsWith('https://'), 'HTTP-Referer 缺失')
  const oa = buildHeaders({ ...cfg, baseUrl: 'https://api.openai.com/v1' })
  assert.equal(oa.Authorization, 'Bearer sk-or-test')
  assert.equal(oa['X-OpenRouter-Title'], undefined)
  assert.equal(oa['HTTP-Referer'], undefined)
  console.log('✓ 请求头组装（OpenRouter 归因头按端点附加）')
}

// N+9. 指数退避：固定 rand 消除随机性（1s 起倍增、30s 封顶、±20% 抖动边界）
{
  assert.deepEqual(
    [0, 1, 2, 3, 4, 5, 6].map(a => backoffDelay(a, () => 0.5)),
    [1000, 2000, 4000, 8000, 16000, 30000, 30000]
  )
  assert.equal(backoffDelay(0, () => 0), 800) // -20% 下界
  assert.equal(backoffDelay(0, () => 1), 1200) // +20% 上界
  assert.equal(backoffDelay(5, () => 0), 24000)
  assert.equal(backoffDelay(5, () => 1), 36000)
  console.log('✓ 指数退避（倍增封顶 + 抖动边界）')
}

// N+10. 致命合成错误判定：4xx 配置类立即失败，408/429/5xx/网络层错误可重试
{
  const withStatus = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status })
  for (const s of [400, 401, 402, 404]) assert.equal(isFatalSpeechError(withStatus(s)), true, `${s} 应为致命错误`)
  for (const s of [429, 408, 500, 502]) assert.equal(isFatalSpeechError(withStatus(s)), false, `${s} 应可重试`)
  assert.equal(isFatalSpeechError(new TypeError('fetch failed')), false)
  console.log('✓ 致命合成错误判定（4xx 配置类 vs 可重试）')
}

console.log('\n全部冒烟测试通过')
