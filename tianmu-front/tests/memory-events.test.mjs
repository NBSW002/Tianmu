import test from 'node:test'
import assert from 'node:assert/strict'
import { notifyMemoryChanged, onMemoryChanged } from '../src/lib/memory-events.mjs'

test('local mutation immediately invalidates all subscribed views without carrying private data',()=>{
  const first=[],second=[];const stopFirst=onMemoryChanged(source=>first.push(source));const stopSecond=onMemoryChanged(source=>second.push(source))
  notifyMemoryChanged();assert.deepEqual(first,['local']);assert.deepEqual(second,['local'])
  stopFirst();notifyMemoryChanged();assert.equal(first.length,1);assert.equal(second.length,2);stopSecond()
})
test('agent tool completion is distinct from the managers own in-flight mutation',()=>{
  const sources=[];const stop=onMemoryChanged(source=>sources.push(source))
  try{notifyMemoryChanged('agent');notifyMemoryChanged();assert.deepEqual(sources,['agent','local'])}finally{stop()}
})
test('cross-tab invalidation accepts only the fixed public signal and closes its channel',()=>{
  const calls=[],sent=[],events=new Map();let receiver,closed=false
  globalThis.window={addEventListener:(name,fn)=>events.set(name,fn),removeEventListener:name=>events.delete(name),BroadcastChannel:class{constructor(){receiver=this}postMessage(value){sent.push(value)}close(){closed=true}}}
  const stop=onMemoryChanged(source=>calls.push(source))
  try{receiver.onmessage({data:'not-a-memory-change'});assert.equal(calls.length,0);receiver.onmessage({data:'changed'});assert.deepEqual(calls,['remote']);notifyMemoryChanged();assert.deepEqual(sent,['changed']);assert.deepEqual(calls,['remote','local'])}finally{stop();delete globalThis.window}
  assert.equal(closed,true);assert.equal(events.size,0)
})
test('blocked BroadcastChannel still permits local and storage fallback invalidation',()=>{
  const events=new Map(),writes=[],calls=[]
  globalThis.window={addEventListener:(name,fn)=>events.set(name,fn),removeEventListener:name=>events.delete(name),BroadcastChannel:class{constructor(){throw new Error('blocked')}},localStorage:{setItem:(...args)=>writes.push(args)}}
  const stop=onMemoryChanged(source=>calls.push(source))
  try{events.get('storage')({key:'echoframe.memory.changed.v1',newValue:'1'});notifyMemoryChanged();assert.deepEqual(calls,['remote','local']);assert.equal(writes.length,1)}finally{stop();delete globalThis.window}
})
