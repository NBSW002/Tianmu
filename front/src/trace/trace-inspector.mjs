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
  const responseRecords = modelLeaves.map(node => modelResponseDetails(node)).filter(timing => timing?.total != null)
  const phaseRecords = responseRecords.filter(timing => timing.first != null)
  const responseTiming = responseRecords.length ? {
    total:responseRecords.reduce((sum, item) => sum + item.total, 0),
    first:phaseRecords.length === responseRecords.length ? phaseRecords.reduce((sum, item) => sum + item.first, 0) : null,
    remaining:phaseRecords.length === responseRecords.length ? phaseRecords.reduce((sum, item) => sum + item.remaining, 0) : null,
    recorded:responseRecords.length, calls:modelCalls,
  } : null
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
    responseTiming, events,
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
