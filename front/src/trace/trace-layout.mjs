// The call hierarchy supplies containment, not execution order. Coordinates use
// start timestamps (compressed, not a duration scale); only overlapping peers
// occupy separate lanes. The duration scale remains in the timeline below.
const start = (node) => Number.isFinite(node.offsetMs) ? node.offsetMs : Infinity
const end = (node) => Number.isFinite(node.durationMs) && Number.isFinite(node.offsetMs)
  ? node.offsetMs + Math.max(0, node.durationMs) : Infinity
const signature = (node) => JSON.stringify([node.name, node.type, node.kind, node.tool, node.model])
const severity = ['FAILED', 'TIMED_OUT', 'CANCELLED', 'RUNNING', 'NEEDS_INPUT', 'UNKNOWN', 'SUCCEEDED']

export function buildTraceGraph(nodes, expanded = new Set()) {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const children = new Map()
  const ancestors = new Map()
  for (const node of nodes) {
    const parent = byId.has(node.parentId) ? node.parentId : null
    if (!children.has(parent)) children.set(parent, [])
    children.get(parent).push(node)
    const chain = new Set()
    let cursor = byId.get(node.parentId)
    while (cursor && !chain.has(cursor.id)) {
      chain.add(cursor.id)
      cursor = byId.get(cursor.parentId)
    }
    ancestors.set(node.id, chain)
  }
  const compare = (a, b) => start(a) - start(b) || ancestors.get(a.id).size - ancestors.get(b.id).size || a.id.localeCompare(b.id)
  const clusters = new Map(), membership = new Map()
  for (const siblings of children.values()) {
    siblings.sort(compare)
    let run = []
    const flush = () => {
      if (run.length > 1) {
        const id = `repeat:${run[0].id}`
        const memberIds = []
        const pending = [...run]
        while (pending.length) {
          const item = pending.pop()
          memberIds.push(item.id)
          pending.push(...(children.get(item.id) || []))
        }
        const cluster = { id, roots: [...run], memberIds }
        clusters.set(id, cluster)
        for (const node of run) membership.set(node.id, cluster)
      }
      run = []
    }
    for (const node of siblings) {
      const previous = run.at(-1)
      if (previous && (signature(previous) !== signature(node) || end(previous) > start(node) || !Number.isFinite(end(previous)) || !Number.isFinite(end(node)))) flush()
      run.push(node)
    }
    flush()
  }
  const visible = []
  const visit = (node) => {
    const cluster = membership.get(node.id)
    if (cluster && !expanded.has(cluster.id)) {
      if (cluster.roots[0].id !== node.id) return
      const members = cluster.memberIds.map((id) => byId.get(id))
      visible.push({ ...node, clusterId: cluster.id, repeatCount: cluster.roots.length,
        memberIds: cluster.memberIds, durationMs: end(cluster.roots.at(-1)) - start(node),
        status: severity.find((status) => members.some((item) => item.status === status)) || node.status })
      return
    }
    visible.push({ ...node, expandedCluster: cluster?.roots[0].id === node.id ? cluster.id : null })
    for (const child of children.get(node.id) || []) visit(child)
  }
  for (const root of children.get(null) || []) visit(root)
  visible.sort(compare)

  const lanes = [], result = [], edges = [], continued = new Set()
  const connect = (source, target, kind) => {
    edges.push({ id: `${kind}:${source.id}:${target.id}`, source: source.id, target: target.id })
    continued.add(source.id)
  }
  let column = -1, lastStart = -Infinity
  for (const node of visible) {
    const time = start(node), known = Number.isFinite(time)
    // Ancestors contain their descendants; they are not parallel work.
    let lane = known ? lanes.findIndex((items) => items.every((item) => end(item.data) <= time || ancestors.get(node.id).has(item.data.id))) : -1
    if (lane < 0) { lane = lanes.length; lanes.push([]) }
    const previous = lanes[lane].at(-1)
    if (!known || time !== lastStart || (previous && previous.position.x === column * 232)) column++
    lastStart = time
    const item = { id: node.id, type: 'trace', position: { x: column * 232, y: lane * 116 },
      sourcePosition: 'right', targetPosition: 'left', data: node }
    if (previous && known) connect(previous, item, 'order')
    // A parallel branch starts from its closest visible ancestor. Subsequent
    // edges show order within that lane, not an invented causal dependency.
    if (!previous && lane > 0 && known) {
      const preceding = [...result].reverse().find((candidate) => end(candidate.data) <= time)
        || [...result].reverse().find((candidate) => ancestors.get(node.id).has(candidate.id))
      if (preceding) connect(preceding, item, 'branch')
    }
    // Rejoin finished branches at the next step outside those scopes, without
    // asserting that an overlapping sibling has already completed.
    if (known) lanes.forEach((items, index) => {
      const tail = items.at(-1)
      if (index !== lane && tail && !continued.has(tail.id)
          && items.every((entry) => end(entry.data) <= time || ancestors.get(node.id).has(entry.id))) {
        connect(tail, item, 'join')
      }
    })
    result.push(item)
    lanes[lane].push(item)
  }
  return { nodes: result, edges, clusters }
}
