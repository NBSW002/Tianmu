<script setup>
/* global defineProps */
import { computed } from 'vue'
import TraceDonut from './TraceDonut.vue'
const props = defineProps({ selection: { type: Object, required: true }, showEmpty:Boolean })
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
const compactTotal = computed(() => total.value >= 1000000 ? {value:(total.value/1000000).toFixed(2),unit:'MB'} : total.value >= 1000 ? {value:(total.value/1000).toFixed(2),unit:'KB'} : {value:String(total.value),unit:'B'})
const bytes = value => value == null ? '未记录' : `${new Intl.NumberFormat('zh-CN').format(value)} B`
</script>

<template>
  <section v-if="selection.visible || showEmpty" class="context-section" aria-label="上下文模块装配">
    <header><h3>上下文构成</h3><span v-if="selection.visible">{{ selection.slots.length }} 个模块</span></header>
    <p v-if="!selection.visible" class="context-empty">未记录</p>
    <TraceDonut v-if="complete && total > 0" :segments="slots" :value="compactTotal.value" :unit="compactTotal.unit" caption="上下文大小" :label="`上下文 ${bytes(total)}；${slots.map(slot => `${slot.label} ${slot.share.toFixed(1)}%`).join('；')}`" />
    <dl class="context-slots">
      <div v-for="slot in slots" :key="slot.name">
        <dt><i :style="{ background: slot.color }" aria-hidden="true"></i>{{ slot.label }}</dt>
        <dd>{{ bytes(slot.bytes) }}<span v-if="slot.share != null">{{ slot.share.toFixed(1) }}%</span></dd>
      </div>
    </dl>
    <details v-if="selection.visible" class="context-protocol" aria-label="上下文技术信息">
      <summary>技术信息</summary>
      <dl><div><dt>结构版本</dt><dd>{{ selection.version || '未记录' }}</dd></div><div><dt>投影协议</dt><dd>{{ selection.projection || '未记录' }}</dd></div></dl>
    </details>
  </section>
</template>

<style scoped>
.context-section{margin:0;padding:16px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));color:var(--workbench-ink);font-size:12px;min-width:0;}
.context-empty{margin:24px 0;color:var(--workbench-muted);font-size:12px;}
header{display:flex;align-items:center;justify-content:space-between;gap:8px;}h3{margin:0;font-size:13px;font-weight:500;}header>span{font-size:11px;color:var(--workbench-muted);white-space:nowrap;}
.context-slots{margin:0;}.context-slots>div{display:flex;justify-content:space-between;align-items:baseline;gap:8px;padding:8px 0;border-bottom:1px solid var(--workbench-border-subtle);}
dt{min-width:0;overflow-wrap:anywhere;}.context-slots dt{display:flex;align-items:center;gap:6px;font-size:11px;}.context-slots i{width:7px;height:7px;border-radius:2px;flex-shrink:0;}
dd{margin:0;font-size:11px;font-variant-numeric:tabular-nums;}.context-slots dd{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:2px 8px;white-space:nowrap;}.context-slots dd span{width:39px;text-align:right;color:var(--workbench-muted);}
.context-protocol{margin-top:12px;font-size:11px;color:var(--workbench-muted);}summary{cursor:pointer;}summary:focus-visible{outline:2px solid var(--workbench-focus);outline-offset:3px;}.context-protocol dl{margin:8px 0 0;}.context-protocol dl>div{display:grid;grid-template-columns:64px minmax(0,1fr);gap:8px;padding:4px 0;}.context-protocol dd{overflow-wrap:anywhere;}
</style>
