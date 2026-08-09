<script setup lang="ts">
import { computed } from 'vue'
import type { BookMeta } from '../types'
import { cyrb53 } from '../lib/hash'

const props = defineProps<{ book: BookMeta }>()

/** 无封面书籍（TXT）按书名哈希生成渐变封面 */
const gradient = computed(() => {
  const h = parseInt(cyrb53(props.book.title).slice(0, 8), 16)
  const hue1 = h % 360
  const hue2 = (hue1 + 48) % 360
  return `linear-gradient(150deg, hsl(${hue1} 62% 52%) 0%, hsl(${hue2} 70% 34%) 100%)`
})

const initial = computed(() => props.book.title.slice(0, 8))
</script>

<template>
  <div
    class="relative flex h-full w-full items-center justify-center overflow-hidden rounded-xl"
    :style="book.cover ? undefined : { background: gradient }"
  >
    <img
      v-if="book.cover"
      :src="book.cover"
      :alt="book.title"
      class="h-full w-full object-cover"
      loading="lazy"
    />
    <template v-else>
      <div class="absolute inset-0 bg-gradient-to-t from-black/35 via-transparent to-white/10" />
      <div class="px-3 text-center font-serif text-[15px] font-bold leading-snug text-white/95" style="text-shadow: 0 1px 6px rgba(0,0,0,.4)">
        {{ initial }}
      </div>
      <div class="absolute bottom-1.5 right-2 text-[9px] uppercase tracking-widest text-white/60">
        {{ book.format }}
      </div>
    </template>
  </div>
</template>
