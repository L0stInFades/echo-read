import type { TtsModelInfo } from '../types'

/**
 * 音色目录：OpenRouter TTS 模型的精细音色体系（2026-08 依据真实
 * /models?output_modalities=speech 响应与官方 TTS 文档整理）。
 *
 * 三类模型：
 * ① 结构化音色 ID —— 解析器展开为 语言/性别/风格（Kokoro、Deepgram、Voxtral、MAI…）
 * ② 具名音色表 —— 逐个标注（Gemini、Grok、Orpheus…）
 * ③ 开放音色 ID —— 服务端不枚举（Fish Audio 参考 ID、MiniMax 系统音色），给精选建议
 */

export interface VoiceInfo {
  id: string
  /** 人类可读名 */
  label: string
  /** 语言键（zh/en/ja/…/multi），未知则缺省 */
  lang?: string
  gender?: 'f' | 'm'
  /** 风格/口音/情绪短注 */
  note?: string
}

export interface VoiceGroup {
  lang: string
  label: string
  voices: VoiceInfo[]
}

/** 开放音色输入型模型的引导信息 */
export interface FreeVoiceHint {
  placeholder: string
  hint: string
  suggestions?: VoiceInfo[]
}

export interface ModelHints {
  /** 快捷推荐用短标签 */
  label?: string
  /** 支持语言摘要 */
  langs?: string
  /** 一句话备注 */
  note?: string
  /** 开放音色 ID 输入（fish/minimax）；有此项则不展示目录 */
  freeVoice?: FreeVoiceHint
  /** voice 可留空（服务端有默认音色） */
  voiceOptional?: boolean
  /** 仅支持 pcm 输出（Gemini），provider 层自动封 WAV */
  pcmOnly?: boolean
  /** 支持声音克隆（input_references） */
  cloning?: boolean
  /** 无在线列表时的兜底默认音色 */
  preferred?: string
}

const LANG_LABELS: Record<string, string> = {
  zh: '中文',
  multi: '多语言',
  en: '英语',
  ja: '日语',
  es: '西班牙语',
  fr: '法语',
  de: '德语',
  it: '意大利语',
  nl: '荷兰语',
  pt: '葡萄牙语',
  hi: '印地语',
  other: '其他'
}
const LANG_ORDER = ['zh', 'multi', 'en', 'ja', 'es', 'fr', 'de', 'it', 'nl', 'pt', 'hi', 'other']

/**
 * 自有键守卫查表：voice/model id 来自服务端或用户输入，constructor/toString 等
 * 原型链属性不得当作命中。键被正则限定为 1-2 个小写字母的查表无撞名可能，不经此守卫。
 */
function own<T>(table: Record<string, T>, key: string): T | undefined {
  return Object.hasOwn(table, key) ? table[key] : undefined
}

export function langLabel(lang: string): string {
  return own(LANG_LABELS, lang) ?? lang
}

/** 去掉 OpenRouter 模型 id 的变体后缀（:free / :nitro 等） */
export function canonicalModelId(id: string): string {
  const i = id.indexOf(':')
  return i > 0 ? id.slice(0, i) : id
}

/* ---------- 各家音色注释表 ---------- */

/** Kokoro：前缀 = 语言 + 性别（a 美音 / b 英音 / z 中文 …；f 女 / m 男） */
const KOKORO_LANG: Record<string, { lang: string; note?: string }> = {
  a: { lang: 'en', note: '美音' },
  b: { lang: 'en', note: '英音' },
  e: { lang: 'es' },
  f: { lang: 'fr' },
  h: { lang: 'hi' },
  i: { lang: 'it' },
  j: { lang: 'ja' },
  p: { lang: 'pt', note: '巴西' },
  z: { lang: 'zh' }
}
/** Kokoro 中文音色的通行中文名（源自同名 Azure 音色移植） */
const KOKORO_ZH: Record<string, string> = {
  xiaobei: '晓北',
  xiaoni: '晓妮',
  xiaoxiao: '晓晓',
  xiaoyi: '晓伊',
  yunjian: '云健',
  yunxi: '云希',
  yunxia: '云夏',
  yunyang: '云扬'
}

