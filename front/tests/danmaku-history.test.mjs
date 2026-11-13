import test from 'node:test'
import assert from 'node:assert/strict'
import {createDanmakuHistoryLoader} from '../src/lib/danmaku-history.mjs'
test('history pages advance exact long IDs and deduplicate live echoes',async()=>{
 const output=[],calls=[];const id='9007199254740993';
 const loader=createDanmakuHistoryLoader(async(bucket,time,after)=>{calls.push([bucket,time,after]);return after==='0'?{items:[{bulletId:id,playbackTime:0}],hasMore:true,nextTime:0,nextId:id}:{items:[{bulletId:id,playbackTime:0},{bulletId:'9007199254740994',playbackTime:1}],hasMore:false}},items=>output.push(...items));
 loader.accept([{bulletId:id}]);await loader.ensure(0);assert.deepEqual(output.map(x=>x.bulletId),['9007199254740994']);assert.equal(calls[1][2],id);await loader.ensure(3);assert.equal(calls.length,2);loader.stop();
})
test('seek and disposal prevent delayed old windows reaching the player',async()=>{
 let release;const output=[];const loader=createDanmakuHistoryLoader(bucket=>bucket===0?new Promise(r=>release=r):Promise.resolve({items:[{bulletId:'2',playbackTime:60}],hasMore:false}),items=>output.push(...items));
 const old=loader.ensure(0);await loader.ensure(60);release({items:[{bulletId:'1',playbackTime:0}],hasMore:false});await old;assert.deepEqual(output.map(x=>x.bulletId),['2']);loader.stop();await loader.ensure(120);assert.equal(output.length,1);
})
