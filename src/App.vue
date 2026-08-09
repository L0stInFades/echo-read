<script setup lang="ts">
import { registerSW } from 'virtual:pwa-register'
import { toasts } from './lib/toast'

registerSW({ immediate: true })
</script>

<template>
  <div class="app-aurora h-full">
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>

    <!-- 全局 Toast -->
    <div class="pointer-events-none fixed inset-x-0 top-0 z-[100] flex flex-col items-center gap-2 px-6 pt-14">
      <TransitionGroup name="fade">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="glass max-w-sm rounded-2xl px-4 py-2.5 text-center text-[13px] shadow-xl"
          :class="{
            'text-red-300': t.kind === 'error',
            'text-emerald-300': t.kind === 'success'
          }"
        >{{ t.text }}</div>
      </TransitionGroup>
    </div>
  </div>
</template>
