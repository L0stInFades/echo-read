<script setup lang="ts">
import BottomSheet from './BottomSheet.vue'
import { useSettingsStore } from '../store/settings'
import type { ReaderTheme } from '../types'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const settings = useSettingsStore()
const reader = settings.reader

const themes: { id: ReaderTheme; label: string; bg: string; text: string }[] = [
  { id: 'dark', label: '暗夜', bg: '#0b0e14', text: '#c9cdd8' },
  { id: 'ink', label: '纯黑', bg: '#000', text: '#9aa0ae' },
  { id: 'light', label: '明亮', bg: '#f7f5f0', text: '#35322c' },
  { id: 'paper', label: '纸墨', bg: '#f2e8d5', text: '#4a3f2f' },
  { id: 'eye', label: '护眼', bg: '#dce8dd', text: '#2f4234' }
]
</script>

<template>
  <BottomSheet :open="open" title="阅读样式" @close="emit('close')">
    <div class="mb-5">
      <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">主题</div>
      <div class="grid grid-cols-5 gap-2">
        <button
          v-for="t in themes"
          :key="t.id"
          class="flex flex-col items-center gap-1.5"
          @click="reader.theme = t.id"
        >
          <span
            class="flex h-12 w-full items-center justify-center rounded-xl border text-sm font-bold transition-all"
            :class="reader.theme === t.id ? 'border-[var(--accent)] ring-2 ring-[var(--accent)]/40' : 'border-[var(--border)]'"
            :style="{ background: t.bg, color: t.text }"
          >文</span>
          <span class="text-[11px]" :class="reader.theme === t.id ? 'text-[var(--accent)]' : 'text-[var(--text-2)]'">{{ t.label }}</span>
        </button>
      </div>
    </div>

    <div class="mb-5">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-xs font-medium tracking-wider text-[var(--text-3)]">字号</span>
        <span class="text-xs font-semibold text-[var(--accent)]">{{ reader.fontSize }}px</span>
      </div>
      <input v-model.number="reader.fontSize" type="range" min="14" max="28" step="1" class="w-full accent-[var(--accent)]" />
    </div>

    <div class="mb-5">
      <div class="mb-2 flex items-center justify-between">
        <span class="text-xs font-medium tracking-wider text-[var(--text-3)]">行距</span>
        <span class="text-xs font-semibold text-[var(--accent)]">{{ reader.lineHeight.toFixed(1) }}</span>
      </div>
      <input v-model.number="reader.lineHeight" type="range" min="1.4" max="2.6" step="0.1" class="w-full accent-[var(--accent)]" />
    </div>

    <div class="mb-2">
      <div class="mb-2 text-xs font-medium tracking-wider text-[var(--text-3)]">字体</div>
      <div class="grid grid-cols-2 gap-2">
        <button
          class="rounded-xl border px-3 py-2.5 text-sm transition-all"
          :class="reader.fontFamily === 'serif' ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--border)]'"
          style="font-family: var(--font-serif)"
          @click="reader.fontFamily = 'serif'"
        >宋体 / 衬线</button>
        <button
          class="rounded-xl border px-3 py-2.5 text-sm transition-all"
          :class="reader.fontFamily === 'sans' ? 'border-[var(--accent)] bg-[var(--accent-soft)]' : 'border-[var(--border)]'"
          @click="reader.fontFamily = 'sans'"
        >黑体 / 无衬线</button>
      </div>
    </div>
  </BottomSheet>
</template>
