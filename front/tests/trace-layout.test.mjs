import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTraceGraph } from '../src/trace/trace-layout.mjs'

const span = (id, start, duration, parentId = null, name = id, extra = {}) => ({
  id, name, label: name, type: name, kind: 'STAGE', offsetMs: start, durationMs: duration,
  parentId, status: 'SUCCEEDED', ...extra,
})
const positions = (graph) => Object.fromEntries(graph.nodes.map((node) => [node.id, node.position]))

test('serial siblings advance horizontally and a containing span is not a parallel lane', () => {
  const graph = buildTraceGraph([span('root', 0, 100), span('b', 40, 20, 'root'), span('a', 10, 20, 'root'), span('c', 80, 10, 'root')])
  const p = positions(graph)
  assert.ok(p.root.x < p.a.x && p.a.x < p.b.x && p.b.x < p.c.x)
  assert.equal(new Set(graph.nodes.map((node) => node.position.y)).size, 1)
  assert.deepEqual(graph.edges.map((edge) => [edge.source, edge.target]), [['root', 'a'], ['a', 'b'], ['b', 'c']])
})

test('overlapping peers use separate lanes and simultaneous starts align', () => {
  const graph = buildTraceGraph([span('root', 0, 100), span('a', 10, 40, 'root'), span('b', 10, 20, 'root'), span('c', 55, 10, 'root')])
  const p = positions(graph)
  assert.equal(p.a.x, p.b.x)
  assert.notEqual(p.a.y, p.b.y)
  assert.equal(p.c.y, p.a.y)
  assert.ok(p.c.x > p.a.x)
})

test('repeat clusters retain all subtrees, surface failures and expand without losing spans', () => {
  const nodes = [span('root', 0, 100), span('a', 10, 20, 'root', 'work'), span('a1', 11, 5, 'a'),
    span('b', 40, 20, 'root', 'work'), span('b1', 41, 5, 'b', 'model', { status: 'FAILED' })]
  const collapsed = buildTraceGraph(nodes)
  assert.equal(collapsed.nodes.length, 2)
  const group = collapsed.nodes.find((node) => node.data.clusterId)
  assert.equal(group.data.repeatCount, 2)
  assert.equal(group.data.durationMs, 50)
  assert.equal(group.data.status, 'FAILED')
  assert.deepEqual([...group.data.memberIds].sort(), ['a', 'a1', 'b', 'b1'])
  const expanded = buildTraceGraph(nodes, new Set([group.data.clusterId]))
  assert.deepEqual(expanded.nodes.map((node) => node.id).sort(), nodes.map((node) => node.id).sort())
  assert.equal(new Set(expanded.nodes.map((node) => node.position.y)).size, 1)
  assert.equal(expanded.nodes.find((node) => node.id === 'a').data.expandedCluster, group.data.clusterId)
})

test('parallel repetitions, different tools/models and interrupted sequences are not collapsed together', () => {
  for (const nodes of [
    [span('a', 0, 20, null, 'work'), span('b', 10, 20, null, 'work')],
    [span('a', 0, 10, null, 'tool', { tool: 'read' }), span('b', 10, 10, null, 'tool', { tool: 'write' })],
    [span('a', 0, 10, null, 'model', { model: 'one' }), span('b', 10, 10, null, 'model', { model: 'two' })],
    [span('a', 0, 10, null, 'work'), span('x', 10, 10), span('b', 20, 10, null, 'work')],
  ]) assert.equal(buildTraceGraph(nodes).clusters.size, 0)
})

test('unknown timing stays ungrouped and does not acquire invented sequence edges', () => {
  const graph = buildTraceGraph([span('a', 0, 10), span('b', null, null, null, 'work'), span('c', null, null, null, 'work')])
  assert.equal(graph.clusters.size, 0)
  assert.equal(graph.nodes.length, 3)
  assert.equal(graph.edges.length, 0)
  assert.equal(buildTraceGraph([span('a', 0, 10, null, 'work'), span('b', 10, null, null, 'work')]).clusters.size, 0)
})

test('parallel branches fork after preparation and rejoin only after both scopes finish', () => {
  const graph = buildTraceGraph([span('root', 0, 100), span('prepare', 1, 5, 'root'),
    span('a', 10, 30, 'root'), span('b', 10, 50, 'root'), span('deliver', 70, 10, 'root')])
  assert.ok(graph.edges.some((edge) => edge.source === 'prepare' && edge.target === 'b'))
  assert.ok(graph.edges.some((edge) => edge.source === 'a' && edge.target === 'deliver'))
  assert.ok(graph.edges.some((edge) => edge.source === 'b' && edge.target === 'deliver'))
  assert.ok(!graph.edges.some((edge) => edge.source === 'a' && edge.target === 'b'))
})

test('equal timestamps with nested parents do not produce overlapping cards', () => {
  const graph = buildTraceGraph([span('root', 0, 100), span('a', 0, 50, 'root'), span('b', 0, 10, 'a'), span('c', 0, 60, 'root')])
  const occupied = new Set(graph.nodes.map((node) => `${node.position.x},${node.position.y}`))
  assert.equal(occupied.size, graph.nodes.length)
})

test('large serial batch remains compact and expansion is complete', () => {
  const nodes = Array.from({ length: 2000 }, (_, i) => span(`s${String(i).padStart(4, '0')}`, i * 10, 10, null, 'work'))
  const graph = buildTraceGraph(nodes)
  assert.equal(graph.nodes.length, 1)
  assert.equal(graph.nodes[0].data.repeatCount, 2000)
  assert.equal(buildTraceGraph(nodes, new Set(graph.clusters.keys())).nodes.length, 2000)
})
