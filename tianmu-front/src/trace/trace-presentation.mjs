// A read-only projection: semantic cards retain source IDs; raw spans stay intact.
// No model calls, fabricated durations, or name-only cross-task aggregation.
const titles = { faq: 'FAQ 匹配', intent: '意图识别', emotion: '情绪识别', clarification: '澄清判断', model: '模型思考', tool: '工具调用', background: '后台任务', work: '后台步骤', diagnostic: '异常记录' }
const intentNames = { VIDEO_METRIC: '视频互动统计', DANMAKU_REPORT: '观众反馈报告', KNOWLEDGE: '知识问答', OTHER: '其他任务', UNKNOWN: '未确定' }
const emotionNames = { NEUTRAL: '中性', POSITIVE: '正向', NEGATIVE: '负向', UNKNOWN: '未知' }
const phaseNames = { COLLECT: '采集反馈', COLLECTING: '采集反馈', CLASSIFY: '反馈分类', CLASSIFYING: '反馈分类', MERGE: '合并结果', MERGING: '合并结果', READY: '分析就绪', WAITING_AUTH: '等待授权', FAILED: '分析失败', CANCELLED: '已取消' }
const toolNames = { read_skill: '读取策略', analyze_video_content: '分析视频', analyze_feedback: '分析反馈', create_feedback_report: '生成报告', read_report: '读取报告', search_feedback: '搜索反馈', ask_clarification: '请求澄清' }
const failures = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED'])
const time = n => Number.isFinite(n.offsetMs) ? n.offsetMs : Infinity
const end = n => Number.isFinite(n.offsetMs) && Number.isFinite(n.durationMs) ? n.offsetMs + n.durationMs : Infinity
const compare = (a, b) => time(a) - time(b) || a.id.localeCompare(b.id)
const statusOrder = ['FAILED', 'TIMED_OUT', 'CANCELLED', 'RUNNING', 'WAITING_OPERATION', 'NEEDS_INPUT', 'UNKNOWN', 'SUCCEEDED']
export const cardTitle = n => n?.cardTitle || titles[n?.cardKind] || n?.label || n?.name || '节点'
export const cardIcon = n => ({ faq: 'search', intent: 'route', emotion: 'emotion', clarification: 'question', model: 'model', tool: 'tool', background: 'layers', work: 'stage', diagnostic: 'info' }[n?.cardKind] || (n?.kind === 'MODEL' ? 'model' : n?.kind === 'TOOL' ? 'tool' : 'stage'))

export function semanticKind(n) {
  const t = n.type, name = n.name
  if (t === 'faq_lookup' || name === 'agent.prepare.faq') return 'faq'
  if (t === 'intent_recognition' || name === 'agent.prepare.intent') return 'intent'
  if (t === 'emotion_recognition' || name === 'agent.prepare.emotion') return 'emotion'
  if (t === 'clarification_check' || name === 'agent.prepare.validate') return 'clarification'
  if (name === 'feedback.work') return 'work'
  if (n.kind === 'TOOL' || t === 'tool_call' || name === 'agent.tool' || t === 'read_skill') return 'tool'
  if (n.kind === 'MODEL' || t === 'llm_inference') return 'model'
  return null
}

