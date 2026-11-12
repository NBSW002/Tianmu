<script setup>
/* global defineProps */
import { computed } from 'vue'
import { donutArcs } from '@/trace/trace-donut.mjs'
const props = defineProps({ segments: { type: Array, default: () => [] }, value: String, unit: String, caption: String, label: String })
const arcs = computed(() => donutArcs(props.segments))
</script>
<template>
  <div class="trace-donut" role="img" :aria-label="label || `${caption} ${value} ${unit || ''}`">
    <svg class="donut-graphic" viewBox="0 0 100 100" aria-hidden="true">
      <circle v-for="(arc,index) in arcs" :key="index" cx="50" cy="50" r="42" pathLength="100" :stroke="arc.color" stroke-linecap="butt" :stroke-dasharray="arc.full ? undefined : `${arc.length} ${100 - arc.length}`" :stroke-dashoffset="arc.full ? undefined : -arc.start" />
    </svg>
    <div class="donut-center"><strong>{{ value }}<small v-if="unit"> {{ unit }}</small></strong><span>{{ caption }}</span></div>
  </div>
</template>
<style scoped>
.trace-donut{position:relative;width:112px;height:112px;display:grid;place-items:center;flex-shrink:0;margin:16px auto;}
.donut-graphic{position:absolute;inset:0;width:100%;height:100%;transform:rotate(-90deg);}
.donut-graphic circle{fill:none;stroke-width:9;}
.donut-center{position:relative;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;max-width:76px;}
strong{font:500 18px/1.2 var(--workbench-ui);color:var(--workbench-ink);font-variant-numeric:tabular-nums;white-space:nowrap;}
small,span{font:400 10px/1.4 var(--workbench-ui);color:var(--workbench-muted);}
</style>
