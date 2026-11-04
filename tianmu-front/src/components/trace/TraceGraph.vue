<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { VueFlow, Handle, Position, MarkerType } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { formatDuration, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { buildTraceGraph } from '@/trace/trace-layout.mjs'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ trace: { type: Object, required: true }, selectedId: String, mode: String })
const emit = defineEmits(['select'])
const instance = shallowRef(null)
const panel = shallowRef(null)
const expanded = ref(new Set())
const graph = computed(() => buildTraceGraph(props.trace.nodes, expanded.value))
const nodes = computed(() => graph.value.nodes.map((node) => ({ ...node,
  selected: node.id === props.selectedId || node.data.memberIds?.includes(props.selectedId) })))
const edges = computed(() => graph.value.edges.map((edge) => ({ ...edge,
  type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed, color: '#cfc7d8', width: 14, height: 14 },
  style: { stroke: '#d8d1df', strokeWidth: 1.3 },
})))
const nodeIcon = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
const fit = () => instance.value?.fitView({ padding: 0.16, maxZoom: 1.2, duration: 0 })
// A readable starting scale, even for thousands of spans. Resizing panes must
// preserve the user's zoom and pan instead of repeatedly fitting the whole run.
const resetView = () => instance.value?.setViewport({ x: 36, y: 88, zoom: 1 }, { duration: 0 })
function ready(flow) { instance.value = flow; resetView() }
async function toggleCluster(id) {
  const next = new Set(expanded.value)
  next.has(id) ? next.delete(id) : next.add(id)
  expanded.value = next
  await nextTick()
}
function choose(node) {
  if (node.data.clusterId) toggleCluster(node.data.clusterId)
  emit('select', node.data.id)
}
watch(() => props.trace.id, () => { expanded.value = new Set() })
watch(() => props.selectedId, async () => {
  await nextTick()
  const node = nodes.value.find((item) => item.selected)
  const flow = instance.value
  if (!node || !flow || props.mode !== 'graph') return
  const viewport = flow.getViewport()
  const x = node.position.x * viewport.zoom + viewport.x
  const y = node.position.y * viewport.zoom + viewport.y
  if (x < 0 || x + 184 * viewport.zoom > panel.value.clientWidth || y < 48 || y + 100 * viewport.zoom > panel.value.clientHeight) {
    flow.setViewport({ ...viewport, x: 36 - node.position.x * viewport.zoom, y: 88 - node.position.y * viewport.zoom }, { duration: 0 })
  }
})
</script>

