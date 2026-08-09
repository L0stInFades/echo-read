import { defineStore } from 'pinia'
import { reactive, ref, watch } from 'vue'
import type { OpenAISpeechConfig, ReaderSettings, TTSSettings, TtsModelInfo } from '../types'

const LS_TTS = 'echo-read:tts-settings'
const LS_READER = 'echo-read:reader-settings'
const LS_MODELS = 'echo-read:tts-models'

function defaultTTS(): TTSSettings {
  return {
    provider: 'openai-speech',
    openai: {
      baseUrl: 'https://openrouter.ai/api/v1',
      apiKey: '',
      model: 'openai/gpt-4o-mini-tts',
      voice: 'alloy',
      instructions: '',
      format: 'mp3'
    },
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

function load<T extends Record<string, any>>(key: string, fallback: () => T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback()
    const parsed = JSON.parse(raw)
    const fb = fallback()
    // 一层深合并：嵌套对象（如 openai 配置）缺字段时回退默认值；腐坏数据类型不符则整体回退
    const merged: Record<string, any> = { ...fb, ...parsed }
    for (const k of Object.keys(fb)) {
      if (typeof fb[k] === 'object' && fb[k] !== null && !Array.isArray(fb[k])) {
        merged[k] =
          typeof parsed[k] === 'object' && parsed[k] !== null && !Array.isArray(parsed[k])
            ? { ...fb[k], ...parsed[k] }
            : fb[k]
      }
    }
    return merged as T
  } catch {
    return fallback()
  }
}

/** 模型列表缓存：只接受 {id, name, voices?} 形状的数组，其余一律丢弃 */
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
      out.push(info)
    }
    return out
  } catch {
    return []
  }
}

/** OpenAI 兼容 TTS 的常见模型与音色预设（仅供参考，均可手动输入） */
export const VOICE_PRESETS: Record<string, { label: string; voices: string[] }> = {
  'openai/gpt-4o-mini-tts': {
    label: 'OpenAI GPT-4o Mini TTS',
    voices: ['alloy', 'ash', 'ballad', 'coral', 'echo', 'fable', 'nova', 'onyx', 'sage', 'shimmer', 'verse']
  },
  'openai/tts-1': { label: 'OpenAI TTS-1', voices: ['alloy', 'echo', 'fable', 'onyx', 'nova', 'shimmer'] },
  'google/gemini-2.5-flash-preview-tts': {
    label: 'Google Gemini Flash TTS',
    voices: ['Zephyr', 'Puck', 'Charon', 'Kore', 'Fenrir', 'Aoede', 'Leda', 'Orus', 'Callirrhoe', 'Autonoe']
  }
}

export const useSettingsStore = defineStore('settings', () => {
  const tts = reactive<TTSSettings>(load(LS_TTS, defaultTTS))
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
