<template>
  <svg :width="size" :height="size" :viewBox="`0 0 40 40`" class="geo-avatar">
    <rect x="1" y="1" width="38" height="38" rx="11" fill="none"
          :stroke="color" stroke-opacity=".35" stroke-width="1.4" />
    <rect :x="9 + (seed % 3) * 2" :y="9 + (seed % 2) * 3" :width="8 + (seed % 4) * 3"
          :height="8 + (seed % 3) * 4" rx="2.5"
          :fill="color" fill-opacity=".55" />
    <circle :cx="27 - (seed % 3)" :cy="27 - (seed % 2) * 4" r="4.5"
            :fill="color" fill-opacity=".8" />
    <path v-if="seed % 2 === 0" d="M9 31 Q 20 24 31 31" fill="none"
          :stroke="color" stroke-opacity=".5" stroke-width="1.6" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  seed: { type: Number, default: 0 },
  colorKey: { type: String, default: 'tech' },
  size: { type: Number, default: 40 }
})

const palette = {
  product: 'var(--dept-product)',
  tech: 'var(--dept-tech)',
  design: 'var(--dept-design)',
  market: 'var(--dept-market)',
  ops: 'var(--dept-ops)'
}
const color = computed(() => palette[props.colorKey] || palette.tech)
const seed = computed(() => Math.abs(props.seed || 0))
</script>

<style scoped>
.geo-avatar { display: block; }
</style>
