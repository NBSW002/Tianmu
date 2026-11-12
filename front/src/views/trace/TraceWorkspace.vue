<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAgentTrace, getTracePayload, getTraceRecords, getAgentModelMetadata } from '@/api/agent'
import { useUserStore } from '@/store/user'
import '@/assets/css/workbench-tokens.css'
import TraceGraph from '@/components/trace/TraceGraph.vue'
import TraceDivider from '@/components/trace/TraceDivider.vue'
import TraceIcon from '@/components/trace/TraceIcon.vue'
import TraceCode from '@/components/trace/TraceCode.vue'
import TraceContext from '@/components/trace/TraceContext.vue'
import TraceModelDetails from '@/components/trace/TraceModelDetails.vue'
import TraceResponseTiming from '@/components/trace/TraceResponseTiming.vue'
import TraceTokenUsage from '@/components/trace/TraceTokenUsage.vue'
import TraceInvocationTrail from '@/components/trace/TraceInvocationTrail.vue'
import TraceRunCalls from '@/components/trace/TraceRunCalls.vue'
import { modelResponseDetails, runInspectorSummary, modelInspectorDetails, executionSummary } from '@/trace/trace-inspector.mjs'
import { demoFiles } from '@/trace/demo-trace.mjs'
import { parseTraceFile, formatDuration, formatValue, statusLabel, nodeDisplayName } from '@/trace/trace-data.mjs'
import { buildTracePresentation, buildTraceOverview, defaultExpandedStages, layoutPresentation, modelInvocations, modelReasoningSources, intentDetails, contextDetails, compactionDetails, preparationDetails, executionFacts, presentNode, cardTitle, cardHighlight, cardIcon } from '@/trace/trace-presentation.mjs'
import { traceDetailSources, recordDetails } from '@/trace/trace-presentation.mjs'

const examples = demoFiles.flatMap((data) => parseTraceFile(JSON.stringify(data), '内置演示日志'))
const traces = ref(examples)
const activeId = ref(examples[0].id)
const selectedId = ref(examples[0].nodes.find((node) => node.type === 'llm_inference').id)
const mode = ref('graph')
const graphView = ref('sequence')
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
const payloadAttempt = ref(0)
const inspector = ref(null)
const userStore = useUserStore()
const route = useRoute()
let queryController, payloadController, recordsController, metadataController
const exportingRecords = ref(false)
const exportError = ref('')
const studio = ref(null)
const inspectorWidth = ref(Math.min(560, Math.max(440, window.innerWidth * 0.38)))
const studioWidth = ref(window.innerWidth)
const resizing = ref(false)
const maxInspector = computed(() => Math.max(360, Math.min(720, studioWidth.value - 560)))
function resizeInspector(delta) { inspectorWidth.value = Math.max(360, Math.min(maxInspector.value, inspectorWidth.value + delta)) }
let workspaceObserver
onMounted(() => {
  workspaceObserver = new ResizeObserver(([entry]) => {
    studioWidth.value = entry.contentRect.width
    nextTick(positionTabIndicator)
    if (studioWidth.value > 1100) resizeInspector(0)
  })
  workspaceObserver.observe(studio.value)
  nextTick(positionTabIndicator)
})
onBeforeUnmount(() => workspaceObserver?.disconnect())
const previousTitle = document.title
document.title = 'Trace Studio · EchoFrame运行追踪'
onBeforeUnmount(() => { document.title = previousTitle; queryController?.abort(); payloadController?.abort(); recordsController?.abort(); metadataController?.abort() })

