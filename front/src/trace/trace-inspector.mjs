import { contextDetails, cardTitle, presentNode } from './trace-presentation.mjs'

export function runInspectorSummary(trace, presentation) {
  const allNodes = trace?.nodes || [], byId = new Map(allNodes.map(node => [node.id,node]))
  const inRun = node => {
    const seen = new Set()
    let current = node
    while (current && !seen.has(current.id)) {
      const runId = current.attributes?.['run.id']
      if (runId) return !trace?.runId || runId === trace.runId
      seen.add(current.id); current = byId.get(current.parentId)
    }
    return true
  }
  const nodes = allNodes.filter(inRun)
  const below = (node, ancestor) => {
    const seen = new Set()
    let parent = byId.get(node.parentId)
    while (parent && !seen.has(parent.id)) {
      if (parent.id === ancestor.id) return true
      seen.add(parent.id); parent = byId.get(parent.parentId)
    }
    return false
  }
  const snapshots = nodes.filter(node => contextDetails(node).visible)
  // A nested span may repeat its parent's snapshot; count the assembly once.
  const assemblies = snapshots.filter(node => !snapshots.some(parent => parent.id !== node.id && below(node,parent)))
  const combined = new Map()
  for (const node of assemblies) for (const slot of contextDetails(node).slots) {
    const prior = combined.get(slot.name)
    combined.set(slot.name, {name:slot.name,label:slot.label,bytes:prior ? (prior.bytes == null || slot.bytes == null ? null : prior.bytes + slot.bytes) : slot.bytes})
  }
  const requests = nodes.filter(node => node.name === 'model.request' && node.attributes?.['model.dispatched'] !== false)
  const models = nodes.filter(node => (node.kind === 'MODEL' || node.type === 'llm_inference') && node.attributes?.['model.dispatched'] !== false)
  const modelLeaves = requests.length ? requests : models.filter(node => !models.some(child => child.id !== node.id && below(child,node)))
  const modelCalls = modelLeaves.length
  const responseRecords = modelLeaves.map(node => measuredResponseDetails(node)).filter(timing => timing?.total != null)
  const phaseRecords = responseRecords.filter(timing => timing.first != null)
  const responseTiming = responseRecords.length ? {
    total:responseRecords.reduce((sum, item) => sum + item.total, 0),
    first:phaseRecords.length === responseRecords.length ? phaseRecords.reduce((sum, item) => sum + item.first, 0) : null,
    remaining:phaseRecords.length === responseRecords.length ? phaseRecords.reduce((sum, item) => sum + item.remaining, 0) : null,
    recorded:responseRecords.length, calls:modelCalls,
  } : null
  if (responseTiming && phaseRecords.length !== responseRecords.length) {
    const groups = new Map()
    for (const node of modelLeaves) {
      const timing = measuredResponseDetails(node)
      if (timing?.total == null) continue
      const label = node.attributes?.['model.streaming'] === true ? '流式调用' : node.attributes?.['model.streaming'] === false ? '非流式调用' : '模型调用'
      groups.set(label,(groups.get(label) || 0)+timing.total)
    }
    responseTiming.segments = [...groups].map(([label,value],index) => ({label,value,color:index === 0 ? 'var(--trace-clay)' : 'var(--trace-wait)'}))
    responseTiming.callDuration = true
  }
  const toolNodes = nodes.filter(node => node.kind === 'TOOL' && (node.tool || node.type === 'tool_call'))
  const roots = toolNodes.filter(node => !toolNodes.some(parent => parent.id !== node.id && below(node,parent)))
  const tools = [...new Map(roots.map(node => [node.attributes?.['tool.call_id'] || node.id,node])).values()]
    .sort((a,b) => (a.offsetMs ?? Infinity) - (b.offsetMs ?? Infinity))
    .map(node => {
      const card = presentation?.allCards?.get(node.id) || presentNode(node)
      return {id:node.id,label:cardTitle(card),name:node.tool || node.name,status:node.status,businessError:node.attributes?.['tool.success'] === false,offsetMs:node.offsetMs}
    })
  const decisions = models.filter(node => node.attributes?.['model.decision']).map(node => ({id:node.id,label:'模型决策',name:node.attributes['model.decision'],offsetMs:node.offsetMs,kind:'model'}))
  const events = [...decisions, ...tools.map(tool => ({...tool,kind:'tool'}))].sort((a,b) => (a.offsetMs ?? Infinity) - (b.offsetMs ?? Infinity))
  return {
    responseTiming, events, modelDetails:modelInspectorDetails(modelLeaves, nodes),
    context:{visible:combined.size > 0,slots:[...combined.values()],version:null,projection:null, note:assemblies.length ? `已记录 ${assemblies.length} 次` : null},
    modelCalls, tools,
  }
}

