<script setup lang="ts">
defineProps<{ open: boolean; title?: string }>()
const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="open"
        class="fixed inset-0 z-40 bg-black/55 backdrop-blur-[2px]"
        @click="emit('close')"
      />
    </Transition>
    <Transition name="sheet">
      <div
        v-if="open"
        class="fixed inset-x-0 bottom-0 z-50 mx-auto flex max-h-[82dvh] w-full max-w-xl flex-col rounded-t-3xl border-t border-[var(--border)] bg-[var(--bg-2)] shadow-2xl"
        style="backdrop-filter: blur(28px) saturate(1.5)"
      >
        <div class="flex items-center justify-between px-5 pt-4 pb-2">
          <div class="mx-auto absolute left-1/2 top-2 h-1 w-9 -translate-x-1/2 rounded-full bg-white/15" />
          <h3 class="text-[15px] font-semibold tracking-wide">{{ title }}</h3>
          <button
            class="rounded-full p-1.5 text-[var(--text-2)] active:bg-white/10"
            @click="emit('close')"
            aria-label="关闭"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>
          </button>
        </div>
        <div class="no-scrollbar min-h-0 flex-1 overflow-y-auto px-5 pb-6 safe-bottom">
          <slot />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
