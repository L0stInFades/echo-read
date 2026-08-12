import { defineStore } from 'pinia'
import { reactive, ref, watch } from 'vue'
import type { OpenAISpeechConfig, ReaderSettings, TTSSettings, TtsModelInfo } from '../types'
import { isOpenRouterBase } from '../tts/providers/openai-speech'

const LS_TTS = 'echo-read:tts-settings'
const LS_READER = 'echo-read:reader-settings'
const LS_MODELS = 'echo-read:tts-models'

function defaultTTS(): TTSSettings {
  return {
    provider: 'openai-speech',
    openai: {
      baseUrl: 'https://openrouter.ai/api/v1',
      apiKey: '',
      model: 'hexgrad/kokoro-82m',
      voice: 'zf_xiaoxiao',
      instructions: '',
      format: 'mp3'
    },
    voiceByModel: {},
    rate: 1,
    maxChunkChars: 120,
    prefetch: 2
  }
}

function defaultReader(): ReaderSettings {
  return {
    theme: 'dark',
    fontSize: 19,
    lineHeight: 1.9,
    fontFamily: 'serif',
    paraSpacing: 1
  }
}

function isPlainObject(v: any): v is Record<string, any> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** 逐层合并 + 标量守卫：类型不符（数字还需有限）的键回退默认值，默认值之外的未知键原样保留 */
function mergeValidated(fb: Record<string, any>, parsed: Record<string, any>): Record<string, any> {
  const merged: Record<string, any> = { ...fb, ...parsed }
  for (const k of Object.keys(fb)) {
    const dv = fb[k]
    const pv = parsed[k]
    if (isPlainObject(dv)) {
      merged[k] = isPlainObject(pv) ? mergeValidated(dv, pv) : dv
    } else if (typeof pv !== typeof dv || (typeof dv === 'number' && !Number.isFinite(pv))) {
      merged[k] = dv
    }
  }
  return merged
}

function load<T extends Record<string, any>>(key: string, fallback: () => T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback()
    const parsed = JSON.parse(raw)
    // 顶层非纯对象（字符串/数组会被展开成数字键并经 watch 固化回存储）：整体回退
    if (!isPlainObject(parsed)) return fallback()
    return mergeValidated(fallback(), parsed) as T
  } catch {
    return fallback()
  }
}

// OpenRouter 已下架的历史 TTS 模型（2026-08 实测目录）：存量配置迁移到现役默认模型
const REMOVED_OR_MODELS = new Set([
  'openai/gpt-4o-mini-tts',
  'openai/gpt-4o-mini-tts-2025-12-15',
  'openai/tts-1',
  'openai/tts-1-hd',
  'google/gemini-2.5-flash-preview-tts',
  'google/gemini-2.5-pro-preview-tts'
])

function migrateTTS(s: TTSSettings): TTSSettings {
  if (isOpenRouterBase(s.openai.baseUrl) && REMOVED_OR_MODELS.has(s.openai.model)) {
    const fb = defaultTTS()
    s.openai.model = fb.openai.model
    s.openai.voice = fb.openai.voice
  }
  return s
}

/** 值域守卫：类型正确但越界的存量值会破坏播放——maxChunkChars≤0 令分段步长归零死循环，
 * rate 超出 playbackRate 安全域会抛 NotSupportedError；越界一律回退默认值 */
function sanitizeTTS(s: TTSSettings): TTSSettings {
  const fb = defaultTTS()
  if (s.provider !== 'openai-speech' && s.provider !== 'webspeech') s.provider = fb.provider
  if (s.openai.format !== 'mp3' && s.openai.format !== 'opus' && s.openai.format !== 'pcm') {
    s.openai.format = fb.openai.format
  }
  if (!(s.rate >= 0.25 && s.rate <= 4)) s.rate = fb.rate
  if (!(s.maxChunkChars >= 40)) s.maxChunkChars = fb.maxChunkChars
  // 上界防腐坏数据造成的预取风暴（滑杆范围 0–5，超出即视为损坏）
  if (!(s.prefetch >= 0 && s.prefetch <= 5)) s.prefetch = fb.prefetch
  return s
}

/** 模型列表缓存：只接受 {id, name, …} 形状的数组，其余一律丢弃 */
function loadModels(): TtsModelInfo[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(LS_MODELS) ?? '[]')
    if (!Array.isArray(parsed)) return []
    const out: TtsModelInfo[] = []
    for (const m of parsed) {
      if (typeof m?.id !== 'string' || typeof m?.name !== 'string') continue
      const info: TtsModelInfo = { id: m.id, name: m.name }
      if (Array.isArray(m.voices)) {
        const voices = m.voices.filter((v: any) => typeof v === 'string')
        if (voices.length) info.voices = voices
      }
      if (typeof m.description === 'string') info.description = m.description
      if (typeof m.promptPrice === 'number') info.promptPrice = m.promptPrice
      if (typeof m.completionPrice === 'number') info.completionPrice = m.completionPrice
      out.push(info)
    }
    return out
  } catch {
    return []
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const tts = reactive<TTSSettings>(migrateTTS(sanitizeTTS(load(LS_TTS, defaultTTS))))
  const reader = reactive<ReaderSettings>(load(LS_READER, defaultReader))
  /** 在线拉取到的 TTS 模型列表（缓存，离线可用；空数组表示未拉取过） */
  const ttsModels = ref<TtsModelInfo[]>(loadModels())

  watch(
    () => JSON.stringify(tts),
    v => localStorage.setItem(LS_TTS, v)
  )
  watch(
    () => JSON.stringify(reader),
    v => localStorage.setItem(LS_READER, v)
  )
  watch(
    ttsModels,
    v => localStorage.setItem(LS_MODELS, JSON.stringify(v)),
    { deep: true }
  )

  function openaiConfig(): OpenAISpeechConfig {
    return { ...tts.openai }
  }

  return { tts, reader, ttsModels, openaiConfig }
})
