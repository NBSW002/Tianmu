import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTracePresentation, layoutPresentation } from '../src/trace/trace-presentation.mjs'

const node = (id, name, kind, offsetMs, durationMs, extra = {}) => ({ id, name, kind, offsetMs, durationMs, status: 'SUCCEEDED', attributes: {}, ...extra })

test('linear tree keeps separate decisions, forks parallel work and rejoins before tools', () => {
  const raw = [
    node('faq', 'agent.prepare.faq', 'STAGE', 0, 2, { attributes: { 'faq.hit': false } }),
    node('intent', 'agent.prepare.intent', 'STAGE', 2, 3),
    node('emotion', 'agent.prepare.emotion', 'STAGE', 2, 3),
    node('check', 'agent.prepare.validate', 'STAGE', 5, 1),
    node('model', 'agent.model', 'MODEL', 6, 10),
    node('tool', 'agent.tool', 'TOOL', 16, 2, { tool: 'analyze_feedback' }),
    node('work', 'feedback.work', 'STAGE', 18, 50),
    node('resume', 'agent.model', 'MODEL', 68, 10),
    node('report', 'agent.tool', 'TOOL', 78, 2, { tool: 'create_feedback_report' }),
  ]
  const p = buildTracePresentation(raw)
  assert.equal(p.cards.length, 9)
  assert.deepEqual(p.cards.slice(0, 4).map(n => n.cardKind), ['faq', 'emotion', 'intent', 'clarification'])
  assert.equal(p.allCards.get('tool').tool, 'analyze_feedback')
  assert.equal(p.allCards.get('check').summary, '判断结果未记录')
  const graph = layoutPresentation(p)
  assert.equal(graph.nodes.length, 9)
  assert.ok(graph.edges.some(e => e.source === 'faq' && e.target === 'emotion'))
  assert.ok(graph.edges.some(e => e.source === 'faq' && e.target === 'intent'))
  assert.ok(!graph.edges.some(e => e.source === 'emotion' && e.target === 'intent'))
  assert.ok(graph.edges.some(e => e.source === 'intent' && e.target === 'check'))
  assert.ok(graph.edges.some(e => e.source === 'emotion' && e.target === 'check'))
  assert.ok(graph.edges.every(e => !e.label))

})

test('parallel jobs retain their lanes when a stack expands inline', () => {
  const raw = ['a', 'b'].flatMap((job, i) => [
    node(`w${i}`, 'feedback.work', 'STAGE', 10, 20, { parentId: job, attributes: { 'analysis.job_id': job } }),
    node(`m${i}`, 'feedback.model', 'MODEL', 11, 18, { parentId: `w${i}`, totalTokens: 12, inputTokens: 10, outputTokens: 2 }),
  ])
  const p = buildTracePresentation(raw)
  assert.equal(p.cards.length, 2)
  assert.equal(p.cards[0].modelCalls, 1)
  const graph = layoutPresentation(p, new Set([p.cards[0].id]))
  const first = graph.nodes.find(n => n.id === 'w0')
  const second = graph.nodes.find(n => n.id === p.cards[1].id)
  assert.equal(first.position.x, second.position.x)
  assert.equal(Math.abs(second.position.y - first.position.y), 168)
  assert.equal(first.data.collapseId, p.cards[0].id)
  assert.equal(graph.nodes.length, 2)
})
