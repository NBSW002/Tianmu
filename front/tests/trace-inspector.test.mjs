import test from 'node:test'
import assert from 'node:assert/strict'
import { modelResponseDetails } from '../src/trace/trace-inspector.mjs'

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
