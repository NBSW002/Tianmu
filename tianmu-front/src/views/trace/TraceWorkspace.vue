<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAgentTrace, getTracePayload } from '@/api/agent'
import { useUserStore } from '@/store/user'
import '@/assets/css/workbench-tokens.css'
import EchoMark from '@/components/agent/EchoMark.vue'
import TraceGraph from '@/components/trace/TraceGraph.vue'
import TraceDivider from '@/components/trace/TraceDivider.vue'
import TraceIcon from '@/components/trace/TraceIcon.vue'
import TraceCode from '@/components/trace/TraceCode.vue'
import { demoFiles } from '@/trace/demo-trace.mjs'
import { parseTraceFile, formatDuration, formatValue, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { buildTracePresentation, buildTraceOverview, defaultExpandedStages, layoutPresentation, modelInvocations, intentDetails, contextDetails, compactionDetails, presentNode, cardTitle, cardIcon } from '@/trace/trace-presentation.mjs'

const examples = demoFiles.flatMap((data) => parseTraceFile(JSON.stringify(data), '内置演示日志'))
const traces = ref(examples)
const activeId = ref(examples[0].id)
const selectedId = ref(examples[0].nodes.find((node) => node.type === 'llm_inference').id)
const mode = ref('graph')
const viewTabs = ref(null)
function positionTabIndicator() {
  const active = viewTabs.value?.querySelector('button.active')
  if (active) {
    viewTabs.value.style.setProperty('--active-tab-left', `${active.offsetLeft}px`)
    viewTabs.value.style.setProperty('--active-tab-width', `${active.offsetWidth}px`)
  }
}
watch(mode, () => nextTick(positionTabIndicator))
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
    nextTick(positionTabIndicator)
    if (studioWidth.value > 1100) { resizeSidebar(0); resizeInspector(0) }
  })
  workspaceObserver.observe(studio.value)
  nextTick(positionTabIndicator)
})
onBeforeUnmount(() => workspaceObserver?.disconnect())
const previousTitle = document.title
document.title = 'Trace Studio · EchoFrame运行追踪'
onBeforeUnmount(() => { document.title = previousTitle; queryController?.abort(); payloadController?.abort() })

const trace = computed(() => traces.value.find((item) => item.id === activeId.value) || traces.value[0])
const presentation = computed(() => buildTraceOverview(buildTracePresentation(trace.value.nodes)))
const expanded = ref(defaultExpandedStages(presentation.value))
const graph = computed(() => layoutPresentation(presentation.value, expanded.value))
const selected = computed(() => presentation.value.allCards.get(selectedId.value) || (presentation.value.rawById.has(selectedId.value) ? presentNode(presentation.value.rawById.get(selectedId.value)) : null))
const selectedSource = computed(() => presentation.value.rawById.get(selected.value?.sourceId))
const selectedMembers = computed(() => (selected.value?.memberIds || []).filter(id => id !== selected.value.sourceId).map(id => presentation.value.rawById.get(id)).filter(Boolean))
const invocations = computed(() => modelInvocations(selected.value, presentation.value))
const intentSelection = computed(() => intentDetails(selected.value))
const contextSelection = computed(() => contextDetails(selected.value))
const compactionSelection = computed(() => compactionDetails(selected.value))
const timingNodes = computed(() => mode.value === 'graph' ? graph.value.nodes.map(n => n.data) : trace.value.nodes)
function toggleGroup(id) {
  const next = new Set(expanded.value)
  if (next.has(id)) { next.delete(id); selectedId.value = id } else next.add(id)
  expanded.value = next
}
const clock = (value) => value == null ? '未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
const date = (value) => value == null ? '时间未提供' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))

