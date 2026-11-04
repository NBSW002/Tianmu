<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, onMounted, onBeforeUnmount, shallowRef, watch } from 'vue'
import { VueFlow, Handle, Position, MarkerType } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { layoutTrace, formatDuration, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ trace: { type: Object, required: true }, selectedId: String, mode: String })
const emit = defineEmits(['select'])
const instance = shallowRef(null)
const panel = shallowRef(null)
let resizeObserver, resizeFrame
const nodes = computed(() => layoutTrace(props.trace.nodes).map((node) => ({ ...node, selected: node.id === props.selectedId })))
const edges = computed(() => {
  const ids = new Set(props.trace.nodes.map((node) => node.id))
  const ancestors = new Set()
  let cursor = props.trace.nodes.find((node) => node.id === props.selectedId)
  while (cursor && ids.has(cursor.parentId)) {
    ancestors.add(`${cursor.parentId}-${cursor.id}`)
    cursor = props.trace.nodes.find((node) => node.id === cursor.parentId)
  }
  return props.trace.nodes.filter((node) => ids.has(node.parentId)).map((node) => ({
    id: `${node.parentId}-${node.id}`, source: node.parentId, target: node.id,
    type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed, color: ancestors.has(`${node.parentId}-${node.id}`) ? '#c48bab' : '#cfc7d8', width: 14, height: 14 },
    style: { stroke: ancestors.has(`${node.parentId}-${node.id}`) ? '#c48bab' : '#d8d1df', strokeWidth: ancestors.has(`${node.parentId}-${node.id}`) ? 1.8 : 1.3 },
  }))
})
const nodeIcon = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
const fit = () => instance.value?.fitView({ padding: 0.16, maxZoom: 1.2, duration: 0 })
function ready(flow) { instance.value = flow; fit() }
watch(() => props.trace.id, async () => { await nextTick(); fit() })
watch(() => props.mode, async () => { await nextTick(); fit() })
onMounted(() => {
  resizeObserver = new window.ResizeObserver(() => {
    window.cancelAnimationFrame(resizeFrame)
    resizeFrame = window.requestAnimationFrame(fit)
  })
  resizeObserver.observe(panel.value)
})
onBeforeUnmount(() => { resizeObserver?.disconnect(); window.cancelAnimationFrame(resizeFrame) })
</script>

<template>
  <section ref="panel" class="trace-graph-panel">
    <div v-if="mode === 'graph'" class="graph-titlebar">
      <div class="graph-legend"><span><i class="model-dot" />模型</span><span><i class="tool-dot" />工具</span><span><i class="stage-dot" />阶段</span></div>
    </div>
    <div v-if="mode === 'graph'" class="graph-canvas">
      <VueFlow
        :key="trace.id" :nodes="nodes" :edges="edges" :min-zoom="0.15" :max-zoom="1.8"
        :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :elements-selectable="true" :nodes-focusable="true"
        :delete-key-code="null" :zoom-on-double-click="false" :fit-view-on-init="false" :pan-on-scroll="false"
        @pane-ready="ready" @nodes-initialized="fit" @node-click="({ node }) => emit('select', node.id)"
      >
        <template #node-trace="{ data, selected }">
          <div class="trace-node" :title="data.label" :class="[{ 'is-selected': selected, 'is-failed': ['FAILED','TIMED_OUT'].includes(data.status) }, `kind-${data.kind.toLowerCase()}`]" :data-testid="`trace-node-${data.id}`">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <div class="node-top"><span class="node-icon"><TraceIcon :name="nodeIcon(data)" :size="16" /></span><span class="node-name">{{ nodeDisplayName(data) }}</span><i class="node-status" :class="data.status.toLowerCase()" :title="statusLabel(data.status)" /></div>
            <div class="node-bottom"><span>{{ data.tool || data.model || data.name }}</span><b>{{ formatDuration(data.durationMs) }}</b></div>
            <Handle type="source" :position="Position.Right" :connectable="false" />
          </div>
        </template>
      </VueFlow>
      <div class="graph-controls">
        <button type="button" aria-label="缩小调用图" title="缩小" @click="instance?.zoomOut({ duration: 0 })"><TraceIcon name="minus" :size="16" /></button>
        <button type="button" aria-label="放大调用图" title="放大" @click="instance?.zoomIn({ duration: 0 })"><TraceIcon name="plus" :size="16" /></button>
        <span />
        <button type="button" aria-label="适应画布" title="适应画布" @click="fit"><TraceIcon name="fit" :size="16" /></button>
      </div>
    </div>
    <div v-else class="node-table-wrap">
      <table class="node-table">
        <thead><tr><th>节点 / 类型</th><th>耗时</th><th>状态</th></tr></thead>
        <tbody><tr v-for="node in trace.nodes" :key="node.id" :class="{ active: node.id === selectedId }"><td><button type="button" @click="emit('select', node.id)"><TraceIcon :name="nodeIcon(node)" :size="15" /><span>{{ nodeDisplayName(node) }}<small>{{ node.tool || node.model || node.name }}</small></span></button></td><td>{{ formatDuration(node.durationMs) }}</td><td :class="`status-${node.status.toLowerCase()}`">{{ statusLabel(node.status) }}</td></tr></tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.trace-graph-panel { display: flex; flex-direction: column; flex: 1; min-height: 0; min-width: 0; position: relative; }
