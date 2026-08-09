import type { OpenAISpeechConfig, TtsModelInfo } from '../../types'

/**
 * OpenAI 兼容语音接口。
 * 兼容 OpenRouter（https://openrouter.ai/api/v1）、OpenAI 官方、
 * SiliconFlow、FishAudio 等所有实现该格式的服务。
 */

/** 统一的错误映射：状态码 → 用户可读信息 */
async function ensureOk(res: Response, action: string) {
  if (res.ok) return
  const detail = (await res.text().catch(() => '')).slice(0, 300)
  if (res.status === 401) throw new Error('API Key 无效或已过期（401）')
  if (res.status === 402) throw new Error('账户余额不足（402）')
  if (res.status === 404) throw new Error('接口或模型不存在（404），请检查 Base URL 与模型名')
  if (res.status === 429) throw new Error('请求过于频繁（429），稍后重试')
  throw new Error(`${action}失败（${res.status}）：${detail || res.statusText}`)
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
      Authorization: `Bearer ${cfg.apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model: cfg.model,
      input: text,
      // voice 留空则省略该参数，交由服务端用默认音色（部分服务商要求显式音色，会以 400 告知）
      ...(cfg.voice?.trim() ? { voice: cfg.voice.trim() } : {}),
      response_format: cfg.format,
      ...(cfg.instructions?.trim() ? { instructions: cfg.instructions.trim() } : {})
    }),
    signal
  })
  await ensureOk(res, '合成')
  return res.blob()
}

/**
 * 从 /models 响应中筛出语音合成候选模型（纯函数，便于测试）。
 * 取两种证据的并集：① architecture 模态元数据为「文本进、语音出」
 * （OpenRouter 的专用 TTS 模型标记为 speech，语音对话模型标记为 audio）；
 * ② id 含 tts/speech（OpenAI 官方等无元数据服务，及元数据不全的代理）。
 * 服务端声明 supported_voices 时一并带出，供音色选择。
 */
export function pickTtsModels(json: any): TtsModelInfo[] {
  const data: any[] = Array.isArray(json?.data) ? json.data : []
  const byModality = data.some(m => Array.isArray(m?.architecture?.output_modalities))
  const out: TtsModelInfo[] = []
  for (const m of data) {
    const id = typeof m?.id === 'string' ? m.id : ''
    if (!id) continue
    const outs: string[] = m?.architecture?.output_modalities ?? []
    const hit =
      (byModality &&
        (outs.includes('speech') || outs.includes('audio')) &&
        (m.architecture?.input_modalities?.includes('text') ?? false)) ||
      /tts|speech/i.test(id)
    if (!hit) continue
    const info: TtsModelInfo = { id, name: typeof m?.name === 'string' && m.name ? m.name : id }
    if (Array.isArray(m?.supported_voices)) {
      const voices = m.supported_voices.filter((v: any) => typeof v === 'string')
      if (voices.length) info.voices = voices
    }
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
  const headers = { Authorization: `Bearer ${cfg.apiKey}` }
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