/** Gemini TTS 30 音色：性别与官方风格描述 */
const GEMINI_VOICES: Record<string, { gender: 'f' | 'm'; note: string }> = {
  Zephyr: { gender: 'f', note: '明亮' },
  Puck: { gender: 'm', note: '活泼' },
  Charon: { gender: 'm', note: '知性' },
  Kore: { gender: 'f', note: '坚定' },
  Fenrir: { gender: 'm', note: '激昂' },
  Leda: { gender: 'f', note: '年轻' },
  Orus: { gender: 'm', note: '坚定' },
  Aoede: { gender: 'f', note: '轻快' },
  Callirrhoe: { gender: 'f', note: '随和' },
  Autonoe: { gender: 'f', note: '明亮' },
  Enceladus: { gender: 'm', note: '气声' },
  Iapetus: { gender: 'm', note: '清晰' },
  Umbriel: { gender: 'm', note: '随和' },
  Algieba: { gender: 'm', note: '圆润' },
  Despina: { gender: 'f', note: '圆润' },
  Erinome: { gender: 'f', note: '清晰' },
  Algenib: { gender: 'm', note: '沙哑' },
  Rasalgethi: { gender: 'm', note: '知性' },
  Laomedeia: { gender: 'f', note: '明快' },
  Achernar: { gender: 'f', note: '轻柔' },
  Alnilam: { gender: 'm', note: '坚实' },
  Schedar: { gender: 'm', note: '平稳' },
  Gacrux: { gender: 'f', note: '成熟' },
  Pulcherrima: { gender: 'f', note: '直率' },
  Achird: { gender: 'm', note: '亲切' },
  Zubenelgenubi: { gender: 'm', note: '随性' },
  Vindemiatrix: { gender: 'f', note: '温和' },
  Sadachbia: { gender: 'm', note: '活泼' },
  Sadaltager: { gender: 'm', note: '博识' },
  Sulafat: { gender: 'f', note: '温暖' }
}

/** Grok Voice（20+ 语言自动识别） */
const GROK_VOICES: Record<string, Partial<VoiceInfo>> = {
  eve: { gender: 'f', note: '英音·温暖' },
  ara: { gender: 'f', note: '明快' },
  rex: { gender: 'm', note: '沉稳' },
  sal: {},
  leo: {}
}

/** Orpheus 3B（英语，按自然度排序 tara 最佳） */
const ORPHEUS_VOICES: Record<string, Partial<VoiceInfo>> = {
  tara: { gender: 'f', note: '最自然' },
  leah: { gender: 'f' },
  jess: { gender: 'f' },
  leo: { gender: 'm' },
  dan: { gender: 'm' },
  mia: { gender: 'f' },
  zac: { gender: 'm' }
}

/** Sesame CSM（英语，对话/朗读两种风格） */
const SESAME_VOICES: Record<string, string> = {
  conversational_a: '对话风 A',
  conversational_b: '对话风 B',
  read_speech_a: '朗读风 A',
  read_speech_b: '朗读风 B',
  read_speech_c: '朗读风 C',
  read_speech_d: '朗读风 D',
  none: '模型默认'
}

/** Zonos（英语） */
const ZONOS_VOICES: Record<string, VoiceInfo> = {
  american_female: { id: 'american_female', label: '美音·女声', lang: 'en', gender: 'f' },
  american_male: { id: 'american_male', label: '美音·男声', lang: 'en', gender: 'm' },
  british_female: { id: 'british_female', label: '英音·女声', lang: 'en', gender: 'f' },
  british_male: { id: 'british_male', label: '英音·男声', lang: 'en', gender: 'm' },
  random: { id: 'random', label: '随机音色', lang: 'en', note: '每次不同' }
}

