/**
 * QA G2 组回归：Provider 与 OpenRouter 协议（openai-speech.ts / voices.ts）
 * 运行：npx tsx test/qa-g2.ts
 * 全部为纯函数用例，无浏览器/网络依赖（synthesizeOpenAI、fetchTtsModels 的
 * fetch 路径不在此测，其方言与解析逻辑已抽为纯函数覆盖）。
 */
import assert from 'node:assert'
import {
  pickTtsModels,
  parsePcmParams,
  isOpenRouterBase,
  buildHeaders,
  buildSpeechBody
} from '../src/tts/providers/openai-speech'
import {
  describeVoice,
  modelHints,
  catalogVoices,
  defaultVoiceFor,
  formatModelMeta,
  langLabel
} from '../src/tts/voices'

// G2-01. pickTtsModels：混合形状 /models 响应（模态字段为真值非数组）不抛错
{
  const mixed = pickTtsModels({
    data: [
      { id: 'good/tts', architecture: { input_modalities: ['text'], output_modalities: ['speech'] } },
      { id: 'bad/model-a', architecture: { output_modalities: {} } },
      { id: 'bad/model-b', architecture: { output_modalities: 42 } },
      { id: 'bad/model-c', architecture: { input_modalities: 7, output_modalities: ['speech'] } },
      { id: 'lucky/model', architecture: { input_modalities: ['text'], output_modalities: 'speech' } }
    ]
  })
  // 一条腐坏条目不得摧毁整个列表；字符串 'speech' 不算数组模态证据（不得靠 String.includes 侥幸命中）
  assert.deepEqual(mixed.map(m => m.id), ['good/tts'])
  // 模态字段腐坏但 id 命中启发式的条目仍应保留
  const heur = pickTtsModels({
    data: [
      { id: 'good/tts', architecture: { input_modalities: ['text'], output_modalities: ['speech'] } },
      { id: 'corrupt/tts-x', architecture: { output_modalities: {} } }
    ]
  })
  assert.deepEqual(heur.map(m => m.id), ['corrupt/tts-x', 'good/tts'])
  console.log('✓ G2-01 pickTtsModels 混合腐坏模态字段不抛错、退化为 id 启发式')
}

// G2-02. parsePcmParams：参数名必须有左边界，bitrate= 等其他参数的尾串不是采样率
{
  assert.deepEqual(parsePcmParams('audio/pcm;bitrate=128000'), { rate: 24000, channels: 1 })
  assert.deepEqual(parsePcmParams('audio/pcm;samplerate=48000;xchannels=6'), { rate: 24000, channels: 1 })
  // 正常形态（紧邻分号/带空格/大小写/L16）不受影响
  assert.deepEqual(parsePcmParams('audio/pcm;rate=24000;channels=1'), { rate: 24000, channels: 1 })
  assert.deepEqual(parsePcmParams('audio/pcm; rate=44100; channels=2'), { rate: 44100, channels: 2 })
  assert.deepEqual(parsePcmParams('audio/L16;RATE=16000;CHANNELS=2'), { rate: 16000, channels: 2 })
  assert.deepEqual(parsePcmParams('audio/pcm'), { rate: 24000, channels: 1 })
  console.log('✓ G2-02 parsePcmParams 参数名左边界（bitrate 不污染采样率）')
}

// G2-03. isOpenRouterBase：按主机名判定，撞名域/路径/查询串不得误判
{
  assert.equal(isOpenRouterBase('https://openrouter.ai/api/v1'), true)
  assert.equal(isOpenRouterBase('https://OpenRouter.AI/api/v1'), true)
  assert.equal(isOpenRouterBase('https://openrouter.ai:443/api/v1'), true)
  assert.equal(isOpenRouterBase('https://api.openrouter.ai/v1'), true) // 子域仍属 OpenRouter
  for (const u of [
    'https://api.openai.com/v1',
    'https://openrouter.aiproxy.example.com/v1', // 域名前缀撞名
    'https://notopenrouter.ai/v1', // 后缀撞名
    'https://evil.com/openrouter.ai/steal', // 路径中出现
    'https://api.example.com/v1?ref=openrouter.ai', // 查询串中出现
    'https://openrouter.ai.attacker.com/v1', // 子域伪装
    'not a url',
    ''
  ]) {
    assert.equal(isOpenRouterBase(u), false, u)
  }
  // 误判修复后的下游行为：陌生主机不发归因头，format/instructions 不被 OpenRouter 方言改写
  const cfg = {
    baseUrl: 'https://notopenrouter.ai/v1',
    apiKey: 'k',
    model: 'openai/gpt-4o-mini-tts',
    voice: 'alloy',
    instructions: '温柔',
    format: 'opus' as const
  }
  assert.equal(buildHeaders(cfg)['X-OpenRouter-Title'], undefined)
  assert.equal(buildHeaders(cfg)['HTTP-Referer'], undefined)
  const body = buildSpeechBody(cfg, 'hi')
  assert.equal(body.response_format, 'opus')
  assert.equal(body.instructions, '温柔')
  assert.equal((body as any).provider, undefined)
  console.log('✓ G2-03 isOpenRouterBase 主机名精确判定（撞名域不误判、下游方言不误触）')
}

