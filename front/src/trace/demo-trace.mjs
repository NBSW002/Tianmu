/* global BigInt */
// Clearly labelled, synthetic examples for frontend development; not experiment results.
const start = BigInt(Date.parse('2026-10-29T10:24:31+08:00')) * 1000000n
const id = (n) => `10000000000000${String(n).padStart(2, '0')}`

function example(failed = false) {
  const traceId = failed ? 'b2e75df847f14db79df00f92b1ac2402' : 'a1e75df847f14db79df00f92b1ac2401'
  const runId = failed ? 'demo-tool-timeout' : 'demo-danmaku-query'
  const end = failed ? 1950 : 1420
  const span = (n, parent, name, type, kind, offset, duration, attributes = {}) => ({
    schemaVersion: '1.1', eventId: `${traceId.slice(0, 8)}-47f1-4db7-9df0-${String(n).padStart(12, '0')}`, serviceName: 'Tianmu-operations-agent', serviceVersion: 'demo',
    traceId, spanId: id(n), parentSpanId: parent ? id(parent) : '0000000000000000', name, kind: 'INTERNAL',
    startTimeUnixNano: String(start + BigInt(offset) * 1000000n),
    endTimeUnixNano: String(start + BigInt(offset + duration) * 1000000n), durationNanos: duration * 1000000,
    status: attributes['node.status'] === 'TIMED_OUT' ? 'ERROR' : 'UNSET',
    attributes: {
      'run.id': runId, 'span.type': type, 'node.type': kind,
      'node.status': 'SUCCEEDED', ...attributes,
    },
  })
  const spans = [
    span(1, 0, 'agent.run', 'agent_start', 'REQUEST', 0, end, {
      'run.status': failed ? 'FAILED' : 'SUCCEEDED', 'node.status': failed ? 'FAILED' : 'SUCCEEDED',
      'run.e2e_ms': end, 'usage.scope': 'RUN', 'usage.complete': true,
      'usage.input_tokens': failed ? 208 : 448, 'usage.output_tokens': failed ? 116 : 156,
    }),
    span(2, 1, 'agent.route', 'pre_route', 'ROUTING', 0, 280, { 'request.route': 'HARNESS', 'route.reason': 'READY' }),
    span(3, 2, 'query_understanding', 'query_understanding', 'MODEL', 30, 240, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 80, 'model.output_tokens': 20, 'model.decision': 'PROCEED',
    }),
    span(4, 1, 'agent.loop', 'agent_loop', 'LOOP', 300, failed ? 1610 : 980, { 'node.status': failed ? 'FAILED' : 'SUCCEEDED' }),
    span(5, 4, 'agent.model', 'llm_inference', 'MODEL', 300, 420, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 128, 'model.output_tokens': 96,
      'model.decision': 'CALL_TOOL', 'model.selected_tool': 'query_danmaku_count', 'loop.step': 1,
      'model.first_token_ms': 120, 'model.response_ms': 400,
      'context.schema_version': '1.0', 'context.projection_version': 'chat_native_v3',
      'context.present_slots': ['system_prompt','tool_catalog','focus','environment_context','current_context','runtime_state'],
      'context.slot_bytes': ['system_prompt:3355','tool_catalog:2148','focus:588','environment_context:115','current_context:193','runtime_state:135'],
    }),
    span(6, 4, 'agent.tool', 'tool_call', 'TOOL', 740, failed ? 1150 : 130, {
      'tool.name': 'query_danmaku_count', 'tool.success': !failed, 'loop.step': 1,
      'node.status': failed ? 'TIMED_OUT' : 'SUCCEEDED', ...(failed ? { 'error.code': 'TOOL_TIMEOUT' } : {}),
    }),
    ...(!failed ? [span(7, 4, 'agent.model', 'llm_inference', 'MODEL', 890, 390, {
      'model.name': 'deepseek-chat', 'model.input_tokens': 240, 'model.output_tokens': 40,
      'model.decision': 'FINAL_ANSWER', 'loop.step': 2,
    })] : []),
    span(8, 1, failed ? 'error' : 'agent.deliver', failed ? 'error' : 'answer_ready', 'DELIVERY', failed ? 1910 : 1300, failed ? 40 : 100, {
      'node.status': failed ? 'FAILED' : 'SUCCEEDED', ...(failed ? { 'error.code': 'TOOL_TIMEOUT' } : { 'answer.evidence_count': 1 }),
    }),
  ]
  const details = {
    [id(1)]: { input: { message: '查询视频 1001 昨天的弹幕数', context: { videoId: '1001' } }, output: { status: failed ? 'FAILED' : 'SUCCEEDED', ...(failed ? { error: 'TOOL_TIMEOUT' } : { answer: '视频 1001 昨天共有 128 条已入库弹幕。' }) } },
    [id(2)]: { input: { message: '查询视频 1001 昨天的弹幕数' }, output: { route: 'HARNESS', reason: 'READY', businessDate: '2026-10-28' } },
    [id(3)]: { input: { query: '查询视频 1001 昨天的弹幕数', timezone: 'Asia/Shanghai' }, output: { decision: 'PROCEED', videoId: '1001', date: '2026-10-28' } },
    [id(4)]: { input: { availableTools: ['query_danmaku_count'], maxTurns: 8 }, output: { turns: failed ? 1 : 2, outcome: failed ? 'FAILED' : 'FINAL_ANSWER' } },
    [id(5)]: { input: { messages: [{ role: 'user', content: '查询视频 1001 昨天的弹幕数' }], model: 'deepseek-chat' }, output: { decision: 'CALL_TOOL', tool: 'query_danmaku_count', arguments: { videoId: '1001', date: '2026-10-28' } } },
    [id(6)]: { input: { videoId: '1001', date: '2026-10-28' }, output: failed ? { success: false, error: { code: 'TOOL_TIMEOUT', message: '演示：只读查询超时' } } : { success: true, count: 128, evidenceId: 'evidence-demo-001', scope: '当前已入库且仍保留的弹幕' } },
    [id(7)]: { input: { observation: { count: 128, evidenceId: 'evidence-demo-001' } }, output: { decision: 'FINAL_ANSWER', answer: '视频 1001 昨天共有 128 条已入库弹幕。', evidenceIds: ['evidence-demo-001'] } },
    [id(8)]: { input: failed ? { error: 'TOOL_TIMEOUT' } : { answer: '视频 1001 昨天共有 128 条已入库弹幕。', evidenceIds: ['evidence-demo-001'] }, output: { delivered: true, type: failed ? 'ERROR' : 'ANSWER' } },
  }
  return { demo: true, title: failed ? '弹幕查询 · 工具超时' : '弹幕查询 · 正常完成', spans, details }
}