/** 通义 TTS（DashScope 龙系列，中文为主） */
const QWEN_VOICES: Record<string, Partial<VoiceInfo>> = {
  loongjohn: { label: 'Loong John', lang: 'zh', gender: 'm', note: '中英双语' },
  'longanhuan_v3.6': { label: 'Longan Huan', lang: 'zh' },
  longanlingxin: { label: 'Longan Lingxin', lang: 'zh' },
  longanlufeng: { label: 'Longan Lufeng', lang: 'zh' }
}

/** Voxtral：{地区}_{人名}_{情绪} */
const VOXTRAL_LOCALE: Record<string, { lang: string; note?: string }> = {
  en: { lang: 'en', note: '美音' },
  gb: { lang: 'en', note: '英音' },
  fr: { lang: 'fr' },
  es: { lang: 'es' },
  de: { lang: 'de' },
  it: { lang: 'it' },
  pt: { lang: 'pt' },
  nl: { lang: 'nl' }
}
const VOXTRAL_GENDER: Record<string, 'f' | 'm'> = { paul: 'm', oliver: 'm', jane: 'f', marie: 'f' }
const EMOTION_ZH: Record<string, string> = {
  neutral: '中性',
  happy: '开心',
  cheerful: '欢快',
  excited: '兴奋',
  confident: '自信',
  curious: '好奇',
  sad: '悲伤',
  frustrated: '沮丧',
  angry: '愤怒',
  sarcasm: '讽刺',
  confused: '困惑',
  shameful: '羞愧',
  jealousy: '嫉妒'
}

/** MAI-Voice-2：{locale}-{Name}:MAI-Voice-2 */
const MAI_GENDER: Record<string, 'f' | 'm'> = { Harper: 'f', Valeria: 'f', Soleil: 'f', Klaus: 'm' }
const MAI_LANG: Record<string, string> = { en: 'en', es: 'es', fr: 'fr', de: 'de', it: 'it', ja: 'ja', zh: 'zh', pt: 'pt', nl: 'nl', hi: 'hi' }

/** Deepgram Aura-2 名字性别（据官方目录/名字属性整理，无把握者不标注） */
const DEEPGRAM_GENDER: Record<string, 'f' | 'm'> = {
  thalia: 'f', agathe: 'f', agustina: 'f', alvaro: 'm', amalthea: 'f', andromeda: 'f',
  antonia: 'f', apollo: 'm', arcas: 'm', asteria: 'f', athena: 'f', atlas: 'm',
  aurelia: 'f', aurora: 'f', beatrix: 'f', callista: 'f', carina: 'f', celeste: 'f',
  cesare: 'm', cinzia: 'f', cora: 'f', cordelia: 'f', cornelia: 'f', daphne: 'f',
  delia: 'f', demetra: 'f', diana: 'f', dionisio: 'm', draco: 'm', elara: 'f',
  electra: 'f', elio: 'm', estrella: 'f', fabian: 'm', flavio: 'm', gloria: 'f',
  harmonia: 'f', hector: 'm', helena: 'f', hera: 'f', hermes: 'm', hestia: 'f',
  hyperion: 'm', iris: 'f', izanami: 'f', javier: 'm', julius: 'm', juno: 'f',
  jupiter: 'm', kara: 'f', lara: 'f', lars: 'm', leda: 'f', livia: 'f',
  luciano: 'm', luna: 'f', maia: 'f', mars: 'm', melia: 'f', minerva: 'f',
  neptune: 'm', nestor: 'm', odysseus: 'm', olivia: 'f', ophelia: 'f', orion: 'm',
  orpheus: 'm', pandora: 'f', phoebe: 'f', pluto: 'm', rhea: 'f', roman: 'm',
  sander: 'm', saturn: 'm', selena: 'f', selene: 'f', silvia: 'f', sirio: 'm',
  theia: 'f', uzume: 'f', valerio: 'm', vesta: 'f', viktoria: 'f', zeus: 'm'
}

