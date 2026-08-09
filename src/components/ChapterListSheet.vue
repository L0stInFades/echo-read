<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import BottomSheet from './BottomSheet.vue'
import { getChapterTitles } from '../lib/db'

const props = defineProps<{ open: boolean; bookId: string; current: number }>()
const emit = defineEmits<{ close: []; select: [index: number] }>()

const titles = ref<string[]>([])
const listEl = ref<HTMLElement>()

watch(
  () => props.open,
  async v => {
    if (!v) return
    if (!titles.value.length) titles.value = await getChapterTitles(props.bookId)
    await nextTick()
    const el = listEl.value?.querySelector('[data-current="true"]')
    el?.scrollIntoView({ block: 'center' })
  }
)
</script>

<template>
  <BottomSheet :open="open" title="目录" @close="emit('close')">
    <div ref="listEl" class="-mx-1">
      <button
        v-for="(t, i) in titles"
        :key="i"
        :data-current="i === current"
        class="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition-colors active:bg-white/5"
        :class="i === current ? 'font-semibold text-[var(--accent)]' : 'text-[var(--text)]'"
        @click="emit('select', i); emit('close')"
      >
        <span class="w-7 shrink-0 text-right text-[11px] tabular-nums text-[var(--text-3)]">{{ i + 1 }}</span>
        <span class="min-w-0 flex-1 truncate">{{ t }}</span>
        <svg v-if="i === current" width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
      </button>
    </div>
  </BottomSheet>
</template>