<template>
  <section ref="panel" class="trace-graph-panel">
    <div v-if="mode === 'graph'" class="graph-titlebar">
      <span class="time-direction" title="按开始时间排列，间距不代表耗时；连线表示执行顺序，父子关系见节点详情">时间 →<small>上下并行</small></span>
      <div class="graph-legend"><span><i class="model-dot" />模型</span><span><i class="tool-dot" />工具</span><span><i class="stage-dot" />阶段</span></div>
    </div>
    <div v-if="mode === 'graph'" class="graph-canvas">
      <VueFlow
        :key="trace.id" :nodes="nodes" :edges="edges" :min-zoom="0.15" :max-zoom="1.8"
        :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :elements-selectable="true" :nodes-focusable="true"
        :delete-key-code="null" :zoom-on-double-click="false" :fit-view-on-init="false" :pan-on-scroll="false"
        @pane-ready="ready" @node-click="({ node }) => choose(node)"
      >
        <template #node-trace="{ data, selected }">
          <div class="trace-node" :title="data.clusterId ? `${nodeDisplayName(data)} · ${data.repeatCount} 次，点击展开` : data.label" :class="[{ 'is-selected': selected, 'is-stack': data.clusterId, 'is-failed': ['FAILED','TIMED_OUT'].includes(data.status) }, `kind-${data.kind.toLowerCase()}`]" :data-testid="`trace-node-${data.id}`">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <div class="node-top"><span class="node-icon"><TraceIcon :name="nodeIcon(data)" :size="16" /></span><span class="node-name">{{ nodeDisplayName(data) }}</span><i class="node-status" :class="data.status.toLowerCase()" :title="statusLabel(data.status)" /></div>
            <div class="node-bottom"><span>{{ data.tool || data.model || data.name }}</span><b>{{ formatDuration(data.durationMs) }}</b></div>
            <button v-if="data.clusterId || data.expandedCluster" type="button" class="cluster-toggle nodrag nopan" :aria-expanded="!data.clusterId" :aria-label="data.clusterId ? `展开 ${data.repeatCount} 次 ${nodeDisplayName(data)}` : `收起 ${nodeDisplayName(data)}`" @click.stop="toggleCluster(data.clusterId || data.expandedCluster)">{{ data.clusterId ? `${data.repeatCount} 次 · 展开` : '收起' }}</button>
            <span class="node-time">{{ data.offsetMs == null ? '时间未知' : `+${formatDuration(data.offsetMs)}` }}</span>
            <Handle type="source" :position="Position.Right" :connectable="false" />
          </div>
        </template>
      </VueFlow>
      <div class="graph-controls">
        <button type="button" aria-label="缩小调用图" title="缩小" @click="instance?.zoomOut({ duration: 0 })"><TraceIcon name="minus" :size="16" /></button>
        <button type="button" aria-label="放大调用图" title="放大" @click="instance?.zoomIn({ duration: 0 })"><TraceIcon name="plus" :size="16" /></button>
        <button type="button" aria-label="返回时间起点" title="返回时间起点" @click="resetView">起点</button>
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
.graph-titlebar { display: flex; justify-content: space-between; gap: 12px; padding: 16px 20px; position: absolute; left: 0; right: 0; top: 0; z-index: 2; pointer-events: none; background: linear-gradient(#fdfcfe 60%, #fdfcfe00); }
.time-direction { font-size: 11px; color: #82748d; pointer-events: auto; }
.time-direction small { margin-left: 12px; color: #aaa0b2; font-size: 10px; }
.graph-legend { display: flex; gap: 14px; color: #8b8096; font-size: 10px; }
.graph-legend span { display: flex; align-items: center; gap: 5px; }
.graph-legend i { width: 5px; height: 5px; border-radius: 50%; }
.model-dot { background: #c48fb1; }.tool-dot { background: #8db6a6; }.stage-dot { background: #b6adbf; }
.graph-canvas { position: relative; flex: 1; min-height: 300px; background: radial-gradient(#e6e0eb .6px, transparent .6px) 0 0 / 20px 20px, #fdfcfe; }
.graph-canvas :deep(.vue-flow) { position: absolute; inset: 0; }
.trace-node { position: relative; width: 184px; height: 68px; background: #fff; border: 1px solid #e4dfe9; border-radius: 10px; padding: 12px; color: #62566e; cursor: pointer; box-shadow: 0 2px 7px #30203904; }
.trace-node.is-stack { box-shadow: 3px 4px 0 -1px #f8f3f8, 3px 4px 0 0 #e4d8e5, 6px 8px 0 -1px #fbf7fb, 6px 8px 0 0 #e9dfea; }
.cluster-toggle { position: absolute; right: 6px; top: -13px; border: 1px solid #dfcbdc; background: #fbf4f9; color: #a4658b; border-radius: 6px; padding: 2px 7px; font-size: 10px; cursor: pointer; }
.cluster-toggle:focus-visible { outline: 2px solid #c48bab; outline-offset: 2px; }
.node-time { position: absolute; left: 0; top: 82px; color: #a197ab; font: 10px var(--trace-mono); white-space: nowrap; }
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
.trace-node.is-stack.is-selected { box-shadow: 3px 4px 0 -1px #f8eff6, 3px 4px 0 0 #c799b8, 6px 8px 0 -1px #fbf7fb, 6px 8px 0 0 #dfc4d7; }
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