/** MiniMax 官方系统音色（中文，实测经 OpenRouter 可用；也可填任意自定义音色 ID） */
const MINIMAX_SUGGESTIONS: VoiceInfo[] = [
  { id: 'audiobook_female_1', label: '有声书·女 1', lang: 'zh', gender: 'f' },
  { id: 'audiobook_female_2', label: '有声书·女 2', lang: 'zh', gender: 'f' },
  { id: 'audiobook_male_1', label: '有声书·男 1', lang: 'zh', gender: 'm' },
  { id: 'audiobook_male_2', label: '有声书·男 2', lang: 'zh', gender: 'm' },
  { id: 'presenter_female', label: '女主播', lang: 'zh', gender: 'f' },
  { id: 'presenter_male', label: '男主播', lang: 'zh', gender: 'm' },
  { id: 'female-shaonv', label: '少女音', lang: 'zh', gender: 'f' },
  { id: 'female-tianmei', label: '甜美女声', lang: 'zh', gender: 'f' },
  { id: 'female-yujie', label: '御姐音', lang: 'zh', gender: 'f' },
  { id: 'female-chengshu', label: '成熟女声', lang: 'zh', gender: 'f' },
  { id: 'male-qn-qingse', label: '青涩青年', lang: 'zh', gender: 'm' },
  { id: 'male-qn-jingying', label: '精英青年', lang: 'zh', gender: 'm' },
  { id: 'male-qn-badao', label: '霸道青年', lang: 'zh', gender: 'm' },
  { id: 'male-qn-daxuesheng', label: '大学生音', lang: 'zh', gender: 'm' }
]

const FISH_FREE_VOICE: FreeVoiceHint = {
  placeholder: '留空使用官方默认音色',
  hint: '可填 fish.audio 音色库中任意音色的 Reference ID；正文中可用括号标注情绪（如 (excited)）。'
}
const MINIMAX_FREE_VOICE: FreeVoiceHint = {
  placeholder: 'audiobook_female_1',
  hint: '支持 MiniMax 全部系统音色与自定义克隆音色 ID，下方为官方常用中文音色。',
  suggestions: MINIMAX_SUGGESTIONS
}

/* ---------- 无在线列表时的兜底音色表（与 2026-08 线上 supported_voices 一致） ---------- */

