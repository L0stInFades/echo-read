<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getBook } from '../lib/db'
import { getDerivedChapter, type DerivedChapter } from '../lib/chapters'
import { layoutBlocks, fragText } from '../lib/text'
import { offsetFromPoint, spanElAt } from '../lib/locate'
import { usePlayerStore, type SleepMode } from '../store/player'
import { useLibraryStore } from '../store/library'
import { useSettingsStore } from '../store/settings'
import type { BookMeta } from '../types'
import ChapterListSheet from '../components/ChapterListSheet.vue'
import ReaderStyleSheet from '../components/ReaderStyleSheet.vue'
import TtsSettingsSheet from '../components/TtsSettingsSheet.vue'
import { toast } from '../lib/toast'

const props = defineProps<{ bookId: string }>()
const router = useRouter()
const player = usePlayerStore()
const library = useLibraryStore()
const settings = useSettingsStore()

// 大对象用 shallowRef：DerivedChapter 是不可变共享实例，深响应化纯属浪费
const meta = shallowRef<BookMeta | null>(null)
const derived = shallowRef<DerivedChapter | null>(null)
const currentChapterIndex = ref(0)
const loadFailed = ref(false)

const scrollEl = ref<HTMLElement>()
const bodyEl = ref<HTMLElement>()

const showChapters = ref(false)
const showStyle = ref(false)
const showTts = ref(false)
const showSleep = ref(false)

/* ---------- 渲染布局：段落区间 × 片段区间的双指针归并（O(P+S)，零文本副本） ---------- */

const blocks = computed(() =>
  derived.value ? layoutBlocks(derived.value.paras, derived.value.segments) : []
)

const text = computed(() => derived.value?.text ?? '')

/** 当前应高亮的片段起点（引擎与显示同章时） */
const activeStart = computed(() => {
  const s = player.snap
  if (s.bookId !== props.bookId || s.chapterIndex !== currentChapterIndex.value) return -1
  if (s.state === 'idle') return -1
  return s.segmentStart
})

const synthesizing = computed(() => {
  const s = player.snap
  return s.bookId === props.bookId && s.state !== 'idle' && s.synthesizing
})

/* ---------- 装载（视图侧代际守卫：快速切章/滑块连发时后到者胜） ---------- */

let loadSeq = 0

onMounted(async () => {
  const m = await getBook(props.bookId)
  if (!m) {
    loadFailed.value = true
    return
  }
  meta.value = m
  await loadChapter(m.progress.chapterIndex, m.progress.offset)

  // 引擎若正在播放本书，显示层跟随引擎章节
  if (player.snap.bookId === props.bookId && player.snap.state !== 'idle') {
    if (player.snap.chapterIndex !== currentChapterIndex.value) {
      await loadChapter(player.snap.chapterIndex)
    }
  }
})

onBeforeUnmount(() => {
  // 离开即暂停并保存进度；锁屏/控制中心经 MediaSession 仍可唤起 = 后台续播（设计如此）
  saveProgressNow()
  player.engine.pause()
})

async function loadChapter(index: number, scrollToOffset = 0) {
  const my = ++loadSeq
  const d = await getDerivedChapter(props.bookId, index, settings.tts.maxChunkChars)
  if (my !== loadSeq) return // 已被更新的装载取代
  if (!d) {
    toast('章节内容缺失', 'error')
    return
  }
  derived.value = d
  currentChapterIndex.value = index
  await nextTick()
  if (my !== loadSeq) return
  const el = bodyEl.value
  if (!el || !scrollEl.value) return
  if (scrollToOffset > 0) {
    spanElAt(el, scrollToOffset)?.scrollIntoView({ block: 'center', behavior: 'auto' })
  } else {
    scrollEl.value.scrollTo({ top: 0 })
  }
}

/** 滚动锚点：视口内首个可见 span 的章节偏移（参数变化重派生时保持位置） */
function visibleAnchor(): number {
  const el = bodyEl.value
  if (!el) return 0
  for (const span of el.querySelectorAll<HTMLElement>('[data-start]')) {
    if (span.getBoundingClientRect().bottom > 0) return Number(span.dataset.start)
  }
  return 0
}

// 片段长度变化 → 去抖后按当前锚点重派生（引擎侧由 player store 去抖触发 reload）
let layoutTimer: ReturnType<typeof setTimeout> | null = null
watch(
  () => settings.tts.maxChunkChars,
  () => {
    if (layoutTimer) clearTimeout(layoutTimer)
    layoutTimer = setTimeout(() => {
      const anchor = activeStart.value > 0 ? activeStart.value : visibleAnchor()
      void loadChapter(currentChapterIndex.value, anchor)
    }, 250)
  }
)

