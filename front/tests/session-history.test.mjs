import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeMessages, historicalTurns } from '../src/agent/session-history.mjs'
const message=(sequence,runId,role,text)=>({id:String(sequence),sequence,runId,role,text,at:'2026-11-06T00:00:00Z'})
test('overlapping pages merge without duplicates and keep redacted server text',()=>{
 const page=[message(3,'b','user','second'),message(4,'b','assistant','[先前视频结果当前无权限，已隐藏]')]
 const merged=mergeMessages(page,[message(1,'a','user','first'),message(2,'a','assistant','answer'),page[0]])
 assert.equal(merged.length,4)
 assert.deepEqual(merged.map(m=>m.sequence),[1,2,3,4])
 const turns=historicalTurns(merged,[])
 assert.equal(turns[1].snapshot.answer.text,'[先前视频结果当前无权限，已隐藏]')
 assert.equal(turns[1].reportPath,undefined)
})
test('current live run is rendered once and older selected runs do not show later turns',()=>{
 const messages=[message(1,'a','user','first'),message(2,'a','assistant','answer'),message(3,'b','user','second')]
 assert.equal(historicalTurns(messages,[{runId:'b'}]).length,1)
 assert.equal(historicalTurns(messages,[{runId:'a'}]).length,0)
 assert.throws(()=>mergeMessages([],[{sequence:1}]))
})