const STATIC_VOICES: Record<string, string[]> = {
  'hexgrad/kokoro-82m': [
    'zf_xiaobei', 'zf_xiaoni', 'zf_xiaoxiao', 'zf_xiaoyi', 'zm_yunjian', 'zm_yunxi', 'zm_yunxia', 'zm_yunyang',
    'af_alloy', 'af_aoede', 'af_bella', 'af_heart', 'af_jessica', 'af_kore', 'af_nicole', 'af_nova', 'af_river',
    'af_sarah', 'af_sky', 'am_adam', 'am_echo', 'am_eric', 'am_fenrir', 'am_liam', 'am_michael', 'am_onyx',
    'am_puck', 'am_santa', 'bf_alice', 'bf_emma', 'bf_isabella', 'bf_lily', 'bm_daniel', 'bm_fable', 'bm_george',
    'bm_lewis', 'ef_dora', 'em_alex', 'em_santa', 'ff_siwis', 'hf_alpha', 'hf_beta', 'hm_omega', 'hm_psi',
    'if_sara', 'im_nicola', 'jf_alpha', 'jf_gongitsune', 'jf_nezumi', 'jf_tebukuro', 'jm_kumo', 'pf_dora',
    'pm_alex', 'pm_santa'
  ],
  'qwen/qwen-audio-3.0-tts-flash': ['loongjohn', 'longanhuan_v3.6'],
  'qwen/qwen-audio-3.0-tts-plus': ['longanlingxin', 'longanlufeng'],
  'google/gemini-3.1-flash-tts-preview': Object.keys(GEMINI_VOICES),
  'x-ai/grok-voice-tts-1.0': ['eve', 'ara', 'rex', 'sal', 'leo'],
  'zyphra/zonos-v0.1-transformer': Object.keys(ZONOS_VOICES),
  'zyphra/zonos-v0.1-hybrid': Object.keys(ZONOS_VOICES),
  'canopylabs/orpheus-3b-0.1-ft': Object.keys(ORPHEUS_VOICES),
  'sesame/csm-1b': Object.keys(SESAME_VOICES),
  'mistralai/voxtral-mini-tts-2603': [
    'en_paul_neutral', 'en_paul_cheerful', 'en_paul_happy', 'en_paul_excited', 'en_paul_confident', 'en_paul_sad',
    'en_paul_frustrated', 'en_paul_angry', 'gb_oliver_neutral', 'gb_oliver_cheerful', 'gb_oliver_excited',
    'gb_oliver_curious', 'gb_oliver_confident', 'gb_oliver_sad', 'gb_oliver_angry', 'gb_jane_neutral',
    'gb_jane_confident', 'gb_jane_curious', 'gb_jane_sad', 'gb_jane_frustrated', 'gb_jane_confused',
    'gb_jane_sarcasm', 'gb_jane_shameful', 'gb_jane_jealousy', 'fr_marie_neutral', 'fr_marie_happy',
    'fr_marie_excited', 'fr_marie_curious', 'fr_marie_sad', 'fr_marie_angry'
  ],
  'microsoft/mai-voice-2': [
    'en-US-Harper:MAI-Voice-2', 'es-MX-Valeria:MAI-Voice-2', 'fr-FR-Soleil:MAI-Voice-2', 'de-DE-Klaus:MAI-Voice-2'
  ],
  'microsoft/mai-voice-2-flash': [
    'en-US-Harper:MAI-Voice-2', 'es-MX-Valeria:MAI-Voice-2', 'fr-FR-Soleil:MAI-Voice-2', 'de-DE-Klaus:MAI-Voice-2'
  ],
  'deepgram/aura-2': [
    'aura-2-thalia-en', 'aura-2-amalthea-en', 'aura-2-andromeda-en', 'aura-2-apollo-en', 'aura-2-arcas-en',
    'aura-2-aries-en', 'aura-2-asteria-en', 'aura-2-athena-en', 'aura-2-atlas-en', 'aura-2-aurora-en',
    'aura-2-callista-en', 'aura-2-cora-en', 'aura-2-cordelia-en', 'aura-2-delia-en', 'aura-2-draco-en',
    'aura-2-electra-en', 'aura-2-harmonia-en', 'aura-2-helena-en', 'aura-2-hera-en', 'aura-2-hermes-en',
    'aura-2-hyperion-en', 'aura-2-iris-en', 'aura-2-janus-en', 'aura-2-juno-en', 'aura-2-jupiter-en',
    'aura-2-luna-en', 'aura-2-mars-en', 'aura-2-minerva-en', 'aura-2-neptune-en', 'aura-2-odysseus-en',
    'aura-2-ophelia-en', 'aura-2-orion-en', 'aura-2-orpheus-en', 'aura-2-pandora-en', 'aura-2-phoebe-en',
    'aura-2-pluto-en', 'aura-2-saturn-en', 'aura-2-selene-en', 'aura-2-theia-en', 'aura-2-vesta-en',
    'aura-2-zeus-en', 'aura-2-agathe-fr', 'aura-2-hector-fr', 'aura-2-agustina-es', 'aura-2-alvaro-es',
    'aura-2-antonia-es', 'aura-2-aquila-es', 'aura-2-carina-es', 'aura-2-celeste-es', 'aura-2-diana-es',
    'aura-2-estrella-es', 'aura-2-gloria-es', 'aura-2-javier-es', 'aura-2-luciano-es', 'aura-2-nestor-es',
    'aura-2-olivia-es', 'aura-2-selena-es', 'aura-2-silvia-es', 'aura-2-sirio-es', 'aura-2-valerio-es',
    'aura-2-ama-ja', 'aura-2-ebisu-ja', 'aura-2-fujin-ja', 'aura-2-izanami-ja', 'aura-2-uzume-ja',
    'aura-2-aurelia-de', 'aura-2-elara-de', 'aura-2-fabian-de', 'aura-2-julius-de', 'aura-2-kara-de',
    'aura-2-lara-de', 'aura-2-viktoria-de', 'aura-2-cesare-it', 'aura-2-cinzia-it', 'aura-2-demetra-it',
    'aura-2-dionisio-it', 'aura-2-elio-it', 'aura-2-flavio-it', 'aura-2-livia-it', 'aura-2-maia-it',
    'aura-2-melia-it', 'aura-2-beatrix-nl', 'aura-2-cornelia-nl', 'aura-2-daphne-nl', 'aura-2-hestia-nl',
    'aura-2-lars-nl', 'aura-2-leda-nl', 'aura-2-rhea-nl', 'aura-2-roman-nl', 'aura-2-sander-nl'
  ]
}

