<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useLibraryStore } from '../store/library'
import { useSettingsStore } from '../store/settings'
import { SAMPLE_BOOK_NAME, SAMPLE_BOOK_TEXT } from '../lib/sample'
import BookCover from '../components/BookCover.vue'
import TtsSettingsSheet from '../components/TtsSettingsSheet.vue'
import BottomSheet from '../components/BottomSheet.vue'
import type { BookMeta } from '../types'
import { toast } from '../lib/toast'

const router = useRouter()
const route = useRoute()
const library = useLibraryStore()
const settings = useSettingsStore()

const fileInput = ref<HTMLInputElement>()
const showSettings = ref(false)
const actionBook = ref<BookMeta | null>(null)

onMounted(async () => {
  await library.refresh()
  await maybeEnterDemo()
})

// ?demo=1：自动导入示例书并打开（首次体验/截图验证）。
// hash 路由仅 hash 变化时不重载页面，onMounted 之外还要监听 query 变化
let demoBusy = false
async function maybeEnterDemo() {
  if (route.query.demo !== '1' || demoBusy) return
  demoBusy = true
  const meta = await importSample()
  demoBusy = false
  if (meta) router.replace({ name: 'reader', params: { bookId: meta.id } })
}
watch(() => route.query.demo, () => void maybeEnterDemo())

async function importSample(): Promise<BookMeta | null> {
  const exist = library.books.find(b => b.title === SAMPLE_BOOK_NAME)
  if (exist) return exist
  const file = new File([SAMPLE_BOOK_TEXT], `${SAMPLE_BOOK_NAME}.txt`, { type: 'text/plain' })
  return library.importFile(file)
}

function pickFiles() {
  fileInput.value?.click()
}

async function onFiles(e: Event) {
  const files = Array.from((e.target as HTMLInputElement).files ?? [])
  ;(e.target as HTMLInputElement).value = ''
  for (const f of files) {
    const meta = await library.importFile(f)
    if (meta) {
      toast(`《${meta.title}》导入成功，共 ${meta.chapterCount} 章`, 'success')
    } else if (library.importError) {
      toast(library.importError, 'error', 4000)
    }
  }
}

function openBook(b: BookMeta) {
  router.push({ name: 'reader', params: { bookId: b.id } })
}

/* 长按弹出操作菜单 */
let pressTimer: ReturnType<typeof setTimeout> | null = null
/** 长按成功时刻:触屏抬手后浏览器按 touchend 坐标补发合成 click,
    命中的是刚出现的遮罩会把菜单瞬间关掉,须在 document 捕获阶段吞掉这一次 */
let longPressAt = 0
function swallowGhostClick(e: Event) {
  if (Date.now() - longPressAt < 700) {
    e.preventDefault()
    e.stopPropagation()
  }
  longPressAt = 0
}
function pressStart(b: BookMeta) {
  pressTimer = setTimeout(() => {
    pressTimer = null
    longPressAt = Date.now()
    document.addEventListener('click', swallowGhostClick, { capture: true, once: true })
    actionBook.value = b
  }, 550)
}
function pressEnd() {
  if (pressTimer) {
    clearTimeout(pressTimer)
    pressTimer = null
  } else if (longPressAt) {
    // 合成 click 跟随抬手而非定时器触发,时效窗须从抬手时刻重新起算(慢松手同样要吞)
    longPressAt = Date.now()
  }
}

async function removeBook() {
  if (!actionBook.value) return
  await library.remove(actionBook.value.id)
  toast('已从书架移除', 'success')
  actionBook.value = null
}

function progressText(b: BookMeta) {
  // 单章书没有章节维度,以章内偏移计进度
  if (b.chapterCount <= 1) {
    const p = Math.min(100, (b.progress.offset / Math.max(b.totalChars, 1)) * 100)
    return b.progress.offset === 0 ? '未开始' : `已读 ${p.toFixed(0)}%`
  }
  const p = (b.progress.chapterIndex / Math.max(b.chapterCount - 1, 1)) * 100
  return b.progress.chapterIndex === 0 && b.progress.offset === 0 ? '未开始' : `已读 ${p.toFixed(0)}%`
}

function formatChars(n: number) {
  return n >= 10000 ? (n / 10000).toFixed(1) + ' 万字' : n + ' 字'
}
</script>