// G2-04a. describeVoice：constructor 等原型链键不得当作查表命中
{
  const zf = describeVoice('hexgrad/kokoro-82m', 'zf_constructor')
  assert.deepEqual([zf.label, zf.lang, zf.gender], ['Constructor', 'zh', 'f']) // 修复前 label 为 Function
  const dg = describeVoice('deepgram/aura-2', 'aura-2-constructor-en')
  assert.deepEqual([dg.label, dg.lang, dg.gender], ['Constructor', 'en', undefined])
  const zo = describeVoice('zyphra/zonos-v0.1-transformer', 'constructor')
  assert.deepEqual([zo.id, zo.label], ['constructor', 'constructor']) // 修复前返回缺 id/label 的 {}
  const se = describeVoice('sesame/csm-1b', 'constructor')
  assert.deepEqual([se.id, se.label, se.lang], ['constructor', 'constructor', undefined])
  const vx = describeVoice('mistralai/voxtral-mini-tts-2603', 'en_constructor_constructor')
  assert.deepEqual([vx.label, vx.gender], ['Constructor·Constructor', undefined])
  assert.equal(describeVoice('microsoft/mai-voice-2', 'en-US-constructor:MAI-Voice-2').gender, undefined)
  // 具名表未命中时退化为原样标签，不得误标 lang
  assert.equal(describeVoice('google/gemini-3.1-flash-tts-preview', 'toString').lang, undefined)
  assert.equal(describeVoice('x-ai/grok-voice-tts-1.0', 'valueOf').lang, undefined)
  assert.equal(describeVoice('canopylabs/orpheus-3b-0.1-ft', 'hasOwnProperty').lang, undefined)
  assert.equal(describeVoice('qwen/qwen-audio-3.0-tts-flash', 'constructor').label, 'constructor')
  console.log('✓ G2-04a describeVoice 原型链键不命中、退化为原样标签')
}

// G2-04b. 模型级查表：modelHints/catalogVoices/formatModelMeta 对原型链模型 id 不抛错
{
  assert.equal(modelHints('constructor'), undefined) // 修复前返回 Function
  assert.equal(modelHints('toString/x'), undefined) // 厂商前缀兜底同样守卫
  assert.equal(modelHints('__proto__'), undefined)
  assert.deepEqual(catalogVoices('constructor'), []) // 修复前 TypeError: ids.map is not a function
  assert.equal(defaultVoiceFor('constructor'), '')
  assert.equal(formatModelMeta(undefined, 'constructor'), '')
  assert.equal(langLabel('constructor'), 'constructor') // 修复前返回 Function
  console.log('✓ G2-04b 模型级查表原型链键不抛错（catalogVoices/formatModelMeta）')
}

// G2-04c. 守卫不误伤真实键（正常目录与提示不受影响）
{
  const xiaoxiao = describeVoice('hexgrad/kokoro-82m', 'zf_xiaoxiao')
  assert.deepEqual([xiaoxiao.label, xiaoxiao.lang, xiaoxiao.gender], ['晓晓', 'zh', 'f'])
  assert.equal(describeVoice('zyphra/zonos-v0.1-transformer', 'random').label, '随机音色')
  assert.equal(describeVoice('sesame/csm-1b', 'read_speech_a').label, '朗读风 A')
  assert.equal(describeVoice('google/gemini-3.1-flash-tts-preview', 'Kore').note, '坚定')
  assert.equal(modelHints('fish-audio/s9-future')?.voiceOptional, true)
  assert.equal(catalogVoices('hexgrad/kokoro-82m').length, 54)
  assert.equal(catalogVoices('deepgram/aura-2').length, 90)
  assert.equal(langLabel('zh'), '中文')
  console.log('✓ G2-04c 守卫不误伤真实键（目录/提示/默认音色照常）')
}

console.log('\nG2 组回归全部通过')
