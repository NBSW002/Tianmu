<script setup>
/* global defineProps */
import { computed } from 'vue'
import TraceDonut from './TraceDonut.vue'
import { formatDuration } from '@/trace/trace-data.mjs'
const props = defineProps({ timing: { type: Object, default: null }, aggregate:Boolean, nonStreaming:Boolean })
const segments = computed(() => (props.timing?.first != null && props.timing?.remaining != null ? [
  { label:'首 Token · TTFT',value:props.timing.first,color:'var(--trace-wait)' },
  { label:'后续响应',value:props.timing.remaining,color:'var(--trace-clay)' },
] : []))
const display = computed(() => props.timing?.total == null ? '—' : formatDuration(props.timing.total))
const percentage = value => `${(value / props.timing.total * 100).toFixed(1)}%`
</script>
<template>
  <section class="response-card" aria-label="响应耗时">
    <header><h3>响应耗时</h3><span v-if="aggregate && timing">{{ timing.recorded }}/{{ timing.calls }} 次</span></header>
    <TraceDonut v-if="segments.length" :segments="segments" :value="display" :caption="aggregate ? '累计响应耗时' : '总响应耗时'" :label="segments.map(s => `${s.label} ${formatDuration(s.value)}`).join('，')" />
    <p v-else-if="nonStreaming && timing?.total != null" class="response-total">{{ display }}<small>响应耗时 · 非流式</small></p>
    <template v-else><TraceDonut :segments="[{value:1,color:'var(--workbench-border)'}]" value="—" caption="TTFT" label="该记录没有可用的首 Token 采样，不能回算 TTFT" /><p class="ttft-state">{{ nonStreaming || timing?.calls === 0 ? '非流式调用' : '历史采样不可用' }}</p></template>
    <dl v-if="!segments.length && timing?.first != null"><div><dt><i style="background:var(--trace-wait)" />首 Token 等待</dt><dd>{{ formatDuration(timing.first) }}</dd></div></dl>
    <dl v-if="segments.length"><div v-for="segment in segments" :key="segment.label"><dt><i :style="{ background:segment.color }" />{{ segment.label }}</dt><dd>{{ formatDuration(segment.value) }}<small>{{ percentage(segment.value) }}</small></dd></div></dl>
  </section>
</template>
<style scoped>
.response-card{min-width:0;padding:16px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}
.ttft-state{text-align:center;font-size:11px;color:var(--workbench-muted);margin:8px 0;}header{display:flex;justify-content:space-between;gap:8px;}header span{font-size:11px;color:var(--workbench-muted);}h3{margin:0;font-size:13px;font-weight:600;}dl{margin:0;}dl>div{display:flex;justify-content:space-between;gap:8px;padding:7px 0;font-size:12px;}dt{display:flex;align-items:center;gap:6px;color:var(--workbench-muted);}i{width:7px;height:7px;border-radius:2px;flex-shrink:0;}dd{margin:0;text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums;}dd small{display:block;font-size:11px;color:var(--workbench-muted);margin-top:2px;}.response-total{font-size:26px;font-weight:500;margin:24px 0;font-variant-numeric:tabular-nums;}.response-total small{display:block;font-size:12px;color:var(--workbench-muted);margin-top:4px;font-weight:400;}
</style>