/* ---------- 模型级提示 ---------- */

const MODEL_HINTS: Record<string, ModelHints> = {
  'hexgrad/kokoro-82m': {
    label: 'Kokoro',
    langs: '中/英/日/法/西/意/葡/印地 8 语',
    note: '开源轻量，约 $0.6/百万字符',
    preferred: 'zf_xiaoxiao'
  },
  'qwen/qwen-audio-3.0-tts-flash': { label: '通义 TTS Flash', langs: '中文为主', note: '阿里 DashScope 语音合成' },
  'qwen/qwen-audio-3.0-tts-plus': { label: '通义 TTS Plus', langs: '中文为主', note: '阿里 DashScope 高音质档' },
  'minimax/speech-2.8-turbo': { label: 'MiniMax Turbo', langs: '中/英等多语', freeVoice: MINIMAX_FREE_VOICE },
  'minimax/speech-2.8-hd': { label: 'MiniMax HD', langs: '中/英等多语', freeVoice: MINIMAX_FREE_VOICE },
  'fish-audio/s1': { label: 'Fish S1', langs: '多语言（含中文）', freeVoice: FISH_FREE_VOICE, voiceOptional: true, cloning: true },
  'fish-audio/s2-pro': { label: 'Fish S2 Pro', langs: '多语言（含中文）', freeVoice: FISH_FREE_VOICE, voiceOptional: true, cloning: true },
  'fish-audio/s2.1-pro': { label: 'Fish S2.1 Pro', langs: '多语言（含中文）', freeVoice: FISH_FREE_VOICE, voiceOptional: true, cloning: true },
  'fish-audio/s2.1-pro-free': { label: 'Fish S2.1 免费', langs: '多语言（含中文）', freeVoice: FISH_FREE_VOICE, voiceOptional: true, cloning: true },
  'google/gemini-3.1-flash-tts-preview': {
    label: 'Gemini TTS',
    langs: '24+ 语言（含中文）',
    pcmOnly: true,
    preferred: 'Kore'
  },
  'x-ai/grok-voice-tts-1.0': { label: 'Grok Voice', langs: '20+ 语言自动识别（含中文）' },
  'microsoft/mai-voice-2': { label: 'MAI-Voice-2', langs: '15 语言' },
  'microsoft/mai-voice-2-flash': { label: 'MAI-Voice-2 Flash', langs: '15 语言', note: '低延迟档' },
  'deepgram/aura-2': { label: 'Aura-2', langs: '英/西/法/德/意/荷/日 7 语' },
  'zyphra/zonos-v0.1-transformer': { label: 'Zonos', langs: '英语' },
  'zyphra/zonos-v0.1-hybrid': { label: 'Zonos Hybrid', langs: '英语' },
  'canopylabs/orpheus-3b-0.1-ft': { label: 'Orpheus 3B', langs: '英语' },
  'sesame/csm-1b': { label: 'Sesame CSM', langs: '英语' },
  'mistralai/voxtral-mini-tts-2603': { label: 'Voxtral TTS', langs: '英/法', note: '同一人名多情绪版本', cloning: true }
}

