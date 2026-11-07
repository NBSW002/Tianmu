// A read-only projection: semantic cards retain source IDs; raw spans stay intact.
// No model calls, fabricated durations, or name-only cross-task aggregation.
const titles = { faq: 'FAQ 匹配', intent: '意图识别', emotion: '情绪识别', clarification: '澄清识别', merge: '识别结果汇合', model: '模型思考', tool: '工具调用', background: '后台任务', work: '后台步骤', diagnostic: '异常记录' }
const intentNames = { VIDEO_METRIC: '视频互动统计', DANMAKU_REPORT: '观众反馈报告', KNOWLEDGE: '知识问答', OTHER: '其他任务', UNKNOWN: '未确定' }
const capabilityNames = { conversation:'通用对话', knowledge:'知识与操作咨询', 'video-content':'视频内容理解', 'video-metrics':'视频互动统计', 'danmaku-analysis':'弹幕分析', 'comment-analysis':'评论分析', 'audience-report':'观众反馈报告', 'video-discovery':'查找自己的视频', 'report-query':'历史报告查询', 'smart-comment':'智能评论草稿', 'smart-danmaku':'智能弹幕草稿', unknown:'尚未明确' }
const emotionNames = { NEUTRAL: '中性', POSITIVE: '正向', NEGATIVE: '负向', UNKNOWN: '未知' }
const phaseNames = { COLLECT: '采集反馈', COLLECTING: '采集反馈', CLASSIFY: '反馈分类', CLASSIFYING: '反馈分类', MERGE: '合并结果', MERGING: '合并结果', READY: '分析就绪', WAITING_AUTH: '等待授权', FAILED: '分析失败', CANCELLED: '已取消' }
const toolNames = { query_danmaku_count: '查询弹幕数', read_danmaku: '读取弹幕', read_comments: '读取评论', read_skill: '读取 Skill', analyze_video_content: '分析视频', analyze_feedback: '分析反馈', create_feedback_report: '生成报告', read_report: '读取报告', search_feedback: '搜索反馈', ask_clarification: '请求澄清' }
const failures = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED'])
titles.memory='记忆召回'
titles.memory_restore='恢复固定记忆'
titles.memory_write='整理长期记忆'
titles.compaction='上下文压缩'
Object.assign(titles, { delivery: '结果交付', report_delivery: '报告就绪', finish: '运行结束' })
Object.assign(phaseNames, { ANALYZING: '分析反馈' })
Object.assign(toolNames, { analyze_danmaku: '分析弹幕', analyze_comments: '分析评论' })
const time = n => Number.isFinite(n.offsetMs) ? n.offsetMs : Infinity
const end = n => Number.isFinite(n.offsetMs) && Number.isFinite(n.durationMs) ? n.offsetMs + n.durationMs : Infinity
const compare = (a, b) => time(a) - time(b) || a.id.localeCompare(b.id)
const statusOrder = ['FAILED', 'TIMED_OUT', 'CANCELLED', 'RUNNING', 'WAITING_OPERATION', 'NEEDS_INPUT', 'UNKNOWN', 'SUCCEEDED']
export const cardTitle = n => n?.cardTitle || titles[n?.cardKind] || n?.label || n?.name || '节点'
export const toolDisplayName = name => toolNames[name] || name || '工具调用'
const payloadObject = value => {
  if (typeof value !== 'string') return value || {}
  try { return JSON.parse(value) } catch { return {} }
}
const contextSlotNames = { system_prompt:'系统提示词',tool_catalog:'工具列表',skill_catalog:'Skill 列表',agent_catalog:'A2A Agent 列表',execution_context:'执行环境',focus:'本轮聚焦',memory_context:'记忆召回',environment_context:'页面环境',reference_context:'参考资料',current_context:'当前上下文',runtime_state:'执行状态',output_contract:'输出约定' }
export function contextDetails(node) {
  const attrs=node?.attributes || {}
  const present=Array.isArray(attrs['context.present_slots'])?attrs['context.present_slots']:[]
  const sizes=new Map((Array.isArray(attrs['context.slot_bytes'])?attrs['context.slot_bytes']:[]).filter(value=>typeof value==='string' && /^[a-z_]+[:=]\d+$/.test(value)).map(value=>{const [name,size]=value.split(/[:=]/);return [name,Number(size)]}))
  const slots=Object.keys(contextSlotNames).filter(name=>present.includes(name)).map(name=>({name,label:contextSlotNames[name],bytes:sizes.get(name) ?? null}))
  return {visible:slots.length>0,version:attrs['context.schema_version'] || null,projection:attrs['context.projection_version'] || null,slots}
}
export function compactionDetails(node) {
  const a=node?.attributes || {}
  const number=key=>Number.isFinite(a[key]) && a[key]>=0?a[key]:null
  return {visible:node?.name==='agent.context.compact' || node?.type==='context_compact',from:number('compaction.covered_from'),through:number('compaction.covered_through'),messages:number('compaction.source_messages'),inputBytes:number('compaction.input_bytes'),outputBytes:number('compaction.output_bytes'),savedBytes:number('compaction.saved_bytes'),prompt:a['compaction.prompt_version'] || null,trigger:a['compaction.trigger_reason']==='HISTORY_BUDGET'?'历史窗口达到整理阈值':null}
}
export function intentDetails(node) {
  const attrs=node?.attributes || {}, input=payloadObject(node?.input), output=payloadObject(node?.output)
  const snapshot=Array.isArray(output.candidates)?output.candidates:Array.isArray(input.intentCandidates)?input.intentCandidates:[]
  const ids=Array.isArray(attrs['preparation.intent_candidates'])?attrs['preparation.intent_candidates']:[]
  const candidates=(snapshot.length?snapshot:ids.map(id=>({id}))).slice(0,32).filter(candidate=>candidate && typeof candidate.id==='string')
    .map(candidate=>({...candidate,label:candidate.label || capabilityNames[candidate.id] || candidate.id}))
  const label=id=>candidates.find(candidate=>candidate.id===id)?.label || capabilityNames[id] || id
  const primary=attrs['preparation.intent_primary'] || output.primary || null
  const related=attrs['preparation.intent_related'] || output.related || []
  return { candidates,primary:primary?{id:primary,label:label(primary)}:null,
    related:Array.isArray(related)?related.filter(id=>typeof id==='string').slice(0,4).map(id=>({id,label:label(id)})):[],visible:candidates.length>0 || !!primary }
}
function decisionCalls(node) {
  const out = payloadObject(node.output)
  const primary = out.call || (out.tool ? { name: out.tool, arguments: out.arguments } : null)
  const calls = [primary, ...(Array.isArray(out.additionalCalls) ? out.additionalCalls : [])].filter(call => call?.name)
  const declared = node.attributes?.['model.selected_tools'] || [node.attributes?.['model.selected_tool']].filter(Boolean)
  for (const name of declared) if (!calls.some(call => call.name === name)) calls.push({ name })
  return calls
}

