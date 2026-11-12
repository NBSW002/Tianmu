import test from 'node:test'
import assert from 'node:assert/strict'
import { modelResponseDetails, runInspectorSummary } from '../src/trace/trace-inspector.mjs'

const model = (id,parentId,attributes={}) => ({id,parentId,kind:'MODEL',attributes})
test('response phases come from the leaf provider, not the outer model wrapper',()=>{
  const wrapper=model('wrapper',null,{'model.response_ms':9999}),provider=model('provider','wrapper',{'model.response_ms':1150,'model.first_token_ms':320})
  const unrelated=model('other',null,{'model.response_ms':1,'model.first_token_ms':0})
  const raw=new Map([wrapper,provider,unrelated].map(n=>[n.id,n]))
  assert.deepEqual(modelResponseDetails({...wrapper,memberIds:['wrapper','provider']},raw),{total:1150,first:320,remaining:830})
})
test('multiple model attempts never become one invented response phase chart',()=>{
  const wrapper=model('wrapper'),a=model('a','wrapper',{'model.response_ms':20,'model.first_token_ms':5}),b=model('b','wrapper',{'model.response_ms':40,'model.first_token_ms':10})
  assert.equal(modelResponseDetails({...wrapper,memberIds:['wrapper','a','b']},new Map([wrapper,a,b].map(n=>[n.id,n]))),null)
})
test('missing and invalid first-token timing stays missing while response total survives',()=>{
  for(const first of [undefined,null,-1,200,'10']){
    assert.deepEqual(modelResponseDetails(model('m',null,{'model.response_ms':100,'model.first_token_ms':first})),{total:100,first:null,remaining:null})
  }
  assert.deepEqual(modelResponseDetails(model('m',null,{'model.response_ms':100,'model.first_token_ms':0})),{total:100,first:0,remaining:100})
  assert.deepEqual(modelResponseDetails(model('m',null,{'model.response_ms':0,'model.first_token_ms':0})),{total:0,first:null,remaining:null})
})
test('duration alone and non-model nodes do not acquire response timing',()=>{
  assert.equal(modelResponseDetails({...model('m'),durationMs:100}),null)
  assert.equal(modelResponseDetails({id:'tool',kind:'TOOL',attributes:{'model.response_ms':100}}),null)
})
test('a recorded first token remains visible without inventing total response time',()=>{
  assert.deepEqual(modelResponseDetails(model('m',null,{'model.first_token_ms':120})),{total:null,first:120,remaining:null})
})
test('run overview aggregates separate context assemblies and skips duplicated descendant snapshots',()=>{
  const attrs=(size)=>({'context.present_slots':['system_prompt','focus'],'context.slot_bytes':[`system_prompt:${size}`,'focus:20']})
  const a=model('a',null,attrs(100)),duplicate=model('nested','a',attrs(100)),b=model('b',null,attrs(200))
  const result=runInspectorSummary({nodes:[a,duplicate,b]},{allCards:new Map()})
  assert.equal(result.context.note,'已记录 2 次')
  assert.deepEqual(result.context.slots.map(s=>[s.name,s.bytes]),[['system_prompt',300],['focus',40]])
  assert.equal(result.modelCalls,2)
})
test('run overview preserves unknown slot sizes and counts actual tool roots once',()=>{
  const a=model('a',null,{'context.present_slots':['system_prompt'],'context.slot_bytes':['system_prompt:100']})
  const b=model('b',null,{'context.present_slots':['system_prompt'],'context.slot_bytes':[]})
  const tool={id:'t',kind:'TOOL',type:'tool_call',tool:'read_skill',name:'agent.tool',status:'SUCCEEDED',attributes:{}}
  const nested={...tool,id:'nested',parentId:'t'}
  const cards=new Map([['t',{id:'t',cardKind:'tool',name:'agent.tool',tool:'read_skill',attributes:{}}]])
  const result=runInspectorSummary({nodes:[a,b,tool,nested]},{allCards:cards})
  assert.equal(result.context.slots[0].bytes,null)
  assert.equal(result.tools.length,1)
  assert.equal(result.tools[0].id,'t')
})
test('run overview excludes another run and its descendants',()=>{
  const attrs = id => ({'run.id':id,'context.present_slots':['focus'],'context.slot_bytes':['focus:20']})
  const own = model('own',null,attrs('current'))
  const other = model('other',null,attrs('historical'))
  const nested = {id:'historical-tool',parentId:'other',kind:'TOOL',type:'tool_call',tool:'read_skill',attributes:{}}
  const result = runInspectorSummary({runId:'current',nodes:[own,other,nested]},{allCards:new Map()})
  assert.equal(result.context.slots[0].bytes,20)
  assert.equal(result.modelCalls,1)
  assert.equal(result.tools.length,0)
})

test('run response timing sums recorded provider phases and identifies incomplete coverage',()=>{
  const a={...model('a',null,{'model.response_ms':400,'model.first_token_ms':120}),name:'model.request'}
  const b={...model('b',null,{}),name:'model.request'}
  const result=runInspectorSummary({nodes:[a,b]},{allCards:new Map()})
  assert.deepEqual(result.responseTiming,{total:400,first:120,remaining:280,recorded:1,calls:2})
})
test('run response timing does not invent missing phase or total measurements',()=>{
  const a={...model('a',null,{'model.response_ms':200,'model.first_token_ms':50}),name:'model.request'}
  const b={...model('b',null,{'model.response_ms':300}),name:'model.request'}
  const result=runInspectorSummary({nodes:[a,b]},{allCards:new Map()})
  assert.deepEqual(result.responseTiming,{total:500,first:null,remaining:null,recorded:2,calls:2})
})
