<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAgentTrace, getTracePayload } from '@/api/agent'
import { useUserStore } from '@/store/user'
import TraceGraph from '@/components/trace/TraceGraph.vue'
import TraceDivider from '@/components/trace/TraceDivider.vue'
import TraceIcon from '@/components/trace/TraceIcon.vue'
import TraceCode from '@/components/trace/TraceCode.vue'
import { demoFiles } from '@/trace/demo-trace.mjs'
import { parseTraceFile, formatDuration, formatValue, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { buildTracePresentation, layoutPresentation, presentNode, cardTitle, cardIcon } from '@/trace/trace-presentation.mjs'

const examples = demoFiles.flatMap((data) => parseTraceFile(JSON.stringify(data), '内置演示日志'))
const traces = ref(examples)
const activeId = ref(examples[0].id)
const selectedId = ref(examples[0].nodes.find((node) => node.type === 'llm_inference').id)
const mode = ref('graph')
const traceQuery = ref('')
const queryError = ref('')
const querying = ref(false)
const payloadLoading = ref(false)
const payloadError = ref('')
const inspector = ref(null)
const userStore = useUserStore()
const route = useRoute()
let queryController, payloadController
const studio = ref(null)
const sidebarWidth = ref(224)
const inspectorWidth = ref(330)
const studioWidth = ref(window.innerWidth)
const resizing = ref(false)
const maxSidebar = computed(() => Math.max(180, Math.min(440, studioWidth.value - inspectorWidth.value - 380)))
const maxInspector = computed(() => Math.max(260, Math.min(640, studioWidth.value - sidebarWidth.value - 380)))
function resizeSidebar(delta) { sidebarWidth.value = Math.max(180, Math.min(maxSidebar.value, sidebarWidth.value + delta)) }
function resizeInspector(delta) { inspectorWidth.value = Math.max(260, Math.min(maxInspector.value, inspectorWidth.value + delta)) }
let workspaceObserver
onMounted(() => {
  workspaceObserver = new ResizeObserver(([entry]) => {
    studioWidth.value = entry.contentRect.width
    if (studioWidth.value > 1100) { resizeSidebar(0); resizeInspector(0) }
  })
  workspaceObserver.observe(studio.value)
})
onBeforeUnmount(() => workspaceObserver?.disconnect())
const previousTitle = document.title
document.title = 'Trace Studio · EchoFrame运行追踪'
onBeforeUnmount(() => { document.title = previousTitle; queryController?.abort(); payloadController?.abort() })

const trace = computed(() => traces.value.find((item) => item.id === activeId.value) || traces.value[0])
const expanded = ref(new Set())
const presentation = computed(() => buildTracePresentation(trace.value.nodes))
const graph = computed(() => layoutPresentation(presentation.value, expanded.value))
const selected = computed(() => presentation.value.allCards.get(selectedId.value) || (presentation.value.rawById.has(selectedId.value) ? presentNode(presentation.value.rawById.get(selectedId.value)) : null))
const selectedSource = computed(() => presentation.value.rawById.get(selected.value?.sourceId))
const selectedMembers = computed(() => (selected.value?.memberIds || []).filter(id => id !== selected.value.sourceId).map(id => presentation.value.rawById.get(id)).filter(Boolean))
const timingNodes = computed(() => mode.value === 'graph' ? graph.value.nodes.flatMap(n => n.data.cardKind === 'phase' ? n.data.children : [n.data]) : trace.value.nodes)
function toggleGroup(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) { next.delete(id); selectedId.value = id } else next.add(id)
  expanded.value = next
}
const clock = (value) => value == null ? '未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
const date = (value) => value == null ? '时间未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))