/* ---------- 任意字点读 ---------- */

function onBodyClick(e: MouseEvent) {
  const offset = offsetFromPoint(e.clientX, e.clientY)
  if (offset == null) return
  void playFrom(offset)
}

/** 核心交互：从任意字符偏移开始朗读 */
async function playFrom(offset: number) {
  try {
    const s = player.snap
    if (s.bookId !== props.bookId || s.chapterIndex !== currentChapterIndex.value) {
      if (!(await player.loadBook(props.bookId, currentChapterIndex.value, offset))) return
    } else {
      player.engine.seekToOffset(offset)
    }
    await player.engine.play()
  } catch (e: any) {
    toast(e?.message ?? '播放失败', 'error')
  }
}

async function togglePlay() {
  try {
    const s = player.snap
    const onThisChapter =
      s.bookId === props.bookId && s.chapterIndex === currentChapterIndex.value
    if (onThisChapter && (s.state === 'playing' || s.state === 'paused')) {
      await player.engine.toggle()
      return
    }
    const m = meta.value
    const offset =
      m && m.progress.chapterIndex === currentChapterIndex.value ? m.progress.offset : 0
    if (await player.loadBook(props.bookId, currentChapterIndex.value, offset)) {
      await player.engine.play()
    }
  } catch (e: any) {
    toast(e?.message ?? '播放失败', 'error')
  }
}

async function gotoChapter(index: number) {
  const m = meta.value
  if (!m || index < 0 || index >= m.chapterCount) return
  const wasPlaying = player.snap.state === 'playing' && player.snap.bookId === props.bookId
  player.engine.pause()
  await loadChapter(index)
  try {
    if ((await player.loadBook(props.bookId, index, 0)) && wasPlaying) {
      await player.engine.play()
    }
  } catch (e: any) {
    toast(e?.message ?? '章节加载失败', 'error')
  }
}

/* ---------- 引擎事件跟随 ---------- */

// 引擎自动跨章 → 显示跟随（loading 期间 chapterIndex 为 -1，跳过）
watch(
  () => player.snap.chapterIndex,
  async idx => {
    const s = player.snap
    if (s.bookId !== props.bookId || s.state === 'idle' || s.state === 'loading') return
    if (idx !== currentChapterIndex.value) {
      await loadChapter(idx)
      // 跨章首片段的进度立即落库（片段推进 watcher 会错过 offset=0 这一刻）
      void library.saveProgress(props.bookId, idx, 0)
    }
  }
)

/* ---------- 进度保存（节流：片段推进秒级触发，避免高频全量写） ---------- */

let lastSave = 0
function saveProgressThrottled(chapterIndex: number, offset: number) {
  const now = Date.now()
  if (now - lastSave < 4000) return
  lastSave = now
  void library.saveProgress(props.bookId, chapterIndex, offset)
}

function saveProgressNow() {
  const s = player.snap
  if (s.bookId === props.bookId && s.chapterIndex >= 0) {
    void library.saveProgress(props.bookId, s.chapterIndex, s.segmentStart)
  }
}

// 片段推进 → 高亮滚动 + 保存进度
watch(
  () => [player.snap.segmentIndex, player.snap.segmentStart],
  async () => {
    const s = player.snap
    if (s.bookId !== props.bookId || s.chapterIndex !== currentChapterIndex.value) return
    if (s.state === 'idle' || s.state === 'loading') return
    await nextTick()
    const body = bodyEl.value
    if (!body) return
    const el = spanElAt(body, s.segmentStart)
    if (el && s.state === 'playing') {
      const rect = el.getBoundingClientRect()
      const vh = window.innerHeight
      if (rect.top < 90 || rect.bottom > vh - 170) {
        el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      }
    }
    saveProgressThrottled(s.chapterIndex, s.segmentStart)
  }
)

// 错误提示
let lastError = ''
watch(
  () => player.snap.error,
  err => {
    if (err && err !== lastError) toast(err, 'error', 5000)
    lastError = err ?? ''
  }
)

/* ---------- 快捷倍速 ---------- */

const RATE_STEPS = [0.75, 1, 1.25, 1.5, 1.75, 2]
function cycleRate() {
  const cur = settings.tts.rate
  const next = RATE_STEPS.find(r => r > cur + 0.01) ?? RATE_STEPS[0]
  settings.tts.rate = next
  toast(`${next.toFixed(2)}× 倍速`, 'info', 1200)
}

/* ---------- 睡眠定时 ---------- */

const SLEEP_OPTIONS: { label: string; mode: SleepMode }[] = [
  { label: '15分', mode: 15 },
  { label: '30分', mode: 30 },
  { label: '60分', mode: 60 },
  { label: '90分', mode: 90 },
  { label: '播完本章', mode: 'chapter' },
  { label: '关闭', mode: 'off' }
]

