import test from 'node:test'
import assert from 'node:assert/strict'
import { modelResponseDetails, runInspectorSummary, modelInspectorDetails, runTtftDetails, executionSummary } from '../src/trace/trace-inspector.mjs'

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
  assert.equal(result.responseTiming.total,500)
 assert.equal(result.responseTiming.first,null)
 assert.equal(result.responseTiming.segments.reduce((sum,item)=>sum+item.value,0),500)
})

test('model details preserve reported cache counts and omit calculated rates',()=>{
 const a={...model('a',null,{'model.cache_hit_tokens':80,'model.cache_miss_tokens':20,'model.response_ms':600,'model.first_token_ms':100,'model.streaming':true,'model.context_window':1000}),inputTokens:100,outputTokens:20,model:'flash'}
 const b={...model('b',null,{'model.cache_hit_tokens':0,'model.cache_miss_tokens':200,'model.response_ms':1100,'model.first_token_ms':100,'model.streaming':true,'model.context_window':2000}),inputTokens:200,outputTokens:40,model:'flash'}
 const result=modelInspectorDetails([a,b])
 assert.equal(result.cacheHits,80)
 assert.equal(result.cacheMisses,220)
 assert.equal('tps' in result,false)
 assert.equal('cacheRate' in result,false)
 assert.deepEqual(result.window,{used:100,capacity:1000})
 assert.deepEqual(result.models,['flash'])
})
test('missing capacity, cache and generation data remain unknown',()=>{
 const result=modelInspectorDetails([{...model('a'),inputTokens:500,outputTokens:20,durationMs:1000}])
 assert.deepEqual(result.window,{used:500,capacity:null})
 assert.equal(result.cacheHits,null)
 assert.equal(result.cacheMisses,null)
})
test('peak window context comes from its assembly ancestor instead of summing calls',()=>{
 const parent=model('parent',null,{'context.present_slots':['focus'],'context.slot_bytes':['focus:88']})
 const a={...model('a','parent',{'model.context_window':1000}),inputTokens:500}
 const b={...model('b',null,{'model.context_window':1000}),inputTokens:100}
 const result=modelInspectorDetails([a,b],[parent,a,b])
 assert.deepEqual(result.window,{used:500,capacity:1000})
 assert.equal(result.peakContext.slots[0].bytes,88)
})

test('historical provider spans supply measured duration and inherit byte budget',()=>{
 const parent=model('parent',null,{'context.byte_limit':98304,'context.present_slots':['focus'],'context.slot_bytes':['focus:88']})
 const request={...model('request','parent',{'context.request_bytes':21008,'model.streaming':true,'model.cache_hit_tokens':3072}),name:'model.request',durationMs:1175.752501,inputTokens:4924,outputTokens:116}
 const result=runInspectorSummary({nodes:[parent,request]},{allCards:new Map()})
 assert.deepEqual(result.modelDetails.window,{used:21008,capacity:98304,unit:'B'})
 assert.equal(result.modelDetails.cacheHits,3072)
 assert.equal('tps' in result.modelDetails,false)
 assert.equal(result.responseTiming.recorded,1)
 assert.equal(result.responseTiming.total,1175.752501)
 assert.equal(result.responseTiming.segments[0].label,'流式调用')
})

test('TTFT overview excludes nonstream and unsampled legacy streams without substituting duration',()=>{
 const a=model('a',null,{'model.streaming':true,'model.response_ms':400,'model.first_token_ms':120})
 const b={...model('b',null,{'model.streaming':true}),durationMs:1000}
 const c=model('c',null,{'model.streaming':false,'model.response_ms':500})
 assert.deepEqual(runTtftDetails([a,b,c]),{total:400,first:120,remaining:280,recorded:1,calls:2})
 assert.deepEqual(runTtftDetails([b,c]),{total:null,first:null,remaining:null,recorded:0,calls:1})
})