// Parent scope + recorded loop step identify a round. Time bounds distinguish
// repeated model attempts inside that round; tool names alone never link runs.
export function modelInvocations(node, presentation) {
  if (node?.cardKind !== 'model') return []
  const calls = decisionCalls(node)
  const roundParent = card => {
    let parent = presentation.rawById.get(card.parentId)
    const seen = new Set()
    while (parent?.name === 'agent.tool.batch' && !seen.has(parent.id)) { seen.add(parent.id); parent = presentation.rawById.get(parent.parentId) }
    return parent?.id || card.parentId
  }
  const peers = [...presentation.allCards.values()].filter(card => !card.synthetic && card.parentId && roundParent(card) === roundParent(node)
    && card.turn === node.turn && card.attributes?.['run.id'] === node.attributes?.['run.id']
    && card.attributes?.['run.turn_seq'] === node.attributes?.['run.turn_seq'])
  const next = Math.min(Infinity, ...peers.filter(card => card.cardKind === 'model' && time(card) > time(node)).map(time))
  const tools = peers.filter(card => card.cardKind === 'tool' && Number.isFinite(time(node)) && time(card) >= time(node) && time(card) < next).sort(compare)
  const describe = (call, tool, index) => {
    const input = payloadObject(tool?.input), args = input.arguments ?? (tool?.input != null ? tool.input : call?.arguments)
    const members = (tool?.memberIds || []).map(id => presentation.rawById.get(id)).filter(Boolean)
    const skill = members.find(member => member.attributes?.['skill.name'])?.attributes?.['skill.name']
      || (call?.name === 'read_skill' || tool?.tool === 'read_skill' ? payloadObject(args).name : null)
    return { id: tool?.id || `planned:${index}`, toolId: tool?.id, name: tool?.tool || call.name,
      label: toolDisplayName(tool?.tool || call.name), skill: skill || null, arguments: args,
      inputState: tool ? tool.inputState : args != null ? 'COMPLETE' : node.outputState,
      inputRef: tool?.inputRef, output: tool?.output, outputState: tool?.outputState, outputRef: tool?.outputRef,
      status: tool?.status, businessError: tool?.attributes?.['tool.success'] === false,
      selected: !!call, summary: tool ? cardHighlight(tool) : '已选择，未找到对应执行记录' }
  }
  const unmatched = [...calls]
  const result = tools.map((tool, index) => {
    const i = unmatched.findIndex(call => call.name === tool.tool)
    return describe(i < 0 ? null : unmatched.splice(i, 1)[0], tool, index)
  })
  return [...result, ...unmatched.map((call, index) => describe(call, null, index))]
}
export function cardHighlight(n) {
  if (!n) return ''
  if (failures.has(n.status)) return ({ INVALID_ANALYSIS: '返回内容校验失败', MODEL_UNAVAILABLE: '模型暂时不可用', TOOL_TIMEOUT: '工具执行超时' }[n.attributes?.['error.code']]) || ({ FAILED: '执行失败，查看详情', TIMED_OUT: '执行超时', CANCELLED: '已取消' }[n.status])
  if (['SKIPPED','DISABLED'].includes(n.status)) return n.status === 'DISABLED' ? '本次已禁用' : '本次已跳过'
  if (n.status === 'RUNNING') return '正在处理'
  if (n.status === 'WAITING_OPERATION') return '等待后台结果'
  if (n.cardKind === 'background') return n.failureIds?.length && n.status === 'SUCCEEDED' ? `重试 ${n.failureIds.length} 次后成功` : n.status === 'SUCCEEDED' ? '分析已完成' : '查看分析进度'
  if (n.cardKind === 'execution' || n.cardKind === 'delivery_group') return n.highlight
  if (n.cardKind === 'work' && n.highlight) return n.highlight
  if (n.failureIds?.length) return n.summary?.includes('条异常') ? n.summary : `${n.summary || '已完成'} · ${n.failureIds.length} 条异常`
  if (n.cardKind === 'faq') return n.attributes?.['faq.hit'] === true ? '已命中，直接回答' : n.attributes?.['faq.hit'] === false ? '未命中' : '匹配结果未记录'
  if (n.cardKind === 'clarification') return n.attributes?.['clarification.required'] === true ? '需要补充信息' : n.attributes?.['clarification.required'] === false ? '无需澄清' : '判断结果未记录'
  if (n.cardKind === 'merge' && n.attributes?.['preparation.merge_reason']) return n.summary
  if (n.cardKind === 'merge') return n.attributes?.['request.route'] === 'CLARIFY' ? '先向用户澄清' : n.attributes?.['request.route'] === 'HARNESS' ? '进入执行' : n.summary
  if (n.cardKind === 'tool') {
    if (n.attributes?.['tool.success'] === false) return '工具返回业务错误'
    if (n.tool === 'ask_clarification') return '等待用户补充'
    if (n.status !== 'SUCCEEDED') return n.summary
    if (n.attributes?.['tool.has_evidence']) return '已取得结果与证据'
    return ({ create_feedback_report: '已请求生成报告', analyze_comments: '已请求评论分析', analyze_danmaku: '已请求弹幕分析', read_skill: 'Skill 已载入' }[n.tool]) || '本步已完成'
  }
  return n.summary || '查看执行详情'
}
export const cardIcon = n => ({ faq: 'search', intent: 'route', emotion: 'emotion', clarification: 'question', merge: 'route', model: 'model', tool: 'tool', background: 'layers', work: 'stage', diagnostic: 'info' }[n?.cardKind] || (n?.kind === 'MODEL' ? 'model' : n?.kind === 'TOOL' ? 'tool' : 'stage'))