const methods = { RULE: '规则判断', MODEL: '模型判断', LOOKUP: '知识查询' }
const skipReasons = { CONFIG_DISABLED: '配置禁用', FAQ_HIT: 'FAQ 已命中', CLARIFICATION_REQUIRED: '等待补充信息', RESOURCE_LOOKUP: '交由资源选择处理', RULE_ROUTE: '规则路由已完成' }
export function cardSummary(n) {
  const a = n.attributes || {}, out = n.output
  if (n.synthetic) return n.summary
  if (failures.has(n.status)) return a['error.code'] || ({ FAILED: '执行失败', TIMED_OUT: '执行超时', CANCELLED: '已取消' }[n.status])
  if (['SKIPPED', 'DISABLED'].includes(a['preparation.state'])) return skipReasons[a['preparation.reason']] || '本次未执行'
  switch (n.cardKind || semanticKind(n)) {
    case 'faq': return a['faq.hit'] === true ? `命中 · ${a['faq.id'] || '知识条目'}` : a['faq.hit'] === false ? '未命中，继续处理' : '匹配结果未记录'
    case 'intent': return intentNames[a['preparation.intent']] || '识别结果未记录'
    case 'emotion': return emotionNames[a['preparation.emotion']] || '识别结果未记录'
    case 'clarification': {
      const required = a['clarification.required'] ?? (typeof out?.required === 'boolean' ? out.required : undefined)
      return required === true ? '需要补充信息' : required === false ? '条件完整，无需澄清' : '判断结果未记录'
    }
    case 'model': {
      const decision = a['model.decision'] || out?.decision
      if (decision === 'CALL_TOOL' || decision === 'TOOL') return `调用 ${toolNames[a['model.selected_tool']] || a['model.selected_tool'] || out?.tool || '工具'}`
      return ({ FINAL_ANSWER: '完成本轮决策', ANSWER: '完成本轮决策', CLARIFY: '请求补充信息', PROCEED: '继续执行' }[decision]) || (n.type === 'query_understanding' ? '解析任务条件' : '模型请求')
    }
    case 'tool': return a['tool.success'] === false ? '工具返回业务错误' : a['tool.terminal_delivery'] ? '已生成交付结果' : a['tool.has_evidence'] ? '返回结果与证据' : '查看参数与执行结果'
    case 'work': return phaseNames[a['analysis.work_kind']] || phaseNames[a['analysis.phase']] || (n.model ? '模型处理 · 阶段未记录' : '阶段未记录')
    default: return '查看原始记录'
  }
}

export function presentNode(n) {
  const cardKind = semanticKind(n) || 'diagnostic'
  const card = { ...n, cardKind, sourceId: n.id, memberIds: [n.id], synthetic: false }
  card.cardTitle = cardKind === 'tool' ? toolNames[n.tool] || n.tool || '工具调用' : cardKind === 'work' ? cardSummary(card) : titles[cardKind]
  card.summary = cardSummary(card)
  card.executionLabel = methods[n.attributes?.['preparation.method']] || ''
  const state = n.attributes?.['preparation.state']
  if (['SKIPPED', 'DISABLED'].includes(state) && !failures.has(n.status)) {
    card.status = state; card.durationMs = null; card.summaryOnly = true; card.durationLabel = '未执行'
    card.sourceNote = '本节点记录未执行状态；原始 Span 的耗时仅为状态记录开销。'
  }
  return card
}

