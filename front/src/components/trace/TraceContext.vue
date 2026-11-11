<script setup>
/* global defineProps */
import TraceIcon from './TraceIcon.vue'
defineProps({ selection: { type: Object, required: true } })
const bytes = value => value == null ? '未记录' : `${new Intl.NumberFormat('zh-CN').format(value)} B`
</script>

<template>
  <section v-if="selection.visible" class="context-section" aria-label="上下文模块装配">
    <header><h3>上下文结构</h3><span>{{ selection.slots.length }} 个模块</span></header>
    <dl class="context-slots">
      <div v-for="(slot, index) in selection.slots" :key="slot.name">
        <dt><span class="slot-index" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>{{ slot.label }}</dt>
        <dd>{{ bytes(slot.bytes) }}</dd>
      </div>
    </dl>
    <p class="context-note"><TraceIcon name="info" :size="13" />仅展示本轮实际装配的模块。</p>
    <section class="context-protocol" aria-label="上下文技术信息">
      <h4>技术信息</h4>
      <dl><div><dt>结构版本</dt><dd>{{ selection.version || '未记录' }}</dd></div><div><dt>投影协议</dt><dd>{{ selection.projection || '未记录' }}</dd></div></dl>
    </section>
  </section>
</template>

<style scoped>
.context-section { margin: 24px 0; color: var(--workbench-ink); font-size: 12px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
h3 { margin: 0; font-size: 13px; font-weight: 500; }
header > span { color: var(--workbench-muted); font-size: 11px; white-space: nowrap; }
.context-slots { margin: 0; padding: 4px 12px; border: 1px solid var(--workbench-border); border-radius: 10px; background: var(--workbench-paper); }
.context-slots > div { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; padding: 8px 0; }
.context-slots > div + div { border-top: 1px solid var(--workbench-border-subtle); }
dt { min-width: 0; overflow-wrap: anywhere; }
.slot-index { display: inline-block; width: 24px; color: var(--workbench-muted); font: 10px var(--workbench-mono); }
dd { margin: 0; color: var(--workbench-muted); font: 11px/1.6 var(--workbench-mono); font-variant-numeric: tabular-nums; }
.context-slots dd { flex-shrink: 0; white-space: nowrap; }
.context-note { display: flex; align-items: flex-start; gap: 6px; margin: 9px 0; color: var(--workbench-muted); font-size: 11px; line-height: 1.7; }
.context-note svg { flex-shrink: 0; margin-top: 2px; }
.context-protocol { color: var(--workbench-muted); font-size: 11px; }
summary { cursor: pointer; padding: 3px 0; }
summary:focus-visible { outline: 2px solid var(--workbench-focus); outline-offset: 3px; }
.context-protocol dl { margin: 6px 0 0; }
.context-protocol dl > div { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 10px; padding: 4px 0; }
.context-protocol dd { overflow-wrap: anywhere; }
.context-section {margin:0 0 14px;padding:12px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--workbench-raised);}
.context-slots {padding:0;border:0;border-radius:0;background:transparent;}
.context-protocol {margin-top:10px;padding-top:10px;border-top:1px solid var(--workbench-border-subtle);}
.context-protocol h4 {margin:0;font-size:11px;font-weight:500;}
</style>
