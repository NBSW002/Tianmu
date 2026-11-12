<script setup>
/* global defineProps */
import { computed } from 'vue'
import TraceDonut from './TraceDonut.vue'
const props = defineProps({ selection: { type: Object, required: true }, showEmpty:Boolean, aggregate:Boolean, windowUsage:{type:Object,default:()=>({used:null,capacity:null})} })
const colors = {
  system_prompt:'var(--trace-clay)', tool_catalog:'var(--trace-charcoal)',
  skill_catalog:'var(--trace-skill)', agent_catalog:'var(--trace-agent)',
  execution_context:'var(--trace-execution)', focus:'var(--trace-sage)',
  memory_context:'var(--trace-memory)', environment_context:'var(--trace-environment)',
  reference_context:'var(--trace-reference)', current_context:'var(--trace-current)',
  runtime_state:'var(--trace-runtime)', output_contract:'var(--trace-output)',
}
const total = computed(() => props.selection.slots.reduce((sum, slot) => sum + (Number.isFinite(slot.bytes) && slot.bytes >= 0 ? slot.bytes : 0), 0))
const complete = computed(() => props.selection.slots.every(slot => Number.isFinite(slot.bytes) && slot.bytes >= 0))
const slots = computed(() => props.selection.slots.map(slot => ({ ...slot, color: colors[slot.name] || 'var(--trace-sand)', value:slot.bytes, share: complete.value && total.value > 0 ? slot.bytes / total.value * 100 : null })))
const compact = value => value == null ? '未记录' : value >= 1000 ? `${(value/1000).toFixed(1).replace(/\.0$/, '')}k` : String(value)
const windowSegments = computed(() => {
  const {used,capacity} = props.windowUsage
  if (used == null || capacity == null) return [{value:1,color:'var(--workbench-border)'}]
  return [{value:Math.min(used,capacity),color:'var(--trace-clay)'},{value:Math.max(0,capacity-used),color:'var(--workbench-border)'}]
})
const bytes = value => value == null ? '未记录' : `${new Intl.NumberFormat('zh-CN').format(value)} B`
</script>

<template>
  <section v-if="selection.visible || showEmpty" class="context-section" aria-label="上下文模块装配">
    <header><h3>{{ aggregate ? '上下文峰值' : '上下文窗口' }}</h3></header>
    <TraceDonut :segments="windowSegments" :value="compact(windowUsage.used)" :caption="`/ ${compact(windowUsage.capacity)}`" :label="`已用 ${compact(windowUsage.used)} Token，上下文窗口 ${compact(windowUsage.capacity)} Token`" />
    <p class="window-label">已用 / 上下文窗口</p>
    <dl class="context-slots">
      <div v-for="slot in slots" :key="slot.name">
        <dt><i :style="{ background: slot.color }" aria-hidden="true"></i>{{ slot.label }}</dt>
        <dd>{{ bytes(slot.bytes) }}<span v-if="slot.share != null">{{ slot.share.toFixed(1) }}%</span></dd>
      </div>
    </dl>
    <details v-if="selection.visible && !aggregate" class="context-protocol" aria-label="上下文技术信息">
      <summary>技术信息</summary>
      <dl><div><dt>结构版本</dt><dd>{{ selection.version || '未记录' }}</dd></div><div><dt>投影协议</dt><dd>{{ selection.projection || '未记录' }}</dd></div></dl>
    </details>
  </section>
</template>

<style scoped>
.context-section{margin:0;padding:16px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));color:var(--workbench-ink);font-size:12px;min-width:0;}
.window-label{text-align:center;color:var(--workbench-muted);font-size:11px;margin:-8px 0 16px;}
.context-empty{margin:24px 0;color:var(--workbench-muted);font-size:12px;}
.context-count{margin:6px 0 0;color:var(--workbench-muted);font-size:11px;}
header{display:flex;align-items:center;justify-content:space-between;gap:8px;}h3{margin:0;font-size:13px;font-weight:500;}header>span{font-size:11px;color:var(--workbench-muted);white-space:nowrap;}
.context-slots{margin:0;}.context-slots>div{display:flex;justify-content:space-between;align-items:baseline;gap:8px;padding:8px 0;border-bottom:1px solid var(--workbench-border-subtle);}
dt{min-width:0;overflow-wrap:anywhere;}.context-slots dt{display:flex;align-items:center;gap:6px;font-size:11px;}.context-slots i{width:7px;height:7px;border-radius:2px;flex-shrink:0;}
dd{margin:0;font-size:11px;font-variant-numeric:tabular-nums;}.context-slots dd{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:2px 8px;white-space:nowrap;}.context-slots dd span{width:39px;text-align:right;color:var(--workbench-muted);}
.context-protocol{margin-top:12px;font-size:11px;color:var(--workbench-muted);}summary{cursor:pointer;}summary:focus-visible{outline:2px solid var(--workbench-focus);outline-offset:3px;}.context-protocol dl{margin:8px 0 0;}.context-protocol dl>div{display:grid;grid-template-columns:64px minmax(0,1fr);gap:8px;padding:4px 0;}.context-protocol dd{overflow-wrap:anywhere;}
</style>