<template>
  <div class="no-scrollbar h-full overflow-y-auto">
    <!-- 顶栏 -->
    <header class="safe-top sticky top-0 z-20 border-b border-[var(--border)] bg-[var(--bg)]/80 px-5 pb-3 pt-3" style="backdrop-filter: blur(20px)">
      <div class="mx-auto flex max-w-3xl items-center justify-between">
        <div>
          <h1 class="gradient-text text-xl font-black tracking-wide">EchoRead</h1>
          <p class="mt-0.5 text-[11px] tracking-[.2em] text-[var(--text-3)]">AI 听书 · 声临其境</p>
        </div>
        <div class="flex items-center gap-2">
          <button
            class="glass flex h-10 w-10 items-center justify-center rounded-full text-[var(--text-2)] active:scale-95"
            aria-label="朗读设置"
            @click="showSettings = true"
          >
            <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
          </button>
          <button
            class="flex h-10 items-center gap-1.5 rounded-full px-4 text-sm font-semibold text-white active:scale-95"
            style="background: var(--gradient)"
            @click="pickFiles"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
            导入
          </button>
        </div>
      </div>
    </header>

    <main class="mx-auto max-w-3xl px-5 pb-24 pt-4">
      <!-- API Key 提示 -->
      <button
        v-if="settings.tts.provider === 'openai-speech' && !settings.tts.openai.apiKey"
        class="glass mb-4 flex w-full items-center gap-3 rounded-2xl px-4 py-3 text-left"
        @click="showSettings = true"
      >
        <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-white" style="background: var(--gradient)">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>
        </span>
        <span class="min-w-0 flex-1">
          <span class="block text-sm font-semibold">配置 API Key，开启 AI 朗读</span>
          <span class="mt-0.5 block truncate text-xs text-[var(--text-2)]">支持 OpenRouter / OpenAI 兼容语音接口</span>
        </span>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" class="shrink-0 text-[var(--text-3)]"><path d="m9 18 6-6-6-6"/></svg>
      </button>

      <!-- 空书架 -->
      <div v-if="!library.books.length" class="flex flex-col items-center pt-24 text-center">
        <div class="glass flex h-24 w-24 items-center justify-center rounded-3xl">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="url(#empty-g)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <defs><linearGradient id="empty-g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#7c9bff"/><stop offset="1" stop-color="#ff7cb8"/></linearGradient></defs>
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
          </svg>
        </div>
        <h2 class="mt-5 text-lg font-bold">书架还是空的</h2>
        <p class="mt-1.5 max-w-60 text-sm leading-relaxed text-[var(--text-2)]">导入 TXT 或 EPUB 书籍，轻点任意文字，AI 便从那里开始为你朗读</p>
        <button class="mt-6 rounded-full px-6 py-3 text-sm font-semibold text-white active:scale-95" style="background: var(--gradient)" @click="pickFiles">
          导入第一本书
        </button>
        <button
          class="mt-3 rounded-full border border-[var(--border)] px-6 py-2.5 text-[13px] text-[var(--text-2)] active:bg-white/5"
          @click="importSample().then(m => m && openBook(m))"
        >
          没有书？先听示例 →
        </button>
      </div>

      <!-- 书籍网格 -->
      <div v-else class="grid grid-cols-3 gap-x-4 gap-y-6 sm:grid-cols-4 md:grid-cols-5">
        <div
          v-for="b in library.books"
          :key="b.id"
          class="group select-none"
          @click="openBook(b)"
          @touchstart="pressStart(b)"
          @touchend="pressEnd"
          @touchmove="pressEnd"
          @mousedown="pressStart(b)"
          @mouseup="pressEnd"
          @mouseleave="pressEnd"
          @contextmenu.prevent
        >
          <div class="aspect-[2/3] w-full transition-transform active:scale-95">
            <BookCover :book="b" />
          </div>
          <div class="mt-2 truncate text-[13px] font-medium">{{ b.title }}</div>
          <div class="mt-0.5 truncate text-[11px] text-[var(--text-3)]">
            {{ progressText(b) }} · {{ formatChars(b.totalChars) }}
          </div>
        </div>
      </div>
    </main>

    <!-- 导入中遮罩 -->
    <Transition name="fade">
      <div v-if="library.importing" class="fixed inset-0 z-50 flex flex-col items-center justify-center bg-black/60" style="backdrop-filter: blur(6px)">
        <div class="h-10 w-10 animate-spin rounded-full border-2 border-white/20 border-t-[var(--accent)]" />
        <p class="mt-4 text-sm text-[var(--text-2)]">正在解析书籍…</p>
      </div>
    </Transition>

    <input ref="fileInput" type="file" accept=".txt,.epub" multiple class="hidden" @change="onFiles" />

    <TtsSettingsSheet :open="showSettings" @close="showSettings = false" />

    <!-- 长按操作菜单 -->
    <BottomSheet :open="!!actionBook" :title="actionBook?.title ?? ''" @close="actionBook = null">
      <div class="space-y-2">
        <button class="w-full rounded-xl bg-[var(--surface)] px-4 py-3.5 text-left text-sm active:bg-[var(--surface-2)]" @click="actionBook && openBook(actionBook); actionBook = null">
          继续阅读
        </button>
        <button class="w-full rounded-xl bg-red-500/10 px-4 py-3.5 text-left text-sm text-red-400 active:bg-red-500/20" @click="removeBook">
          从书架删除
        </button>
      </div>
    </BottomSheet>
  </div>
</template>