function selectNode(id) { selectedId.value = id }
function changeTrace() {
  expanded.value = defaultExpandedStages(presentation.value)
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
    const related = invocations.value.map(call => presentation.value.rawById.get(call.toolId)).filter(Boolean)
    const results = await Promise.allSettled([node, ...related].flatMap(target =>
      (target === node ? ['input', 'output', 'error', 'provider_output'] : ['input', 'output']).map(async direction => {
        const reference = target[`${direction}Ref`]
        if (target[direction] != null || !reference || reference === 'UNAVAILABLE') return
        const data = await getTracePayload(target.attributes['run.id'] || current.runId, reference, controller.signal)
        if (!controller.signal.aborted) {
          target[direction] = data.value ?? data.preview ?? null
          target[`${direction}State`] = data.state
        }
      })))
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
      <router-link class="workspace-brand" to="/">EchoFrame<span>个人助手</span></router-link>
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
        <div class="workspace-heading"><span class="brand-symbol"><EchoMark /></span><h1>运行追踪</h1><span class="demo-badge">{{ trace.demo ? '示例' : trace.remote ? '在线查询' : '本地日志' }}</span></div>
        <router-link class="back-agent" to="/agent">返回助手 ↗</router-link>
      </header>
      <div class="run-toolbar">
        <div class="run-context"><h2 :title="trace.title">{{ trace.title }}</h2><span class="model-name" :title="trace.model">{{ trace.model }}</span></div>
        <span class="status-pill" :class="trace.status.toLowerCase()">{{ statusLabel(trace.status) }}</span>
        <div class="run-metrics"><span><TraceIcon name="clock" :size="14" />{{ formatDuration(trace.durationMs) }}</span><span><TraceIcon name="token" :size="14" />{{ formatValue(trace.totalTokens) }}<small>Token</small><small v-if="!trace.usageComplete" title="部分节点未提供用量">部分</small></span></div>
      </div>

      <div class="workspace-panels">
        <section class="graph-column" aria-label="执行过程">
          <div ref="viewTabs" class="view-tabs">
            <button type="button" :class="{ active: mode === 'graph' }" :aria-pressed="mode === 'graph'" @click="mode = 'graph'">执行图</button>
            <button type="button" :class="{ active: mode === 'list' }" :aria-pressed="mode === 'list'" @click="mode = 'list'">原始节点</button>
            <span>{{ graph.nodes.length }} 卡片 / {{ trace.nodes.length }} Span</span>
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
            <div class="selected-node-heading"><span class="selected-node-icon" :class="selected.kind.toLowerCase()"><TraceIcon :name="cardIcon(selected)" :size="19" /></span><div><h2 data-testid="inspector-node-name">{{ mode === 'list' ? nodeDisplayName(selected) : cardTitle(selected) }}</h2><small>{{ selected.executionLabel || selected.tool || selected.model || selected.name }}</small></div></div>
            <div class="node-status-line"><span class="status-pill" :class="selected.status.toLowerCase()">{{ statusLabel(selected.status) }}</span><span><TraceIcon name="clock" :size="13" /><span data-testid="inspector-duration">{{ selected.durationLabel || formatDuration(selected.durationMs) }}</span></span><span v-if="selected.turn != null">{{ selected.turn === 0 ? '前置处理' : `第 ${selected.turn} 轮` }}</span></div>
            <p class="semantic-result">{{ selected.summary }}</p>
            <section v-if="compactionSelection.visible" class="intent-selection" aria-label="上下文压缩范围">
              <h3>压缩范围</h3>
              <p v-if="compactionSelection.trigger">{{ compactionSelection.trigger }}</p>
              <p v-if="compactionSelection.from != null">消息序号 {{ compactionSelection.from }}–{{ compactionSelection.through }} · {{ compactionSelection.messages }} 条</p>
              <p v-if="compactionSelection.inputBytes != null">{{ compactionSelection.inputBytes }} B → {{ compactionSelection.outputBytes }} B，节省 {{ compactionSelection.savedBytes }} B</p>
              <p v-if="compactionSelection.prompt">策略 {{ compactionSelection.prompt }}</p>
              <p>原始消息保留；摘要只覆盖标明的范围，失效来源不会继续使用。</p>
            </section>
            <section v-if="contextSelection.visible" class="intent-selection" aria-label="上下文模块装配">
              <h3>上下文结构</h3>
              <p>协议 {{ contextSelection.version }} · {{ contextSelection.projection }}</p>
              <ol><li v-for="slot in contextSelection.slots" :key="slot.name">{{ slot.label }}<small v-if="slot.bytes != null"> · {{ slot.bytes }} B</small></li></ol>
              <p>仅列出本次实际装配的模块；正文依当前权限查看。</p>
            </section>
            <section v-if="['intent','merge'].includes(selected.cardKind) && intentSelection.visible" class="intent-selection" aria-label="意图能力选择">
              <h3>能力识别</h3>
              <p>主目标：<strong>{{ intentSelection.primary?.label || '未选择具体能力（兼容识别器）' }}</strong></p>
              <p v-if="intentSelection.related.length">关联目标：{{ intentSelection.related.map(item => item.label).join('、') }}</p>
              <details><summary>本轮候选能力 · {{ intentSelection.candidates.length }}</summary>
                <article v-for="candidate in intentSelection.candidates" :key="candidate.id" :class="{ chosen: candidate.id === intentSelection.primary?.id }">
                  <h4>{{ candidate.label }}<small v-if="candidate.id === intentSelection.primary?.id">主目标</small><small v-else-if="intentSelection.related.some(item => item.id === candidate.id)">关联目标</small></h4>
                  <p v-if="candidate.description">{{ candidate.description }}</p><p v-if="candidate.boundary">{{ candidate.boundary }}</p>
                  <p v-if="candidate.skill">Skill：{{ candidate.skill }}</p>
                  <p v-if="candidate.requiredConditions?.length">功能所需条件：{{ candidate.requiredConditions.join('、') }}</p>
                </article>
              </details>
            </section>
            <section v-if="selected.cardKind === 'model'" class="invocation-section" aria-label="本轮调用">
              <h3>本轮调用 <span>{{ invocations.length }} 项</span></h3>
              <p v-if="!invocations.length" class="invocation-empty">{{ ['FINAL_ANSWER', 'ANSWER'].includes(selected.attributes?.['model.decision']) ? '本轮直接生成回答' : '尚无工具或 Skill 调用记录' }}</p>
              <article v-for="call in invocations" :key="call.id" class="invocation-card">
                <div class="invocation-heading"><strong>{{ call.label }}</strong><span :class="{ 'invocation-error': call.businessError || ['FAILED', 'TIMED_OUT'].includes(call.status), 'invocation-pending': !call.status }">{{ call.businessError ? '业务错误' : call.status ? statusLabel(call.status) : '未找到执行记录' }}</span></div>
                <code>{{ call.name }}</code>
                <p v-if="call.skill" class="invocation-skill">Skill：{{ call.skill }}</p>
                <p class="invocation-summary">{{ call.summary }}</p>
                <TraceCode title="调用参数" :value="call.arguments" :state="call.inputState" :reference="call.inputRef" />
                <details v-if="call.toolId"><summary>执行结果</summary><TraceCode title="工具返回" :value="call.output" :state="call.outputState" :reference="call.outputRef" /></details>
                <button v-if="call.toolId" type="button" class="invocation-link" @click="selectNode(call.toolId)">查看执行节点 →</button>
              </article>
            </section>
            <p v-if="selected.sourceNote || selected.legacy" class="projection-notice">{{ selected.sourceNote || '历史记录缺少任务 ID，按同一运行的父级范围归组；内部阶段可能未记录。' }}</p>
            <section v-if="selectedMembers.length" class="member-section"><h3>内部记录 · {{ selectedMembers.length }}</h3><button v-for="member in selectedMembers" :key="member.id" type="button" @click="selectNode(member.id)"><span>{{ nodeDisplayName(member) }}</span><small :class="{ error: ['FAILED', 'TIMED_OUT'].includes(member.status) }">{{ statusLabel(member.status) }} · {{ formatDuration(member.durationMs) }}</small></button></section>
            <dl v-if="selected.kind === 'MODEL' || selected.totalTokens != null" class="token-grid"><div><dt>输入 Token</dt><dd>{{ formatValue(selected.inputTokens) }}</dd></div><div><dt>输出 Token</dt><dd>{{ formatValue(selected.outputTokens) }}</dd></div></dl>
            <p v-if="payloadLoading || payloadError" class="payload-state" role="status">{{ payloadError || '正在加载节点正文…' }}</p>
            <TraceCode v-if="selectedSource" :key="`${selected.id}-input`" title="输入" :value="selected.input" :state="selected.inputState" :reference="selected.inputRef" />
            <TraceCode v-if="selectedSource" :key="`${selected.id}-output`" title="输出" :value="selected.output" :state="selected.outputState" :reference="selected.outputRef" />
            <TraceCode v-if="selectedSource && selected.errorRef" :key="`${selected.id}-error`" title="错误详情" :value="selected.error" :state="selected.errorState" :reference="selected.errorRef" />
            <details v-if="selectedSource && selected.provider_outputRef"><summary>模型原始返回</summary><TraceCode title="模型原始返回" :value="selected.provider_output" :state="selected.provider_outputState" :reference="selected.provider_outputRef" /></details>
            <section class="metadata-section"><h3>节点属性</h3><dl class="node-metadata"><div><dt>{{ selected.synthetic ? '展示分组 ID' : 'Span ID' }}</dt><dd>{{ selected.id }}</dd></div><div><dt>Parent Span ID</dt><dd>{{ selected.parentId || '根节点' }}</dd></div><div><dt>开始时间 · 北京</dt><dd>{{ date(selected.startMs) }} {{ clock(selected.startMs) }}</dd></div><div><dt>模型</dt><dd>{{ selected.model || '—' }}</dd></div><div><dt>原始名称</dt><dd>{{ selected.name }}</dd></div></dl></section>
            <TraceCode v-if="selectedSource" title="原始属性" :value="selected.attributes" state="COMPLETE" />
          </div>
        </aside>
      </div>
    </main>
  </div>
</template>

<style>
html.trace-workspace-page, html.trace-workspace-page body { min-width: 0; background: var(--workbench-paper); }
html.trace-workspace-page #app { max-width: none; background: var(--workbench-paper); }

</style>

<style scoped>
.trace-studio {
  --ink: var(--workbench-ink); --muted: var(--workbench-muted); --line: var(--workbench-border); --accent: var(--workbench-accent);
  --trace-mono: var(--workbench-mono);
  display: flex; height: 100dvh; min-height: 600px; color: var(--ink); background: var(--workbench-paper);
  font: 14px/1.6 'Harmony Font', 'PingFang SC', 'Microsoft YaHei', sans-serif; color-scheme: light;
}
.trace-studio * { box-sizing: border-box; }
.trace-studio.resizing { user-select: none; cursor: col-resize; }
.trace-studio button, .trace-studio select { font: inherit; }
.trace-studio button { cursor: pointer; }
.trace-studio button:disabled { opacity: .5; cursor: wait; }
.trace-studio a { color: inherit; text-decoration: none; }
.trace-studio :is(button, a, input, select, summary):focus-visible { outline: 3px solid var(--workbench-muted); outline-offset: 3px; }
.inspector-column:focus-visible { outline: 2px solid var(--workbench-muted); outline-offset: -2px; }
.workspace-sidebar { width: var(--sidebar-width); flex-shrink: 0; display: flex; flex-direction: column; padding: 28px 16px 20px; background: var(--workbench-paper); border-right: 1px solid var(--line); }
.trace-search { margin-top: 28px; }
.trace-search label { display: block; color: var(--workbench-accent); font-size: 12px; margin-bottom: 8px; }
.search-field { display: flex; border: 1px solid var(--workbench-border); background: var(--workbench-raised); border-radius: 9px; }
.search-field input { width: 100%; min-width: 0; border: 0; background: transparent; padding: 10px; color: var(--workbench-accent); font: 12px var(--trace-mono); border-radius: 9px; }
.search-field button { border: 0; background: transparent; color: var(--workbench-accent); display: grid; place-items: center; padding: 8px; border-radius: 8px; }
.query-state { font-size: 12px; color: var(--workbench-ink); margin: 8px 0 0; overflow-wrap: anywhere; }
.query-state.error, .payload-state { color: var(--workbench-accent); }
.payload-state { font-size: 12px; }
.projection-notice { margin: 0; padding: 8px 20px; font-size: 12px; color: var(--workbench-ink); background: var(--workbench-paper); }
.semantic-result { padding: 12px; border-radius: 8px; background: var(--workbench-paper); color: var(--workbench-accent); font-size: 13px; }
.invocation-section { margin: 20px 0; }
.intent-selection { margin: 20px 0; font-size: 12px; }
.intent-selection h3 { font-size: 13px; font-weight: 500; }
.intent-selection p { margin: 6px 0; overflow-wrap: anywhere; }
.intent-selection strong { font-weight: 500; color: var(--workbench-accent); }
.intent-selection summary { cursor: pointer; color: var(--workbench-muted); }
.intent-selection article { border-bottom: 1px solid var(--workbench-border); padding: 10px 0; }
.intent-selection h4 { font-size: 12px; font-weight: 500; margin: 0; display: flex; align-items: center; gap: 8px; }
.intent-selection h4 small,.intent-selection .chosen h4 { color: var(--workbench-accent); font-weight: 400; }
.invocation-section h3 { display: flex; justify-content: space-between; font-size: 13px; font-weight: 500; }
.invocation-section h3 span,.invocation-empty { color: var(--workbench-muted); font-size: 12px; }
.invocation-card { padding: 12px; margin-top: 10px; border-radius: 10px; background: var(--workbench-paper); border: 1px solid var(--workbench-border); }
.invocation-heading { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.invocation-heading strong { font-weight: 500; }.invocation-heading span { color: var(--workbench-success); }
.invocation-heading .invocation-error { color: var(--workbench-error); }
.invocation-heading .invocation-pending { color: var(--workbench-muted); }
.invocation-card code { display: block; overflow-wrap: anywhere; color: var(--workbench-muted); font: 11px/1.6 var(--trace-mono); margin-top: 4px; }
.invocation-skill,.invocation-summary { font-size: 12px; overflow-wrap: anywhere; margin: 8px 0; }
.invocation-card summary { font-size: 12px; cursor: pointer; }
.invocation-link { padding: 6px 0 0; border: 0; background: none; color: var(--workbench-accent); font-size: 12px!important; }
.member-section { margin: 20px 0; }
.member-section h3 { font-size: 12px; font-weight: 500; color: var(--workbench-ink); }
.member-section button { display: flex; justify-content: space-between; gap: 8px; width: 100%; background: none; border: 0; border-bottom: 1px solid var(--workbench-border); padding: 8px 0; text-align: left; color: var(--workbench-ink); }
.member-section button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.member-section small { font-size: 12px; flex-shrink: 0; color: var(--workbench-muted); }
.member-section small.error { color: var(--workbench-accent); }
.workspace-brand { font-size: 24px; font-weight: 800; letter-spacing: 2px; padding: 0 10px; }
.workspace-brand span { display: block; font-size: 12px; color: var(--workbench-muted); font-weight: 400; letter-spacing: 4px; margin-top: 2px; }
.history-heading { display: flex; justify-content: space-between; font-size: 12px; color: var(--workbench-ink); padding: 0 9px; margin: 24px 0 12px; }
.run-list { overflow: auto; flex: 1; min-height: 100px; }
.run-list button { display: block; text-align: left; width: 100%; border: 0; background: none; padding: 12px 10px; border-radius: 9px; margin-bottom: 5px; }
.run-list button:hover { background: rgba(41,39,34,0.502); }
.run-list button.selected { background: var(--workbench-raised); box-shadow: inset 2px 0 var(--workbench-accent), 0 2px 8px rgba(41,39,34,0.016); }
.run-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--workbench-ink); }
.run-dot { display: inline-block; width: 5px; height: 5px; border-radius: 50%; background: var(--workbench-muted); margin: 0 7px 2px 0; }
.run-dot.succeeded { background: var(--workbench-success); }.run-dot.failed, .run-dot.timed_out { background: var(--workbench-accent); }
.run-subtitle { display: block; font-size: 12px; color: var(--workbench-ink); margin-top: 5px; }
.run-id { display: block; font: 12px/1.6 var(--trace-mono); color: var(--workbench-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: 5px; }
.mobile-run-select { display: none; }
.run-info { font-size: 12px; border-top: 1px solid var(--workbench-border); padding-top: 15px; color: var(--workbench-ink); max-height: 40%; overflow: auto; }
.run-info summary { cursor: pointer; }
dl { margin: 0; }
.run-info dl { padding: 8px 0; }
.run-info dl>div, .node-metadata>div { margin-top: 13px; }
dt { color: var(--workbench-ink); font-size: 12px; }
dd { margin: 4px 0 0; overflow-wrap: anywhere; font: 12px/1.7 var(--trace-mono); color: var(--workbench-ink); }
.example-button { display: flex; align-items: center; gap: 8px; align-self: flex-start; margin-top: 16px; padding: 0; border: 0; background: none; font-size: 12px!important; color: var(--workbench-accent); }
.workspace-main { flex: 1; min-width: 0; display: flex; flex-direction: column; background: var(--workbench-paper); }
.workspace-header { height: 78px; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; padding: 0 24px; border-bottom: 1px solid var(--line); background: rgba(41,39,34,0.678); }
.workspace-heading { display: flex; align-items: center; gap: 10px; }
.brand-symbol { display: grid; place-items: center; width: 37px; height: 37px; border-radius: 12px; color: var(--workbench-accent); background: linear-gradient(135deg, var(--workbench-border), var(--workbench-paper)); }
h1 { margin: 0; font-size: 15px; font-weight: 650; }
.demo-badge { color: var(--workbench-accent); background: var(--workbench-paper); border-radius: 5px; padding: 2px 6px; font-size: 12px; }
.back-agent { font-size: 12px; color: var(--workbench-ink)!important; }
.run-toolbar { display: flex; align-items: center; gap: 14px; padding: 18px 24px; min-width: 0; }
.run-context { min-width: 0; }
.run-context h2 { margin: 0 0 3px; font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.status-pill { display: inline-flex; align-items: center; padding: 3px 8px; border-radius: 5px; font-size: 12px; line-height: 1.5; color: var(--workbench-ink); background: var(--workbench-paper); white-space: nowrap; }
.status-pill.succeeded { color: var(--workbench-success); background: var(--workbench-success-soft); }
.status-pill.failed, .status-pill.timed_out { color: var(--workbench-accent); background: var(--workbench-paper); }
.status-pill.running { color: var(--workbench-accent); background: var(--workbench-paper); }
.status-pill.needs_input { color: var(--workbench-ink); background: var(--workbench-paper); }
.model-name { display: block; font: 12px/1.6 var(--trace-mono); color: var(--workbench-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-metrics { display: flex; gap: 18px; margin-left: auto; flex-shrink: 0; color: var(--workbench-ink); font: 12px var(--trace-mono); }
.run-metrics>span { display: flex; gap: 6px; align-items: center; }
.run-metrics small { font: 12px 'Microsoft YaHei', sans-serif; color: var(--workbench-ink); }
.workspace-panels { display: grid; grid-template-columns: minmax(0, 1fr) 8px var(--inspector-width); flex: 1; min-height: 0; margin: 0 20px 20px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; background: var(--workbench-raised); }
.graph-column { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.view-tabs { display: flex; align-items: center; gap: 24px; height: 50px; padding: 0 20px; border-bottom: 1px solid var(--line); flex-shrink: 0; }
.view-tabs button { height: 100%; padding: 0; border: 0; border-bottom: 2px solid transparent; background: none; color: var(--workbench-ink); font-size: 12px; }
.view-tabs button.active { color: var(--workbench-accent); border-bottom-color: var(--workbench-accent); }
.view-tabs>span { margin-left: auto; color: var(--workbench-muted); font-size: 12px; }
.inspector-column { min-width: 0; overflow: auto; border-left: 1px solid var(--line); scrollbar-width: thin; scrollbar-color: var(--workbench-border) transparent; }
.inspector-heading { position: sticky; top: 0; z-index: 2; background: var(--workbench-raised); height: 50px; display: flex; align-items: center; border-bottom: 1px solid var(--line); padding: 0 20px; font-size: 12px; color: var(--workbench-ink); }
.inspector-content { padding: 24px 20px; }
.selected-node-heading { display: flex; align-items: flex-start; gap: 10px; }
.selected-node-heading>div { min-width: 0; }
.selected-node-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--workbench-paper); color: var(--workbench-accent); flex-shrink: 0; }
.selected-node-icon.tool { background: var(--workbench-success-soft); color: var(--workbench-success); }
.selected-node-heading small { display: block; margin-top: 3px; font: 12px/1.7 var(--trace-mono); color: var(--workbench-ink); overflow-wrap: anywhere; }
.selected-node-heading h2 { font-size: 15px; font-weight: 600; margin: 0; overflow-wrap: anywhere; color: var(--workbench-accent); }
.node-status-line { display: flex; gap: 12px; align-items: center; margin: 18px 0 24px; color: var(--workbench-ink); font-size: 12px; flex-wrap: wrap; }
.node-status-line>span { display: inline-flex; align-items: center; gap: 5px; }
.metadata-section { border-top: 1px solid var(--line); padding-top: 20px; margin-top: 26px; }
.metadata-section h3 { margin: 0; color: var(--workbench-ink); font-size: 12px; font-weight: 500; }
.token-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; background: var(--workbench-sidebar); border: 1px solid var(--workbench-border); border-radius: 9px; padding: 12px 14px; margin: 0 0 24px; }
.token-grid>div+div { border-left: 1px solid var(--workbench-border); padding-left: 16px; }
.token-grid dd { font-size: 18px; color: var(--workbench-accent); line-height: 1.5; }
.timing-panel { flex: 0 0 260px; padding: 14px 20px; overflow: auto; border-top: 1px solid var(--line); scrollbar-width: thin; scrollbar-color: var(--workbench-border) transparent; }
.timing-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.timing-heading h2 { font-size: 12px; font-weight: 500; margin: 0; color: var(--workbench-ink); }
.timing-heading>span { font: 12px var(--trace-mono); color: var(--workbench-ink); }
.timing-axis { margin: 0 66px 8px 150px; display: flex; justify-content: space-between; color: var(--workbench-ink); font: 12px var(--trace-mono); }
.timing-row { display: grid; grid-template-columns: 140px minmax(80px, 1fr) 60px; gap: 10px; align-items: center; width: 100%; background: none; border: 0; border-radius: 6px; padding: 5px; color: var(--workbench-ink); text-align: left; font-size: 12px!important; }
.timing-row:hover, .timing-row.active { background: var(--workbench-paper); }
.timing-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.timing-track { height: 6px; position: relative; background: var(--workbench-paper); border-radius: 3px; }
.timing-track i { position: absolute; height: 6px; border-radius: 3px; background: var(--workbench-muted); }
.timing-track i.bar-model { background: var(--workbench-accent); }
.timing-track i.bar-tool { background: var(--workbench-success); }
.timing-row.failed .timing-track i { background: var(--workbench-accent); }
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
  .mobile-run-select { display: block; grid-column: 1/-1; width: 100%; background: var(--workbench-raised); border: 1px solid var(--workbench-border); border-radius: 8px; padding: 9px; color: var(--workbench-ink); font-size: 12px!important; }
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
/* Shared surfaces and typography; the trace retains its diagnostic density. */
.trace-studio {--ink:var(--workbench-ink);--muted:var(--workbench-muted);--line:var(--workbench-border);--accent:var(--workbench-accent);background:var(--workbench-paper);font-family:var(--workbench-ui);font-variant-numeric:tabular-nums;}
.workspace-sidebar {background:var(--workbench-sidebar);padding-top:32px;}
.workspace-brand {font:500 28px/1.3 var(--workbench-display);letter-spacing:-.7px;}
.workspace-brand span {font:12px/1.8 var(--workbench-ui);letter-spacing:3px;color:var(--workbench-muted);}
.workspace-main,.workspace-header {background:var(--workbench-paper);}
.workspace-header {height:44px;min-height:44px;}
.workspace-heading h1 {font-size:14px;font-weight:500;}
.brand-symbol {color:var(--workbench-accent);background:transparent;border-radius:0;font-size:32px;}
.workspace-panels,.inspector-heading {background:var(--workbench-raised);}
.workspace-panels {border-width:.5px;}
.run-list button.selected {background:var(--workbench-raised);box-shadow:none;}
.run-list button:hover {background:var(--workbench-paper);}
.search-field {background:var(--workbench-raised);border-color:var(--workbench-border);}
.search-field:focus-within {border-color:var(--workbench-accent);}
.demo-badge,.status-pill.running {color:var(--workbench-accent);background:var(--workbench-soft);}
.status-pill.succeeded {color:var(--workbench-success);background:var(--workbench-success-soft);}
.status-pill.failed,.status-pill.timed_out {color:var(--workbench-error);background:var(--workbench-error-soft);}
.status-pill.needs_input {color:var(--workbench-warning);background:var(--workbench-warning-soft);}
.query-state.error,.payload-state,.member-section small.error {color:var(--workbench-error);}
.view-tabs button.active {color:var(--workbench-accent);border-bottom-color:var(--workbench-accent);}
.selected-node-icon {color:var(--workbench-accent);background:var(--workbench-soft);}
.selected-node-icon.tool {color:var(--workbench-success);background:var(--workbench-success-soft);}
.timing-row:hover,.timing-row.active {background:var(--workbench-soft);}
.timing-track {background:var(--workbench-sidebar);}
.timing-track i {background:var(--workbench-connector);}
.timing-track i.bar-model {background:var(--workbench-accent);}
.timing-track i.bar-tool {background:var(--workbench-success);}
.timing-row.failed .timing-track i {background:var(--workbench-error);}
.run-dot.failed,.run-dot.timed_out {background:var(--workbench-error);}
.trace-studio :is(button,a,input,select,summary):focus-visible {outline:2px solid var(--workbench-focus);outline-offset:3px;}
.inspector-column:focus-visible {outline-color:var(--workbench-focus);}
@media(max-width:700px){.workspace-brand{font-size:24px;}.workspace-header{height:64px;}.view-tabs{gap:16px;}.view-tabs>span{font-size:12px;}.run-context{max-width:100%;}}
.workspace-header {height:44px;min-height:44px;}
.workspace-heading h1 {font-size:18px;font-weight:500;}
.workspace-heading .brand-symbol {width:24px;height:24px;font-size:24px;}
.run-toolbar {min-height:44px;padding:8px 24px;gap:10px;flex-wrap:wrap;}
.run-context {display:flex;align-items:center;gap:12px;min-width:0;}
.run-context h2 {margin:0;font-size:15px;font-weight:500;}
.model-name {font:11px/1.6 var(--workbench-ui);color:var(--workbench-muted);}
.view-tabs,.inspector-heading {height:40px;}
.workspace-panels {margin:0 24px 24px;}
.projection-notice {font-size:11px;line-height:1.5;}
@media(max-width:700px){.run-toolbar{padding:8px 16px;}.workspace-header{padding:0 16px;}.workspace-panels{margin:0 16px 16px;}.run-context{flex-wrap:wrap;gap:4px 8px;}.run-metrics{gap:10px;}.workspace-heading h1{font-size:17px;}}


.workspace-heading h1 {font:500 25.6px/1.4 var(--workbench-heading);}
.run-context h2 {font:500 20.8px/1.5 var(--workbench-heading);color:var(--workbench-ink);}
.run-metrics,.timing-axis,.timing-heading>span,.model-name,.selected-node-heading small,dd {font-family:var(--workbench-ui);font-variant-numeric:tabular-nums;}
.run-id {font-family:var(--trace-mono);}
.selected-node-heading h2,.token-grid dd,.trace-search label {color:var(--workbench-ink);}
.search-field input {color:var(--workbench-ink);font-family:var(--workbench-ui);}
.search-field:focus-within {border-color:var(--workbench-focus);box-shadow:var(--workbench-focus-ring);}
.search-field input:focus-visible {outline:none;}
.back-agent,.example-button {color:var(--workbench-link)!important;}
.view-tabs button.active {color:var(--workbench-ink);border-bottom-color:var(--workbench-charcoal);}
.inspector-heading,.token-grid,.member-section button {border-color:var(--workbench-border-subtle);}
@media(max-width:700px){.workspace-heading h1 {font-size:21px;}.run-context h2{font-size:20px;}}
</style>
