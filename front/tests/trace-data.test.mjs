import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTraceFile, layoutTrace } from '../src/trace/trace-data.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'

test('provider leaves supply usage once when wrappers and resumed segment summaries coexist', () => {
  const span=(id,parent,name,attrs)=>({traceId:'t',spanId:id,parentSpanId:parent,name,attributes:attrs})
  const [trace]=parseTraceFile(JSON.stringify([
    span('root',null,'agent.run',{'node.type':'REQUEST'}),
    span('wrapper','root','agent.model',{'node.type':'MODEL','model.input_tokens':100,'model.output_tokens':10}),
    span('call','wrapper','model.request',{'node.type':'MODEL','model.input_tokens':100,'model.output_tokens':10}),
    span('second','root','model.request',{'node.type':'MODEL','model.input_tokens':200,'model.output_tokens':20}),
    span('finish','root','finish',{'span.type':'finish','usage.scope':'RUN_PROCESS_SEGMENT','usage.input_tokens':200,'usage.output_tokens':20,'usage.complete':true}),
  ]))
  assert.equal(trace.totalTokens,330);assert.equal(trace.usageComplete,true);assert.equal(trace.title,'本次运行')
})

test('missing actual provider usage remains partial despite a complete later segment', () => {
  const spans=[{traceId:'t',spanId:'missing',name:'model.request',attributes:{'node.type':'MODEL'}},
    {traceId:'t',spanId:'later',name:'model.request',attributes:{'node.type':'MODEL','model.input_tokens':5,'model.output_tokens':2}},
    {traceId:'t',spanId:'finish',name:'finish',attributes:{'usage.scope':'RUN_PROCESS_SEGMENT','usage.complete':true,'usage.input_tokens':5,'usage.output_tokens':2}}]
  const [trace]=parseTraceFile(JSON.stringify(spans));assert.equal(trace.usageComplete,false);assert.equal(trace.totalTokens,7)
})

test('run token summaries are not double-counted with child model usage', () => {
  const [trace] = parseTraceFile(JSON.stringify(demoFiles[0]))
  assert.equal(trace.totalTokens, 604)
  assert.equal(trace.durationMs, 1420)
  const model = trace.nodes.find((node) => node.type === 'llm_inference')
  assert.equal(model.durationMs, 420)
  assert.equal(model.input.messages[0].role, 'user')
  assert.equal(model.output.decision, 'CALL_TOOL')
})

test('JSONL replay is deduplicated and separate traces remain separate', () => {
  const first = demoFiles[0].spans
  const records = [...first, first[0], ...demoFiles[1].spans]
  const traces = parseTraceFile(records.map((span) => JSON.stringify(span)).join('\n'))
  assert.equal(traces.length, 2)
  assert.equal(traces[0].nodes.length, first.length)
  assert.equal(traces[1].nodes.find((node) => node.kind === 'TOOL').status, 'TIMED_OUT')
})

test('raw exports preserve missing payloads, unknown status and unknown token counts', () => {
  const [trace] = parseTraceFile(JSON.stringify([{
    traceId: 'trace', spanId: 'node', name: 'agent.model', parentSpanId: 'absent', status: 'UNSET',
    startTimeUnixNano: '1793240671000000000', endTimeUnixNano: '1793240671001000001',
    attributes: { 'node.type': 'MODEL', 'detail.input_ref': 'private-reference' },
  }]))
  assert.equal(trace.totalTokens, null)
  assert.equal(trace.nodes[0].status, 'UNKNOWN')
  assert.equal(trace.nodes[0].input, null)
  assert.equal(trace.nodes[0].inputRef, 'private-reference')
  assert.equal(trace.nodes[0].durationMs, 1.000001)
  assert.deepEqual(layoutTrace(trace.nodes)[0].position, { x: 0, y: 0 })
})

test('reject malformed exports and cyclic relationships before drawing', () => {
  for (const value of ['{invalid', 'null', '[]', '{"spans":[{}]}']) {
    assert.throws(() => parseTraceFile(value))
  }
  const spans = [{ traceId: 't', spanId: 'a', parentSpanId: 'b', name: 'a' }, { traceId: 't', spanId: 'b', parentSpanId: 'a', name: 'b' }]
  assert.throws(() => parseTraceFile(JSON.stringify(spans)), /循环/)
})

test('resumed segments aggregate actual model spans without treating model-labelled markers as calls', () => {
  const span = (id, name, attributes) => ({traceId:'resumed', spanId:id, name, attributes})
  const [trace] = parseTraceFile(JSON.stringify([
    span('start','agent_start',{'node.type':'STAGE','model.name':'flash'}),
    span('foreground','agent.model',{'node.type':'MODEL','model.name':'flash','model.input_tokens':100,'model.output_tokens':10}),
    span('background','feedback.model',{'node.type':'MODEL','model.name':'flash','model.input_tokens':200,'model.output_tokens':20}),
    span('finish','finish',{'span.type':'finish','usage.scope':'RUN_PROCESS_SEGMENT','usage.input_tokens':200,'usage.output_tokens':20,'usage.complete':true}),
  ]))
  assert.equal(trace.totalTokens,330)
  assert.equal(trace.usageComplete,true)
})