// Synthetic concurrency fixture: both branches really overlap. This is not a
// claim about the production worker's concurrency or measured performance.
function parallelExample(compact = false) {
  const traceId = 'c3e75df847f14db79df00f92b1ac2403'
  const spans = [], details = {}
  const add = (n, parent, name, kind, offset, duration, extra = {}) => {
    spans.push({ traceId, spanId: id(n), parentSpanId: parent ? id(parent) : null, name,
      startTimeUnixNano: String(start + BigInt(offset) * 1000000n),
      endTimeUnixNano: String(start + BigInt(offset + duration) * 1000000n),
      status: 'UNSET', attributes: { 'node.type': kind, 'node.status': 'SUCCEEDED', 'run.id': 'demo-parallel-batches', ...extra } })
    details[id(n)] = { input: { example: true }, output: { example: true, completed: true } }
  }
  if (compact) {
    const forkTrace = 'd4e75df847f14db79df00f92b1ac2404'
    add(60, null, 'agent.run', 'REQUEST', 0, 2780)
    add(61, 60, 'agent.model', 'MODEL', 0, 320, { 'model.name': '演示模型' })
    add(62, 60, 'agent.tool', 'TOOL', 390, 940, { 'tool.name': 'read_danmaku' })
    add(63, 60, 'agent.tool', 'TOOL', 390, 1030, { 'tool.name': 'read_comments' })
    add(64, 60, 'agent.model', 'MODEL', 1490, 1290, { 'model.name': '演示模型', 'model.decision': 'FINAL_ANSWER' })
    spans.forEach(span => { span.traceId = forkTrace; span.attributes['run.id'] = 'demo-feedback-fork' })
    return { demo: true, title: '视频反馈 · 分叉与汇合', spans, details }
  }
  add(20, null, 'agent.run', 'REQUEST', 0, 7000)
  add(21, 20, '准备数据', 'STAGE', 20, 300)
  add(22, 20, '弹幕分析', 'STAGE', 400, 4800)
  add(23, 20, '评论分析', 'STAGE', 400, 4800)
  for (let i = 0; i < 4; i++) {
    add(30 + i, 22, 'feedback.work', 'MODEL', 450 + i * 1100, 1000, { 'model.name': '演示模型' })
    add(40 + i, 23, 'feedback.work', 'MODEL', 450 + i * 1100, 1000, { 'model.name': '演示模型' })
  }
  add(50, 20, '生成报告', 'MODEL', 5300, 1400, { 'model.name': '演示模型' })
  add(51, 20, 'agent.deliver', 'DELIVERY', 6800, 100, { 'span.type': 'answer_ready' })
  return { demo: true, title: '报告分析 · 并行与重复步骤', spans, details }
}