/** 按厂商前缀兜底（未来同厂新模型自动继承音色语义） */
const VENDOR_HINTS: Record<string, ModelHints> = {
  'fish-audio': { langs: '多语言（含中文）', freeVoice: FISH_FREE_VOICE, voiceOptional: true, cloning: true },
  minimax: { langs: '中/英等多语', freeVoice: MINIMAX_FREE_VOICE }
}

export function modelHints(modelId: string): ModelHints | undefined {
  const id = canonicalModelId(modelId)
  return own(MODEL_HINTS, id) ?? own(VENDOR_HINTS, id.split('/')[0])
}

/* ---------- 音色解析 ---------- */

function cap(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s
}

/** 解析单个音色 ID 为带语言/性别/风格的条目；无法识别时退化为原样标签 */
export function describeVoice(modelId: string, voiceId: string): VoiceInfo {
  const cid = canonicalModelId(modelId)
  const vendor = cid.split('/')[0]

  if (vendor === 'hexgrad') {
    const m = /^([a-z])([fm])_(.+)$/.exec(voiceId)
    if (m && KOKORO_LANG[m[1]]) {
      const { lang, note } = KOKORO_LANG[m[1]]
      const label = lang === 'zh' ? own(KOKORO_ZH, m[3]) ?? cap(m[3]) : cap(m[3])
      return { id: voiceId, label, lang, gender: m[2] as 'f' | 'm', note }
    }
  }

  if (vendor === 'deepgram') {
    const m = /^aura-2-([a-z]+)-([a-z]{2})$/.exec(voiceId)
    if (m) {
      return { id: voiceId, label: cap(m[1]), lang: LANG_LABELS[m[2]] ? m[2] : 'other', gender: own(DEEPGRAM_GENDER, m[1]) }
    }
  }

  if (vendor === 'mistralai') {
    const m = /^([a-z]{2})_([a-z]+)_([a-z]+)$/.exec(voiceId)
    if (m && VOXTRAL_LOCALE[m[1]]) {
      const { lang, note } = VOXTRAL_LOCALE[m[1]]
      const emotion = own(EMOTION_ZH, m[3]) ?? cap(m[3])
      return {
        id: voiceId,
        label: `${cap(m[2])}·${emotion}`,
        lang,
        gender: own(VOXTRAL_GENDER, m[2]),
        note
      }
    }
  }

  if (vendor === 'microsoft') {
    const m = /^([a-z]{2})-([A-Z]{2})-([A-Za-z]+):/.exec(voiceId)
    if (m) {
      return {
        id: voiceId,
        label: m[3],
        lang: MAI_LANG[m[1]] ?? 'other',
        gender: own(MAI_GENDER, m[3]),
        note: `${m[1]}-${m[2]}`
      }
    }
  }

  if (vendor === 'google' && own(GEMINI_VOICES, voiceId)) {
    return { id: voiceId, label: voiceId, lang: 'multi', ...GEMINI_VOICES[voiceId] }
  }

  if (vendor === 'x-ai' && own(GROK_VOICES, voiceId)) {
    return { id: voiceId, label: cap(voiceId), lang: 'multi', ...GROK_VOICES[voiceId] }
  }

  if (vendor === 'zyphra' && own(ZONOS_VOICES, voiceId)) return { ...ZONOS_VOICES[voiceId] }

  if (vendor === 'canopylabs' && own(ORPHEUS_VOICES, voiceId)) {
    return { id: voiceId, label: cap(voiceId), lang: 'en', ...ORPHEUS_VOICES[voiceId] }
  }

  if (vendor === 'sesame' && own(SESAME_VOICES, voiceId)) {
    return { id: voiceId, label: SESAME_VOICES[voiceId], lang: 'en' }
  }

  if (vendor === 'qwen' && own(QWEN_VOICES, voiceId)) {
    return { id: voiceId, label: cap(voiceId), ...QWEN_VOICES[voiceId] }
  }

  if (vendor === 'minimax') {
    const hit = MINIMAX_SUGGESTIONS.find(v => v.id === voiceId)
    if (hit) return { ...hit }
  }

  return { id: voiceId, label: voiceId }
}

