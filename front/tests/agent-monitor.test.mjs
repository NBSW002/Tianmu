import test from 'node:test';
import assert from 'node:assert/strict';
import { createRunMonitor } from '../src/agent/run-monitor.mjs';

const tick = () => new Promise(resolve => setImmediate(resolve));
test('background analysis keeps its model counter separate and exposes authorization pauses', async () => {
  const turn = { runId: 'run', events: [] };
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (t, value) => { t.snapshot = value; },
    getAgentRun: async () => ({ status: 'SUCCEEDED', totalModelCalls: 3 }),
    readAgentEvents: async (id, cursor, signal, receive) => {
      receive({ type: 'PROGRESS', id: '1', payload: { sequence: 1, data: { stage: 'analysis_waiting_auth' } } });
      receive({ type: 'PROGRESS', id: '2', payload: { sequence: 2, data: { stage: 'feedback_analysis', model_calls: 5 } } });
    } });
  try { await monitor.start(turn); assert.equal(turn.events[0].waitingAuth, true); assert.equal(turn.events[1].analysisCalls, 5); assert.equal(turn.snapshot.totalModelCalls, 3); }
  finally { monitor.stop(); }
});
test('closing monitoring aborts the active subscription without cancelling the server task', async () => {
  let signal;
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: () => assert.fail('late snapshot'),
    getAgentRun: () => assert.fail('no polling after close'),
    readAgentEvents: (id, cursor, current) => new Promise(resolve => {
      signal = current; current.addEventListener('abort', resolve, { once: true });
    }) });
  const done = monitor.start({ runId: 'run', events: [] });
  monitor.stop(); await done;
  assert.equal(signal.aborted, true);
});
test('a delayed snapshot from the previous conversation cannot overwrite the new conversation', async () => {
  let resolveOld; const applied = [];
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (turn, snapshot) => { turn.snapshot = snapshot; applied.push(turn.runId); },
    readAgentEvents: async () => {},
    getAgentRun: id => id === 'old' ? new Promise(resolve => { resolveOld = resolve; }) : Promise.resolve({ status: 'SUCCEEDED' }) });
  try {
    const old = monitor.start({ runId: 'old', events: [] }); await tick();
    const current = monitor.start({ runId: 'current', events: [] }); await current;
    resolveOld({ status: 'SUCCEEDED' }); await old;
    assert.deepEqual(applied, ['current']);
  } finally { monitor.stop(); }
});
test('replayed events are deduplicated and terminal refresh ends the subscription', async () => {
  const turn = { runId: 'run', events: [] }; let refreshed = 0;
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (t, snapshot) => { t.snapshot = snapshot; refreshed++; },
    getAgentRun: async () => ({ status: 'SUCCEEDED' }),
    readAgentEvents: async (id, cursor, signal, onEvent) => {
      const event = { type: 'PROGRESS', id: '1', payload: { sequence: 1, data: { stage: 'preparing' } } };
      onEvent(event); onEvent(event);
    } });
  try { await monitor.start(turn); assert.equal(turn.events.length, 1); assert.equal(refreshed, 1); }
  finally { monitor.stop(); }
});
test('authorization failure stops monitoring and preserves the actionable error', async () => {
  const notice = { value: '' }; let calls = 0;
  const monitor = createRunMonitor({ notice, applySnapshot: () => assert.fail('unauthorized'),
    getAgentRun: () => assert.fail('unauthorized'),
    readAgentEvents: async () => { calls++; throw Object.assign(new Error('Access revoked'), { status: 403 }); } });
  try { await monitor.start({ runId: 'run', events: [] }); assert.equal(calls, 1); assert.equal(notice.value, 'Access revoked'); assert.equal(monitor.signal.aborted, true); }
  finally { monitor.stop(); }
});