export function semanticKind(n) {
  const t = n.type, name = n.name
  if (t === 'context_compact' || name === 'agent.context.compact') return 'compaction'
  if (t === 'memory_recall' || name === 'agent.memory.recall') return 'memory'
  if (name === 'agent.memory.restore') return 'memory_restore'
  if (name === 'agent.memory.extract') return 'memory_write'
  if (t === 'faq_lookup' || name === 'agent.prepare.faq') return 'faq'
  if (t === 'intent_recognition' || name === 'agent.prepare.intent') return 'intent'
  if (t === 'emotion_recognition' || name === 'agent.prepare.emotion') return 'emotion'
  if (t === 'clarification_check' || name === 'agent.prepare.validate' || name === 'agent.prepare.clarification') return 'clarification'
  if (t === 'preparation_merge' || name === 'agent.prepare.merge') return 'merge'
  if (name === 'feedback.deliver') return 'report_delivery'
  if (t === 'answer_ready' || name === 'agent.deliver') return 'delivery'
  if (t === 'finish' || name === 'finish') return 'finish'
  if (name === 'feedback.work') return 'work'
  if (n.kind === 'TOOL' || t === 'tool_call' || name === 'agent.tool' || t === 'read_skill') return 'tool'
  if (n.kind === 'MODEL' || t === 'llm_inference') return 'model'
  return null
}

