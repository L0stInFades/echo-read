<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import BottomSheet from './BottomSheet.vue'
import { useSettingsStore } from '../store/settings'
import { testOpenAIConfig, fetchTtsModels, isOpenRouterBase } from '../tts/providers/openai-speech'
import {
  RECOMMENDED_MODELS,
  modelHints,
  catalogVoices,
  groupVoices,
  defaultVoiceFor,
  formatModelMeta
} from '../tts/voices'
import { audioCacheStats, clearAudioCache } from '../lib/db'
import { toast } from '../lib/toast'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const settings = useSettingsStore()
const tts = settings.tts

const testing = ref(false)
const testResult = ref('')
const cache = ref({ count: 0, bytes: 0 })
const showAdvanced = ref(false)
const fetchingModels = ref(false)
/** 音色语言筛选：空串 = 全部 */
const voiceLang = ref('')

function serverVoicesFor(id: string): string[] | undefined {
  return settings.ttsModels.find(m => m.id === id)?.voices
}

const hints = computed(() => modelHints(tts.openai.model))
const freeVoice = computed(() => hints.value?.freeVoice)
const voiceCatalog = computed(() => catalogVoices(tts.openai.model, serverVoicesFor(tts.openai.model)))
const voiceGroups = computed(() => groupVoices(voiceCatalog.value))
const shownGroups = computed(() =>
  voiceLang.value ? voiceGroups.value.filter(g => g.lang === voiceLang.value) : voiceGroups.value
)
const modelMeta = computed(() =>
  formatModelMeta(settings.ttsModels.find(m => m.id === tts.openai.model), tts.openai.model)
)
/** 语气指令仅对 OpenAI 官方等直连服务有意义（OpenRouter 现役 TTS 模型均不支持） */
const showInstructions = computed(() => !isOpenRouterBase(tts.openai.baseUrl))

/** 在线模型下拉项：当前模型不在列表中时补一项，避免 select 显示空白 */
const modelOptions = computed(() => {
  const list = settings.ttsModels
  return tts.openai.model && !list.some(m => m.id === tts.openai.model)
    ? [{ id: tts.openai.model, name: `${tts.openai.model}（手动）` }, ...list]
    : list
})

async function fetchModels() {
  fetchingModels.value = true
  try {
    const list = await fetchTtsModels(settings.openaiConfig())
    settings.ttsModels = list
    if (list.length) toast(`发现 ${list.length} 个语音模型`, 'success')
    else toast('该服务未列出语音模型，可手动输入模型名', 'info')
  } catch (e: any) {
    toast(e?.message ?? '获取模型列表失败', 'error')
  } finally {
    fetchingModels.value = false
  }
}

// 切换模型：恢复该模型记忆的音色，否则用目录默认音色（中文优先）
watch(
  () => tts.openai.model,
  id => {
    const mem = tts.voiceByModel[id]
    const usable = mem !== undefined && (mem !== '' || modelHints(id)?.voiceOptional)
    tts.openai.voice = usable ? mem : defaultVoiceFor(id, serverVoicesFor(id))
    voiceLang.value = ''
  }
)
// 记住每个模型的音色选择
watch(
  () => tts.openai.voice,
  v => {
    if (tts.openai.model) tts.voiceByModel[tts.openai.model] = v
  }
)
// 在线列表刷新可能让目录收敛:失效的语言筛选会清空音色区,而筛选行同时隐藏、无 UI 可解,须自动复位
watch(voiceGroups, gs => {
  if (voiceLang.value && !gs.some(g => g.lang === voiceLang.value)) voiceLang.value = ''
})

async function runTest() {
  if (!tts.openai.apiKey) {
    toast('请先填写 API Key', 'error')
    return
  }
  testing.value = true
  testResult.value = ''
  const r = await testOpenAIConfig(settings.openaiConfig())
  testing.value = false
  testResult.value = r.message
  toast(r.message, r.ok ? 'success' : 'error')
}

async function refreshCache() {
  cache.value = await audioCacheStats()
}

async function clearCache() {
  await clearAudioCache()
  await refreshCache()
  toast('音频缓存已清空', 'success')
}