export function buildTracePresentation(raw) {
  const byId = new Map(raw.map(n => [n.id, n])), children = new Map()
  for (const n of raw) { if (!children.has(n.parentId)) children.set(n.parentId, []); children.get(n.parentId).push(n) }
  const ancestors = n => { const result = []; const seen = new Set([n.id]); let p = byId.get(n.parentId); while (p && !seen.has(p.id)) { result.push(p); seen.add(p.id); p = byId.get(p.parentId) } return result }
  const subtree = n => { const result = [], todo = [n], seen = new Set(); while (todo.length) { const x = todo.pop(); if (seen.has(x.id)) continue; seen.add(x.id); result.push(x); todo.push(...(children.get(x.id) || [])) } return result }
  const cards = [], groups = new Map(), owned = new Set(), jobs = new Map()
  for (const n of raw.filter(n => n.name === 'feedback.work')) {
    const a = n.attributes || {}, scope = a['run.id'] || ancestors(n).find(p => p.name === 'agent.run')?.id || n.parentId || n.id
    // Historical exports lack job IDs: keep them within their original parent scope.
    const key = JSON.stringify([scope, a['analysis.job_id'] || `legacy:${n.parentId}`])
    if (!jobs.has(key)) jobs.set(key, [])
    jobs.get(key).push(n)
  }
  const aggregate = (id, roots, kind, title) => {
    const members = [...new Map(roots.flatMap(subtree).map(n => [n.id, n])).values()]
    const start = Math.min(...roots.map(time)), stop = Math.max(...roots.map(end))
    const candidates = members.filter(n => n.kind === 'MODEL' || n.type === 'llm_inference' || (n.model && (n.inputTokens != null || n.outputTokens != null)))
    const models = candidates.filter(n => !candidates.some(other => other.id !== n.id && ancestors(other).some(p => p.id === n.id)))
    const sum = key => models.length && models.every(n => n[key] != null) ? models.reduce((v, n) => v + n[key], 0) : null
    const errors = members.filter(n => failures.has(n.status))
    return { id, sourceId: null, synthetic: true, cardKind: kind, cardTitle: title, name: title, label: title, kind: 'STAGE',
      parentId: roots[0].parentId, offsetMs: Number.isFinite(start) ? start : null, durationMs: Number.isFinite(stop - start) ? stop - start : null,
      startMs: roots[0].startMs, status: statusOrder.find(s => members.some(n => n.status === s)) || 'UNKNOWN',
      memberIds: members.map(n => n.id), rootIds: roots.map(n => n.id), failureIds: errors.map(n => n.id),
      inputTokens: sum('inputTokens'), outputTokens: sum('outputTokens'), totalTokens: sum('totalTokens'), modelCalls: models.length,
      model: [...new Set(models.map(n => n.model).filter(Boolean))].join(' / '), repeatCount: roots.length, attributes: {},
      summary: `${roots.length} 个步骤${errors.length ? ` · ${errors.length} 条异常` : ''}`, children: [] }
  }
  for (const roots of jobs.values()) {
    roots.sort(compare)
    const job = aggregate(`job:${roots[0].id}`, roots, 'background', '后台反馈分析')
    job.summary = `${job.modelCalls} 次模型调用 · ${roots.length} 个步骤${job.failureIds.length ? ` · ${job.failureIds.length} 条异常` : ''}`
    job.legacy = !roots[0].attributes?.['analysis.job_id']
    job.jobId = roots[0].attributes?.['analysis.job_id'] || null
    let batch = []
    const flush = () => {
      if (batch.length > 1) {
        const group = aggregate(`batch:${batch[0].id}`, batch, 'work', phaseNames[batch[0].attributes?.['analysis.work_kind']] || (batch[0].model ? '模型处理' : '后台步骤'))
        group.summary = `${batch.length} 次 · 串行${group.failureIds.length ? ` · ${group.failureIds.length} 条异常` : ''}`
        group.children = batch.map(n => ({ ...presentNode(n), memberIds: subtree(n).map(x => x.id) }))
        groups.set(group.id, group); job.children.push(group)
      } else if (batch.length) job.children.push({ ...presentNode(batch[0]), memberIds: subtree(batch[0]).map(x => x.id) })
      batch = []
    }
    const signature = n => JSON.stringify([n.parentId, n.attributes?.['analysis.work_kind'], n.attributes?.['analysis.phase'], n.attributes?.['resource.id'], n.attributes?.['video.bvid'], n.model, n.attributes?.['execution.attempt'] || 1])
    for (const n of roots) {
      if (batch.length && (signature(batch.at(-1)) !== signature(n) || end(batch.at(-1)) > time(n) || !Number.isFinite(end(n)) || !Number.isFinite(time(n)))) flush()
      batch.push(n)
    }
    flush(); groups.set(job.id, job); cards.push(job); job.memberIds.forEach(id => owned.add(id))
  }
  for (const n of raw) {
    if (owned.has(n.id)) continue
    const kind = semanticKind(n)
    const owners = ancestors(n).filter(p => semanticKind(p))
    // Child provider spans are available through the semantic operation, not duplicated on the main graph.
    if ((kind || failures.has(n.status)) && !owners.length && n.kind !== 'REQUEST') {
      const card = presentNode(n), members = subtree(n)
      card.memberIds = members.map(x => x.id)
      card.failureIds = members.filter(x => failures.has(x.status)).map(x => x.id)
      if (card.failureIds.length && !failures.has(card.status)) card.summary += ` · 内部 ${card.failureIds.length} 条异常`
      const stage = ({ intent: 'intent', emotion: 'emotion' })[kind]
      const preparation = ancestors(n).find(p => p.name === 'agent.preparation')
      if (stage && n.attributes?.['preparation.method'] === 'MODEL' && preparation?.attributes?.['preparation.skipped_stages']?.includes(stage)) {
        card.sourceNote = '模型分支已启动，本次准备结果未采用该分支结果。'
      }
      cards.push(card)
    }
  }
  // Deterministic routing may record intent only in the preparation result.
  // Render it as explicitly sourced summary, never as an invented timed model call.
  for (const n of raw.filter(n => n.type === 'pre_route' || n.name === 'agent.preparation')) {
    const a = n.attributes || {}, scope = subtree(n)
    if (a['preparation.intent'] && a['preparation.intent'] !== 'UNKNOWN' && !scope.some(x => semanticKind(x) === 'intent')) {
      cards.push({ ...presentNode(n), id: `summary:intent:${n.id}`, cardKind: 'intent', cardTitle: '意图识别', summary: intentNames[a['preparation.intent']] || a['preparation.intent'],
        synthetic: true, summaryOnly: true, durationMs: null, offsetMs: Number.isFinite(end(n)) ? end(n) : n.offsetMs, sourceId: n.id, memberIds: [n.id], status: 'UNKNOWN', sourceNote: '来自准备阶段汇总；独立步骤耗时未记录' })
    }
  }
  cards.sort(compare)
  const notices = []
  if (!cards.some(n => n.cardKind === 'emotion')) notices.push('情绪识别：未执行或未记录')
  if (!cards.some(n => n.cardKind === 'clarification')) notices.push('澄清判断：无独立记录')
  if (!cards.length) notices.push('暂无业务节点，请查看原始节点')
  const allCards = new Map()
  const index = list => list.forEach(n => { allCards.set(n.id, n); if (n.children?.length) index(n.children) })
  index(cards)
  return { cards, allCards, groups, notices, rawById: byId }
}

