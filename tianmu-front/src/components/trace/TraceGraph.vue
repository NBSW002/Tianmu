<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, shallowRef, watch } from 'vue'
import { VueFlow, Handle, Position, MarkerType } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { formatDuration, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { cardTitle, cardIcon } from '@/trace/trace-presentation.mjs'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ trace: { type: Object, required: true }, graph: { type: Object, required: true }, expanded: { type: Set, required: true }, selectedId: String, mode: String })
const emit = defineEmits(['select', 'toggle'])
const instance = shallowRef(null)
const panel = shallowRef(null)
const nodes = computed(() => props.graph.nodes.map((node) => ({ ...node,
  selected: node.id === props.selectedId || node.data.memberIds?.includes(props.selectedId) })))
const edges = computed(() => props.graph.edges.map((edge) => ({ ...edge,
  type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed, color: '#cfc7d8', width: 14, height: 14 },
  style: { stroke: edge.relation === 'call' ? '#bd85a6' : '#d8d1df', strokeWidth: 1.3, strokeDasharray: edge.relation === 'call' ? undefined : '4 4' },
})))
const nodeIcon = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
const phaseNodes = computed(() => nodes.value.filter(n => n.id.startsWith('phase:') || n.id.startsWith('job:')))
function locate(node) {
  instance.value?.setViewport({ x: 28 - node.position.x, y: 32 - node.position.y, zoom: 1 }, { duration: 0 })
}
const fit = () => instance.value?.fitView({ padding: 0.16, maxZoom: 1.2, duration: 0 })
// A readable starting scale, even for thousands of spans. Resizing panes must
// preserve the user's zoom and pan instead of repeatedly fitting the whole run.
const resetView = () => instance.value?.setViewport({ x: 28, y: 32, zoom: 1 }, { duration: 0 })
function ready(flow) { instance.value = flow; resetView() }
function choose(node) { emit('select', node.data.id) }
watch(() => props.selectedId, async () => {
  await nextTick()
  const node = nodes.value.find((item) => item.selected)
  const flow = instance.value
  if (!node || !flow || props.mode !== 'graph') return
  const viewport = flow.getViewport()
  const x = node.position.x * viewport.zoom + viewport.x
  const y = node.position.y * viewport.zoom + viewport.y
  if (x < 0 || x + 304 * viewport.zoom > panel.value.clientWidth || y < 0 || y + 124 * viewport.zoom > panel.value.clientHeight) {
    flow.setViewport({ ...viewport, x: 28 - node.position.x * viewport.zoom, y: 32 - node.position.y * viewport.zoom }, { duration: 0 })
  }
})
</script>

