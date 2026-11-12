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
  if (!Number.isFinite(response) || response < 0) return null
  const phases = Number.isFinite(first) && first >= 0 && first <= response && response > 0
  return { total: response, first: phases ? first : null, remaining: phases ? response - first : null }
}