const trace = computed(() => traces.value.find((item) => item.id === activeId.value) || traces.value[0])
const presentation = computed(() => buildTraceOverview(buildTracePresentation(trace.value.nodes)))
const expanded = ref(defaultExpandedStages(presentation.value))
const graph = computed(() => layoutPresentation(presentation.value, expanded.value))
const selected = computed(() => presentation.value.allCards.get(selectedId.value) || (presentation.value.rawById.has(selectedId.value) ? presentNode(presentation.value.rawById.get(selectedId.value)) : null))
const selectedSource = computed(() => presentation.value.rawById.get(selected.value?.sourceId))
const selectedMembers = computed(() => (selected.value?.memberIds || []).filter(id => id !== selected.value.sourceId).map(id => presentation.value.rawById.get(id)).filter(Boolean))
const invocations = computed(() => modelInvocations(selected.value, presentation.value))
const reasoningSources = computed(() => selected.value?.cardKind === 'run' ? [] : modelReasoningSources(selected.value, presentation.value))
const intentSelection = computed(() => intentDetails(selected.value))
const runSelection = computed(() => selected.value?.cardKind === 'run')
const providerMetadata = ref([])
const runSummary = computed(() => runInspectorSummary(trace.value,presentation.value,providerMetadata.value))
const modelDetails = computed(() => {
  if (runSelection.value) return runSummary.value.modelDetails
  const candidates = [selectedSource.value, ...selectedMembers.value].filter(node => node && (node.kind === 'MODEL' || node.type === 'llm_inference') && node.attributes?.['model.dispatched'] !== false)
  const ids = new Set(candidates.map(node => node.id))
  const parents = new Set()
  for (const node of candidates) {
    let parent = presentation.value.rawById.get(node.parentId)
    const seen = new Set()
    while (parent && !seen.has(parent.id)) {
      seen.add(parent.id); if (ids.has(parent.id)) parents.add(parent.id)
      parent = presentation.value.rawById.get(parent.parentId)
    }
  }
  const scopeIds = new Set([selected.value?.sourceId || selected.value?.id,...(selected.value?.memberIds || []),...invocations.value.map(call => call.toolId).filter(Boolean)])
  const scope = trace.value.nodes.filter(node => {
    const seen=new Set();let current=node
    while (current && !seen.has(current.id)) {
      if (scopeIds.has(current.id)) return true
      seen.add(current.id);current=presentation.value.rawById.get(current.parentId)
    }
    return false
  })
  return {...modelInspectorDetails(candidates.filter(node => !parents.has(node.id)), trace.value.nodes,providerMetadata.value),...executionSummary(scope)}
})
watch([() => trace.value.remote, () => userStore.user?.token], async ([remote,token], _, onCleanup) => {
  metadataController?.abort(); providerMetadata.value=[]
  if (!remote || !token) return
  const controller = new AbortController(); metadataController=controller
  onCleanup(() => controller.abort())
  try {
    const result = await getAgentModelMetadata(controller.signal)
    if (!controller.signal.aborted && Array.isArray(result)) providerMetadata.value=result
  } catch { /* The Trace remains readable if provider discovery is unavailable. */ }
}, { immediate:true })
const contextSelection = computed(() => {
  if (runSelection.value) return modelDetails.value.peakContext || {visible:false,slots:[]}
  const context=contextDetails(selected.value)
  if (context.visible) return context
  const request=selectedMembers.value.find(node => node.name === 'model.request') || selectedSource.value
  const attrs=request?.attributes || {}
  const slots=[['context.system_bytes','系统消息'],['context.user_bytes','用户消息'],['context.assistant_bytes','助手消息'],['context.tool_result_bytes','工具结果'],['context.tools_bytes','工具定义']]
    .filter(([key]) => Number.isFinite(attrs[key]) && attrs[key]>0).map(([name,label])=>({name,label,bytes:attrs[name]}))
  return {...context,visible:slots.length>0,slots}
})
const responseSelection = computed(() => modelResponseDetails(selected.value,presentation.value.rawById))
const compactionSelection = computed(() => compactionDetails(selected.value))
const preparationSelection = computed(() => preparationDetails(selected.value))
const executionSelection = computed(() => executionFacts(selected.value, presentation.value).filter(fact => fact !== '思考状态未记录'))
const usefulNotices = computed(() => presentation.value.notices.filter(notice => !['情绪识别：未执行或未记录', '澄清判断：无独立记录'].includes(notice)))
const standardSelection = computed(() => recordDetails(selectedSource.value).filter(row => row.value !== '未采集' && row.value !== '本节点不适用'))
const privateSources = computed(() => selected.value?.cardKind === 'run' ? [] : ['prompt','code','execution'].flatMap(direction => traceDetailSources(selected.value,presentation.value,direction).map(source => ({direction,source}))))
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
watch([() => trace.value.id, () => selectedSource.value?.id, payloadAttempt], async (_, __, onCleanup) => {
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
    const facts=runSummary.value.modelDetails
    const skillInputs=runSelection.value && facts.skillNames.reduce((sum,call)=>sum+call.count,0)<facts.skillCalls
      ? runSummary.value.tools.filter(call=>call.name==='read_skill').map(call=>presentation.value.rawById.get(call.id)).filter(Boolean) : []
    const results = await Promise.allSettled([...new Set([node, ...related, ...skillInputs, ...reasoningSources.value, ...privateSources.value.map(item=>item.source)])].flatMap(target =>
      [...new Set([...(target === node ? ['input', 'output', 'error', 'provider_output', 'reasoning'] : related.includes(target) ? ['input', 'output'] : skillInputs.includes(target) ? ['input'] : []),...(reasoningSources.value.includes(target)?['reasoning']:[]),...privateSources.value.filter(item=>item.source===target).map(item=>item.direction)])].map(async direction => {
        const reference = target[`${direction}Ref`]
        if (target[direction] != null || !reference || reference === 'UNAVAILABLE') return
        let data
        try {data=await getTracePayload(target.attributes['run.id'] || current.runId, reference, controller.signal)}
        catch(error){if(skillInputs.includes(target) && !related.includes(target))return;throw error}
        if (!controller.signal.aborted) {
          target[direction] = data.value ?? data.preview ?? null
          target[`${direction}State`] = data.state
        }
      })))
    const failure = results.find((result) => result.status === 'rejected')
    if (failure) throw failure.reason
  } catch (error) { if (!controller.signal.aborted) payloadError.value = `部分节点正文加载失败：${error.message}` }
  finally { if (!controller.signal.aborted) payloadLoading.value = false }
})
watch(selectedId, async () => { await nextTick(); if (inspector.value) inspector.value.scrollTop = 0 })
watch(() => userStore.user?.token, () => {
  recordsController?.abort();exportError.value=''
  cancelQuery()
  queryError.value = '登录状态已变化，请重新查询'
  payloadController?.abort()
  traces.value = traces.value.filter((item) => !item.remote)
  if (!traces.value.length) traces.value = examples
  if (!traces.value.some((item) => item.id === activeId.value)) activeId.value = traces.value[0].id
  changeTrace()
}, { flush: 'sync' })
watch(() => trace.value.id, () => { recordsController?.abort(); exportError.value='' })
async function exportRecords() {
  recordsController?.abort(); const controller=new AbortController(); recordsController=controller; exportingRecords.value=true;exportError.value=''
  const id=trace.value.id
  try {
    const rows=await getTraceRecords(id,controller.signal)
    if(controller.signal.aborted) return
    if(!Array.isArray(rows) || rows.some(row=>row.schema_version!=='1.0' || row.trace_id!==id))throw new Error('结构化记录格式不正确')
    const url=URL.createObjectURL(new Blob([JSON.stringify(rows,null,2)],{type:'application/json'}));const link=document.createElement('a');link.href=url;link.download=`trace-${id}-records.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000)
  } catch(error) { if(!controller.signal.aborted)exportError.value=error.message }
  finally { if(recordsController===controller)exportingRecords.value=false }
}
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
  <div ref="studio" class="trace-studio" :class="{ resizing }" :style="{ '--inspector-width': `${inspectorWidth}px` }">
    <main class="workspace-main">
      <header class="workspace-header">
        <div class="workspace-heading"><router-link class="trace-wordmark" to="/">EchoFrame</router-link><span class="brand-separator">/</span><h1>运行追踪</h1><span class="demo-badge">{{ trace.demo ? '示例' : trace.remote ? '在线查询' : '本地日志' }}</span></div>
        <router-link class="back-agent" to="/agent">返回助手 ↗</router-link>
        <button v-if="trace.remote" class="export-records" type="button" :disabled="exportingRecords" title="导出状态、计量和受控详情引用，正文仍需权限校验" @click="exportRecords">{{ exportingRecords ? '正在导出…' : '导出结构化记录' }}</button>
      </header>
      <div class="trace-controlbar">
        <label class="trace-run-picker">运行记录<select v-model="activeId" aria-label="当前运行" @change="cancelQuery(); changeTrace()"><option v-for="item in traces" :key="item.id" :value="item.id">{{ item.title }} · {{ date(item.startMs) }} {{ clock(item.startMs) }}</option></select></label>
        <form class="trace-search" @submit.prevent="queryTrace"><label for="trace-id-query">Trace ID</label><div class="search-field"><input id="trace-id-query" v-model="traceQuery" placeholder="输入 Trace ID" autocomplete="off" spellcheck="false" :aria-invalid="!!queryError" aria-describedby="trace-query-state" @input="cancelQuery(); queryError = ''" /><button type="submit" :disabled="querying" :aria-label="querying ? '正在查询 Trace' : '查询 Trace'"><TraceIcon name="arrow" :size="16" /></button></div></form>
        <details class="trace-run-info"><summary>运行信息</summary><dl><dt>开始时间 · 北京</dt><dd>{{ date(trace.startMs) }} {{ clock(trace.startMs) }}</dd><dt>Run ID</dt><dd>{{ trace.runId }}</dd><dt>Trace ID</dt><dd>{{ trace.id }}</dd><dt>来源</dt><dd>{{ trace.source }}</dd></dl><button type="button" @click="trace.demo ? downloadExample() : resetDemo()">{{ trace.demo ? '下载示例' : '返回示例' }}</button></details>
        <p v-if="queryError || querying" id="trace-query-state" class="query-state" :class="{error:queryError}" role="status">{{ queryError || '正在查询…' }}</p>
        <span v-else id="trace-query-state" class="sr-only" role="status"></span>
      </div>
      <div class="run-toolbar">
        <p v-if="exportError" role="status">{{ exportError }}</p>
        <div class="run-context"><h2 :title="trace.title">{{ trace.title }}</h2><span class="model-name" :title="trace.model">{{ trace.model }}</span></div>
        <span class="status-pill" :class="trace.status.toLowerCase()">{{ statusLabel(trace.status) }}</span>
        <div class="run-metrics"><span title="运行自身记录的耗时；可能与所有节点覆盖的时间跨度不同"><TraceIcon name="clock" :size="14" /><small>运行耗时</small>{{ formatDuration(trace.durationMs) }}</span><span><TraceIcon name="token" :size="14" />{{ formatValue(trace.totalTokens) }}<small>Token</small><small v-if="!trace.usageComplete" title="部分节点未提供用量">部分</small></span><span v-if="trace.foregroundModelCalls != null || trace.preparationModelCalls != null || trace.backgroundModelCalls != null" title="来源任务的历史调用不计入本次"><small>本次模型调用：</small>前台 {{ trace.foregroundModelCalls ?? '未知' }} · 前置 {{ trace.preparationModelCalls ?? '未知' }} · 后台 {{ trace.backgroundModelCalls ?? '未知' }}</span></div>
      </div>

      <div class="workspace-panels">
        <section class="graph-column" aria-label="执行过程">
          <div ref="viewTabs" class="view-tabs">
            <button type="button" :class="{ active: mode === 'graph' }" :aria-pressed="mode === 'graph'" @click="mode = 'graph'">执行图</button>
            <button type="button" :class="{ active: mode === 'list' }" :aria-pressed="mode === 'list'" @click="mode = 'list'">原始节点</button>
            <div class="view-meta">
              <div v-if="mode === 'graph'" class="graph-view-switch" role="group" aria-label="连线视图"><button type="button" :aria-pressed="graphView === 'sequence'" title="按记录时间推算的阅读顺序，不表示父子或因果关系" @click="graphView = 'sequence'">执行顺序 · 时间</button><button type="button" :aria-pressed="graphView === 'ancestry'" title="展示 Span 的直接父子与省略中间节点后的祖先关系" @click="graphView = 'ancestry'">Span 父子</button></div>
              <div v-if="mode === 'graph' && graphView === 'ancestry'" class="trace-legend" aria-label="连线图例"><span title="直接父子 Span">─ 父子</span><span title="中间 Span 未显示，连接到最近的可见祖先">┄ 祖先</span></div>
              <span class="view-count" title="卡片是合并后的可视步骤；Span 是日志中的原始记录">{{ graph.nodes.length }} 卡片 / {{ trace.nodes.length }} Span</span>
            </div>
          </div>
          <TraceGraph :trace="trace" :graph="graph" :expanded="expanded" :selected-id="selectedId" :mode="mode" :graph-view="graphView" @select="selectNode" @toggle="toggleGroup" />
          <p v-if="mode === 'graph' && usefulNotices.length" class="projection-notice">{{ usefulNotices.join(' · ') }}</p>
          <section class="timing-panel" aria-label="执行时间线">
            <div class="timing-header">
              <div class="timing-heading"><h2>执行时间线</h2><span title="从最早节点开始到最晚节点结束的时间范围">节点跨度 {{ formatDuration(trace.extentMs) }}</span></div>
              <div class="timing-axis"><span>0 ms</span><span>{{ Math.round(trace.extentMs / 2) }} ms</span><span>{{ Math.round(trace.extentMs) }} ms</span></div>
            </div>
            <button v-for="node in timingNodes" :key="node.id" type="button" class="timing-row" :class="{ active: node.id === selectedId, failed: ['FAILED', 'TIMED_OUT'].includes(node.status) }" :aria-label="`查看 ${node.label}，耗时 ${formatDuration(node.durationMs)}`" :aria-pressed="node.id === selectedId" @click="selectNode(node.id)">
              <span class="timing-name" :title="node.label">{{ mode === 'graph' ? cardTitle(node) : nodeDisplayName(node) }}</span><span class="timing-track"><i v-if="node.durationMs != null && node.offsetMs != null" :style="timingStyle(node)" :class="`bar-${node.kind.toLowerCase()}`" /></span><span class="timing-value">{{ formatDuration(node.durationMs) }}</span>
            </button>
          </section>
        </section>

        <TraceDivider label="调整节点详情宽度" :value="inspectorWidth" :min="360" :max="maxInspector" reverse @resize="resizeInspector" @dragging="resizing = $event" @reset="resizeInspector(Math.min(560, studioWidth * 0.38) - inspectorWidth)" />
        <aside ref="inspector" class="inspector-column" aria-label="节点详情" tabindex="0">
          <div class="inspector-heading"><span class="inspector-label">节点详情</span><div v-if="selected" class="inspector-selection"><TraceIcon :name="cardIcon(selected)" :size="15" /><strong data-testid="inspector-node-name" :title="mode === 'list' ? nodeDisplayName(selected) : cardTitle(selected)">{{ mode === 'list' ? nodeDisplayName(selected) : cardTitle(selected) }}</strong><span v-if="!runSelection" class="status-pill" :class="selected.status.toLowerCase()">{{ statusLabel(selected.status) }}</span><span v-if="!runSelection && selected.durationMs != null" class="selection-duration" title="节点耗时">{{ formatDuration(selected.durationMs) }}</span></div></div>
          <div v-if="selected" class="inspector-content" :key="selected.id">
            <p v-if="!runSelection && (cardHighlight(selected) || selected.summary)" class="selected-result">{{ cardHighlight(selected) || selected.summary }}</p>

            <div v-if="runSelection || contextSelection.visible || selected.kind === 'MODEL' || selected.cardKind === 'model' || selected.totalTokens != null" class="inspector-dashboard">
              <TraceContext :selection="contextSelection" :show-empty="runSelection || selected.kind === 'MODEL' || selected.cardKind === 'model'" :aggregate="runSelection" :window-usage="modelDetails.window" />
              <div class="model-performance">
              <TraceResponseTiming v-if="runSelection" :timing="runSummary.ttftTiming" aggregate />
              <TraceResponseTiming v-else-if="selected.kind === 'MODEL' || selected.cardKind === 'model'" :timing="responseSelection" :non-streaming="modelDetails.types.length === 1 && modelDetails.types[0] === '非流式'" />
              <TraceTokenUsage v-if="runSelection || selected.kind === 'MODEL' || selected.totalTokens != null" :node="runSelection ? trace : selected" :partial="runSelection && !trace.usageComplete" />
              </div>
              <TraceModelDetails :details="modelDetails" />
              <TraceRunCalls v-if="runSelection" :calls="runSummary.events" @select="selectNode" />
              <TraceInvocationTrail v-if="selected.cardKind === 'model'" :calls="invocations" :decision="selected.attributes?.['model.decision']" @select="selectNode" />
            </div>
            <div v-if="payloadLoading || payloadError" class="payload-state" role="status"><span>{{ payloadError || '正在加载节点正文…' }}</span><button v-if="payloadError && !payloadLoading" type="button" @click="payloadAttempt++">重试加载</button></div>
            <TraceCode v-if="selectedSource && (selected.input != null || selected.inputRef)" :key="`${selected.id}-input`" title="输入" :value="selected.input" :state="selected.inputState" :reference="selected.inputRef" />
            <TraceCode v-if="selectedSource && (selected.output != null || selected.outputRef)" :key="`${selected.id}-output`" title="输出" :value="selected.output" :state="selected.outputState" :reference="selected.outputRef" />
            <section v-if="selected.attributes?.['analysis.raw_records'] != null" class="intent-selection" aria-label="反馈聚类统计">
              <h3>反馈聚类统计</h3>
              <p>原始 {{ selected.attributes['analysis.raw_records'] }} 条 · 过滤 {{ selected.attributes['analysis.filtered_records'] }} 条</p>
              <p>主题桶 {{ selected.attributes['analysis.clusters'] }} 个 · 未归类 {{ selected.attributes['analysis.unclassified_records'] ?? 0 }} 条</p>
              <p>模型读取 {{ selected.attributes['analysis.representatives'] }} 条代表；桶数量是向量近似分配。</p>
            </section>
            <section v-if="compactionSelection.visible" class="intent-selection" aria-label="上下文压缩范围">
              <h3>压缩范围</h3>
              <p v-if="compactionSelection.trigger">{{ compactionSelection.trigger }}</p>
              <p v-if="compactionSelection.from != null">消息序号 {{ compactionSelection.from }}–{{ compactionSelection.through }} · {{ compactionSelection.messages }} 条</p>
              <p v-if="compactionSelection.inputBytes != null">{{ compactionSelection.inputBytes }} B → {{ compactionSelection.outputBytes }} B，节省 {{ compactionSelection.savedBytes }} B</p>
              <p v-if="compactionSelection.prompt">策略 {{ compactionSelection.prompt }}</p>
              <p>原始消息保留；摘要只覆盖标明的范围，失效来源不会继续使用。</p>
            </section>
            <section v-if="standardSelection.length" class="metadata-section" aria-label="统一记录字段"><h3>统一记录字段</h3><dl class="node-metadata"><div v-for="row in standardSelection" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div></dl></section>
            <section v-if="executionSelection.length" class="intent-selection" aria-label="执行与复用情况"><h3>执行与复用情况</h3><p v-for="fact in executionSelection" :key="fact">{{ fact }}</p></section>
            <section v-if="selected.cardKind === 'merge' && preparationSelection.visible" class="intent-selection" aria-label="前置合并与改写">
              <h3>前置合并与改写</h3>
              <p>{{ preparationSelection.reason }}</p>
              <p>规则任务：{{ preparationSelection.ruleTask }}</p><p>最终任务：{{ preparationSelection.effectiveTask }}</p>
              <p v-if="preparationSelection.defaults.length">默认补齐：{{ preparationSelection.defaults.join('、') }}</p>
              <p>Query 改写：{{ preparationSelection.rewrite }}</p>
            </section>
            <section v-if="['intent','merge'].includes(selected.cardKind) && intentSelection.visible" class="intent-selection" aria-label="意图能力选择">
              <h3>能力识别</h3>
              <p>主目标：<strong>{{ intentSelection.primary?.label || '未选择具体能力（兼容识别器）' }}</strong></p>
              <p v-if="intentSelection.related.length">关联目标：{{ intentSelection.related.map(item => item.label).join('、') }}</p>
              <section class="candidate-list" aria-label="本轮候选能力"><h4>本轮候选能力 · {{ intentSelection.candidates.length }}</h4>
                <article v-for="candidate in intentSelection.candidates" :key="candidate.id" :class="{ chosen: candidate.id === intentSelection.primary?.id }">
                  <h4>{{ candidate.label }}<small v-if="candidate.id === intentSelection.primary?.id">主目标</small><small v-else-if="intentSelection.related.some(item => item.id === candidate.id)">关联目标</small></h4>
                  <p v-if="candidate.description">{{ candidate.description }}</p><p v-if="candidate.boundary">{{ candidate.boundary }}</p>
                  <p v-if="candidate.skill">Skill：{{ candidate.skill }}</p>
                  <p v-if="candidate.requiredConditions?.length">功能所需条件：{{ candidate.requiredConditions.join('、') }}</p>
                </article>
              </section>
            </section>
            <section v-if="selected.cardKind === 'model'" class="invocation-section" aria-label="本轮调用">
              <h3>调用详情 · {{ invocations.length }} 项</h3>
              <p v-if="!invocations.length" class="invocation-empty">{{ ['FINAL_ANSWER', 'ANSWER'].includes(selected.attributes?.['model.decision']) ? '本轮直接生成回答' : '尚无工具或 Skill 调用记录' }}</p>
              <article v-for="call in invocations" :key="call.id" class="invocation-card">
                <div class="invocation-heading"><strong>{{ call.label }}</strong><span :class="{ 'invocation-error': call.businessError || ['FAILED', 'TIMED_OUT'].includes(call.status), 'invocation-pending': !call.status }">{{ call.businessError ? '业务错误' : call.status ? statusLabel(call.status) : '未找到执行记录' }}</span></div>
                <code>{{ call.name }}</code>
                <p v-if="call.skill" class="invocation-skill">Skill：{{ call.skill }}</p>
                <p class="invocation-summary">{{ call.summary }}</p>
                <div class="invocation-payloads">
                  <TraceCode title="调用参数" :value="call.arguments" :state="call.inputState" :reference="call.inputRef" />
                  <TraceCode v-if="call.toolId" title="工具返回" :value="call.output" :state="call.outputState" :reference="call.outputRef" />
                </div>
                <button v-if="call.toolId" type="button" class="invocation-link" @click="selectNode(call.toolId)">查看执行节点 →</button>
              </article>
            </section>
            <p v-if="selected.sourceNote || selected.legacy" class="projection-notice">{{ selected.sourceNote || '历史记录缺少任务 ID，按同一运行的父级范围归组；内部阶段可能未记录。' }}</p>
            <section v-if="selectedMembers.length" class="member-section"><h3>内部记录 · {{ selectedMembers.length }}</h3><button v-for="member in selectedMembers" :key="member.id" type="button" @click="selectNode(member.id)"><span>{{ nodeDisplayName(member) }}</span><small :class="{ error: ['FAILED', 'TIMED_OUT'].includes(member.status) }">{{ statusLabel(member.status) }} · {{ formatDuration(member.durationMs) }}</small></button></section>
            <TraceCode v-if="selectedSource && selected.errorRef" :key="`${selected.id}-error`" title="错误详情" :value="selected.error" :state="selected.errorState" :reference="selected.errorRef" />
            <TraceCode v-for="(source, index) in reasoningSources" :key="source.id" :title="reasoningSources.length > 1 ? `模型思考 · 第 ${index + 1} 次调用` : '模型思考'" :value="source.reasoning" :state="source.reasoningState" :reference="source.reasoningRef" />
            <TraceCode v-if="selectedSource && selected.provider_outputRef" title="模型原始返回" :value="selected.provider_output" :state="selected.provider_outputState" :reference="selected.provider_outputRef" />
            <TraceCode v-for="item in privateSources" :key="`${item.source.id}-${item.direction}`" :title="{prompt:'提示词快照',code:'生成的代码',execution:'代码执行结果'}[item.direction]" :subtitle="item.source.attributes?.['prompt.variant'] || item.source.id" :value="item.source[item.direction]" :state="item.source[`${item.direction}State`]" :reference="item.source[`${item.direction}Ref`]" />
            <section class="metadata-section"><h3>节点属性</h3><dl class="node-metadata"><div><dt>{{ selected.synthetic ? '展示分组 ID' : 'Span ID' }}</dt><dd>{{ selected.id }}</dd></div><div><dt>Parent Span ID</dt><dd>{{ selected.parentId || '根节点' }}</dd></div><div><dt>开始时间 · 北京</dt><dd>{{ date(selected.startMs) }} {{ clock(selected.startMs) }}</dd></div><div><dt>模型</dt><dd>{{ selected.model || '—' }}</dd></div><div><dt>原始名称</dt><dd>{{ selected.name }}</dd></div></dl></section>
            <TraceCode v-if="selectedSource" title="原始属性" :value="selected.attributes" state="COMPLETE" />
          </div>
        </aside>
      </div>
    </main>
  </div>
</template>

<style scoped>
.trace-studio{--trace-card:#faf9f5;--trace-clay:#d97757;--trace-charcoal:#65635d;--trace-sage:#a0ae98;--trace-sand:#ddd0bf;--trace-wait:#c9c3b8;--workbench-ui:'Anthropic Sans','Microsoft YaHei','PingFang SC',Arial,sans-serif;font-family:var(--workbench-ui);}
.trace-wordmark{font:500 27px/1 Georgia,serif;color:var(--workbench-ink);}
.brand-separator{font-size:26px;color:var(--workbench-border);font-weight:400;}
.trace-controlbar{display:flex;align-items:center;flex-wrap:wrap;gap:12px 20px;padding:12px 24px;border-bottom:1px solid var(--workbench-border);background:var(--workbench-paper);}
.trace-run-picker{display:flex;align-items:center;gap:10px;font-size:12px;color:var(--workbench-muted);min-width:0;}
.trace-run-picker select{max-width:320px;min-width:0;border:1px solid var(--workbench-border);border-radius:7px;padding:7px 10px;background:var(--workbench-raised);color:var(--workbench-ink);font-size:12px;}
.trace-controlbar .trace-search{display:flex;align-items:center;gap:10px;margin:0;min-width:0;}
.trace-controlbar .trace-search label{margin:0;font-size:12px;color:var(--workbench-muted);white-space:nowrap;}
.trace-controlbar .search-field{width:220px;min-width:0;}
.trace-controlbar .search-field input{padding:7px 10px;font-family:var(--workbench-ui);color:var(--workbench-ink);}
.trace-controlbar .query-state{flex-basis:100%;margin:0;}
.trace-run-info{margin-left:auto;font-size:12px;max-width:100%;}
.trace-run-info summary{cursor:pointer;color:var(--workbench-muted);}
.trace-run-info[open]{flex-basis:100%;margin-left:0;}
.trace-run-info dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:8px 12px;margin:12px 0;}
.trace-run-info dt{color:var(--workbench-muted);}.trace-run-info dd{margin:0;overflow-wrap:anywhere;}
.trace-run-info button{border:1px solid var(--workbench-border);border-radius:6px;padding:5px 10px;background:var(--workbench-raised);color:var(--workbench-ink);}
.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0;}
</style>

<style>
html.trace-workspace-page, html.trace-workspace-page body { min-width: 0; background: var(--workbench-paper); }
html.trace-workspace-page #app { max-width: none; background: var(--workbench-paper); }

</style>

<style scoped>
.export-records { padding: 7px 10px; border: 1px solid var(--workbench-border); border-radius: 7px; background: var(--workbench-raised); color: var(--workbench-ink); font: inherit; font-size: 12px; cursor: pointer; }
.export-records:disabled { opacity: .6; cursor: wait; }
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
.invocation-payloads { border-top: 1px solid var(--workbench-border); margin-top: 12px; padding-top: 6px; }
.invocation-payloads > details > summary { padding: 6px 0; color: var(--workbench-muted); }
.invocation-payloads :deep(.trace-code) { margin: 6px 0 12px; }
.invocation-heading > span { flex-shrink: 0; font-size: 11px; line-height: 1.7; }
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
@media(min-width:1101px) {
  .graph-column { overflow: auto; }
  .timing-panel { flex-basis: clamp(260px, 34dvh, 400px); }
}
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
.workspace-brand {font:500 28px/1.3 var(--workbench-display);letter-spacing:var(--workbench-brand-spacing);}
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

/* Keep the inspector readable without requiring repeated disclosure clicks. */
.inspector-content {padding:16px;}
.node-overview,.metadata-section,.intent-selection,.invocation-section,.member-section {
  min-width:0;margin:0 0 14px;padding:12px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--workbench-raised);
}
.intent-selection[aria-label="执行与复用情况"] {max-height:280px;overflow:auto;overscroll-behavior:contain;scrollbar-width:thin;}
.intent-selection[aria-label="执行与复用情况"] p {padding:6px 0;margin:0;line-height:1.65;}
.intent-selection[aria-label="执行与复用情况"] p+p {border-top:1px solid var(--workbench-border-subtle);}
.payload-state {display:flex;align-items:flex-start;justify-content:space-between;gap:8px;padding:10px 12px;border:1px solid var(--workbench-error-soft);border-radius:9px;background:var(--workbench-error-soft);line-height:1.6;}
.payload-state button {flex:none;border:0;padding:0;background:none;color:var(--workbench-error);cursor:pointer;text-decoration:underline;}
.timing-header {position:sticky;top:0;z-index:1;padding:14px 0 1px;background:var(--workbench-raised);}
.timing-panel {padding-top:0;}
.node-overview .node-status-line {margin:14px 0 12px;gap:8px;}
.node-overview .semantic-result {margin:0;border:1px solid var(--workbench-border-subtle);color:var(--workbench-ink);overflow-wrap:anywhere;}
.metadata-section h3,.intent-selection h3,.invocation-section h3,.member-section h3 {margin:0 0 10px;font-size:12px;}
.node-metadata {margin:0;}
.node-metadata>div {padding:8px 0;}
.node-metadata>div+div {border-top:1px solid var(--workbench-border-subtle);}
.node-metadata dt {font-size:11px;color:var(--workbench-muted);}
.node-metadata dd {margin:3px 0 0;font-size:12px;line-height:1.7;overflow-wrap:anywhere;}
.candidate-list {margin-top:12px;padding-top:10px;border-top:1px solid var(--workbench-border-subtle);}
.candidate-list>h4 {color:var(--workbench-muted);}
.candidate-list article:last-child {border-bottom:0;padding-bottom:0;}
.invocation-card {padding:10px;}
.invocation-payloads {border-top:0;padding-top:0;margin-top:10px;}
.invocation-payloads :deep(.trace-code) {margin:10px 0 0;}
.member-section button:last-child {border-bottom:0;}
.token-grid {margin:0 0 14px;gap:10px;padding:10px 12px;}
.workspace-panels {border-color:var(--workbench-border);border-radius:10px;}
.view-tabs {height:48px;padding:0 20px;gap:22px;}
.view-tabs button {font-size:13px;color:var(--workbench-muted);}
.view-tabs button.active {color:var(--workbench-ink);font-weight:500;border-bottom-color:var(--workbench-accent);}
.view-meta {display:flex;align-items:center;gap:24px;margin-left:auto;min-width:0;color:var(--workbench-muted);font-size:11px;white-space:nowrap;}
.trace-legend {display:flex;align-items:center;gap:14px;}
.trace-legend span {display:inline-flex;align-items:center;gap:5px;}
.trace-legend i {width:6px;height:6px;border-radius:50%;background:var(--workbench-charcoal);}
.trace-legend .legend-model {background:var(--workbench-accent);}
.trace-legend .legend-tool {background:var(--workbench-success);}
.view-count {color:var(--workbench-tertiary);}
.graph-view-switch {display:flex;gap:2px;padding:3px;border-radius:7px;background:var(--workbench-paper);}
.view-tabs .graph-view-switch button {height:26px;padding:0 8px;border:0;border-radius:5px;color:var(--workbench-muted);font-size:12px;}
.view-tabs .graph-view-switch button[aria-pressed="true"] {background:var(--workbench-raised);color:var(--workbench-ink);box-shadow:0 0 0 1px var(--workbench-border);}
.timing-heading h2,.inspector-heading {font-size:13px;font-weight:500;}
.timing-row {min-height:30px;}
.node-overview {background:var(--workbench-paper);border-color:var(--workbench-ring);padding:14px;}
.node-overview .selected-node-heading h2 {font-size:15px;font-weight:600;line-height:1.4;}
.node-overview .selected-node-heading small {color:var(--workbench-muted);margin-top:4px;}
.node-overview .selected-node-icon {width:38px;height:38px;border-radius:10px;}
.node-overview .semantic-result {background:transparent;border:0;padding:0;color:var(--workbench-muted);}
.metadata-section h3,.intent-selection h3,.invocation-section h3,.member-section h3 {font-size:13px;font-weight:500;}
.node-metadata dt {color:var(--workbench-tertiary);}
.node-metadata dd {color:var(--workbench-ink);font-weight:500;}
.token-grid {background:var(--workbench-paper);border-color:var(--workbench-border);}
.token-grid dd {font-size:19px;font-weight:500;}
.invocation-section {padding:0;border:0;border-radius:0;background:transparent;}
.invocation-section h3 {margin:0 0 12px;}
.invocation-card {padding:12px;margin:0;background:var(--workbench-raised);border:1px solid var(--workbench-border);border-radius:9px;}
.invocation-card+.invocation-card {margin-top:12px;}
.invocation-heading {align-items:center;}
.invocation-heading strong {font-size:13px;font-weight:500;}
.invocation-heading>span {padding:1px 6px;border-radius:4px;background:var(--workbench-success-soft);}
.invocation-heading>.invocation-error {background:var(--workbench-error-soft);}
.invocation-heading>.invocation-pending {background:var(--workbench-sidebar);}
.invocation-summary {color:var(--workbench-muted);line-height:1.6;}
.invocation-payloads :deep(.trace-code) {margin:12px 0 0;border:0;border-radius:0;background:transparent;overflow:visible;}
.invocation-payloads :deep(.code-heading) {padding:0 0 7px;border:0;background:transparent;align-items:center;}
.invocation-payloads :deep(.code-heading h3) {font-size:11px;color:var(--workbench-muted);font-weight:400;}
.invocation-payloads :deep(pre),.invocation-payloads :deep(.code-empty),.invocation-payloads :deep(.code-protected) {border-radius:6px;background:var(--workbench-paper);padding:10px;}
.invocation-link {margin-top:12px;padding:0;font-size:12px;}
.inspector-content {font-size:13px;line-height:1.7;letter-spacing:.02em;}
.inspector-content :is(h2,h3,h4) {letter-spacing:.01em;line-height:1.5;}
.node-metadata dd {line-height:1.8;}
.invocation-summary {line-height:1.75;}
.timing-row {line-height:20px;letter-spacing:.01em;border-left:2px solid transparent;}
.timing-row.active {border-left-color:var(--workbench-accent);font-weight:500;}
.timing-axis,.timing-value,.run-metrics {letter-spacing:0;font-variant-numeric:tabular-nums;}
@media(max-width:1200px){.trace-legend{display:none;}}
@media(max-width:700px){.view-tabs{padding:0 10px;gap:10px;}.view-meta{gap:4px;font-size:10px;}.view-count{display:none;}.view-tabs .graph-view-switch button{padding:0 6px;font-size:11px;}}
.trace-studio{display:block;--trace-card:#faf9f5;--trace-clay:#d97757;--trace-charcoal:#65635d;--trace-sage:#a0ae98;--trace-sand:#ddd0bf;--trace-wait:#c9c3b8;font-family:var(--workbench-ui);}
.workspace-main{height:100%;display:flex;flex-direction:column;min-width:0;}
.workspace-header{height:64px;flex-shrink:0;}
.workspace-heading{gap:16px;}.workspace-heading h1{font-size:20px;font-weight:500;}
.run-toolbar{padding:10px 24px;gap:12px;flex-wrap:wrap;}
.run-context h2{font-size:16px;}.run-metrics{flex-wrap:wrap;gap:10px 16px;flex-shrink:1;}
.workspace-panels{margin:0 24px 20px;grid-template-columns:minmax(0,1fr) 8px var(--inspector-width);}
.graph-column{overflow:hidden;}
.timing-panel{flex:0 0 clamp(180px,25dvh,260px);}
.inspector-column{overflow:auto;container-type:inline-size;}
.inspector-heading{height:50px;justify-content:space-between;font-size:13px;}
.inspector-heading>span{font-size:12px;color:var(--workbench-muted);}
.inspector-content{padding:16px;letter-spacing:0;font-size:12px;}
.node-overview{padding:0 0 16px;margin-bottom:0;border:0;background:transparent;}
.node-overview .semantic-result{margin:10px 0;font-size:12px;}
.node-status-line{flex-wrap:wrap;}
.inspector-dashboard{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;align-items:stretch;margin-bottom:16px;}
.inspector-dashboard>*{min-width:0;}
.metadata-section summary,.invocation-section summary{cursor:pointer;font-size:13px;padding:10px 0;color:var(--workbench-muted);}
.inspector-content :deep(.trace-donut strong){font-family:var(--workbench-ui);}
.inspector-dashboard :deep(.context-slots>div){flex-wrap:wrap;}
@container(max-width:410px){.inspector-dashboard{grid-template-columns:minmax(0,1fr);}}
@media(max-width:1100px){.trace-studio{height:auto;min-height:100dvh;}.workspace-main{height:auto;min-height:100dvh;}.workspace-panels{display:grid;grid-template-columns:minmax(0,1fr);overflow:visible;flex:none;}.workspace-panels>.trace-divider{display:none;}.graph-column{height:660px;min-height:0;}.inspector-column{border-left:0;border-top:1px solid var(--workbench-border);overflow:visible;}.inspector-heading{position:static;}.inspector-dashboard{grid-template-columns:repeat(2,minmax(0,1fr));}.run-metrics{margin-left:0;}.trace-controlbar{padding-inline:20px;}}
@media(max-width:600px){.workspace-panels{margin:0 12px 12px;}.workspace-header{padding:0 12px;gap:8px;flex-wrap:wrap;height:auto;min-height:64px;}.trace-wordmark{font-size:22px;}.workspace-heading{gap:8px;}.workspace-heading h1{font-size:17px;}.trace-controlbar{padding:12px;gap:12px;}.trace-run-picker,.trace-controlbar .trace-search{width:100%;}.trace-run-picker select{flex:1;max-width:100%;}.trace-controlbar .search-field{flex:1;width:auto;}.trace-run-info{margin-left:0;}.run-toolbar{padding:10px 12px;}.run-context{width:100%;}.graph-column{height:580px;}.inspector-content{padding:12px;}.inspector-dashboard{gap:10px;}.model-name{white-space:normal;}.view-tabs{height:auto;min-height:48px;flex-wrap:wrap;padding-block:8px;}.view-meta{flex-wrap:wrap;}.trace-run-info dl{grid-template-columns:80px minmax(0,1fr);}}
@media(max-width:440px){.inspector-dashboard{grid-template-columns:minmax(0,1fr);}}
.trace-studio {
  --workbench-paper: #f5f4ef;
  --workbench-raised: #ffffff;
  --workbench-sidebar: #eaeae3;
  --workbench-ink: #242724;
  --workbench-muted: #70736c;
  --workbench-tertiary: #70736c;
  --workbench-border: #deded5;
  --workbench-border-subtle: #edede7;
  --workbench-accent: #cf684b;
  --workbench-success: #56744f;
  --workbench-success-soft: #edf2ea;
  --workbench-soft: #faece7;
  --workbench-connector: #a4a69d;
  --trace-card: #ffffff;
  --trace-clay: #cf684b;
  --trace-charcoal: #70736c;
  --trace-sage: #8d9d87;
  --trace-sand: #cbbfae;
  --trace-wait: #8fa489;
  --trace-environment: #c2a884;
  --trace-current: #8da1a6;
  --trace-runtime: #b39a9c;
  --trace-skill: #b38f79;
  --trace-agent: #999cb0;
  --trace-execution: #a5aa91;
  --trace-memory: #8d9e98;
  --trace-reference: #bca795;
  --trace-output: #b6aa93;
}

.model-performance{display:flex;flex-direction:column;gap:12px;min-width:0;}
.model-performance :deep(.response-card){flex:1;}
 .timing-heading h2,.inspector-label{font-weight:600;}
.inspector-heading{gap:12px;}
.inspector-label{white-space:nowrap;flex-shrink:0;}
.inspector-selection{display:flex;align-items:center;justify-content:flex-end;gap:8px;min-width:0;}
.selected-result{margin:0 0 16px;padding:10px 12px;border:1px solid var(--workbench-border);border-radius:8px;color:var(--workbench-ink);overflow-wrap:anywhere;}
.inspector-selection strong{font-size:12px;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-width:0;}
.inspector-selection>svg{flex-shrink:0;color:var(--workbench-muted);}
.inspector-selection .status-pill{font-size:10px;padding:2px 5px;}
.selection-duration{white-space:nowrap;font-size:11px;color:var(--workbench-muted);font-variant-numeric:tabular-nums;}
@container(max-width:410px){.inspector-selection{gap:5px;}.inspector-heading{padding-inline:12px;gap:8px;}.inspector-selection .status-pill.succeeded{display:none;}}
</style>