const methods = { RULE: '规则判断', MODEL: '模型判断', LOOKUP: '知识查询' }
const defaultNames = {video_id:'当前视频',report_template:'报告模板',sources:'默认来源',time_range:'默认时间范围'}
export function preparationDetails(node) {
  const a=node?.attributes || {}
  return {
    visible: !!a['preparation.merge_reason'],
    ruleTask:a['preparation.rule_task'] || '未记录',effectiveTask:a['preparation.effective_task'] || '未记录',
    defaults:Array.isArray(a['preparation.defaults'])?a['preparation.defaults'].map(key=>defaultNames[key]).filter(Boolean):[],
    reason:({RULE_MATCHED:'原文规则匹配',CAPABILITY_DEFAULTS:'按已识别能力补齐默认条件',RULE_PRESERVED:'保留原文规则确认的任务',DEFAULTS_SATISFIED:'宿主条件已补齐缺失字段',MODEL_CONDITIONS_REJECTED:'舍弃与原文不一致的模型条件'}[a['preparation.merge_reason']]) || '查看最终合并结果',
    rewrite:({ACCEPTED:'已采用，经任务条件等价校验',REJECTED:'已舍弃，沿用用户原话',SKIPPED:a['query.rewrite_reason']==='CLARIFICATION_REQUIRED'?'等待必要澄清，未执行改写':'无需改写，沿用用户原话'}[a['query.rewrite_state']]) || '未记录'
  }
}
const skipReasons = { CONFIG_DISABLED: '配置禁用', FAQ_HIT: 'FAQ 已命中', CLARIFICATION_REQUIRED: '等待补充信息', RESOURCE_LOOKUP: '交由资源选择处理', RULE_ROUTE: '规则路由已完成' }
export function cardSummary(n) {
  const a = n.attributes || {}, out = n.output
  if (n.synthetic) return n.summary
  if (failures.has(n.status)) return a['error.code'] || ({ FAILED: '执行失败', TIMED_OUT: '执行超时', CANCELLED: '已取消' }[n.status])
  if (['SKIPPED', 'DISABLED'].includes(a['preparation.state'])) return skipReasons[a['preparation.reason']] || '本次未执行'
  switch (n.cardKind || semanticKind(n)) {
    case 'delivery': return n.status === 'SUCCEEDED' ? '结果已返回用户' : '查看交付状态'
    case 'report_delivery': return n.status === 'SUCCEEDED' ? '后台报告已就绪并完成交接' : '查看报告交付状态'
    case 'finish': return ({ SUCCEEDED: '本次运行已成功结束', NEEDS_INPUT: '等待用户补充信息', CANCELLED: '本次运行已取消' }[n.status]) || '查看最终运行状态'
    case 'merge': {
      if(a['request.route']==='CLARIFY')return ({REQUIRED_CONDITIONS:'必要条件仍缺失或存在歧义',UNKNOWN_GOAL:'任务目标尚未明确',UNRESOLVED_TASK:'任务尚不能可靠确定',UNRESOLVED_CONDITIONS:'必要条件尚不能确定',MISSING_FIELDS:'必要字段仍未补齐',AMBIGUOUS_TARGET:'需要确定唯一任务目标',CONTRADICTORY_CONSTRAINTS:'需要解决用户条件冲突',CONFIRMATION_REQUIRED:'需要用户确认后继续',UNSPECIFIED:'模型要求澄清，未提供结构化原因',MODEL_AMBIGUITY:'存在尚未解决的关键歧义',RECOGNITION_UNAVAILABLE:'识别服务暂不可用'}[a['clarification.reason']]) || '调用澄清工具，等待补充'
      const defaults=preparationDetails(n).defaults
      if(a['request.route']==='HARNESS' && defaults.length)return `采用${defaults.join('、')}，进入 Agent`
      if(a['preparation.merge_reason']==='RULE_PRESERVED')return '按原文已确认的任务继续执行'
      if(a['preparation.merge_reason']==='MODEL_CONDITIONS_REJECTED')return '舍弃不一致的模型条件，保留宿主确认条件'
      return ({ HARNESS: '条件已确认，进入 Agent', FAQ: '直接回答' }[a['request.route']]) || '合并三个独立识别结果'
    }
    case 'faq': return a['faq.hit'] === true ? `命中 · ${a['faq.id'] || '知识条目'}` : a['faq.hit'] === false ? '未命中，继续处理' : '匹配结果未记录'
    case 'memory': return Number.isFinite(a['memory.selected_count']) ? `采用 ${a['memory.selected_count']} 条 · ${a['memory.context_bytes'] || 0} B` : '按本轮作用域召回记忆'
    case 'memory_restore': return `恢复原快照 ${a['memory.selected_count'] || 0} 条 · 未重新检索`
    case 'memory_write': return Number.isFinite(a['memory.candidate_count']) ? `新增 ${a['memory.added_count'] || 0} · 更新 ${a['memory.updated_count'] || 0} · 重复 ${a['memory.duplicate_count'] || 0} · 冲突 ${a['memory.conflict_count'] || 0}` : '从近期对话整理有来源的偏好与目标'
    case 'compaction': return Number.isFinite(a['compaction.source_messages']) ? `${a['compaction.source_messages']} 条消息 · 节省 ${a['compaction.saved_bytes'] || 0} B` : '整理旧历史，保留近期原文'
    case 'intent': return intentDetails(n).primary?.label || intentNames[a['preparation.intent']] || '识别结果未记录'
    case 'emotion': return emotionNames[a['preparation.emotion']] || '识别结果未记录'
    case 'clarification': {
      const required = a['clarification.required'] ?? (typeof out?.required === 'boolean' ? out.required : undefined)
      return required === true ? '需要补充信息' : required === false ? '条件完整，无需澄清' : '判断结果未记录'
    }
    case 'model': {
      const decision = a['model.decision'] || out?.decision
      const calls = decisionCalls(n)
      if (calls.length) return `调用 ${calls.map(call => toolDisplayName(call.name)).join('、')}`
      if (decision === 'CALL_TOOL' || decision === 'TOOL') return '调用工具（名称未记录）'
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
  card.cardTitle = cardKind === 'tool' ? toolNames[n.tool] || n.tool || '工具调用' : cardKind === 'work' ? phaseNames[n.attributes?.['analysis.work_kind']] || phaseNames[n.attributes?.['analysis.phase']] || '后台步骤' : titles[cardKind]
  if (cardKind === 'model' && n.turn > 0) card.cardTitle = `模型思考 · 第 ${n.turn} 轮`
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
    const errors = members.filter(n => failures.has(n.status) && !ancestors(n).some(p => members.some(m => m.id === p.id) && failures.has(p.status)))
    const latest = new Map()
    for (const root of [...roots].sort(compare)) {
      const key = root.attributes?.['analysis.work_id'] || root.id
      const prior = latest.get(key)
      if (!prior || (root.attributes?.['execution.attempt'] || 1) >= (prior.attributes?.['execution.attempt'] || 1)) latest.set(key,root)
    }
    return { id, sourceId: null, synthetic: true, cardKind: kind, cardTitle: title, name: title, label: title, kind: 'STAGE',
      parentId: roots[0].parentId, offsetMs: Number.isFinite(start) ? start : null, durationMs: Number.isFinite(stop - start) ? stop - start : null,
      startMs: roots[0].startMs, status: statusOrder.find(s => [...latest.values()].some(n => n.status === s)) || 'UNKNOWN',
      memberIds: members.map(n => n.id), rootIds: roots.map(n => n.id), failureIds: errors.map(n => n.id),
      inputTokens: sum('inputTokens'), outputTokens: sum('outputTokens'), totalTokens: sum('totalTokens'), modelCalls: models.length,
      model: [...new Set(models.map(n => n.model).filter(Boolean))].join(' / '), repeatCount: roots.length, attributes: {},
      summary: `${roots.length} 个步骤${errors.length ? ` · ${errors.length} 条异常` : ''}`, children: [] }
  }
  for (const roots of jobs.values()) {
    roots.sort(compare)
    const job = aggregate(`job:${roots[0].id}`, roots, 'background', '后台反馈分析')
    job.summary = `${job.modelCalls} 次模型调用 · ${roots.length} 个步骤${job.failureIds.length ? ` · ${job.failureIds.length} 次失败尝试${job.status === 'SUCCEEDED' ? '，重试后成功' : ''}` : ''}`
    job.legacy = !roots[0].attributes?.['analysis.job_id']
    job.jobId = roots[0].attributes?.['analysis.job_id'] || null
    const units = [], retries = new Map()
    for (const root of roots) {
      const workId = root.attributes?.['analysis.work_id']
      const key = workId && JSON.stringify([root.parentId, workId, root.attributes?.['analysis.work_kind'], root.attributes?.['analysis.phase']])
      if (!key) { units.push([root]); continue }
      const attempts = retries.get(key)
      if (attempts && Number.isFinite(end(root)) && end(attempts.at(-1)) <= time(root)
          && (root.attributes?.['execution.attempt'] || 1) > (attempts.at(-1).attributes?.['execution.attempt'] || 1)) attempts.push(root)
      else { const next = [root]; retries.set(key, next); units.push(next) }
    }
    const steps = units.map(attempts => {
      if (attempts.length === 1) return { ...presentNode(attempts[0]), memberIds: subtree(attempts[0]).map(n => n.id) }
      const retry = aggregate(`retry:${attempts[0].id}`, attempts, 'work', cardTitle(presentNode(attempts[0])))
      retry.attributes = { ...attempts[0].attributes }
      retry.children = attempts.map(n => ({ ...presentNode(n), cardTitle: `第 ${n.attributes?.['execution.attempt'] || 1} 次尝试`, memberIds: subtree(n).map(x => x.id) }))
      retry.highlight = retry.status === 'SUCCEEDED' ? `重试 ${attempts.length - 1} 次后成功` : `${attempts.length} 次尝试 · ${retry.status === 'RUNNING' ? '处理中' : '查看异常'}`
      retry.summary = retry.highlight
      groups.set(retry.id, retry)
      return retry
    }).sort(compare)
    let batch = []
    const flush = () => {
      if (batch.length > 1) {
        const roots = batch.flatMap(n => n.rootIds?.map(id => byId.get(id)) || [byId.get(n.id)])
        const group = aggregate(`batch:${batch[0].id}`, roots, 'work', cardTitle(batch[0]))
        group.summary = `${batch.length} 次 · 串行${group.failureIds.length ? ` · ${group.failureIds.length} 条异常` : ''}`
        group.children = batch
        group.repeatCount = batch.length
        const failed = batch.filter(n => failures.has(n.status)).length
        group.highlight = `${batch.filter(n => n.status === 'SUCCEEDED').length} 成功${failed ? ` · ${failed} 异常` : ''}${group.failureIds.length && !failed ? ' · 含重试' : ''}`
        groups.set(group.id, group); job.children.push(group)
      } else if (batch.length) job.children.push(batch[0])
      batch = []
    }
    const signature = n => JSON.stringify([n.parentId, n.attributes?.['analysis.work_kind'], n.attributes?.['analysis.phase'], n.attributes?.['resource.id'], n.attributes?.['video.bvid'], n.model, n.attributes?.['execution.attempt'] || 1])
    for (const n of steps) {
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
      if (n.kind === 'MODEL') {
        const candidates = members.filter(m => m.kind === 'MODEL' || m.type === 'llm_inference')
        const calls = candidates.filter(m => !candidates.some(c => c.id !== m.id && ancestors(c).some(p => p.id === m.id)))
        for (const key of ['inputTokens','outputTokens','totalTokens'])
          card[key] = calls.length && calls.every(c => c[key] != null) ? calls.reduce((sum,c) => sum + c[key],0) : null
        card.model = [...new Set(calls.map(c => c.model).filter(Boolean))].join(' / ') || card.model
      }
      card.memberIds = members.map(x => x.id)
      card.failureIds = members.filter(x => failures.has(x.status)).map(x => x.id)
      if (card.failureIds.length && !failures.has(card.status)) card.summary += ` · 内部 ${card.failureIds.length} 条异常`
      const stage = ({ intent: 'intent', emotion: 'emotion', clarification: 'understanding' })[kind]
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
  if (!cards.some(n => ['memory_write','compaction'].includes(n.cardKind)) && !cards.some(n => n.cardKind === 'emotion')) notices.push('情绪识别：未执行或未记录')
  if (!cards.some(n => ['memory_write','compaction'].includes(n.cardKind)) && !cards.some(n => n.cardKind === 'clarification')) notices.push('澄清判断：无独立记录')
  if (!cards.length) notices.push('暂无业务节点，请查看原始节点')
  const allCards = new Map()
  const index = list => list.forEach(n => { allCards.set(n.id, n); if (n.children?.length) index(n.children) })
  index(cards)
  const presentation = { cards, allCards, groups, notices, rawById: byId }
  for (const card of cards.filter(n => n.cardKind === 'model')) {
    const calls = modelInvocations(card, presentation)
    if (calls.length && !failures.has(card.status)) card.summary = `调用 ${calls.map(call => call.skill ? `Skill：${call.skill}` : call.label).join('、')}`
  }
  return presentation
}

/** A short overview over the same source-backed cards; expansion restores each original step. */
export function buildTraceOverview(presentation) {
  const cards = [], allCards = new Map(presentation.allCards), groups = new Map(presentation.groups)
  const group = (children, kind, title) => {
    if (!children.length) return
    const last = children.at(-1), first = children[0]
    const failures = [...new Set(children.flatMap(n => n.failureIds || []))]
    const status = kind === 'delivery_group' ? last.attributes?.['run.status'] || last.status : statusOrder.find(s => children.some(n => n.status === s)) || 'UNKNOWN'
    const sources = [['analyze_video_content','视频'],['analyze_danmaku','弹幕'],['analyze_comments','评论']].filter(([tool]) => children.some(n => n.tool === tool)).map(([,name]) => name)
    const highlight = kind === 'delivery_group' ? status === 'SUCCEEDED' ? children.some(n => n.cardKind === 'report_delivery') ? '报告已返回' : '结果已返回' : status === 'NEEDS_INPUT' ? '等待用户补充' : '查看最终状态'
      : sources.length ? sources.join('、') : `${children.filter(n => n.cardKind === 'tool').length} 项工具调用`
    const result = { id: `${kind}:${first.id}`, sourceId: null, synthetic: true, cardKind: kind, cardTitle: title, name: title, label: title,
      kind: 'STAGE', parentId: first.parentId, offsetMs: first.offsetMs, startMs: first.startMs,
      durationMs: Number.isFinite(end(last)) && Number.isFinite(time(first)) ? end(last) - time(first) : null,
      status, highlight, summary: highlight, children, repeatCount: children.length, memberIds: [...new Set(children.flatMap(n => n.memberIds || [n.id]))], failureIds: failures, attributes: {},
    }
    cards.push(result); groups.set(result.id,result); allCards.set(result.id,result)
  }
  let pending = [], category = null
  const flush = () => { if (pending.length) group(pending,category,category === 'execution' ? '执行任务' : '交付结果'); pending=[]; category=null }
  for (const card of presentation.cards) {
    const next = card.cardKind === 'model' || card.cardKind === 'tool' && card.tool !== 'ask_clarification' ? 'execution'
      : ['report_delivery','delivery','finish'].includes(card.cardKind) ? 'delivery_group' : null
    if (next !== category) flush()
    if (next) { category=next; pending.push(card) } else cards.push(card)
  }
  flush()
  return { ...presentation, cards, allCards, groups }
}

// Keep the agent loop visible while retaining the compact background groups.
export function defaultExpandedStages(presentation) {
  return new Set(presentation.cards.filter(card => card.cardKind === 'execution').map(card => card.id))
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
  // A merge span measures waiting as well as evaluation. Its graph transition
  // occurs when fan-in completes; its original start/duration remain in details.
  const graphTime = card => card.cardKind === 'merge' && Number.isFinite(end(card)) ? end(card) : time(card)
  cards.sort((a, b) => graphTime(a) - graphTime(b) || compare(a, b))
  const displayStarts = new Map(), siblings = new Map()
  for (const card of cards) {
    const key = card.parentId || 'root'
    if (!siblings.has(key)) siblings.set(key, [])
    siblings.get(key).push(card)
  }
  for (const peers of siblings.values()) {
    let batch = [], commonEnd = Infinity
    const flush = () => { if (batch.length > 1) batch.forEach(n => displayStarts.set(n.id, graphTime(batch[0]))); batch = []; commonEnd = Infinity }
    for (const card of peers) {
      if (card.summaryOnly || !Number.isFinite(end(card))) { flush(); continue }
      if (graphTime(card) >= commonEnd) flush()
      batch.push(card); commonEnd = Math.min(commonEnd, end(card))
    }
    flush()
  }
  cards.sort((a, b) => (displayStarts.get(a.id) ?? graphTime(a)) - (displayStarts.get(b.id) ?? graphTime(b)) || compare(a, b))
  const nodes = [], edges = [], lanes = [], frontier = new Map()
  let lastX = 0
  const stop = n => n.summaryOnly ? time(n) : end(n)
  const connect = (a, b, relation) => {
    if (a.id === b.id) return
    edges.push({ id: `${relation}:${a.id}:${b.id}`, source: a.id, target: b.id, relation })
  }
  for (const card of cards) {
    const displayTime = displayStarts.get(card.id) ?? graphTime(card)
    const known = Number.isFinite(displayTime)
    const finished = known ? [...frontier.values()].filter(n => Number.isFinite(stop(n.data)) && stop(n.data) <= displayTime) : []
    // Each ended branch joins the next chronological step. A new overlapping
    // branch forks from the last completed step, never from still-running work.
    let predecessors = finished
    if (known && !finished.length) {
      const completed = nodes.filter(n => Number.isFinite(stop(n.data)) && stop(n.data) <= displayTime)
      const latest = Math.max(-Infinity, ...completed.map(n => stop(n.data)))
      predecessors = completed.filter(n => stop(n.data) === latest)
    }
    let lane = known ? lanes.findIndex(n => Number.isFinite(stop(n.data)) && stop(n.data) <= displayTime) : -1
    if (lane < 0) lane = lanes.length
    const x = Math.max(lastX, 0, ...predecessors.map(n => n.position.x + 176))
    const item = { id: card.id, type: 'trace', position: { x, y: lane * 76 }, data: card }
    for (const previous of predecessors) {
      const call = previous.data.cardKind === 'model' && card.cardKind === 'tool' && previous.data.parentId === card.parentId && previous.data.attributes?.['model.selected_tool'] === card.tool
      connect(previous, item, call ? 'call' : finished.length > 1 ? 'join' : finished.length ? 'order' : 'branch')
    }
    finished.forEach(n => frontier.delete(n.id))
    frontier.set(card.id, item); lanes[lane] = item; nodes.push(item); lastX = x
  }
  // Keep entry fan-out centred on its continuation, rather than attaching the
  // whole graph to the top lane and letting every branch grow downwards.
  const entryGroups = new Map()
  for (const node of nodes.filter(n => ['intent', 'emotion', 'clarification'].includes(n.data.cardKind)
      && n.data.attributes?.['preparation.method'] === 'MODEL' && !n.data.summaryOnly)) {
    const key = node.data.parentId || 'entry'
    if (!entryGroups.has(key)) entryGroups.set(key, [])
    entryGroups.get(key).push(node)
  }
  for (const group of entryGroups.values()) {
    if (group.length !== 3 || new Set(group.map(n => n.data.cardKind)).size !== 3) continue
    if (Math.max(...group.map(n => time(n.data))) >= Math.min(...group.map(n => end(n.data)))) continue
    const oldLanes = new Set(group.map(n => n.position.y))
    const middle = [...oldLanes].reduce((sum, y) => sum + y, 0) / oldLanes.size
    for (const node of nodes) if (!group.includes(node) && node.position.y === group[0].position.y) node.position.y = middle
    group.find(n => n.data.cardKind === 'intent').position.y = middle - 76
    group.find(n => n.data.cardKind === 'emotion').position.y = middle
    group.find(n => n.data.cardKind === 'clarification').position.y = middle + 76
  }
  const toolBatches = new Map()
  for (const node of nodes.filter(n => n.data.cardKind === 'tool')) {
    const parent = presentation.rawById.get(node.data.parentId)
    if (parent?.name !== 'agent.tool.batch' || parent.attributes?.['tool.execution_mode'] !== 'PARALLEL') continue
    if (!toolBatches.has(parent.id)) toolBatches.set(parent.id, [])
    toolBatches.get(parent.id).push(node)
  }
  for (const group of toolBatches.values()) {
    if (group.length < 2 || Math.max(...group.map(n => time(n.data))) >= Math.min(...group.map(n => end(n.data)))) continue
    const ids = new Set(group.map(n => n.id))
    const incoming = [...new Set(edges.filter(e => ids.has(e.target) && !ids.has(e.source)).map(e => e.source))].map(id => nodes.find(n => n.id === id)).filter(Boolean)
    const centre = incoming.length ? incoming.reduce((sum,n) => sum+n.position.y,0)/incoming.length : group.reduce((sum,n)=>sum+n.position.y,0)/group.length
    group.sort((a,b)=>(a.data.attributes?.['tool.batch_index'] || 0)-(b.data.attributes?.['tool.batch_index'] || 0))
      .forEach((node,index)=>{node.position.y=centre+(index-(group.length-1)/2)*76})
  }
  return { nodes, edges }
}
