import type { OpenAISpeechConfig, TtsModelInfo } from '../../types'
import { modelHints } from '../voices'

/**
 * OpenAI 兼容语音接口。
 * 兼容 OpenRouter（https://openrouter.ai/api/v1）、OpenAI 官方、
 * SiliconFlow、FishAudio 等所有实现该格式的服务。
 *
 * OpenRouter 的 /audio/speech 与 OpenAI 官方有三处差异（据官方文档 + 实测）：
 * ① response_format 仅支持 mp3 / pcm；
 * ② instructions 不是顶层参数，须经 provider.options.openai 透传；
 * ③ Gemini TTS 仅回 pcm 裸流（Content-Type 携带 rate/channels），需客户端封 WAV。
 */

/** 是否 OpenRouter 端点（决定请求体方言）：按主机名判定，撞名域/路径/查询串中的 openrouter.ai 不算 */
export function isOpenRouterBase(baseUrl: string): boolean {
  try {
    const host = new URL(baseUrl).hostname.toLowerCase()
    return host === 'openrouter.ai' || host.endsWith('.openrouter.ai')
  } catch {
    return false
  }
}

/**
 * OpenRouter 应用归因头（https://openrouter.ai/docs/app-attribution）：
 * 用量在用户的 OpenRouter 控制台按应用归属展示。仅对 openrouter.ai 附加，
 * 其他 OpenAI 兼容服务不发送非标头，避免触发陌生 CORS 校验。
 */
const APP_ATTRIBUTION: Record<string, string> = {
  'HTTP-Referer': 'https://github.com/L0stInFades/echo-read',
  'X-OpenRouter-Title': 'EchoRead',
  'X-OpenRouter-Categories': 'audio-gen'
}

/** 组装鉴权 + 归因请求头（纯函数，便于测试） */
export function buildHeaders(cfg: OpenAISpeechConfig): Record<string, string> {
  return {
    Authorization: `Bearer ${cfg.apiKey}`,
    ...(isOpenRouterBase(cfg.baseUrl) ? APP_ATTRIBUTION : {})
  }
}

/** 携带 HTTP 状态码的接口错误（网络层 TypeError 等无状态码，不属此类） */
export interface SpeechHttpError extends Error {
  status: number
}

function httpError(message: string, status: number): SpeechHttpError {
  return Object.assign(new Error(message), { status })
}

/**
 * 配置类致命错误判定：4xx（408/429 除外）说明 Key/模型/参数有问题，重试无意义；
 * 429/408/5xx/无状态码（网络抖动）视为可恢复，交给上层退避重试。
 */
export function isFatalSpeechError(e: unknown): boolean {
  const status = (e as Partial<SpeechHttpError> | null)?.status
  return typeof status === 'number' && status >= 400 && status < 500 && status !== 408 && status !== 429
}

/** 统一的错误映射：状态码/JSON 错误体 → 用户可读信息，错误对象携带 status 供重试策略分类 */
async function ensureOk(res: Response, action: string) {
  if (res.ok) return
  const raw = (await res.text().catch(() => '')).slice(0, 500)
  let msg = ''
  try {
    msg = String(JSON.parse(raw)?.error?.message ?? '')
  } catch {
    /* 非 JSON 错误体 */
  }
  if (res.status === 401) throw httpError('API Key 无效或已过期（401）', res.status)
  if (res.status === 402) throw httpError('账户余额不足（402）', res.status)
  if (res.status === 404) throw httpError('接口或模型不存在（404），请检查 Base URL 与模型名', res.status)
  if (res.status === 429) throw httpError('请求过于频繁（429），稍后重试', res.status)
  if (res.status >= 500) throw httpError(`上游服务商暂时故障（${res.status}），稍后重试${msg ? `：${msg}` : ''}`, res.status)
  throw httpError(`${action}失败（${res.status}）：${msg || raw || res.statusText}`, res.status)
}

/** 组装 /audio/speech 请求体（纯函数，便于测试） */
export function buildSpeechBody(cfg: OpenAISpeechConfig, text: string): Record<string, unknown> {
  const or = isOpenRouterBase(cfg.baseUrl)
  const pcmOnly = or && !!modelHints(cfg.model)?.pcmOnly
  // OpenRouter 只认 mp3/pcm：pcm-only 模型强制 pcm，其余不合法值收敛为 mp3
  const format = pcmOnly ? 'pcm' : or && cfg.format !== 'pcm' ? 'mp3' : cfg.format
  const voice = cfg.voice?.trim()
  const instructions = cfg.instructions?.trim()
  return {
    model: cfg.model,
    input: text,
    // voice 留空则省略该参数：fish-audio 等用服务端默认音色（无默认音色的服务商会以 400 告知）
    ...(voice ? { voice } : {}),
    response_format: format,
    ...(instructions
      ? or
        ? { provider: { options: { openai: { instructions } } } }
        : { instructions }
      : {})
  }
}

/** 从 Content-Type（如 audio/pcm;rate=24000;channels=1）解析采样参数 */
export function parsePcmParams(contentType: string): { rate: number; channels: number } {
  return {
    // 参数名必须有左边界：bitrate=/samplerate= 等其他参数的尾串不是采样率
    rate: Number(/(?:^|[;\s])rate=(\d+)/i.exec(contentType)?.[1]) || 24000,
    channels: Number(/(?:^|[;\s])channels=(\d+)/i.exec(contentType)?.[1]) || 1
  }
}

