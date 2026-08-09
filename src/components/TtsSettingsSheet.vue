<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import BottomSheet from './BottomSheet.vue'
import { useSettingsStore, VOICE_PRESETS } from '../store/settings'
import { testOpenAIConfig, fetchTtsModels } from '../tts/providers/openai-speech'
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

/** 模型的已知音色：内置预设优先，其次在线列表的 supported_voices；空数组表示未知 */
function voicesFor(id: string): string[] {
  return VOICE_PRESETS[id]?.voices ?? settings.ttsModels.find(m => m.id === id)?.voices ?? []
}

const presetModels = computed(() => Object.entries(VOICE_PRESETS))
const voicePresets = computed(() => voicesFor(tts.openai.model))
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

// 切换模型时若当前音色不在该模型已知音色中，回退到第一个已知音色
watch(
  () => tts.openai.model,
  id => {
    const voices = voicesFor(id)
    if (voices.length && !voices.includes(tts.openai.voice)) tts.openai.voice = voices[0]
  }
)

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
            v-for="[id, p] in presetModels"
            :key="id"
            class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
            :class="tts.openai.model === id
              ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
              : 'border-[var(--border)] text-[var(--text-2)]'"
            @click="tts.openai.model = id; if (VOICE_PRESETS[id]) tts.openai.voice = VOICE_PRESETS[id].voices[0]"
          >
            {{ p.label }}
          </button>
        </div>
      </div>

      <!-- 音色 -->
      <div class="mb-4">
        <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">音色</div>
        <input
          v-model.trim="tts.openai.voice"
          type="text"
          placeholder="留空则使用服务默认音色"
          class="mb-2 w-full rounded-xl border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5 text-sm outline-none focus:border-[var(--accent)]"
        />
        <div v-if="voicePresets.length" class="flex flex-wrap gap-1.5">
          <button
            v-for="v in voicePresets"
            :key="v"
            class="rounded-full border px-2.5 py-1 text-[11px] capitalize transition-all"
            :class="tts.openai.voice === v
              ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
              : 'border-[var(--border)] text-[var(--text-2)]'"
            @click="tts.openai.voice = v"
          >
            {{ v }}
          </button>
        </div>
      </div>

      <!-- 语气指令 -->
      <div class="mb-4">
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
      兼容所有 OpenAI 格式语音接口：OpenRouter、OpenAI 官方、SiliconFlow、FishAudio 等。
      在 <a href="https://openrouter.ai/settings/keys" target="_blank" class="text-[var(--accent)]">openrouter.ai</a> 创建 API Key，推荐模型 openai/gpt-4o-mini-tts。
    </p>
  </BottomSheet>
</template>