function selectNode(id) { selectedId.value = id }
function changeTrace() {
  expanded.value = new Set()
  selectedId.value = presentation.value.cards[0]?.id || trace.value.nodes[0].id
}
function cancelQuery() {
  queryController?.abort()
  queryController = null
  querying.value = false
}
async function queryTrace() {
  cancelQuery()
  queryError.value = ''
  const id = traceQuery.value.trim().toLowerCase()
  if (!/^[a-f0-9]{32}$/.test(id)) { queryError.value = '请输入完整的 32 位 Trace ID'; return }
  const local = traces.value.find((item) => item.id === id)
  if (local && !local.remote) { activeId.value = local.id; changeTrace(); return }
  const controller = new AbortController()
  queryController = controller
  querying.value = true
  try {
    if (!userStore.isLogin) throw new Error('请先登录网站后查询 Trace')
    const data = await getAgentTrace(id, controller.signal)
    if (controller.signal.aborted) return
    const result = parseTraceFile(JSON.stringify(data), '在线查询').find((item) => item.id === id)
    if (!result) throw new Error('返回的 Trace 与查询 ID 不一致')
    result.remote = true
    traces.value = [result, ...traces.value.filter((item) => item.id !== id)].slice(0, 30)
    activeId.value = id
    changeTrace()
  } catch (error) { if (!controller.signal.aborted) queryError.value = error.message }
  finally { if (queryController === controller) querying.value = false }
}
watch([() => trace.value.id, () => selectedSource.value?.id], async (_, __, onCleanup) => {
  payloadController?.abort()
  const node = selectedSource.value, current = trace.value
  payloadError.value = ''
  payloadLoading.value = false
  if (!current.remote || !node) return
  const controller = new AbortController()
  payloadController = controller
  onCleanup(() => controller.abort())
  payloadLoading.value = true
  try {
    const results = await Promise.allSettled(['input', 'output'].map(async (direction) => {
      const reference = node[`${direction}Ref`]
      if (node[direction] != null || !reference || reference === 'UNAVAILABLE') return
      const data = await getTracePayload(node.attributes['run.id'] || current.runId, reference, controller.signal)
      if (!controller.signal.aborted) {
        node[direction] = data.value ?? data.preview ?? null
        node[`${direction}State`] = data.state
      }
    }))
    const failure = results.find((result) => result.status === 'rejected')
    if (failure) throw failure.reason
  } catch (error) { if (!controller.signal.aborted) payloadError.value = error.message }
  finally { if (!controller.signal.aborted) payloadLoading.value = false }
})
watch(selectedId, async () => { await nextTick(); if (inspector.value) inspector.value.scrollTop = 0 })
watch(() => userStore.user?.token, () => {
  cancelQuery()
  queryError.value = '登录状态已变化，请重新查询'
  payloadController?.abort()
  traces.value = traces.value.filter((item) => !item.remote)
  if (!traces.value.length) traces.value = examples
  if (!traces.value.some((item) => item.id === activeId.value)) activeId.value = traces.value[0].id
  changeTrace()
}, { flush: 'sync' })
function timingStyle(node) {
  const extent = trace.value.extentMs || 1
  return { left: `${Math.min(100, Math.max(0, (node.offsetMs || 0) / extent * 100))}%`, width: `${Math.min(100, Math.max(0.5, (node.durationMs || 0) / extent * 100))}%` }
}
function resetDemo() {
  cancelQuery()
  traces.value = examples
  activeId.value = examples[0].id
  changeTrace()
}
watch(() => route.query.traceId, (id) => {
  if (typeof id !== 'string') return
  traceQuery.value = id
  queryTrace()
}, { immediate: true })
function downloadExample() {
  const link = document.createElement('a')
  const url = URL.createObjectURL(new Blob([JSON.stringify(demoFiles[0], null, 2)], { type: 'application/json' }))
  link.href = url
  link.download = 'tianmu-trace-demo.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <div ref="studio" class="trace-studio" :class="{ resizing }" :style="{ '--sidebar-width': `${sidebarWidth}px`, '--inspector-width': `${inspectorWidth}px` }">
    <aside class="workspace-sidebar">
      <router-link class="workspace-brand" to="/">EchoFrame<span>运营助手</span></router-link>
      <form class="trace-search" @submit.prevent="queryTrace">
        <label for="trace-id-query">Trace ID</label>
        <div class="search-field"><input id="trace-id-query" v-model="traceQuery" placeholder="输入 Trace ID" autocomplete="off" spellcheck="false" :aria-invalid="!!queryError" aria-describedby="trace-query-state" @input="cancelQuery(); queryError = ''" /><button type="submit" :disabled="querying" :aria-label="querying ? '正在查询 Trace' : '查询 Trace'" title="查询"><TraceIcon name="arrow" :size="16" /></button></div>
        <p id="trace-query-state" class="query-state" :class="{ error: queryError }" role="status">{{ queryError || (querying ? '正在查询…' : '查询已有运行，不会启动新任务') }}</p>
      </form>
      <div class="history-heading">运行记录<span>{{ traces.length }}</span></div>
      <nav class="run-list" aria-label="运行记录">
        <button v-for="item in traces" :key="item.id" type="button" :class="{ selected: activeId === item.id }" :aria-current="activeId === item.id ? 'true' : undefined" @click="cancelQuery(); activeId = item.id; changeTrace()">
          <span class="run-title" :title="item.title"><i class="run-dot" :class="item.status.toLowerCase()" />{{ item.title }}</span>
          <span class="run-subtitle">{{ statusLabel(item.status) }} · {{ formatDuration(item.durationMs) }}</span>
          <span class="run-id" :title="item.id">{{ item.id }}</span>
        </button>
      </nav>
      <select class="mobile-run-select" v-model="activeId" aria-label="当前运行" @change="cancelQuery(); changeTrace()"><option v-for="item in traces" :key="item.id" :value="item.id">{{ item.title }}</option></select>
      <details class="run-info" open>
        <summary>运行信息</summary>
        <dl><div><dt>开始时间 · 北京</dt><dd>{{ date(trace.startMs) }} {{ clock(trace.startMs) }}</dd></div><div><dt>Run ID</dt><dd>{{ trace.runId }}</dd></div><div><dt>Trace ID</dt><dd>{{ trace.id }}</dd></div><div><dt>来源</dt><dd>{{ trace.source }}</dd></div></dl>
      </details>
      <button class="example-button" type="button" @click="trace.demo ? downloadExample() : resetDemo()">{{ trace.demo ? '下载示例' : '返回示例' }}<TraceIcon :name="trace.demo ? 'download' : 'arrow'" :size="14" /></button>
    </aside>
    <TraceDivider label="调整运行记录宽度" :value="sidebarWidth" :min="180" :max="maxSidebar" @resize="resizeSidebar" @dragging="resizing = $event" @reset="resizeSidebar(224 - sidebarWidth)" />

    <main class="workspace-main">
      <header class="workspace-header">
        <div class="workspace-heading"><span class="brand-symbol"><TraceIcon name="trace" :size="21" /></span><h1>运行追踪</h1><span class="demo-badge">{{ trace.demo ? '示例' : trace.remote ? '在线查询' : '本地日志' }}</span></div>
        <router-link class="back-agent" to="/agent">返回 Agent ↗</router-link>
      </header>
      <div class="run-toolbar">
        <div class="run-context"><h2 :title="trace.title">{{ trace.title }}</h2><span class="model-name" :title="trace.model">{{ trace.model }}</span></div>
        <span class="status-pill" :class="trace.status.toLowerCase()">{{ statusLabel(trace.status) }}</span>
        <div class="run-metrics"><span><TraceIcon name="clock" :size="14" />{{ formatDuration(trace.durationMs) }}</span><span><TraceIcon name="token" :size="14" />{{ formatValue(trace.totalTokens) }}<small>Token</small><small v-if="!trace.usageComplete" title="部分节点未提供用量">部分</small></span></div>
      </div>

      <div class="workspace-panels">
        <section class="graph-column" aria-label="执行过程">
          <div class="view-tabs">
            <button type="button" :class="{ active: mode === 'graph' }" :aria-pressed="mode === 'graph'" @click="mode = 'graph'">执行图</button>
            <button type="button" :class="{ active: mode === 'list' }" :aria-pressed="mode === 'list'" @click="mode = 'list'">原始节点</button>
            <span>{{ presentation.cards.length }} 卡片 / {{ trace.nodes.length }} Span</span>
          </div>
          <TraceGraph :trace="trace" :graph="graph" :expanded="expanded" :selected-id="selectedId" :mode="mode" @select="selectNode" @toggle="toggleGroup" />
          <p v-if="mode === 'graph' && presentation.notices.length" class="projection-notice">{{ presentation.notices.join(' · ') }}</p>
          <section class="timing-panel" aria-label="执行时间线">
            <div class="timing-heading"><h2>执行时间线</h2><span>{{ formatDuration(trace.extentMs) }}</span></div>
            <div class="timing-axis"><span>0 ms</span><span>{{ Math.round(trace.extentMs / 2) }}</span><span>{{ Math.round(trace.extentMs) }}</span></div>
            <button v-for="node in timingNodes" :key="node.id" type="button" class="timing-row" :class="{ active: node.id === selectedId, failed: ['FAILED', 'TIMED_OUT'].includes(node.status) }" :aria-label="`查看 ${node.label}，耗时 ${formatDuration(node.durationMs)}`" :aria-pressed="node.id === selectedId" @click="selectNode(node.id)">
              <span class="timing-name" :title="node.label">{{ mode === 'graph' ? cardTitle(node) : nodeDisplayName(node) }}</span><span class="timing-track"><i v-if="node.durationMs != null && node.offsetMs != null" :style="timingStyle(node)" :class="`bar-${node.kind.toLowerCase()}`" /></span><span class="timing-value">{{ formatDuration(node.durationMs) }}</span>
            </button>
          </section>
        </section>

        <TraceDivider label="调整节点详情宽度" :value="inspectorWidth" :min="260" :max="maxInspector" reverse @resize="resizeInspector" @dragging="resizing = $event" @reset="resizeInspector(330 - inspectorWidth)" />
        <aside ref="inspector" class="inspector-column" aria-label="节点详情" tabindex="0">
          <div class="inspector-heading">节点详情</div>
          <div v-if="selected" class="inspector-content" :key="selected.id">
            <div class="selected-node-heading"><span class="selected-node-icon" :class="selected.kind.toLowerCase()"><TraceIcon :name="cardIcon(selected)" :size="19" /></span><div><h2 data-testid="inspector-node-name">{{ mode === 'list' ? nodeDisplayName(selected) : cardTitle(selected) }}</h2><small>{{ selected.tool || selected.model || selected.name }}</small></div></div>
            <div class="node-status-line"><span class="status-pill" :class="selected.status.toLowerCase()">{{ statusLabel(selected.status) }}</span><span><TraceIcon name="clock" :size="13" /><span data-testid="inspector-duration">{{ formatDuration(selected.durationMs) }}</span></span><span v-if="selected.turn != null">第 {{ selected.turn }} 轮</span></div>
            <p class="semantic-result">{{ selected.summary }}</p>
            <p v-if="selected.sourceNote || selected.legacy" class="projection-notice">{{ selected.sourceNote || '历史记录缺少任务 ID，按同一运行的父级范围归组；内部阶段可能未记录。' }}</p>
            <section v-if="selectedMembers.length" class="member-section"><h3>内部记录 · {{ selectedMembers.length }}</h3><button v-for="member in selectedMembers" :key="member.id" type="button" @click="selectNode(member.id)"><span>{{ nodeDisplayName(member) }}</span><small :class="{ error: ['FAILED', 'TIMED_OUT'].includes(member.status) }">{{ statusLabel(member.status) }} · {{ formatDuration(member.durationMs) }}</small></button></section>
            <dl v-if="selected.kind === 'MODEL' || selected.totalTokens != null" class="token-grid"><div><dt>输入 Token</dt><dd>{{ formatValue(selected.inputTokens) }}</dd></div><div><dt>输出 Token</dt><dd>{{ formatValue(selected.outputTokens) }}</dd></div></dl>
            <p v-if="payloadLoading || payloadError" class="payload-state" role="status">{{ payloadError || '正在加载节点正文…' }}</p>
            <TraceCode v-if="selectedSource" :key="`${selected.id}-input`" title="输入" :value="selected.input" :state="selected.inputState" :reference="selected.inputRef" />
            <TraceCode v-if="selectedSource" :key="`${selected.id}-output`" title="输出" :value="selected.output" :state="selected.outputState" :reference="selected.outputRef" />
            <section class="metadata-section"><h3>节点属性</h3><dl class="node-metadata"><div><dt>{{ selected.synthetic ? '展示分组 ID' : 'Span ID' }}</dt><dd>{{ selected.id }}</dd></div><div><dt>Parent Span ID</dt><dd>{{ selected.parentId || '根节点' }}</dd></div><div><dt>开始时间 · 北京</dt><dd>{{ date(selected.startMs) }} {{ clock(selected.startMs) }}</dd></div><div><dt>模型</dt><dd>{{ selected.model || '—' }}</dd></div><div><dt>原始名称</dt><dd>{{ selected.name }}</dd></div></dl></section>
            <TraceCode v-if="selectedSource" title="原始属性" :value="selected.attributes" state="COMPLETE" />
          </div>
        </aside>
      </div>
    </main>
  </div>
</template>

<style>
html.trace-workspace-page, html.trace-workspace-page body { min-width: 0; background: #fbfafc; }
html.trace-workspace-page #app { max-width: none; background: #fbfafc; }
</style>

<style scoped>
.trace-studio {
  --ink: #292b38; --muted: #8c8e9c; --line: #eeedf2; --accent: #cc5b81;
  --trace-mono: 'Cascadia Code', 'SFMono-Regular', Consolas, monospace;
  display: flex; height: 100dvh; min-height: 600px; color: var(--ink); background: #fbfafc;
  font: 14px/1.6 'Harmony Font', 'PingFang SC', 'Microsoft YaHei', sans-serif; color-scheme: light;
}
.trace-studio * { box-sizing: border-box; }
.trace-studio.resizing { user-select: none; cursor: col-resize; }
.trace-studio button, .trace-studio select { font: inherit; }
.trace-studio button { cursor: pointer; }
.trace-studio button:disabled { opacity: .5; cursor: wait; }
.trace-studio a { color: inherit; text-decoration: none; }
.trace-studio :is(button, a, input, select, summary):focus-visible { outline: 3px solid #e8b0c4; outline-offset: 3px; }
.inspector-column:focus-visible { outline: 2px solid #e8b0c4; outline-offset: -2px; }
.workspace-sidebar { width: var(--sidebar-width); flex-shrink: 0; display: flex; flex-direction: column; padding: 28px 16px 20px; background: #f4f2f6; border-right: 1px solid var(--line); }
.trace-search { margin-top: 28px; }
.trace-search label { display: block; color: #76657f; font-size: 11px; margin-bottom: 8px; }
.search-field { display: flex; border: 1px solid #e3d9e6; background: #fff; border-radius: 9px; }
.search-field input { width: 100%; min-width: 0; border: 0; background: transparent; padding: 10px; color: #51445f; font: 11px var(--trace-mono); border-radius: 9px; }
.search-field button { border: 0; background: transparent; color: #ac7099; display: grid; place-items: center; padding: 8px; border-radius: 8px; }
.query-state { font-size: 10px; color: #8b8096; margin: 8px 0 0; overflow-wrap: anywhere; }
.query-state.error, .payload-state { color: #ab5774; }
.payload-state { font-size: 11px; }
.projection-notice { margin: 0; padding: 8px 20px; font-size: 10px; color: #928398; background: #fbf9fc; }
.semantic-result { padding: 12px; border-radius: 8px; background: #faf6fa; color: #706078; font-size: 13px; }
.member-section { margin: 20px 0; }
.member-section h3 { font-size: 12px; font-weight: 500; color: #766881; }
.member-section button { display: flex; justify-content: space-between; gap: 8px; width: 100%; background: none; border: 0; border-bottom: 1px solid #f0ecf3; padding: 8px 0; text-align: left; color: #766881; }
.member-section button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.member-section small { font-size: 10px; flex-shrink: 0; color: #99909f; }
.member-section small.error { color: #b6596b; }
.workspace-brand { font-size: 24px; font-weight: 800; letter-spacing: 2px; padding: 0 10px; }
.workspace-brand span { display: block; font-size: 11px; color: #9992a0; font-weight: 400; letter-spacing: 4px; margin-top: 2px; }
.history-heading { display: flex; justify-content: space-between; font-size: 11px; color: #847a8c; padding: 0 9px; margin: 24px 0 12px; }
.run-list { overflow: auto; flex: 1; min-height: 100px; }
.run-list button { display: block; text-align: left; width: 100%; border: 0; background: none; padding: 12px 10px; border-radius: 9px; margin-bottom: 5px; }
.run-list button:hover { background: #ffffff80; }
.run-list button.selected { background: #fff; box-shadow: inset 2px 0 #c786a4, 0 2px 8px #44304e04; }
.run-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: #51485d; }
.run-dot { display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: #9e92b0; margin: 0 7px 2px 0; }
.run-dot.succeeded { background: #6b9e87; }.run-dot.failed, .run-dot.timed_out { background: #c36c83; }
.run-subtitle { display: block; font-size: 11px; color: #8a8190; margin-top: 5px; }
.run-id { display: block; font: 9px/1.6 var(--trace-mono); color: #9b8da2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 5px; }
.mobile-run-select { display: none; }
.run-info { font-size: 12px; border-top: 1px solid #e6e1e9; padding-top: 15px; color: #766b7d; max-height: 40%; overflow: auto; }
.run-info summary { cursor: pointer; }
dl { margin: 0; }
.run-info dl { padding: 8px 0; }
.run-info dl>div, .node-metadata>div { margin-top: 13px; }
dt { color: #8c8295; font-size: 11px; }
dd { margin: 4px 0 0; overflow-wrap: anywhere; font: 11px/1.7 var(--trace-mono); color: #625c70; }
.example-button { display: flex; align-items: center; gap: 8px; align-self: flex-start; margin-top: 16px; padding: 0; border: 0; background: none; font-size: 11px!important; color: #8b748a; }
.workspace-main { flex: 1; min-width: 0; display: flex; flex-direction: column; background: #fdfcfe; }
.workspace-header { height: 78px; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; padding: 0 24px; border-bottom: 1px solid var(--line); background: #ffffffad; }
.workspace-heading { display: flex; align-items: center; gap: 10px; }
.brand-symbol { display: grid; place-items: center; width: 37px; height: 37px; border-radius: 12px; color: #bd7298; background: linear-gradient(135deg, #fce3ed, #ebe9fb); }
h1 { margin: 0; font-size: 15px; font-weight: 650; }
.demo-badge { color: #94768a; background: #f5eef5; border-radius: 5px; padding: 2px 6px; font-size: 10px; }
.back-agent { font-size: 12px; color: #8d7d8d!important; }
.run-toolbar { display: flex; align-items: center; gap: 14px; padding: 18px 24px; min-width: 0; }
.run-context { min-width: 0; }
.run-context h2 { margin: 0 0 3px; font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-pill { display: inline-flex; align-items: center; padding: 3px 8px; border-radius: 5px; font-size: 11px; line-height: 1.5; color: #746a80; background: #f0edf4; white-space: nowrap; }
.status-pill.succeeded { color: #438266; background: #edf6f0; }
.status-pill.failed, .status-pill.timed_out { color: #b6596b; background: #fbeef0; }
.status-pill.running { color: #8165a4; background: #f2ecf9; }
.status-pill.needs_input { color: #997732; background: #fbf4e7; }
.model-name { display: block; font: 10px/1.6 var(--trace-mono); color: #80758c; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-metrics { display: flex; gap: 18px; margin-left: auto; flex-shrink: 0; color: #6e6479; font: 12px var(--trace-mono); }
.run-metrics>span { display: flex; gap: 6px; align-items: center; }
.run-metrics small { font: 10px 'Microsoft YaHei', sans-serif; color: #928398; }
.workspace-panels { display: grid; grid-template-columns: minmax(0, 1fr) 8px var(--inspector-width); flex: 1; min-height: 0; margin: 0 20px 20px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: #fff; }
.graph-column { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.view-tabs { display: flex; align-items: center; gap: 24px; height: 50px; padding: 0 20px; border-bottom: 1px solid var(--line); flex-shrink: 0; }
.view-tabs button { height: 100%; padding: 0; border: 0; border-bottom: 2px solid transparent; background: none; color: #8b8195; font-size: 12px; }
.view-tabs button.active { color: #b5668c; border-bottom-color: #cc87a5; }
.view-tabs>span { margin-left: auto; color: #958b9e; font-size: 11px; }
.inspector-column { min-width: 0; overflow: auto; border-left: 1px solid var(--line); scrollbar-width: thin; scrollbar-color: #d8cfdf transparent; }
.inspector-heading { position: sticky; top: 0; z-index: 2; background: #fff; height: 50px; display: flex; align-items: center; border-bottom: 1px solid var(--line); padding: 0 20px; font-size: 12px; color: #6a6077; }
.inspector-content { padding: 24px 20px; }
.selected-node-heading { display: flex; align-items: flex-start; gap: 10px; }
.selected-node-heading>div { min-width: 0; }
.selected-node-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: #f5eef5; color: #ac7099; flex-shrink: 0; }
.selected-node-icon.tool { background: #edf5f0; color: #658d79; }
.selected-node-heading small { display: block; margin-top: 3px; font: 10px/1.7 var(--trace-mono); color: #81758c; overflow-wrap: anywhere; }
.selected-node-heading h2 { font-size: 15px; font-weight: 600; margin: 0; overflow-wrap: anywhere; color: #453b51; }
.node-status-line { display: flex; gap: 12px; align-items: center; margin: 18px 0 24px; color: #74697f; font-size: 11px; flex-wrap: wrap; }
.node-status-line>span { display: inline-flex; align-items: center; gap: 5px; }
.metadata-section { border-top: 1px solid var(--line); padding-top: 20px; margin-top: 26px; }
.metadata-section h3 { margin: 0; color: #766881; font-size: 12px; font-weight: 500; }
.token-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; background: #faf8fb; border: 1px solid #f0ecf3; border-radius: 9px; padding: 12px 14px; margin: 0 0 24px; }
.token-grid>div+div { border-left: 1px solid #eae4ee; padding-left: 16px; }
.token-grid dd { font-size: 18px; color: #5d4c6b; line-height: 1.5; }
.timing-panel { flex: 0 0 260px; padding: 14px 20px; overflow: auto; border-top: 1px solid var(--line); scrollbar-width: thin; scrollbar-color: #d8cfdf transparent; }
.timing-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.timing-heading h2 { font-size: 12px; font-weight: 500; margin: 0; color: #6a6077; }
.timing-heading>span { font: 10px var(--trace-mono); color: #8e8398; }
.timing-axis { margin: 0 66px 8px 150px; display: flex; justify-content: space-between; color: #8e8398; font: 10px var(--trace-mono); }
.timing-row { display: grid; grid-template-columns: 140px minmax(80px, 1fr) 60px; gap: 10px; align-items: center; width: 100%; background: none; border: 0; border-radius: 6px; padding: 5px; color: #786c86; text-align: left; font-size: 12px!important; }
.timing-row:hover, .timing-row.active { background: #faf0f5; }
.timing-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.timing-track { height: 6px; position: relative; background: #f5f2f7; border-radius: 3px; }
.timing-track i { position: absolute; height: 6px; border-radius: 3px; background: #b9acc9; }
.timing-track i.bar-model { background: #c48fb1; }
.timing-track i.bar-tool { background: #8db6a6; }
.timing-row.failed .timing-track i { background: #cf8795; }
.timing-value { text-align: right; }
@media(min-width:1101px) { .graph-column { overflow: auto; } }
@media(max-width:1100px) {
  .workspace-sidebar { width: 180px; padding-inline: 12px; }
  .workspace-panels { grid-template-columns: minmax(0, 1fr); overflow: auto; }
  .graph-column { height: 660px; min-height: 660px; }
  .inspector-column { border-left: 0; border-top: 1px solid var(--line); overflow: visible; }
}
@media(max-width:700px) {
  .trace-studio { flex-direction: column; height: auto; min-height: 100dvh; }
  .workspace-sidebar { width: 100%; display: grid; grid-template-columns: 1fr auto; gap: 14px; padding: 16px; border-right: 0; border-bottom: 1px solid var(--line); }
  .workspace-brand { font-size: 21px; padding: 0; }
  .workspace-brand span { display: none; }
  .trace-search { grid-column: 1/-1; grid-row: 2; margin: 0; }
  .trace-search label { display: none; }
  .mobile-run-select { display: block; grid-column: 1/-1; width: 100%; background: #fff; border: 1px solid #e6dce5; border-radius: 8px; padding: 9px; color: #706279; font-size: 12px!important; }
  .history-heading, .run-list, .run-info, .example-button { display: none; }
  .workspace-header { height: 64px; padding: 0 16px; }
  .run-toolbar { padding: 14px 16px; gap: 9px; flex-wrap: wrap; }
  .run-context { flex: 1; }
  .run-metrics { width: 100%; margin-top: 4px; }
  .run-metrics { gap: 12px; flex-wrap: wrap; }
  .workspace-panels { margin: 0 10px 12px; flex: none; }
  .view-tabs { padding: 0 14px; gap: 20px; }
  .graph-column { height: 660px; min-height: 660px; }
  .timing-panel { padding-inline: 8px; }
  .timing-row { grid-template-columns: 95px minmax(40px, 1fr) 58px; gap: 6px; }
  .timing-axis { margin-left: 101px; margin-right: 64px; }
}
@media(prefers-reduced-motion:reduce) { .trace-studio * { transition: none!important; scroll-behavior: auto!important; } }
</style>