// Explicitly synthetic entry-pipeline examples, separate from real exported traces.
function preparationExample(clarify = false) {
  const traceId = clarify ? 'f6e75df847f14db79df00f92b1ac2406' : 'e5e75df847f14db79df00f92b1ac2405'
  const spans = [], details = {}
  const add = (n, parent, name, type, kind, offset, duration, attributes = {}, output = {}) => {
    spans.push({ traceId, spanId: id(n), parentSpanId: parent ? id(parent) : null, name,
      startTimeUnixNano: String(start + BigInt(offset) * 1000000n), endTimeUnixNano: String(start + BigInt(offset + duration) * 1000000n),
      status: 'UNSET', attributes: { 'node.type': kind, 'span.type': type, 'node.status': 'SUCCEEDED', ...attributes } })
    details[id(n)] = { input: { example: true, query: clarify ? '帮我分析一下' : '还是不明白，解释一下构图' }, output }
  }
  add(70, null, 'agent.run', 'receive_query', 'REQUEST', 0, 900, { 'run.status': clarify ? 'NEEDS_INPUT' : 'SUCCEEDED', 'node.status': clarify ? 'NEEDS_INPUT' : 'SUCCEEDED' })
  add(71, 70, 'agent.preparation', 'pre_route', 'ROUTING', 0, 350)
  add(72, 71, 'agent.prepare.faq', 'faq_lookup', 'ROUTING', 0, 20, { 'faq.hit': false })
  add(73, 71, 'agent.prepare.intent', 'intent_recognition', 'MODEL', 30, 220, { 'preparation.method': 'MODEL', 'preparation.intent': clarify ? 'OTHER' : 'KNOWLEDGE' }, { intent: clarify ? 'OTHER' : 'KNOWLEDGE' })
  add(74, 71, 'agent.prepare.emotion', 'emotion_recognition', 'MODEL', 30, 280, { 'preparation.method': 'MODEL', 'preparation.emotion': clarify ? 'NEUTRAL' : 'NEGATIVE' }, { emotion: clarify ? 'NEUTRAL' : 'NEGATIVE' })
  add(75, 71, 'agent.prepare.clarification', 'clarification_check', 'MODEL', 30, 260, { 'preparation.method': 'MODEL', 'clarification.required': clarify }, { needsClarification: clarify })
  add(76, 71, 'agent.prepare.merge', 'preparation_merge', 'ROUTING', 320, 10, { 'request.route': clarify ? 'CLARIFY' : 'HARNESS' }, { route: clarify ? 'CLARIFY' : 'HARNESS' })
  if (clarify) add(77, 70, 'agent.tool', 'tool_call', 'TOOL', 360, 10, { 'tool.name': 'ask_clarification', 'tool.success': true, 'clarification.required': true }, { terminalClarification: '你希望分析哪个视频，还是想讨论一个具体问题？' })
  else add(77, 70, 'agent.model', 'llm_inference', 'MODEL', 360, 450, { 'model.decision': 'FINAL_ANSWER' }, { answer: '可以从主体、留白和视线方向理解构图。' })
  return { demo: true, title: clarify ? '前置处理 · 澄清工具' : '前置处理 · 三路并行', spans, details }
}
export const demoFiles = [example(), example(true), parallelExample(), parallelExample(true), preparationExample(), preparationExample(true)]
