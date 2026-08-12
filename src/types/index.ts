/** 全局共享类型定义 */

export type BookFormat = 'txt' | 'epub'

/** 书架上的书籍元数据（章节内容单独存储） */
export interface BookMeta {
  id: string
  title: string
  author: string
  format: BookFormat
  /** dataURL 封面；txt 无封面时为空，用渐变封面渲染 */
  cover?: string
  intro?: string
  chapterCount: number
  totalChars: number
  createdAt: number
  lastReadAt?: number
  /** 阅读进度：章节索引 + 章节内字符偏移 */
  progress: { chapterIndex: number; offset: number }
}

/** 章节目录条目（独立存储，避免巨型 meta） */
export interface ChapterIndex {
  bookId: string
  titles: string[]
}

/**
 * 单章内容：只存一份规范纯文本（段落以 \n 分隔）。
 * 段落边界、朗读片段、高亮区域一律用 Range 偏移表达，杜绝文本副本。
 */
export interface ChapterContent {
  bookId: string
  index: number
  title: string
  text: string
}

/** 字符偏移区间 [start, end) —— 段落/句子/合成片段/高亮的统一表达 */
export interface Range {
  start: number
  end: number
}

/** 解析器产物（入库前） */
export interface ParsedBook {
  title: string
  author: string
  intro?: string
  cover?: string
  chapters: { title: string; paragraphs: string[] }[]
}

/** TTS 服务商类型 */
export type TTSProviderKind = 'openai-speech' | 'webspeech'

/** OpenAI 兼容语音接口配置（OpenRouter / OpenAI / SiliconFlow / FishAudio 等通用） */
export interface OpenAISpeechConfig {
  baseUrl: string        // 默认 https://openrouter.ai/api/v1
  apiKey: string
  model: string          // 如 openai/gpt-4o-mini-tts
  voice: string          // 如 alloy
  instructions?: string  // 部分模型支持的语气指令
  format: 'mp3' | 'opus' | 'pcm'
}

export interface TTSSettings {
  provider: TTSProviderKind
  openai: OpenAISpeechConfig
  /** 每模型记忆的音色选择（切换模型来回不丢音色） */
  voiceByModel: Record<string, string>
  /** 播放倍速（客户端 playbackRate，避免重复合成） */
  rate: number
  /** 单个合成片段的最大字符数 */
  maxChunkChars: number
  /** 预取片段数 */
  prefetch: number
}

export type ReaderTheme = 'dark' | 'light' | 'paper' | 'eye' | 'ink'

export interface ReaderSettings {
  theme: ReaderTheme
  fontSize: number
  lineHeight: number
  fontFamily: 'sans' | 'serif'
  paraSpacing: number
}

export type PlayerState = 'idle' | 'loading' | 'playing' | 'paused' | 'error'

/** 在线拉取到的 TTS 模型条目 */
export interface TtsModelInfo {
  id: string
  name: string
  /** 服务端声明的可用音色（supported_voices），null/缺失表示未提供 */
  voices?: string[]
  /** 模型简介 */
  description?: string
  /** 每字符美元单价（OpenRouter TTS 按输入字符计价） */
  promptPrice?: number
  /** 输出 token 单价（Gemini 等按 token 计费的模型才有） */
  completionPrice?: number
}