// Only a single leaf model invocation can provide a two-phase response chart.
export function modelResponseDetails(node, rawById = new Map()) {
  if (!node || (node.kind !== 'MODEL' && node.cardKind !== 'model')) return null
  const members = [...new Set([node.sourceId || node.id, ...(node.memberIds || [])])]
    .map(id => rawById.get(id)).filter(Boolean)
  const models = members.filter(n => n.kind === 'MODEL' || n.type === 'llm_inference')
  const hasAncestor = (child, id) => {
    const seen = new Set()
    let current = rawById.get(child.parentId)
    while (current && !seen.has(current.id)) {
      if (current.id === id) return true
      seen.add(current.id); current = rawById.get(current.parentId)
    }
    return false
  }
  const leaves = models.filter(n => !models.some(other => other.id !== n.id && hasAncestor(other, n.id)))
  if (leaves.length > 1) return null
  const attrs = (leaves[0] || node).attributes || {}
  const response = attrs['model.response_ms'], first = attrs['model.first_token_ms']
  if (!Number.isFinite(response) || response < 0) {
    return Number.isFinite(first) && first >= 0 ? { total:null, first, remaining:null } : null
  }
  const phases = Number.isFinite(first) && first >= 0 && first <= response && response > 0
  return { total: response, first: phases ? first : null, remaining: phases ? response - first : null }
}


export function modelInspectorDetails(calls, nodes = calls) {
  const byId = new Map(nodes.map(node => [node.id,node]))
  const positive = value => Number.isFinite(value) && value >= 0
  const records = calls.map(node => {
    const attrs = node.attributes || {}
    const input = node.inputTokens ?? attrs['model.input_tokens']
    const capacity = [attrs['model.context_window'], attrs['model.context_window_tokens'], attrs['context.window_tokens']].find(value => positive(value) && value > 0) ?? null
    let contextNode = node
    const seen = new Set()
    while (!contextDetails(contextNode).visible && byId.has(contextNode.parentId) && !seen.has(contextNode.id)) {
      seen.add(contextNode.id); contextNode = byId.get(contextNode.parentId)
    }
    let budgetNode = node
    const budgetSeen = new Set()
    while (!(budgetNode.attributes?.['context.byte_limit'] > 0) && byId.has(budgetNode.parentId) && !budgetSeen.has(budgetNode.id)) {
      budgetSeen.add(budgetNode.id); budgetNode = byId.get(budgetNode.parentId)
    }
    const byteLimit = budgetNode.attributes?.['context.byte_limit']
    const requestBytes = attrs['context.request_bytes'] ?? attrs['context.input_bytes'] ?? budgetNode.attributes?.['context.input_bytes']
    const byteWindow = positive(requestBytes) && byteLimit > 0 ? {used:requestBytes,capacity:byteLimit,unit:'B'} : null
    return {node, attrs, input:positive(input) ? input : null, capacity, byteWindow, context:contextDetails(contextNode)}
  })
  const peak = records.filter(record => record.input != null).sort((a,b) => {
    if (a.capacity != null && b.capacity != null) return b.input/b.capacity - a.input/a.capacity
    return b.input-a.input
  })[0]
  const cached = records.filter(record => positive(record.attrs['model.cache_hit_tokens']) && record.input > 0 && record.attrs['model.cache_hit_tokens'] <= record.input)
  const cacheRate = cached.length ? cached.reduce((sum,r) => sum+r.attrs['model.cache_hit_tokens'],0) / cached.reduce((sum,r) => sum+r.input,0) * 100 : null
  const generation = records.filter(record => positive(record.node.outputTokens) && measuredResponseDetails(record.node)?.total > 0)
  const tps = generation.length ? generation.reduce((sum,r) => sum+r.node.outputTokens,0) / generation.reduce((sum,r) => sum+measuredResponseDetails(r.node).total,0) * 1000 : null
  return {
    cacheRate, tps, cachePartial:cached.length < calls.length, tpsPartial:generation.length < calls.length,
    models:[...new Set(records.map(r => r.node.model || r.attrs['model.name']).filter(Boolean))],
    types:[...new Set(records.map(r => r.attrs['model.streaming'] === true ? '流式' : r.attrs['model.streaming'] === false ? '非流式' : null).filter(Boolean))],
    count:calls.length,
    window:peak?.capacity != null ? {used:peak.input,capacity:peak.capacity} : (records.filter(record => record.byteWindow).sort((a,b) => b.byteWindow.used/b.byteWindow.capacity-a.byteWindow.used/a.byteWindow.capacity)[0]?.byteWindow || {used:peak?.input ?? null,capacity:null}),
    peakContext:peak?.context?.visible ? peak.context : null,
  }
}


export function measuredResponseDetails(node, rawById = new Map()) {
  const direct = modelResponseDetails(node,rawById)
  if (direct?.total != null) return direct
  const members = [...new Set([node?.sourceId || node?.id,...(node?.memberIds || [])])].map(id => rawById.get(id)).filter(Boolean)
  const requests = members.filter(member => member.name === 'model.request')
  const source = requests.length === 1 ? requests[0] : requests.length === 0 ? node : null
  if (source?.name === 'model.request' && Number.isFinite(source.durationMs) && source.durationMs >= 0) return {total:source.durationMs,first:null,remaining:null,callDuration:true}
  return direct
}
