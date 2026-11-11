<script setup>
/* global defineProps, defineEmits */
import { computed, nextTick, shallowRef, watch } from 'vue'
import { VueFlow, Handle, BaseEdge, Position, MarkerType } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { formatDuration, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { cardTitle, cardHighlight } from '@/trace/trace-presentation.mjs'
import TraceIcon from './TraceIcon.vue'

const props = defineProps({ trace: { type: Object, required: true }, graph: { type: Object, required: true }, expanded: { type: Set, required: true }, selectedId: String, mode: String })
const emit = defineEmits(['select', 'toggle'])
const instance = shallowRef(null)
const panel = shallowRef(null)
const nodes = computed(() => props.graph.nodes.map((node) => ({ ...node,
  selected: node.id === props.selectedId || node.data.memberIds?.includes(props.selectedId) })))
const edges = computed(() => props.graph.edges.map((edge) => ({ ...edge,
  type: 'orthogonal', markerEnd: { type: MarkerType.ArrowClosed, color: 'var(--workbench-connector)', width: 14, height: 14 },
  style: { stroke: 'var(--workbench-connector)', strokeWidth: 1.3, strokeDasharray: edge.relation === 'dependency' ? '5 4' : edge.relation === 'trigger' ? '2 4' : edge.relation === 'ancestor' ? '8 3 2 3' : undefined },
})))
function edgePath({ sourceX, sourceY, targetX, targetY }) {
  if (sourceY === targetY) return `M ${sourceX} ${sourceY} L ${targetX} ${targetY}`
  const middleX = (sourceX + targetX) / 2
  return `M ${sourceX} ${sourceY} H ${middleX} V ${targetY} H ${targetX}`
}
const nodeIcon = (node) => node.kind === 'MODEL' ? 'model' : node.kind === 'TOOL' ? 'tool' : 'stage'
let fitTicket = 0
async function fit(readable = true) {
  const flow = instance.value, ticket = ++fitTicket
  if (!flow) return
  await flow.fitView({ padding: 0.05, maxZoom: 1, duration: 0 })
  if (ticket !== fitTicket || flow !== instance.value) return
  const viewport = flow.getViewport(), canvas = panel.value?.querySelector('.graph-canvas')
  if (!canvas || !nodes.value.length) return
  if (readable !== false && viewport.zoom < 0.8) {
    const ys = nodes.value.map(node => node.position.y)
    flow.setViewport({ x: 28, y: canvas.clientHeight * 0.38 - (Math.min(...ys) + Math.max(...ys) + 56) / 2, zoom: 1 }, { duration: 0 })
    return
  }
  const top = Math.min(...nodes.value.map(node => node.position.y * viewport.zoom + viewport.y))
  // Place the complete flow slightly above the canvas centre, leaving room
  // towards the timeline without clipping the highest parallel branch.
  const lift = Math.max(0, Math.min(64, canvas.clientHeight * 0.12, top - 24))
  flow.setViewport({ ...viewport, y: viewport.y - lift }, { duration: 0 })
}
const fitAnimated = () => fit(false)
// A readable starting scale, even for thousands of spans. Resizing panes must
// preserve the user's zoom and pan instead of repeatedly fitting the whole run.
const resetView = () => instance.value?.setViewport({ x: 28, y: 48, zoom: 1 }, { duration: 0 })
function panSegment(direction) {
  const flow = instance.value, canvas = panel.value?.querySelector('.graph-canvas')
  if (!flow || !canvas || !nodes.value.length) return
  const viewport = flow.getViewport()
  const left = Math.min(...nodes.value.map(node => node.position.x))
  const right = Math.max(...nodes.value.map(node => node.position.x + 136))
  const maxX = 28 - left * viewport.zoom
  const minX = Math.min(maxX, canvas.clientWidth - 28 - right * viewport.zoom)
  const x = Math.max(minX, Math.min(maxX, viewport.x - direction * canvas.clientWidth * 0.7))
  flow.setViewport({ ...viewport, x }, { duration: 180 })
}
function ready(flow) { instance.value = flow; nextTick(fit) }
const nodeTone = node => ['memory_restore','memory_write','memory_dream','memory_request'].includes(node.cardKind) ? 'memory' : ['faq','intent','emotion','clarification','merge','background','work','memory','compaction'].includes(node.cardKind) ? node.cardKind : node.cardKind === 'tool' || node.kind === 'TOOL' ? 'tool' : node.kind === 'MODEL' || node.cardKind === 'model' ? 'model' : 'stage';
const stackCount = node => node.children?.length || node.repeatCount || 0
function choose(node) { emit('select', node.data.id) }
watch(() => props.selectedId, async () => {
  await nextTick()
  const node = nodes.value.find((item) => item.selected)
  const flow = instance.value
  if (!node || !flow || props.mode !== 'graph') return
  const viewport = flow.getViewport()
  const x = node.position.x * viewport.zoom + viewport.x
  const y = node.position.y * viewport.zoom + viewport.y
  const canvas = panel.value.querySelector('.graph-canvas')
  if (!canvas) return
  const outsideX = x < 24 || x + 136 * viewport.zoom > canvas.clientWidth - 24
  const outsideY = y < 24 || y + 56 * viewport.zoom > canvas.clientHeight - 64
  if (!outsideX && !outsideY) return
  flow.setViewport({ ...viewport,
    x: outsideX ? canvas.clientWidth * .4 - node.position.x * viewport.zoom : viewport.x,
    y: outsideY ? canvas.clientHeight * .4 - node.position.y * viewport.zoom : viewport.y,
  }, { duration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 180 })
})
</script>

<template>
  <section ref="panel" class="trace-graph-panel">
    <div v-if="mode === 'graph'" class="graph-canvas">
      <VueFlow
        :key="trace.id" :nodes="nodes" :edges="edges" :min-zoom="0.15" :max-zoom="1.8"
        :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :elements-selectable="true" :nodes-focusable="true"
        :delete-key-code="null" :zoom-on-double-click="false" :fit-view-on-init="false" :pan-on-scroll="false"
        @pane-ready="ready" @nodes-initialized="fit" @node-click="({ node }) => choose(node)"
      >
        <template #edge-orthogonal="edge">
          <BaseEdge :id="edge.id" :path="edgePath(edge)" :marker-end="edge.markerEnd" :style="edge.style" />
        </template>
        <template #node-trace="{ data, selected }">
          <div class="trace-node" :title="data.summary" :class="[{ 'is-selected': selected, 'is-stack': data.children?.length && !expanded.has(data.id), 'is-failed': ['FAILED','TIMED_OUT'].includes(data.status) }, `tone-${nodeTone(data)}`]" :data-testid="`trace-node-${data.id}`">
            <Handle type="target" :position="Position.Left" :connectable="false" />
            <span class="node-name" :aria-label="`${cardTitle(data)}，${statusLabel(data.status)}`">{{ cardTitle(data) }}</span>
            <span class="node-highlight" :title="cardHighlight(data)">{{ cardHighlight(data) }}</span>
            <button v-if="data.children?.length || data.collapseId" type="button" class="cluster-toggle nodrag nopan" :aria-expanded="!!data.collapseId || expanded.has(data.id)" :aria-label="`${data.collapseId || expanded.has(data.id) ? '收起' : '展开'} ${data.collapseId ? '执行阶段' : cardTitle(data)}`" :title="data.collapseId || expanded.has(data.id) ? '收起阶段' : `包含 ${stackCount(data)} 个步骤，点击展开`" @click.stop="emit('toggle', data.collapseId || data.id)">{{ data.collapseId || expanded.has(data.id) ? '−' : `×${stackCount(data)}` }}</button>

            <Handle type="source" :position="Position.Right" :connectable="false" />
          </div>
        </template>
      </VueFlow>
      <div class="graph-controls">
        <button type="button" aria-label="查看上一段流程" title="上一段流程" @click="panSegment(-1)"><TraceIcon name="chevron" :size="16" class="previous-segment" /></button>
        <button type="button" aria-label="查看下一段流程" title="下一段流程" @click="panSegment(1)"><TraceIcon name="chevron" :size="16" /></button>
        <span />
        <button type="button" aria-label="缩小调用图" title="缩小" @click="instance?.zoomOut({ duration: 0 })"><TraceIcon name="minus" :size="16" /></button>
        <button type="button" aria-label="放大调用图" title="放大" @click="instance?.zoomIn({ duration: 0 })"><TraceIcon name="plus" :size="16" /></button>
        <button type="button" aria-label="返回时间起点" title="返回时间起点" @click="resetView">起点</button>
        <span />
        <button type="button" aria-label="适应画布" title="适应画布" @click="fitAnimated"><TraceIcon name="fit" :size="16" /></button>
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
.graph-titlebar { display: flex; justify-content: space-between; gap: 12px; padding: 16px 20px; flex-shrink: 0; border-bottom: 1px solid var(--workbench-border); background: var(--workbench-raised); }
.time-direction { font-size: 12px; color: var(--workbench-ink); pointer-events: auto; }
.time-direction small { margin-left: 12px; color: var(--workbench-muted); font-size: 12px; }
.graph-legend { display: flex; gap: 14px; color: var(--workbench-ink); font-size: 12px; }
.graph-legend span { display: flex; align-items: center; gap: 5px; }
.graph-legend i { width: 5px; height: 5px; border-radius: 50%; }
.model-dot { background: var(--workbench-accent); }.tool-dot { background: var(--workbench-success); }.stage-dot { background: var(--workbench-muted); }
.graph-canvas { position: relative; flex: 1; min-height: 300px; background: radial-gradient(var(--workbench-border) .6px, transparent .6px) 0 0 / 20px 20px, var(--workbench-paper); }
.graph-canvas :deep(.vue-flow) { position: absolute; inset: 0; }
.trace-node { position: relative; width: 248px; height: 116px; background: var(--workbench-raised); border: 1px solid var(--workbench-border); border-radius: 10px; padding: 12px; color: var(--workbench-ink); cursor: pointer; box-shadow: 0 2px 7px rgba(41,39,34,0.016); }
.trace-node.is-stack { box-shadow: 3px 4px 0 -1px var(--workbench-paper), 3px 4px 0 0 var(--workbench-border), 6px 8px 0 -1px var(--workbench-paper), 6px 8px 0 0 var(--workbench-border); }
.cluster-toggle { position: absolute; right: 6px; top: -13px; border: 1px solid var(--workbench-border); background: var(--workbench-paper); color: var(--workbench-accent); border-radius: 6px; padding: 2px 7px; font-size: 12px; cursor: pointer; }
.cluster-toggle:focus-visible { outline: 2px solid var(--workbench-accent); outline-offset: 2px; }
.node-time { position: absolute; left: 0; top: 128px; color: var(--workbench-muted); font: 12px var(--workbench-ui); white-space: nowrap; }
.node-top { display: flex; align-items: center; gap: 7px; }
.node-icon { display: flex; color: var(--workbench-muted); }
.node-name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-status { width: 5px; height: 5px; border-radius: 50%; background: var(--workbench-muted); margin-left: auto; flex-shrink: 0; }
.node-status.disabled, .node-status.skipped { background: var(--workbench-muted); }
.node-status.succeeded { background: var(--workbench-success); }.node-status.failed, .node-status.timed_out { background: var(--workbench-accent); }
.node-summary { margin-top: 9px; font-size: 12px; color: var(--workbench-ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 9px; color: var(--workbench-muted); font: 12px var(--workbench-ui); }
.node-bottom span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-bottom b { color: var(--workbench-ink); font-weight: 400; white-space: nowrap; }
.kind-model { border-color: var(--workbench-border); background: var(--workbench-paper); }.kind-model .node-icon { color: var(--workbench-accent); }
.kind-tool { border-color: var(--workbench-success-soft); background: var(--workbench-success-soft); }.kind-tool .node-icon { color: var(--workbench-success); }
.trace-node.is-failed { border-color: var(--workbench-muted); background: var(--workbench-paper); }
.trace-node:hover { border-color: var(--workbench-accent); }
.trace-node.is-selected { border-color: var(--workbench-accent); background: var(--workbench-paper); box-shadow: 0 0 0 3px rgba(41,39,34,0.169), 0 4px 14px rgba(41,39,34,0.039); }
.trace-node.is-stack.is-selected { box-shadow: 3px 4px 0 -1px var(--workbench-paper), 3px 4px 0 0 var(--workbench-accent), 6px 8px 0 -1px var(--workbench-paper), 6px 8px 0 0 var(--workbench-muted); }
.trace-node :deep(.vue-flow__handle) { width: 5px; height: 5px; min-width: 5px; min-height: 5px; background: var(--workbench-muted); border: 2px solid var(--workbench-raised); }
.trace-node.is-selected :deep(.vue-flow__handle) { background: var(--workbench-accent); }
.graph-canvas :deep(.vue-flow__node:focus-visible) { outline: 3px solid var(--workbench-muted); outline-offset: 4px; border-radius: 10px; }
.graph-controls { position: absolute; bottom: 16px; right: 16px; display: flex; align-items: center; gap: 2px; padding: 4px; background: var(--workbench-raised); border: 1px solid var(--workbench-border); border-radius: 9px; }
.graph-controls button { display: flex; padding: 7px; border: 0; background: none; color: var(--workbench-accent); cursor: pointer; border-radius: 5px; }
.graph-controls .previous-segment {transform:rotate(180deg);}
.graph-controls button:hover { background: var(--workbench-paper); }.graph-controls button:focus-visible { outline: 2px solid var(--workbench-accent); }
.graph-controls>span { height: 15px; width: 1px; background: var(--workbench-border); margin: 0 3px; }
.node-table-wrap { overflow: auto; flex: 1; padding: 20px 16px; }
.node-table { border-collapse: collapse; width: 100%; font-size: 12px; }
.node-table th { text-align: left; padding: 12px 8px; font-weight: 400; color: var(--workbench-muted); }
.node-table td { border-top: 1px solid var(--workbench-border); padding: 10px 8px; color: var(--workbench-ink); }
.node-table tr.active { background: var(--workbench-paper); }
.node-table button { display: flex; align-items: center; gap: 10px; border: 0; background: none; padding: 4px 0; color: var(--workbench-accent); text-align: left; cursor: pointer; font: 12px var(--workbench-ui); }
.node-table button:focus-visible { outline: 2px solid var(--workbench-accent); outline-offset: 3px; }
.node-table small { display: block; color: var(--workbench-muted); font-size: 12px; margin-top: 5px; }
.node-table .status-failed, .node-table .status-timed_out { color: var(--workbench-accent); }
@media(max-width:700px) { .graph-legend { gap: 10px; }.graph-titlebar { padding: 14px; } }

.graph-titlebar {background:var(--workbench-raised);flex-wrap:wrap;}
.graph-canvas {background:radial-gradient(var(--workbench-border) .6px,transparent .6px) 0 0/20px 20px,var(--workbench-paper);}
.trace-node {color:var(--workbench-ink);background:var(--workbench-raised);border-color:var(--workbench-border);}
.trace-node.is-stack {box-shadow:3px 4px 0 -1px var(--workbench-paper),3px 4px 0 0 var(--workbench-border),6px 8px 0 -1px var(--workbench-raised),6px 8px 0 0 var(--workbench-border);}
.kind-model {background:var(--workbench-raised);border-color:var(--workbench-border);}.kind-model .node-icon,.model-dot{color:var(--workbench-accent);}.model-dot{background:var(--workbench-accent);}
.kind-tool {background:var(--workbench-raised);border-color:var(--workbench-border);}.kind-tool .node-icon{color:var(--workbench-success);}.tool-dot{background:var(--workbench-success);}
.trace-node.is-failed {background:var(--workbench-error-soft);border-color:var(--workbench-error);}
.trace-node.is-selected,.trace-node.is-stack.is-selected {background:var(--workbench-sidebar);border-color:var(--workbench-ring-deep);box-shadow:var(--workbench-ring-shadow);}
.node-status.failed,.node-status.timed_out {background:var(--workbench-error);}.node-table .status-failed,.node-table .status-timed_out{color:var(--workbench-error);}
.cluster-toggle {background:var(--workbench-raised);border-color:var(--workbench-border);color:var(--workbench-accent);}
.graph-controls {background:var(--workbench-raised);}.graph-controls button{color:var(--workbench-muted);}
.node-table tr.active {background:var(--workbench-soft);}

.node-icon,.kind-model .node-icon {color:var(--workbench-muted);}
.cluster-toggle {color:var(--workbench-link);}
.graph-controls button:hover {box-shadow:var(--workbench-ring-shadow);}
.graph-controls button:focus-visible,.cluster-toggle:focus-visible,.node-table button:focus-visible {outline-color:var(--workbench-focus);}
.graph-canvas :deep(.vue-flow__node:focus-visible){outline-color:var(--workbench-focus);}
/* Warm, compact two-line cards; detailed timing and payloads stay in the inspector. */
.graph-titlebar {padding:10px 16px;border-bottom:0;}
.time-direction small {margin-left:8px;}
.graph-canvas {background:var(--workbench-paper);min-height:240px;}
.trace-node {width:136px;height:48px;display:flex;flex-direction:column;align-items:flex-start;justify-content:center;padding:5px 10px;gap:2px;border:1px solid transparent;border-radius:7px;box-shadow:none;box-sizing:border-box;}
.node-name {font-size:13px;font-weight:500;line-height:18px;max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
.node-highlight {font-size:11px;line-height:16px;max-width:100%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;opacity:.82;}
.trace-node.tone-model {background:var(--workbench-soft);color:#83462f;}
.trace-node.tone-tool {background:var(--workbench-success-soft);color:var(--workbench-success);}
.trace-node.tone-stage {background:var(--workbench-sidebar);color:var(--workbench-charcoal);}
.trace-node.is-selected,.trace-node.is-stack.is-selected {border-color:currentColor;box-shadow:0 0 0 1px currentColor;}
.trace-node.is-failed {border-color:var(--workbench-error);}
.trace-node:hover {border-color:currentColor;}
.trace-node :deep(.vue-flow__handle) {opacity:0;}
.trace-node.is-stack {box-shadow:2px 3px 0 -1px var(--workbench-paper),2px 3px 0 0 var(--workbench-border);}
.trace-node.tone-faq {background:var(--workbench-raised);color:var(--workbench-muted);border-color:var(--workbench-border);}
.trace-node.tone-intent {background:var(--workbench-soft);color:#83462f;}
.trace-node.tone-emotion {background:var(--workbench-sidebar);color:var(--workbench-charcoal);}
.trace-node.tone-clarification {background:var(--workbench-warning-soft);color:var(--workbench-warning);}
.trace-node.tone-merge {background:#e2dfd3;color:var(--workbench-charcoal);}
.trace-node.tone-background {background:#eee8dd;color:#71604c;}
.trace-node.tone-work {background:#f0ece3;color:var(--workbench-muted);}
.trace-node.tone-memory {background:#e9ede2;color:var(--workbench-success);}
.trace-node.tone-compaction {background:#eee8dd;color:#71604c;}
.trace-node.is-failed {border-color:var(--workbench-error);}
.trace-node.is-selected,.trace-node.is-stack.is-selected {border-color:var(--workbench-ring-deep);box-shadow:var(--workbench-ring-shadow);}
.cluster-toggle {top:-9px;right:-8px;font-size:10px;font-weight:500;padding:1px 5px;min-width:27px;height:18px;line-height:14px;border:0;border-radius:6px;background:var(--workbench-raised);color:var(--workbench-muted);box-shadow:var(--workbench-ring-shadow);font-variant-numeric:tabular-nums;}
.cluster-toggle:hover {background:var(--workbench-sidebar);color:var(--workbench-ink);}
.trace-node.is-failed {border-color:var(--workbench-error);}
.graph-controls {bottom:12px;right:12px;}
.graph-controls {gap:3px;padding:5px;border-color:var(--workbench-border);box-shadow:0 2px 8px rgba(20,20,19,.04);}
.graph-controls button {min-width:30px;min-height:30px;justify-content:center;align-items:center;}
.node-name {font-size:13px;letter-spacing:.01em;}
.node-highlight {opacity:.8;}
.trace-node {height:56px;padding:7px 10px;gap:3px;}
.node-name {font-size:13px;line-height:19px;letter-spacing:.02em;}
.node-highlight {font-size:12px;line-height:18px;letter-spacing:.01em;opacity:1;}
.trace-node.is-selected,.trace-node.is-stack.is-selected {border-color:var(--workbench-accent);box-shadow:0 0 0 1px var(--workbench-accent);}
.trace-node.is-selected .node-name {font-weight:600;}
.trace-node.is-failed.is-selected {border-color:var(--workbench-error);box-shadow:0 0 0 1px var(--workbench-error);}
</style>