function pickSleep(mode: SleepMode) {
  player.setSleepTimer(mode)
  showSleep.value = false
}

/** 定时按钮文案：分钟模式 mm:ss 倒计时，本章模式「本章」 */
const sleepLabel = computed(() => {
  if (player.sleepMode === 'chapter') return '本章'
  const r = player.sleepRemaining
  return `${Math.floor(r / 60)}:${String(r % 60).padStart(2, '0')}`
})

const playing = computed(
  () => player.snap.state === 'playing' && player.snap.bookId === props.bookId
)
const chapterProgress = computed(() => {
  const s = player.snap
  if (s.bookId !== props.bookId || s.chapterIndex !== currentChapterIndex.value || !s.segmentCount)
    return 0
  return Math.min(1, s.segmentIndex / s.segmentCount)
})

const bodyStyle = computed(() => ({
  fontSize: settings.reader.fontSize + 'px',
  lineHeight: String(settings.reader.lineHeight),
  fontFamily: settings.reader.fontFamily === 'serif' ? 'var(--font-serif)' : 'var(--font-sans)'
}))
</script>

<template>
  <div
    class="flex h-full flex-col"
    :class="`reader-theme-${settings.reader.theme}`"
    :style="{ background: 'var(--reader-bg)', color: 'var(--reader-text)' }"
  >
    <!-- 加载失败 -->
    <div v-if="loadFailed" class="flex h-full flex-col items-center justify-center gap-4">
      <p class="text-sm opacity-60">书籍不存在或已被删除</p>
      <button class="rounded-full border border-current px-5 py-2 text-sm" @click="router.replace('/')">返回书架</button>
    </div>

    <template v-else>
      <!-- 顶栏 -->
      <header
        class="safe-top z-20 flex items-center gap-1 border-b px-2 pb-2 pt-2"
        :style="{ borderColor: 'color-mix(in srgb, var(--reader-text) 12%, transparent)', background: 'var(--reader-bg)' }"
      >
        <button class="rounded-full p-2.5 active:opacity-60" aria-label="返回" @click="router.back()">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>
        </button>
        <div class="min-w-0 flex-1">
          <div class="truncate text-[13px] font-semibold">{{ derived?.title ?? '…' }}</div>
          <div class="truncate text-[11px]" :style="{ color: 'var(--reader-dim)' }">{{ meta?.title }}</div>
        </div>
        <button class="rounded-full p-2.5 active:opacity-60" aria-label="目录" @click="showChapters = true">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01"/></svg>
        </button>
        <button class="rounded-full p-2.5 active:opacity-60" aria-label="阅读样式" @click="showStyle = true">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 7V5h16v2M9 20h6M12 5v15"/></svg>
        </button>
        <button class="rounded-full p-2.5 active:opacity-60" aria-label="朗读设置" @click="showTts = true">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v18M7 8v8M17 8v8M2 11v2M22 11v2"/></svg>
        </button>
      </header>

      <!-- 正文 -->
      <div ref="scrollEl" class="no-scrollbar min-h-0 flex-1 overflow-y-auto">
        <div
          ref="bodyEl"
          class="reader-body mx-auto max-w-xl px-5 pb-40 pt-5"
          :style="bodyStyle"
          @click="onBodyClick"
        >
          <h1
            class="mb-6 text-center font-bold"
            :style="{ fontSize: settings.reader.fontSize + 3 + 'px' }"
          >{{ derived?.title }}</h1>
          <p v-for="(para, pi) in derived?.paras ?? []" :key="pi">
            <span
              v-for="sp in blocks[pi]"
              :key="sp.start"
              class="seg"
              :class="{
                'seg-active': activeStart >= sp.start && activeStart < sp.end,
                shimmer: synthesizing && activeStart >= sp.start && activeStart < sp.end
              }"
              :data-start="Math.max(sp.start, para.start)"
              :data-end="Math.min(sp.end, para.end)"
            >{{ fragText(text, sp, para) }}</span>
          </p>

          <!-- 章末导航 -->
          <div v-if="meta" class="mt-10 flex items-center gap-3 text-sm" :style="{ color: 'var(--reader-dim)' }">
            <button
              class="flex-1 rounded-xl border py-3 disabled:opacity-30"
              :style="{ borderColor: 'color-mix(in srgb, var(--reader-text) 18%, transparent)' }"
              :disabled="currentChapterIndex === 0"
              @click.stop="gotoChapter(currentChapterIndex - 1)"
            >上一章</button>
            <span class="text-xs tabular-nums">{{ currentChapterIndex + 1 }} / {{ meta.chapterCount }}</span>
            <button
              class="flex-1 rounded-xl border py-3 disabled:opacity-30"
              :style="{ borderColor: 'color-mix(in srgb, var(--reader-text) 18%, transparent)' }"
              :disabled="currentChapterIndex >= meta.chapterCount - 1"
              @click.stop="gotoChapter(currentChapterIndex + 1)"
            >下一章</button>
          </div>
        </div>
      </div>

      <!-- 底部悬浮播放坞 -->
      <div class="pointer-events-none fixed inset-x-0 bottom-0 z-30 px-4 pb-5 safe-bottom">
        <div class="pointer-events-auto mx-auto max-w-md">
          <!-- 睡眠定时选项（展开态） -->
          <div v-if="showSleep" class="glass mb-2 flex flex-wrap items-center justify-center gap-1.5 rounded-2xl px-3 py-2 shadow-2xl">
            <button
              v-for="o in SLEEP_OPTIONS"
              :key="o.label"
              class="rounded-full border px-2.5 py-1 text-[11px] transition-all"
              :class="player.sleepMode === o.mode
                ? 'border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]'
                : 'border-[var(--border)] text-[var(--text-2)]'"
              @click="pickSleep(o.mode)"
            >{{ o.label }}</button>
          </div>
          <div class="glass flex items-center gap-3 rounded-full py-2 pl-4 pr-2 shadow-2xl">
            <!-- 进度 -->
            <div class="flex h-10 min-w-0 flex-1 flex-col justify-center" @click="showChapters = true">
              <div class="truncate text-[12px] font-medium" :class="player.snap.retryNote ? 'text-amber-400' : ''">
                {{ player.snap.retryNote || (playing || player.snap.state === 'paused' ? (player.snap.chapterTitle || derived?.title) : '轻点正文任意字开始朗读') }}
              </div>
              <div class="mt-1.5 h-0.5 w-full overflow-hidden rounded-full bg-white/10">
                <div
                  class="h-full rounded-full transition-[width] duration-500"
                  style="background: var(--gradient)"
                  :style="{ width: (chapterProgress * 100).toFixed(1) + '%' }"
                />
              </div>
            </div>
            <!-- 上一章 -->
            <button
              class="shrink-0 rounded-full p-2 text-[var(--text-2)] active:scale-90"
              aria-label="上一章"
              :disabled="currentChapterIndex === 0"
              @click="gotoChapter(currentChapterIndex - 1)"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6 8.5 6V6z"/></svg>
            </button>
            <!-- 播放/暂停 -->
            <button
              class="relative flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-white transition-transform active:scale-90"
              :class="{ 'pulse-ring': playing }"
              style="background: var(--gradient)"
              aria-label="播放/暂停"
              @click="togglePlay"
            >
              <svg v-if="player.snap.state === 'loading' || synthesizing" class="animate-spin" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.2-8.56"/></svg>
              <svg v-else-if="playing" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h4v14H6zM14 5h4v14h-4z"/></svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l11-6.5z"/></svg>
            </button>
            <!-- 下一章 -->
            <button
              class="shrink-0 rounded-full p-2 text-[var(--text-2)] active:scale-90"
              aria-label="下一章"
              :disabled="!!meta && currentChapterIndex >= meta.chapterCount - 1"
              @click="gotoChapter(currentChapterIndex + 1)"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M16 6h2v12h-2zM6 18l8.5-6L6 6z"/></svg>
            </button>
            <!-- 睡眠定时：未激活显示月亮，激活显示剩余 mm:ss 或「本章」 -->
            <button
              class="shrink-0 rounded-full active:scale-90"
              :class="player.sleepMode === 'off'
                ? 'p-2 text-[var(--text-2)]'
                : 'px-1 py-1 text-[11px] font-bold tabular-nums text-[var(--accent)]'"
              aria-label="睡眠定时"
              @click="showSleep = !showSleep"
            >
              <svg v-if="player.sleepMode === 'off'" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>
              <template v-else>{{ sleepLabel }}</template>
            </button>
            <!-- 倍速 -->
            <button
              class="shrink-0 rounded-full px-2 py-1 text-[11px] font-bold tabular-nums text-[var(--text-2)] active:scale-90"
              @click="cycleRate"
            >{{ settings.tts.rate.toFixed(2).replace(/\.?0+$/, '') }}×</button>
          </div>
        </div>
      </div>

      <ChapterListSheet
        :open="showChapters"
        :book-id="bookId"
        :current="currentChapterIndex"
        @close="showChapters = false"
        @select="gotoChapter"
      />
      <ReaderStyleSheet :open="showStyle" @close="showStyle = false" />
      <TtsSettingsSheet :open="showTts" @close="showTts = false" />
    </template>
  </div>
</template>