/**
 * 模型的完整音色目录：在线列表优先，其次内置兜底表。
 * 开放音色模型（fish/minimax）返回空数组，由 freeVoice 建议接管。
 */
export function catalogVoices(modelId: string, serverVoices?: string[]): VoiceInfo[] {
  if (modelHints(modelId)?.freeVoice) return []
  const ids = serverVoices?.length ? serverVoices : own(STATIC_VOICES, canonicalModelId(modelId)) ?? []
  return ids.map(v => describeVoice(modelId, v))
}

/** 按语言分组：中文 → 多语言 → 英语 → … → 其他/未知 */
export function groupVoices(voices: VoiceInfo[]): VoiceGroup[] {
  const map = new Map<string, VoiceInfo[]>()
  for (const v of voices) {
    const key = v.lang ?? 'other'
    const list = map.get(key)
    if (list) list.push(v)
    else map.set(key, [v])
  }
  const rank = (lang: string) => {
    const i = LANG_ORDER.indexOf(lang)
    return i < 0 ? LANG_ORDER.length : i
  }
  return [...map.entries()]
    .sort((a, b) => rank(a[0]) - rank(b[0]))
    .map(([lang, list]) => ({ lang, label: langLabel(lang), voices: list }))
}

/** 切换模型后的默认音色：模型偏好 → 中文 → 多语言 → 首个；开放音色模型给建议首项或空 */
export function defaultVoiceFor(modelId: string, serverVoices?: string[]): string {
  const hints = modelHints(modelId)
  if (hints?.freeVoice) {
    return hints.voiceOptional ? '' : hints.freeVoice.suggestions?.[0]?.id ?? ''
  }
  const all = catalogVoices(modelId, serverVoices)
  if (!all.length) return ''
  if (hints?.preferred && all.some(v => v.id === hints.preferred)) return hints.preferred
  const best = all.find(v => v.lang === 'zh') ?? all.find(v => v.lang === 'multi')
  return (best ?? all[0]).id
}

/** 快捷推荐（听书场景优选，覆盖 免费/低价/中文/多语言 各档） */
export const RECOMMENDED_MODELS: { id: string; label: string; tag: string }[] = [
  { id: 'hexgrad/kokoro-82m', label: 'Kokoro', tag: '超低价' },
  { id: 'qwen/qwen-audio-3.0-tts-flash', label: '通义 TTS', tag: '中文' },
  { id: 'minimax/speech-2.8-turbo', label: 'MiniMax', tag: '有声书' },
  { id: 'fish-audio/s2.1-pro-free:free', label: 'Fish S2.1', tag: '免费' },
  { id: 'google/gemini-3.1-flash-tts-preview', label: 'Gemini TTS', tag: '30 音色' },
  { id: 'x-ai/grok-voice-tts-1.0', label: 'Grok Voice', tag: '多语言' }
]

/** 模型信息行：价格 · 语言 · 音色数 · 能力标记 */
export function formatModelMeta(info: TtsModelInfo | undefined, modelId: string): string {
  const hints = modelHints(modelId)
  const parts: string[] = []
  if (info?.completionPrice) {
    parts.push('按 token 计费')
  } else if (info?.promptPrice) {
    const perM = info.promptPrice * 1e6
    parts.push(`≈$${perM >= 10 ? Math.round(perM) : Math.round(perM * 100) / 100}/百万字符`)
  } else if (modelId.includes(':free')) {
    parts.push('免费')
  }
  if (hints?.langs) parts.push(hints.langs)
  const n = catalogVoices(modelId, info?.voices).length
  if (n) parts.push(`${n} 个音色`)
  else if (hints?.freeVoice) parts.push('开放音色 ID')
  if (hints?.cloning) parts.push('支持克隆')
  if (hints?.note) parts.push(hints.note)
  return parts.join(' · ')
}
