import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTracePresentation, layoutPresentation } from '../src/trace/trace-presentation.mjs'

const node = (id, name, kind, offsetMs, durationMs, extra = {}) => ({ id, name, kind, offsetMs, durationMs, status: 'SUCCEEDED', attributes: {}, ...extra })

test('stage overview retains independent decisions and tools, and separates resumed execution', () => {
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
  assert.deepEqual(p.phases.map(n => n.cardTitle), ['请求判断', 'Agent 执行', '后台反馈分析', '恢复执行'])
  assert.deepEqual(p.phases[0].children.map(n => n.cardKind), ['faq', 'emotion', 'intent', 'clarification'])
  assert.equal(p.allCards.get('tool').tool, 'analyze_feedback')
  assert.equal(p.allCards.get('check').summary, '判断结果未记录')
  const graph = layoutPresentation(p)
  assert.equal(graph.nodes.length, 4)
  assert.ok(graph.nodes.every((n, i) => i === 0 || n.position.x > graph.nodes[i - 1].position.x))
  assert.ok(graph.edges.every(e => !e.label))
  assert.equal(raw.length, 9)
})

test('actual overlapping jobs keep separate lanes and expansion cannot overlap the next branch', () => {
  const raw = ['a', 'b'].flatMap((job, i) => [
    node(`w${i}`, 'feedback.work', 'STAGE', 10, 20, { parentId: job, attributes: { 'analysis.job_id': job } }),
    node(`m${i}`, 'feedback.model', 'MODEL', 11, 18, { parentId: `w${i}`, totalTokens: 12, inputTokens: 10, outputTokens: 2 }),
  ])
  const p = buildTracePresentation(raw)
  assert.equal(p.phases.length, 2)
  assert.equal(p.phases[0].modelCalls, 1)
  const graph = layoutPresentation(p, new Set([p.phases[0].id]))
  const roots = graph.nodes.filter(n => n.data.cardKind === 'background')
  assert.equal(roots[0].position.x, roots[1].position.x)
  assert.ok(roots[1].position.y >= 312)
})