test('text deltas retain completed remarks across rounds and monitor restarts without replay duplicates', async () => {
  const turn = { runId: 'run', events: [] };
  const monitor = createRunMonitor({ notice: { value: '' }, applySnapshot: (t, s) => { t.snapshot = s; },
    getAgentRun: async () => ({ status: 'SUCCEEDED', answer: { text: 'verified' } }),
    readAgentEvents: async (id, cursor, signal, receive) => {
      const send = (sequence, type, data = {}) => receive({ type, id: String(sequence), payload: { sequence, data } });
      if(cursor>=7){send(2,'TEXT_START');send(3,'TEXT_DELTA',{text:'我先查询数据。'});send(4,'TEXT_END');send(6,'TEXT_DELTA',{text:'你'});send(7,'TEXT_DELTA',{text:'好'});return;}
      send(1, 'MODEL_SELECTED', { label: 'Pinned model' });
      send(2, 'TEXT_START');send(3, 'TEXT_DELTA', { text: '我先查询数据。' });send(4, 'TEXT_END');assert.equal(turn.streamingText, '');
      assert.deepEqual(turn.streamingNotes, [{sequence:4,text:'我先查询数据。'}]);
      send(5, 'TEXT_START');send(6, 'TEXT_DELTA', { text: '你' });send(6, 'TEXT_DELTA', { text: '你' });send(7, 'TEXT_DELTA', { text: '好' });
      assert.equal(turn.streamingText, '你好');
    } });
  try { await monitor.start(turn); await monitor.start(turn); assert.equal(turn.streamingNotes.length,1); assert.equal(turn.streamingText,'你好'); assert.equal(turn.modelLabel, 'Pinned model'); assert.equal(turn.snapshot.answer.text, 'verified'); }
  finally { monitor.stop(); }
});

test('progress coalesces repeated phases and omits low-level tools while updating the analysis counter', async () => {
  const turn={runId:'run',events:[]};
  const monitor=createRunMonitor({notice:{value:''},applySnapshot:(t,s)=>{t.snapshot=s},getAgentRun:async()=>({status:'SUCCEEDED'}),
    readAgentEvents:async(id,cursor,signal,receive)=>{
      [{stage:'tool',tool:'read_skill'},{stage:'tool_completed',tool:'read_skill'},
        {stage:'feedback_analysis',phase:'ANALYZING',model_calls:1},{stage:'feedback_analysis',phase:'ANALYZING',model_calls:5},
        {stage:'finalizing'}].forEach((data,i)=>receive({type:'PROGRESS',payload:{sequence:i+1,data}}));
    }});
  try {await monitor.start(turn);assert.equal(turn.events.length,2);assert.equal(turn.events[0].analysisCalls,5);assert.match(turn.events[0].label,/我在/);}
  finally{monitor.stop()}
});

test('expired transport cursor can replay retained text without appending it twice', async () => {
  const turn={runId:'run',events:[]};let attempts=0;
  const monitor=createRunMonitor({notice:{value:''},applySnapshot:(t,s)=>{t.snapshot=s},getAgentRun:async()=>({status:'SUCCEEDED'}),
    readAgentEvents:async(id,cursor,signal,receive)=>{
      const send=(sequence,type,data={})=>receive({type,payload:{sequence,data}});
      if(attempts++===0){send(1,'TEXT_START');send(2,'TEXT_DELTA',{text:'我先看一下。'});send(3,'TEXT_END');throw Object.assign(new Error('expired'),{code:'EVENT_CURSOR_EXPIRED'});}
      assert.equal(cursor,0);send(1,'TEXT_START');send(2,'TEXT_DELTA',{text:'我先看一下。'});send(3,'TEXT_END');send(4,'TEXT_START');send(5,'TEXT_DELTA',{text:'后续正文'});
    }});
  try{await monitor.start(turn);assert.equal(turn.streamingNotes.length,1);assert.equal(turn.streamingNotes[0].text,'我先看一下。');assert.equal(turn.streamingText,'后续正文');}
  finally{monitor.stop()}
});