<template>
  <section ref="panel" class="trace-graph-panel">
    <div v-if="mode === 'graph'" class="graph-titlebar">
      <span class="time-direction">阶段流程 →</span>
      <div class="graph-legend"><span><i class="model-dot" />模型</span><span><i class="tool-dot" />工具</span></div>
    </div>
    <nav v-if="mode === 'graph'" class="phase-navigation" aria-label="定位执行阶段"><button v-for="(node, index) in phaseNodes" :key="node.id" type="button" :class="{ active: node.selected }" @click="locate(node); emit('select', node.id)"><small>{{ String(index + 1).padStart(2, '0') }}</small>{{ cardTitle(node.data) }}<TraceIcon name="arrow" :size="12" /></button></nav>
    <div v-if="mode === 'graph'" class="graph-canvas">
      <VueFlow
        :key="trace.id" :nodes="nodes" :edges="edges" :min-zoom="0.15" :max-zoom="1.8"
        :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :elements-selectable="true" :nodes-focusable="true"
        :delete-key-code="null" :zoom-on-double-click="false" :fit-view-on-init="false" :pan-on-scroll="false"
        @pane-ready="ready" @node-click="({ node }) => choose(node)"
      >
        <template #node-trace="{ data, selected }">
          <div v-if="data.cardKind === 'phase'" class="phase-card" :class="{ 'is-selected': selected }">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <header><strong>{{ cardTitle(data) }}</strong><span>{{ formatDuration(data.durationMs) }}</span></header>
            <div class="phase-caption"><span>内部步骤 · 按开始时间 ↓</span><span>{{ data.children.length }} 项</span></div>
            <div class="phase-step-list nodrag nopan nowheel" @wheel.stop><button v-for="(step, index) in data.children" :key="step.id" type="button" class="phase-step nodrag nopan" :class="[{ active: selectedId === step.id, failed: step.failureIds?.length || ['FAILED','TIMED_OUT'].includes(step.status) }, `step-${step.cardKind}`]" @click.stop="emit('select', step.id)">
              <span class="step-number">{{ index + 1 }}</span><span class="step-content"><span class="step-title"><TraceIcon :name="cardIcon(step)" :size="14" />{{ cardTitle(step) }}<small v-if="step.turn > 0">第 {{ step.turn }} 轮</small></span><span class="step-result" :title="step.summary">{{ step.summary }}</span></span><span class="step-duration">{{ step.summaryOnly ? '汇总' : formatDuration(step.durationMs) }}</span>
            </button></div>
            <Handle type="source" :position="Position.Right" :connectable="false" />
          </div>
          <div v-else class="trace-node" :title="data.summary" :class="[{ 'is-selected': selected, 'is-stack': data.children?.length && !expanded.has(data.id), 'is-failed': ['FAILED','TIMED_OUT'].includes(data.status) || data.failureIds?.length }, `kind-${data.cardKind}`]" :data-testid="`trace-node-${data.id}`">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <div class="node-top"><span class="node-icon"><TraceIcon :name="cardIcon(data)" :size="16" /></span><span class="node-name">{{ cardTitle(data) }}</span><i class="node-status" :class="data.status.toLowerCase()" :title="statusLabel(data.status)" /></div>
            <div class="node-summary">{{ data.summary }}</div>
            <div class="node-bottom"><span>{{ data.model || (data.turn > 0 ? `第 ${data.turn} 轮` : data.summaryOnly ? '阶段汇总' : '') }}</span><b>{{ formatDuration(data.durationMs) }}</b></div>
            <button v-if="data.children?.length" type="button" class="cluster-toggle nodrag nopan" :aria-expanded="expanded.has(data.id)" :aria-label="`${expanded.has(data.id) ? '收起' : '展开'} ${cardTitle(data)}`" @click.stop="emit('toggle', data.id)">{{ expanded.has(data.id) ? '收起' : `${data.repeatCount} 步 · 展开` }}</button>
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
.graph-titlebar { display: flex; justify-content: space-between; gap: 12px; padding: 16px 20px; flex-shrink: 0; border-bottom: 1px solid #f0ecf3; background: #fff; }
.time-direction { font-size: 11px; color: #82748d; pointer-events: auto; }
.time-direction small { margin-left: 12px; color: #aaa0b2; font-size: 10px; }
.graph-legend { display: flex; gap: 14px; color: #8b8096; font-size: 10px; }
.graph-legend span { display: flex; align-items: center; gap: 5px; }
.graph-legend i { width: 5px; height: 5px; border-radius: 50%; }
.model-dot { background: #c48fb1; }.tool-dot { background: #8db6a6; }.stage-dot { background: #b6adbf; }
.phase-navigation { display: flex; gap: 6px; padding: 10px 16px; overflow-x: auto; flex-shrink: 0; background: #faf8fb; border-bottom: 1px solid #eee9f1; }
.phase-navigation button { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; border: 1px solid #e8e0eb; border-radius: 6px; background: white; color: #776680; padding: 6px 9px; font-size: 11px; cursor: pointer; }
.phase-navigation button.active { border-color: #c995b1; background: #fcf3f8; }
.phase-navigation small { color: #b2a1b7; font: 10px var(--trace-mono); }
.phase-navigation button:focus-visible, .phase-step:focus-visible { outline: 2px solid #bb739a; outline-offset: 2px; }
.phase-card { width: 304px; border: 1px solid #e4dce9; border-radius: 12px; background: #fff; padding: 16px 14px 10px; box-shadow: 0 3px 12px #49314f07; }
.phase-card.is-selected { border-color: #c78aab; }
.phase-step-list { max-height: 312px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: #dfd3e3 transparent; padding: 2px; }
.phase-card :deep(.vue-flow__handle) { top: 29px; }
.phase-card header { display: flex; align-items: center; justify-content: space-between; color: #58455f; }
.phase-card header strong { font-size: 15px; font-weight: 600; }
.phase-card header>span { font: 11px var(--trace-mono); color: #95859c; }
.phase-caption { display: flex; justify-content: space-between; color: #a092a7; font-size: 10px; margin: 8px 0 14px; }
.phase-step { position: relative; display: flex; align-items: center; width: 100%; height: 68px; margin: 0 0 10px; gap: 9px; padding: 9px; border: 1px solid #ece7ef; border-radius: 7px; background: #fcfbfd; color: #716079; text-align: left; cursor: pointer; }
.phase-step.active { border-color: #bf85a6; box-shadow: 0 0 0 2px #f5e6ef; }
.phase-step.failed { border-color: #d994a3; }
.step-number { align-self: flex-start; color: #b2a3b8; font: 9px/18px var(--trace-mono); }
.step-content { flex: 1; min-width: 0; }
.step-title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; }
.step-title small { font-weight: 400; font-size: 9px; color: #a18fa8; }
.step-result { display: block; margin-top: 5px; font-size: 11px; color: #928299; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.step-duration { color: #a08ca8; font: 9px var(--trace-mono); white-space: nowrap; }
.step-model { background: #fdf8fc; border-color: #eadbe7; }.step-model svg { color: #b379a0; }
.step-tool { background: #f7fbf9; border-color: #deebe3; }.step-tool svg { color: #78a18c; }
.graph-canvas { position: relative; flex: 1; min-height: 300px; background: radial-gradient(#e6e0eb .6px, transparent .6px) 0 0 / 20px 20px, #fdfcfe; }
.graph-canvas :deep(.vue-flow) { position: absolute; inset: 0; }
.trace-node { position: relative; width: 304px; height: 118px; background: #fff; border: 1px solid #e4dfe9; border-radius: 10px; padding: 12px; color: #62566e; cursor: pointer; box-shadow: 0 2px 7px #30203904; }
.trace-node.is-stack { box-shadow: 3px 4px 0 -1px #f8f3f8, 3px 4px 0 0 #e4d8e5, 6px 8px 0 -1px #fbf7fb, 6px 8px 0 0 #e9dfea; }
.cluster-toggle { position: absolute; right: 6px; top: -13px; border: 1px solid #dfcbdc; background: #fbf4f9; color: #a4658b; border-radius: 6px; padding: 2px 7px; font-size: 10px; cursor: pointer; }
.cluster-toggle:focus-visible { outline: 2px solid #c48bab; outline-offset: 2px; }
.node-time { position: absolute; left: 0; top: 130px; color: #a197ab; font: 10px var(--trace-mono); white-space: nowrap; }
.node-top { display: flex; align-items: center; gap: 7px; }
.node-icon { display: flex; color: #9b8ca7; }
.node-name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-status { width: 5px; height: 5px; border-radius: 50%; background: #b6adbf; margin-left: auto; flex-shrink: 0; }
.node-status.succeeded { background: #7aaf98; }.node-status.failed, .node-status.timed_out { background: #cb7c8e; }
.node-summary { margin-top: 9px; font-size: 12px; color: #74677f; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; color: #958a9e; font: 10px var(--trace-mono); }
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