// Presentation edges describe chronological order, not causal dependencies.
// Only an explicit loop decision + selected tool establishes a call edge.
export function layoutPresentation(presentation, expanded = new Set()) {
  const cards = []
  const visit = (card, collapseId = null) => {
    if (expanded.has(card.id) && card.children?.length) {
      card.children.forEach((child, i) => visit(child, i === 0 ? collapseId || card.id : null))
    } else cards.push({ ...card, collapseId })
  }
  presentation.cards.forEach(card => visit(card))
  cards.sort(compare)
  const nodes = [], edges = [], lanes = [], frontier = new Map()
  let lastX = 0
  const stop = n => n.summaryOnly ? time(n) : end(n)
  const connect = (a, b, relation) => {
    if (a.id === b.id) return
    edges.push({ id: `${relation}:${a.id}:${b.id}`, source: a.id, target: b.id, relation })
  }
  for (const card of cards) {
    const known = Number.isFinite(time(card))
    const finished = known ? [...frontier.values()].filter(n => Number.isFinite(stop(n.data)) && stop(n.data) <= time(card)) : []
    // Each ended branch joins the next chronological step. A new overlapping
    // branch forks from the last completed step, never from still-running work.
    let predecessors = finished
    if (known && !finished.length) {
      const completed = nodes.filter(n => Number.isFinite(stop(n.data)) && stop(n.data) <= time(card))
      const latest = Math.max(-Infinity, ...completed.map(n => stop(n.data)))
      predecessors = completed.filter(n => stop(n.data) === latest)
    }
    let lane = known ? lanes.findIndex(n => Number.isFinite(stop(n.data)) && stop(n.data) <= time(card)) : -1
    if (lane < 0) lane = lanes.length
    const x = Math.max(lastX, 0, ...predecessors.map(n => n.position.x + 292))
    const item = { id: card.id, type: 'trace', position: { x, y: lane * 168 }, data: card }
    for (const previous of predecessors) {
      const call = previous.data.cardKind === 'model' && card.cardKind === 'tool' && previous.data.parentId === card.parentId && previous.data.attributes?.['model.selected_tool'] === card.tool
      connect(previous, item, call ? 'call' : finished.length > 1 ? 'join' : finished.length ? 'order' : 'branch')
    }
    finished.forEach(n => frontier.delete(n.id))
    frontier.set(card.id, item); lanes[lane] = item; nodes.push(item); lastX = x
  }
  return { nodes, edges }
}
