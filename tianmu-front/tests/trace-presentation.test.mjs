import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTracePresentation, layoutPresentation } from '../src/trace/trace-presentation.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'
import { parseTraceFile } from '../src/trace/trace-data.mjs'

test('entry recognition forks into three real model spans and joins before the clarification tool', () => {
  const [trace] = parseTraceFile(JSON.stringify(demoFiles.find(d => d.title === '前置处理 · 澄清工具')))
  const presentation = buildTracePresentation(trace.nodes)
  const graph = layoutPresentation(presentation)
  const get = kind => graph.nodes.find(n => n.data.cardKind === kind)
  const faq = get('faq'), merge = get('merge')
  const branches = ['intent', 'emotion', 'clarification'].map(get)
  assert.equal(new Set(branches.map(n => n.position.y)).size, 3)
  assert.equal(faq.position.y, get('emotion').position.y)
  assert.equal(merge.position.y, get('emotion').position.y)
  assert.equal(get('intent').position.y, get('emotion').position.y - 88)
  assert.equal(get('clarification').position.y, get('emotion').position.y + 88)
  for (const branch of branches) {
    assert.equal(branch.data.kind, 'MODEL')
    assert.ok(graph.edges.some(e => e.source === faq.id && e.target === branch.id))
    assert.ok(graph.edges.some(e => e.source === branch.id && e.target === merge.id))
  }
  const tool = get('tool')
  assert.equal(tool.data.tool, 'ask_clarification')
  assert.ok(graph.edges.some(e => e.source === merge.id && e.target === tool.id))
  assert.equal(graph.nodes.filter(n => n.data.cardKind === 'model').length, 0)
  assert.equal(get('clarification').data.summary, '需要补充信息')
})

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
  assert.equal(Math.abs(second.position.y - first.position.y), 88)
  assert.equal(first.data.collapseId, p.cards[0].id)
  assert.equal(graph.nodes.length, 2)
})

test('disabled recognition is an explicit annotation, never a successful timed model call', () => {
  const raw = [
    node('intent', 'agent.prepare.intent', 'ROUTING', 0, 2, { attributes: { 'preparation.method': 'RULE', 'preparation.state': 'EXECUTED', 'preparation.intent': 'VIDEO_METRIC' } }),
    node('emotion', 'agent.prepare.emotion', 'ROUTING', 2, 1, { status: 'DISABLED', attributes: { 'preparation.method': 'NONE', 'preparation.state': 'DISABLED', 'preparation.reason': 'CONFIG_DISABLED' } }),
    node('check', 'agent.prepare.validate', 'ROUTING', 3, 2, { attributes: { 'preparation.method': 'RULE', 'clarification.required': false } }),
  ]
  const p = buildTracePresentation(raw)
  assert.equal(p.allCards.get('intent').executionLabel, '规则判断')
  const emotion = p.allCards.get('emotion')
  assert.equal(emotion.summary, '配置禁用')
  assert.equal(emotion.status, 'DISABLED')
  assert.equal(emotion.durationMs, null)
  assert.equal(emotion.durationLabel, '未执行')
  assert.equal(raw[1].durationMs, 1)
  assert.equal(p.allCards.get('check').summary, '条件完整，无需澄清')
  assert.equal(p.notices.length, 0)
})

test('FAQ skips and old missing records remain distinguishable from disabled configuration', () => {
  const p = buildTracePresentation([
    node('intent', 'agent.prepare.intent', 'ROUTING', 0, 1, { status: 'SKIPPED', attributes: { 'preparation.state': 'SKIPPED', 'preparation.reason': 'FAQ_HIT' } }),
    node('check', 'agent.prepare.validate', 'STAGE', 2, 1),
  ])
  assert.equal(p.allCards.get('intent').summary, 'FAQ 已命中')
  assert.equal(p.allCards.get('intent').status, 'SKIPPED')
  assert.equal(p.allCards.get('check').summary, '判断结果未记录')
  assert.ok(p.notices.includes('情绪识别：未执行或未记录'))
})