function formatBytes(n: number) {
  if (n > 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + ' MB'
  if (n > 1024) return (n / 1024).toFixed(0) + ' KB'
  return n + ' B'
}

// 每次打开弹窗时刷新缓存统计（组件常驻，onMounted 取数会过期）
watch(
  () => props.open,
  v => v && refreshCache()
)
</script>

<template>
  <BottomSheet :open="open" title="AI 朗读设置" @close="emit('close')">
    <!-- 引擎选择 -->
    <div class="mb-5">
      <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">朗读引擎</div>
      <div class="grid grid-cols-2 gap-2">
        <button
          class="rounded-xl border px-3 py-3 text-left transition-all"
          :class="tts.provider === 'openai-speech'
            ? 'border-[var(--accent)] bg-[var(--accent-soft)]'
            : 'border-[var(--border)] bg-[var(--surface)]'"
          @click="tts.provider = 'openai-speech'"
        >
          <div class="text-sm font-semibold">AI TTS</div>
          <div class="mt-0.5 text-[11px] leading-tight text-[var(--text-2)]">OpenRouter / OpenAI 兼容接口</div>
        </button>
        <button
          class="rounded-xl border px-3 py-3 text-left transition-all"
          :class="tts.provider === 'webspeech'
            ? 'border-[var(--accent)] bg-[var(--accent-soft)]'
            : 'border-[var(--border)] bg-[var(--surface)]'"
          @click="tts.provider = 'webspeech'"
        >
          <div class="text-sm font-semibold">系统语音</div>
          <div class="mt-0.5 text-[11px] leading-tight text-[var(--text-2)]">免费离线，质量取决于设备</div>
        </button>
      </div>
    </div>

    <template v-if="tts.provider === 'openai-speech'">
      <!-- API 配置 -->
      <div class="mb-4">
        <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">API 配置</div>
        <div class="space-y-2.5">
          <label class="block">
            <span class="mb-1 block text-xs text-[var(--text-2)]">Base URL</span>
            <input
              v-model.trim="tts.openai.baseUrl"
              type="url"
              placeholder="https://openrouter.ai/api/v1"
              class="w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none placeholder:text-[var(--text-3)] focus:border-[var(--accent)]"
            />
          </label>
          <label class="block">
            <span class="mb-1 block text-xs text-[var(--text-2)]">API Key</span>
            <input
              v-model.trim="tts.openai.apiKey"
              type="password"
              placeholder="sk-or-..."
              autocomplete="off"
              class="w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none placeholder:text-[var(--text-3)] focus:border-[var(--accent)]"
            />
          </label>
        </div>
      </div>

      <!-- 模型 -->
      <div class="mb-4">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-xs font-medium tracking-wider text-[var(--text-3)]">模型</span>
          <button
            class="text-xs text-[var(--accent)] transition-opacity disabled:opacity-50"
            :disabled="fetchingModels"
            @click="fetchModels"
          >
            {{ fetchingModels ? '拉取中…' : settings.ttsModels.length ? '刷新在线列表' : '获取在线模型' }}
          </button>
        </div>
        <select
          v-if="settings.ttsModels.length"
          v-model="tts.openai.model"
          class="mb-2 w-full appearance-none rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none focus:border-[var(--accent)]"
        >
          <option v-for="m in modelOptions" :key="m.id" :value="m.id">{{ m.name }}</option>
        </select>
        <input
          v-model.trim="tts.openai.model"
          type="text"
          class="mb-2 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none focus:border-[var(--accent)]"
        />
        <div class="flex flex-wrap gap-1.5">
          <button
            v-for="r in RECOMMENDED_MODELS"
            :key="r.id"
            class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
            :class="tts.openai.model === r.id
              ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
              : 'border-[var(--border)] text-[var(--text-2)]'"
            @click="tts.openai.model = r.id"
          >
            {{ r.label }}<span class="ml-1 opacity-60">{{ r.tag }}</span>
          </button>
        </div>
        <p v-if="modelMeta" class="mt-2 text-[11px] leading-relaxed text-[var(--text-3)]">{{ modelMeta }}</p>
      </div>

      <!-- 音色 -->
      <div class="mb-4">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-xs font-medium tracking-wider text-[var(--text-3)]">音色</span>
          <span v-if="voiceCatalog.length" class="text-[11px] text-[var(--text-3)]">{{ voiceCatalog.length }} 个</span>
        </div>

        <!-- 开放音色模型（Fish / MiniMax）：自由输入 + 官方建议 -->
        <template v-if="freeVoice">
          <input
            v-model.trim="tts.openai.voice"
            type="text"
            :placeholder="freeVoice.placeholder"
            class="mb-2 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none placeholder:text-[var(--text-3)] focus:border-[var(--accent)]"
          />
          <p class="mb-2 text-[11px] leading-relaxed text-[var(--text-3)]">{{ freeVoice.hint }}</p>
          <div v-if="hints?.voiceOptional || freeVoice.suggestions?.length" class="flex flex-wrap gap-1.5">
            <button
              v-if="hints?.voiceOptional"
              class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
              :class="tts.openai.voice === ''
                ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-2)]'"
              @click="tts.openai.voice = ''"
            >
              默认音色
            </button>
            <button
              v-for="v in freeVoice.suggestions"
              :key="v.id"
              :title="v.id"
              class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
              :class="tts.openai.voice === v.id
                ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-2)]'"
              @click="tts.openai.voice = v.id"
            >
              {{ v.label }}
              <span v-if="v.gender === 'f'" class="text-pink-400">♀</span>
              <span v-else-if="v.gender === 'm'" class="text-sky-400">♂</span>
            </button>
          </div>
        </template>

        <!-- 有目录模型：语言分组 + 性别/风格标注 -->
        <template v-else-if="voiceCatalog.length">
          <input
            v-model.trim="tts.openai.voice"
            type="text"
            placeholder="音色 ID"
            class="mb-2 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none focus:border-[var(--accent)]"
          />
          <div v-if="voiceGroups.length > 1" class="mb-2 flex flex-wrap gap-1.5">
            <button
              class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
              :class="voiceLang === ''
                ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-2)]'"
              @click="voiceLang = ''"
            >
              全部
            </button>
            <button
              v-for="g in voiceGroups"
              :key="g.lang"
              class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
              :class="voiceLang === g.lang
                ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-2)]'"
              @click="voiceLang = g.lang"
            >
              {{ g.label }}<span class="ml-0.5 opacity-60">{{ g.voices.length }}</span>
            </button>
          </div>
          <div class="max-h-64 space-y-2 overflow-y-auto pr-1">
            <div v-for="g in shownGroups" :key="g.lang">
              <div v-if="shownGroups.length > 1" class="mb-1 text-[11px] text-[var(--text-3)]">{{ g.label }}</div>
              <div class="flex flex-wrap gap-1.5">
                <button
                  v-for="v in g.voices"
                  :key="v.id"
                  :title="v.id + (v.note ? '（' + v.note + '）' : '')"
                  class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
                  :class="tts.openai.voice === v.id
                    ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                    : 'border-[var(--border)] text-[var(--text-2)]'"
                  @click="tts.openai.voice = v.id"
                >
                  {{ v.label }}<span v-if="v.note" class="opacity-60">·{{ v.note }}</span>
                  <span v-if="v.gender === 'f'" class="text-pink-400">♀</span>
                  <span v-else-if="v.gender === 'm'" class="text-sky-400">♂</span>
                </button>
              </div>
            </div>
          </div>
        </template>

        <!-- 未知模型：纯手动 -->
        <input
          v-else
          v-model.trim="tts.openai.voice"
          type="text"
          placeholder="该模型未提供音色列表，可手动填写（留空试用服务默认）"
          class="w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none placeholder:text-[var(--text-3)] focus:border-[var(--accent)]"
        />
      </div>

      <!-- 语气指令（OpenAI 官方等直连服务） -->
      <div v-if="showInstructions" class="mb-4">
        <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">语气指令（部分模型支持）</div>
        <input
          v-model.trim="tts.openai.instructions"
          type="text"
          placeholder="如：用温暖沉静的女声朗读"
          class="w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none placeholder:text-[var(--text-3)] focus:border-[var(--accent)]"
        />
      </div>

      <!-- 测试连接 -->
      <button
        class="mb-5 w-full rounded-xl py-3 text-sm font-semibold text-white transition-opacity active:opacity-80"
        style="background: var(--gradient)"
        :disabled="testing"
        @click="runTest"
      >
        {{ testing ? '正在试音…' : testResult ? `结果：${testResult}` : '试听测试（合成「你好」）' }}
      </button>
    </template>

    <!-- 倍速 -->
    <div class="mb-5">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-xs font-medium tracking-wider text-[var(--text-3)]">播放倍速</span>
        <span class="text-xs font-semibold text-[var(--accent)]">{{ tts.rate.toFixed(2) }}×</span>
      </div>
      <input
        v-model.number="tts.rate"
        type="range"
        min="0.5"
        max="2.5"
        step="0.05"
        class="w-full accent-[var(--accent)]"
      />
    </div>

    <!-- 高级 -->
    <button class="mb-2 text-xs text-[var(--text-2)]" @click="showAdvanced = !showAdvanced">
      {{ showAdvanced ? '收起高级选项 ▲' : '高级选项 ▼' }}
    </button>
    <div v-if="showAdvanced" class="mb-4 space-y-3 rounded-xl border border-[var(--border)] p-3.5">
      <label class="flex items-center justify-between text-sm">
        <span>单片段字数 <span class="text-xs text-[var(--text-3)]">({{ tts.maxChunkChars }})</span></span>
        <input v-model.number="tts.maxChunkChars" type="range" min="80" max="400" step="10" class="w-36 accent-[var(--accent)]" />
      </label>
      <label class="flex items-center justify-between text-sm">
        <span>预取片段数 <span class="text-xs text-[var(--text-3)]">({{ tts.prefetch }})</span></span>
        <input v-model.number="tts.prefetch" type="range" min="0" max="5" step="1" class="w-36 accent-[var(--accent)]" />
      </label>
      <div class="flex items-center justify-between border-t border-[var(--border)] pt-3 text-sm">
        <span class="text-[var(--text-2)]">音频缓存 {{ cache.count }} 条 · {{ formatBytes(cache.bytes) }}</span>
        <button class="text-xs text-red-400" @click="clearCache">清空</button>
      </div>
    </div>

    <p class="text-[11px] leading-relaxed text-[var(--text-3)]">
      已内置 OpenRouter 全部语音模型的音色目录（含中文音色标注），也兼容 OpenAI 官方、SiliconFlow 等
      OpenAI 格式接口。在 <a href="https://openrouter.ai/settings/keys" target="_blank" class="text-[var(--accent)]">openrouter.ai</a>
      创建 API Key 即可使用；Fish S2.1 有免费档可先试听。
    </p>
  </BottomSheet>
</template>