.graph-titlebar { display: flex; justify-content: flex-end; padding: 16px 20px; position: absolute; right: 0; top: 0; z-index: 2; pointer-events: none; }
.graph-legend { display: flex; gap: 14px; color: #8b8096; font-size: 10px; }
.graph-legend span { display: flex; align-items: center; gap: 5px; }
.graph-legend i { width: 5px; height: 5px; border-radius: 50%; }
.model-dot { background: #c48fb1; }.tool-dot { background: #8db6a6; }.stage-dot { background: #b6adbf; }
.graph-canvas { position: relative; flex: 1; min-height: 300px; background: radial-gradient(#e6e0eb .6px, transparent .6px) 0 0 / 20px 20px, #fdfcfe; }
.graph-canvas :deep(.vue-flow) { position: absolute; inset: 0; }
.trace-node { width: 184px; height: 68px; background: #fff; border: 1px solid #e4dfe9; border-radius: 10px; padding: 12px; color: #62566e; cursor: pointer; box-shadow: 0 2px 7px #30203904; }
.node-top { display: flex; align-items: center; gap: 7px; }
.node-icon { display: flex; color: #9b8ca7; }
.node-name { font-size: 12px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-status { width: 5px; height: 5px; border-radius: 50%; background: #b6adbf; margin-left: auto; flex-shrink: 0; }
.node-status.succeeded { background: #7aaf98; }.node-status.failed, .node-status.timed_out { background: #cb7c8e; }
.node-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; color: #958a9e; font: 9px var(--trace-mono); }
.node-bottom span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-bottom b { color: #81738f; font-weight: 400; white-space: nowrap; }
.kind-model { border-color: #e4d3e0; background: #fdf9fc; }.kind-model .node-icon { color: #b17b9e; }
.kind-tool { border-color: #d8e6de; background: #fbfdfc; }.kind-tool .node-icon { color: #7caa95; }
.trace-node.is-failed { border-color: #e7bcc7; background: #fff8fa; }
.trace-node:hover { border-color: #c9aec5; }
.trace-node.is-selected { border-color: #bb739a; background: #fcf5fa; box-shadow: 0 0 0 3px #d5acc22b, 0 4px 14px #58324b0a; }
.trace-node :deep(.vue-flow__handle) { width: 5px; height: 5px; min-width: 5px; min-height: 5px; background: #c9bdcf; border: 2px solid #fff; }
.trace-node.is-selected :deep(.vue-flow__handle) { background: #c284aa; }
.graph-canvas :deep(.vue-flow__node:focus-visible) { outline: 3px solid #e8b0c4; outline-offset: 4px; border-radius: 10px; }
.graph-controls { position: absolute; bottom: 16px; right: 16px; display: flex; align-items: center; gap: 2px; padding: 4px; background: #fff; border: 1px solid #e8e1ec; border-radius: 9px; }
.graph-controls button { display: flex; padding: 7px; border: 0; background: none; color: #9a819d; cursor: pointer; border-radius: 5px; }
.graph-controls button:hover { background: #f7eff6; }.graph-controls button:focus-visible { outline: 2px solid #d9a5c1; }
.graph-controls>span { height: 15px; width: 1px; background: #e8e1ec; margin: 0 3px; }
.node-table-wrap { overflow: auto; flex: 1; padding: 20px 16px; }
.node-table { border-collapse: collapse; width: 100%; font-size: 11px; }
.node-table th { text-align: left; padding: 12px 8px; font-weight: 400; color: #92859e; }
.node-table td { border-top: 1px solid #eeedf2; padding: 10px 8px; color: #7d7089; }
.node-table tr.active { background: #fcf3f9; }
.node-table button { display: flex; align-items: center; gap: 10px; border: 0; background: none; padding: 4px 0; color: #766380; text-align: left; cursor: pointer; font: 11px var(--trace-mono); }
.node-table button:focus-visible { outline: 2px solid #d9a5c1; outline-offset: 3px; }
.node-table small { display: block; color: #96899f; font-size: 10px; margin-top: 5px; }
.node-table .status-failed, .node-table .status-timed_out { color: #bc6f85; }
@media(max-width:700px) { .graph-legend { gap: 10px; }.graph-titlebar { padding: 14px; } }
</style>