/** 16-bit LE PCM 裸流封 WAV 头（浏览器 <audio> 无法直接播放裸 PCM） */
export function pcmToWav(pcm: ArrayBuffer, rate: number, channels: number): Blob {
  const header = new ArrayBuffer(44)
  const v = new DataView(header)
  const blockAlign = channels * 2
  const writeStr = (off: number, s: string) => {
    for (let i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i))
  }
  writeStr(0, 'RIFF')
  v.setUint32(4, 36 + pcm.byteLength, true)
  writeStr(8, 'WAVE')
  writeStr(12, 'fmt ')
  v.setUint32(16, 16, true)
  v.setUint16(20, 1, true) // PCM
  v.setUint16(22, channels, true)
  v.setUint32(24, rate, true)
  v.setUint32(28, rate * blockAlign, true)
  v.setUint16(32, blockAlign, true)
  v.setUint16(34, 16, true)
  writeStr(36, 'data')
  v.setUint32(40, pcm.byteLength, true)
  return new Blob([header, pcm], { type: 'audio/wav' })
}

export async function synthesizeOpenAI(
  cfg: OpenAISpeechConfig,
  text: string,
  signal?: AbortSignal
): Promise<Blob> {
  const base = cfg.baseUrl.replace(/\/+$/, '')
  const res = await fetch(`${base}/audio/speech`, {
    method: 'POST',
    headers: {
      ...buildHeaders(cfg),
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(buildSpeechBody(cfg, text)),
    signal
  })
  await ensureOk(res, '合成')
  const ctype = res.headers.get('content-type') ?? ''
  if (/audio\/(x-)?pcm|audio\/l16/i.test(ctype)) {
    const { rate, channels } = parsePcmParams(ctype)
    return pcmToWav(await res.arrayBuffer(), rate, channels)
  }
  return res.blob()
}

/**
 * 从 /models 响应中筛出语音合成候选模型（纯函数，便于测试）。
 * 取两种证据的并集：① architecture 模态元数据为「文本进、语音出」
 * （OpenRouter 的专用 TTS 模型标记为 speech，语音对话模型标记为 audio）；
 * ② id 含 tts/speech（OpenAI 官方等无元数据服务，及元数据不全的代理）。
 * 一并带出 supported_voices / 简介 / 单价，供音色目录与模型信息展示。
 */
export function pickTtsModels(json: any): TtsModelInfo[] {
  const data: any[] = Array.isArray(json?.data) ? json.data : []
  const byModality = data.some(m => Array.isArray(m?.architecture?.output_modalities))
  const out: TtsModelInfo[] = []
  for (const m of data) {
    const id = typeof m?.id === 'string' ? m.id : ''
    if (!id) continue
    // 模态字段可能是任意形状（聚合代理元数据残缺），非数组一律视作无模态证据
    const outs: string[] = Array.isArray(m?.architecture?.output_modalities) ? m.architecture.output_modalities : []
    const ins: string[] = Array.isArray(m?.architecture?.input_modalities) ? m.architecture.input_modalities : []
    const hit =
      (byModality && (outs.includes('speech') || outs.includes('audio')) && ins.includes('text')) ||
      /tts|speech/i.test(id)
    if (!hit) continue
    const info: TtsModelInfo = { id, name: typeof m?.name === 'string' && m.name ? m.name : id }
    if (Array.isArray(m?.supported_voices)) {
      const voices = m.supported_voices.filter((v: any) => typeof v === 'string')
      if (voices.length) info.voices = voices
    }
    if (typeof m?.description === 'string' && m.description.trim()) info.description = m.description.trim()
    const prompt = Number(m?.pricing?.prompt)
    if (Number.isFinite(prompt) && prompt > 0) info.promptPrice = prompt
    const completion = Number(m?.pricing?.completion)
    if (Number.isFinite(completion) && completion > 0) info.completionPrice = completion
    out.push(info)
  }
  return out.sort((a, b) => a.id.localeCompare(b.id))
}

/**
 * 拉取服务商的在线模型列表并筛出 TTS 模型。
 * OpenRouter 的专用 TTS 模型只在 ?output_modalities=speech 查询下出现；
 * 不识该参数的服务（OpenAI 官方等）回退全量 /models，由 pickTtsModels 客户端过滤。
 */
export async function fetchTtsModels(cfg: OpenAISpeechConfig): Promise<TtsModelInfo[]> {
  const base = cfg.baseUrl.replace(/\/+$/, '')
  const headers = buildHeaders(cfg)
  let res = await fetch(`${base}/models?output_modalities=speech`, { headers })
  if (!res.ok) res = await fetch(`${base}/models`, { headers })
  await ensureOk(res, '获取模型列表')
  return pickTtsModels(await res.json())
}

/** 校验配置是否可用（用极短文本试合成） */
export async function testOpenAIConfig(cfg: OpenAISpeechConfig): Promise<{ ok: boolean; message: string }> {
  try {
    const blob = await synthesizeOpenAI(cfg, '你好', undefined)
    if (blob.size < 10) return { ok: false, message: '返回的音频为空' }
    return { ok: true, message: '连接成功' }
  } catch (e: any) {
    return { ok: false, message: e?.message ?? String(e) }
  }
}