test('provider discovery supplies the real Token window and never crosses providers',()=>{
 const call={...model('m',null,{'model.provider':'deepseek','context.byte_limit':98304,'context.request_bytes':21000}),model:'deepseek-flash',inputTokens:4928}
 const discovery=[{provider:'deepseek',model:'deepseek-flash',contextWindow:1048576}]
 assert.deepEqual(modelInspectorDetails([call],[call],discovery).window,{used:4928,capacity:1048576})
 const compatible={...call,attributes:{...call.attributes,'model.provider':'compatible'}}
 assert.deepEqual(modelInspectorDetails([compatible],[compatible],discovery).window,{used:21000,capacity:98304,unit:'B'})
 const captured={...call,attributes:{...call.attributes,'model.context_window':128000}}
 assert.deepEqual(modelInspectorDetails([captured],[captured],discovery).window,{used:4928,capacity:128000})
})
test('missing cache reporting is partial and does not turn into zero',()=>{
 const a=model('a',null,{'model.cache_hit_tokens':0,'model.cache_miss_tokens':20})
 const b=model('b')
 const details=modelInspectorDetails([a,b])
 assert.equal(details.cacheHits,0);assert.equal(details.cacheMisses,20)
 assert.equal(details.cacheHitPartial,true);assert.equal(details.cacheMissPartial,true)
})

test('Skill activation nested under read_skill counts once and tools count actual executions',()=>{
 const tool={id:'read',kind:'TOOL',type:'tool_call',tool:'read_skill',attributes:{'tool.call_id':'call1'}}
 const child={...tool,id:'read-child',parentId:'read'}
 const skill={id:'skill',parentId:'read',kind:'SKILL',name:'agent.skill',attributes:{'skill.name':'audience-report'}}
 const other={...tool,id:'report',tool:'generate_feedback_report',attributes:{'tool.call_id':'call2'}}
 assert.deepEqual(executionSummary([tool,child,skill,other]),{toolCalls:2,skillCalls:1,toolNames:[{name:'read_skill',count:1},{name:'generate_feedback_report',count:1}],skillNames:[{name:'audience-report',count:1}],compression:'—'})
 const summary=runInspectorSummary({nodes:[tool,child,skill,other]})
 assert.equal(summary.modelDetails.skillCalls,1);assert.equal(summary.modelDetails.toolCalls,2)
})
test('compression distinguishes a new trigger, inherited summaries, and tool result reduction',()=>{
 const node=attrs=>({...model('m'),attributes:attrs})
 assert.equal(executionSummary([node({'context.results_compacted':0,'context.compaction_segments':0})]).compression,'未触发')
 assert.equal(executionSummary([node({'context.compaction_segments':2})]).compression,'沿用历史摘要')
 assert.equal(executionSummary([node({'context.results_compacted':1})]).compression,'结果已精简')
 assert.equal(executionSummary([{id:'c',name:'agent.context.compact',status:'FAILED',attributes:{}}]).compression,'已触发')
})
test('model full name comes only from matching provider metadata and keeps its model ID',()=>{
 const call={...model('m',null,{'model.provider':'deepseek'}),model:'deepseek-flash',inputTokens:10}
 const metadata=[{provider:'deepseek',model:'deepseek-flash',name:'DeepSeek-V4.1-Flash',contextWindow:1048576}]
 const details=modelInspectorDetails([call],[call],metadata)
 assert.deepEqual(details.models,['DeepSeek-V4.1-Flash']);assert.deepEqual(details.modelIds,['deepseek-flash'])
 const other={...call,attributes:{'model.provider':'compatible'}}
 assert.deepEqual(modelInspectorDetails([other],[other],metadata).models,['deepseek-flash'])
})

test('executed names aggregate repeated calls and exclude planned tools',()=>{
 const first={id:'t1',kind:'TOOL',tool:'read_skill',attributes:{},input:{arguments:{name:'audience-report'}}}
 const nested={id:'s1',parentId:'t1',kind:'SKILL',name:'agent.skill',attributes:{'skill.name':'audience-report'}}
 const second={id:'t2',kind:'TOOL',tool:'read_skill',attributes:{},input:JSON.stringify({name:'audience-report'})}
 const planned=model('m',null,{'model.selected_tools':['never_executed']})
 const summary=executionSummary([planned,first,nested,second])
 assert.deepEqual(summary.toolNames,[{name:'read_skill',count:2}])
 assert.deepEqual(summary.skillNames,[{name:'audience-report',count:2}])
 assert.equal(summary.skillCalls,2)
 assert.deepEqual(executionSummary([planned]).toolNames,[])
})
