import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTracePresentation, buildTraceOverview, cardHighlight, cardSummary, preparationDetails, defaultExpandedStages, layoutPresentation, modelInvocations, modelReasoningSources, intentDetails, contextDetails, compactionDetails } from '../src/trace/trace-presentation.mjs'
import { demoFiles } from '../src/trace/demo-trace.mjs'
import { parseTraceFile } from '../src/trace/trace-data.mjs'

test('a waiting merge joins three recognizers even when its measured span overlaps them',()=>{
  const p=buildTracePresentation([
    node('faq','agent.prepare.faq','STAGE',0,1,{parentId:'entry'}),
    ...['intent','emotion','clarification'].map((kind,i)=>node(kind,`agent.prepare.${kind}`,'MODEL',2+i*.1,3+i*.2,{parentId:'entry',attributes:{'preparation.method':'MODEL'}})),
    node('merge','agent.prepare.merge','STAGE',2.2,4,{parentId:'entry'}),
    node('memory','agent.memory.recall','STAGE',7,1,{parentId:'entry'}),
  ]);
  const g=layoutPresentation(p),merge=g.nodes.find(n=>n.id==='merge');
  assert.deepEqual(g.edges.filter(e=>e.target==='merge').map(e=>e.source).sort(),['clarification','emotion','intent']);
  assert.equal(new Set(g.nodes.filter(n=>['intent','emotion','clarification'].includes(n.id)).map(n=>n.position.x)).size,1);
  for(const kind of ['intent','emotion','clarification'])assert.ok(merge.position.x>g.nodes.find(n=>n.id===kind).position.x);
  assert.ok(g.edges.some(e=>e.source==='merge'&&e.target==='memory'));
});

test('model cards expose only reasoning payloads from their actual descendant calls',()=>{
  const p=buildTracePresentation([
    node('m','agent.model','MODEL',0,5),
    node('http','model.request','MODEL',1,3,{parentId:'m',reasoningRef:'private-a'}),
    node('next','agent.model','MODEL',6,5),
    node('http2','model.request','MODEL',7,3,{parentId:'next',reasoningRef:'private-b'}),
  ]);
  assert.deepEqual(modelReasoningSources(p.allCards.get('m'),p).map(n=>n.id),['http']);
});

test('merge explains host defaults and rewrite disposition without fabricating legacy data',()=>{
  const node={cardKind:'merge',attributes:{'request.route':'HARNESS','preparation.rule_task':'unclassified','preparation.effective_task':'audience.report','preparation.merge_reason':'CAPABILITY_DEFAULTS','preparation.defaults':['video_id','report_template','sources','time_range'],'query.rewrite_state':'ACCEPTED'}}
  const details=preparationDetails(node)
  assert.equal(details.visible,true);assert.equal(details.ruleTask,'unclassified');assert.equal(details.effectiveTask,'audience.report')
  assert.deepEqual(details.defaults,['当前视频','报告模板','默认来源','默认时间范围'])
  assert.match(cardSummary(node),/当前视频.*默认时间范围/);assert.match(details.rewrite,/等价校验/)
  node.attributes['query.rewrite_state']='REJECTED';assert.match(preparationDetails(node).rewrite,/舍弃.*原话/)
  node.attributes['request.route']='CLARIFY';node.attributes['clarification.reason']='REQUIRED_CONDITIONS';assert.match(cardSummary(node),/必要条件/)
  assert.equal(preparationDetails({}).visible,false);assert.equal(preparationDetails(null).rewrite,'未记录')
})

test('compaction reports actual range and byte savings without inventing absent values',()=>{
  const value=compactionDetails({name:'agent.context.compact',attributes:{'compaction.covered_from':1,'compaction.covered_through':8,'compaction.source_messages':8,'compaction.input_bytes':7000,'compaction.output_bytes':1400,'compaction.saved_bytes':5600,'compaction.trigger_reason':'HISTORY_BUDGET'}})
  assert.equal(value.visible,true);assert.equal(value.through,8);assert.equal(value.savedBytes,5600)
  assert.equal(compactionDetails({}).visible,false);assert.equal(compactionDetails({name:'agent.context.compact'}).inputBytes,null)
})

test('context slots retain canonical order and absent capabilities are not fabricated',()=>{
  const result=contextDetails({attributes:{'context.schema_version':'1.0','context.projection_version':'chat_native_v1','context.present_slots':['current_context','memory_context','system_prompt','unknown'],'context.slot_bytes':['current_context=120','memory_context=80','unknown=999','<script>']}})
  assert.deepEqual(result.slots.map(s=>s.name),['system_prompt','memory_context','current_context'])
  assert.equal(result.slots[1].bytes,80)
  assert.equal(result.slots.some(s=>s.name==='agent_catalog'),false)
  assert.equal(contextDetails({}).visible,false)
})

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
  assert.equal(get('intent').position.y, get('emotion').position.y - 76)
  assert.equal(get('clarification').position.y, get('emotion').position.y + 76)
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

test('a merge covering fan-in waiting follows all recognition branches without changing measured times', () => {
  const raw = [
    node('faq', 'agent.prepare.faq', 'STAGE', 0, 5),
    node('intent', 'agent.prepare.intent', 'MODEL', 10, 70, { parentId: 'prepare', attributes: { 'preparation.method': 'MODEL' } }),
    node('emotion', 'agent.prepare.emotion', 'MODEL', 12, 55, { parentId: 'prepare', attributes: { 'preparation.method': 'MODEL' } }),
    node('clarification', 'agent.prepare.clarification', 'MODEL', 14, 75, { parentId: 'prepare', attributes: { 'preparation.method': 'MODEL' } }),
    node('merge', 'agent.prepare.merge', 'STAGE', 11, 80, { parentId: 'prepare' }),
    node('model', 'agent.model', 'MODEL', 100, 20),
  ]
  const graph = layoutPresentation(buildTracePresentation(raw))
  const merge = graph.nodes.find(n => n.id === 'merge')
  for (const id of ['intent', 'emotion', 'clarification']) {
    const branch = graph.nodes.find(n => n.id === id)
    assert.ok(merge.position.x > branch.position.x)
    assert.ok(graph.edges.some(e => e.source === id && e.target === merge.id))
  }
  assert.ok(graph.edges.some(e => e.source === merge.id && e.target === 'model'))
  assert.equal(merge.data.offsetMs, 11)
  assert.equal(merge.data.durationMs, 80)
  const late = raw.map(n => n.id === 'clarification' ? { ...n, durationMs: 160 } : n)
  const partial = layoutPresentation(buildTracePresentation(late))
  assert.ok(partial.edges.some(e => e.source === 'intent' && e.target === 'merge'))
  assert.ok(!partial.edges.some(e => e.source === 'clarification' && e.target === 'merge'), 'A late branch must not be shown as completed before fan-in returns')
  assert.equal(partial.nodes.find(n => n.id === 'clarification').data.durationMs, 160)
})
test('intent cards show capability selection before payloads load and expand the actual candidate snapshot', () => {
  const raw=node('intent','agent.prepare.intent','MODEL',0,10,{attributes:{'preparation.intent':'DANMAKU_REPORT','preparation.intent_primary':'audience-report','preparation.intent_related':['video-content','comment-analysis'],'preparation.intent_candidates':['audience-report','video-content','comment-analysis']}})
  const card=buildTracePresentation([raw]).cards[0]
  assert.equal(card.summary,'观众反馈报告')
  assert.equal(intentDetails(card).candidates.length,3)
  assert.deepEqual(intentDetails(card).related.map(item=>item.label),['视频内容理解','评论分析'])
  raw.input={intentCandidates:[{id:'audience-report',label:'自定义报告名称',description:'来自本轮能力快照',skill:'audience-report',requiredConditions:['视频对象']}]}
  const loaded=buildTracePresentation([raw]).cards[0]
  assert.equal(intentDetails(loaded).primary.label,'自定义报告名称')
  assert.equal(intentDetails(loaded).candidates[0].description,'来自本轮能力快照')
  assert.equal(intentDetails(node('old','agent.prepare.intent','MODEL',0,1,{attributes:{'preparation.intent':'OTHER'}})).visible,false)
})

test('model details list every call in its round, resolve Skill and retain provider members without duplicate cards', () => {
  const p = buildTracePresentation([
    node('model', 'agent.model', 'MODEL', 0, 4, { parentId: 'loop', turn: 1, attributes: { 'model.selected_tool': 'read_skill' }, output: { kind: 'TOOL', call: { name: 'read_skill', arguments: { name: 'feedback' } }, additionalCalls: [{ name: 'read_comments', arguments: { video_id: '1001' } }] } }),
    node('provider', 'model.request', 'MODEL', 1, 2, { parentId: 'model', totalTokens: 12 }),
    node('skill', 'agent.tool', 'TOOL', 5, 2, { parentId: 'loop', turn: 1, tool: 'read_skill', input: { name: 'read_skill', arguments: { name: 'feedback' } } }),
    node('skill-load', 'agent.skill', 'STAGE', 6, 0.5, { parentId: 'skill', attributes: { 'skill.name': 'feedback', 'skill.version': '1' } }),
    node('comments', 'agent.tool', 'TOOL', 8, 1, { parentId: 'loop', turn: 1, tool: 'read_comments', attributes: { 'tool.success': false }, output: { error_code: 'UNAVAILABLE' } }),
    node('other-loop', 'agent.tool', 'TOOL', 6, 1, { parentId: 'other', turn: 1, tool: 'read_comments' }),
    node('later-turn', 'agent.tool', 'TOOL', 10, 1, { parentId: 'loop', turn: 2, tool: 'read_comments' }),
  ])
  const model = p.allCards.get('model'), calls = modelInvocations(model, p)
  assert.deepEqual(calls.map(c => c.toolId), ['skill', 'comments'])
  assert.equal(calls[0].skill, 'feedback')
  assert.deepEqual(calls[0].arguments, { name: 'feedback' })
  assert.equal(calls[1].businessError, true)
  assert.equal(model.cardTitle, '模型思考 · 第 1 轮')
  assert.match(model.summary, /Skill：feedback、读取评论/)
  assert.ok(model.memberIds.includes('provider'))
  assert.ok(!p.cards.some(card => card.id === 'provider'))
})

test('planned tools remain unexecuted and repeated model attempts do not borrow later executions', () => {
  const p = buildTracePresentation([
    node('first', 'agent.model', 'MODEL', 0, 2, { parentId: 'loop', turn: 1, attributes: { 'model.selected_tools': ['read_comments', 'read_danmaku'] } }),
    node('retry', 'agent.model', 'MODEL', 3, 2, { parentId: 'loop', turn: 1, attributes: { 'model.selected_tool': 'read_comments' } }),
    node('tool', 'agent.tool', 'TOOL', 6, 1, { parentId: 'loop', turn: 1, tool: 'read_comments', status: 'TIMED_OUT' }),
  ])
  const first = modelInvocations(p.allCards.get('first'), p)
  assert.equal(first.length, 2)
  assert.ok(first.every(call => !call.toolId && !call.status))
  const retry = modelInvocations(p.allCards.get('retry'), p)
  assert.equal(retry[0].toolId, 'tool')
  assert.equal(retry[0].status, 'TIMED_OUT')
})

test('retry aggregation keeps attempts and errors under the same work while unrelated work remains separate', () => {
  const work = (id, start, attempt, status, workId) => node(id, 'feedback.work', 'STAGE', start, 2, { parentId: 'run', status, attributes: { 'analysis.job_id': 'job', 'analysis.work_id': workId, 'analysis.phase': 'MERGING', 'execution.attempt': attempt } })
  const p = buildTracePresentation([work('failed', 0, 1, 'FAILED', 'a'), work('retried', 3, 2, 'SUCCEEDED', 'a'), work('other', 6, 1, 'SUCCEEDED', 'b')])
  const retry = p.allCards.get('retry:failed')
  assert.ok(retry)
  assert.equal(retry.status, 'SUCCEEDED')
  assert.equal(cardHighlight(retry), '重试 1 次后成功')
  assert.deepEqual(retry.children.map(c => c.id), ['failed', 'retried'])
  assert.ok(retry.failureIds.includes('failed'))
  assert.ok(!retry.memberIds.includes('other'))
  assert.ok(p.allCards.has('other'))
  assert.equal(p.rawById.size, 3)
})

test('overview collapses execution and delivery while expansion preserves original calls', () => {
  const p=buildTracePresentation([
    node('faq','agent.prepare.faq','STAGE',0,1,{attributes:{'faq.hit':false}}),
    node('decision','agent.model','MODEL',2,3),
    node('tool','agent.tool','TOOL',5,2,{tool:'analyze_video_content'}),
    node('background','feedback.work','STAGE',8,20,{attributes:{'analysis.job_id':'job'}}),
    node('ready','feedback.deliver','DELIVERY',30,1),node('answer','agent.deliver','DELIVERY',32,1),node('finish','finish','STAGE',34,1),
  ])
  const overview=buildTraceOverview(p)
  assert.deepEqual(overview.cards.map(c=>c.cardKind),['faq','execution','background','delivery_group'])
  assert.equal(cardHighlight(overview.cards[0]),'未命中')
  assert.equal(cardHighlight(overview.cards[1]),'视频')
  assert.equal(cardHighlight(overview.cards[3]),'报告已返回')
  const execution=overview.cards[1]
  assert.deepEqual(execution.children.map(c=>c.id),['decision','tool'])
  const defaults=defaultExpandedStages(overview)
  assert.deepEqual([...defaults],[execution.id])
  const expanded=layoutPresentation(overview,defaults)
  assert.ok(expanded.nodes.some(n=>n.id==='decision'));assert.ok(expanded.nodes.some(n=>n.id==='tool'))
  assert.ok(expanded.nodes.some(n=>n.data.cardKind==='background'))
  assert.ok(expanded.nodes.some(n=>n.data.cardKind==='delivery_group'))
  assert.ok(!layoutPresentation(overview,new Set()).nodes.some(n=>n.id==='decision'))
  assert.ok(overview.allCards.has('answer'));assert.ok(overview.rawById.has('finish'))
  assert.equal(p.cards.length,7)
})

test('clarification stays directly visible and failed final outcomes are not labelled delivered', () => {
  const p=buildTraceOverview(buildTracePresentation([
    node('clarify','agent.tool','TOOL',0,1,{tool:'ask_clarification'}),
    node('finish','finish','STAGE',2,1,{attributes:{'run.status':'FAILED'}}),
  ]))
  assert.equal(p.cards[0].cardKind,'tool');assert.equal(cardHighlight(p.cards[0]),'等待用户补充')
  assert.equal(p.cards[1].status,'FAILED');assert.ok(!cardHighlight(p.cards[1]).includes('已返回'))
})

test('retry outcome replaces an earlier attempt without hiding its error records', () => {
  const work=(id,attempt,status,start)=>node(id,'feedback.work','STAGE',start,4,{status,parentId:'run',attributes:{'analysis.job_id':'job','analysis.work_id':'work','execution.attempt':attempt,'analysis.phase':'MERGING'}})
  const raw=[work('first',1,'FAILED',0),node('error','feedback.model','MODEL',1,2,{status:'FAILED',parentId:'first'}),work('retry',2,'SUCCEEDED',6)]
  const job=buildTracePresentation(raw).cards[0]
  assert.equal(job.status,'SUCCEEDED');assert.equal(job.failureIds.length,1);assert.ok(job.memberIds.includes('error'));assert.match(job.summary,/重试后成功/)
  raw.push(node('another','feedback.work','STAGE',12,3,{status:'FAILED',parentId:'run',attributes:{'analysis.job_id':'job','analysis.work_id':'other'}}))
  assert.equal(buildTracePresentation(raw).cards[0].status,'FAILED')
})

test('delivery nodes and actual provider usage survive semantic projection', () => {
  const p=buildTracePresentation([
    node('outer','agent.model','MODEL',0,5),node('provider','model.request','MODEL',1,3,{parentId:'outer',inputTokens:10,outputTokens:3,totalTokens:13,model:'flash'}),
    node('report','feedback.deliver','DELIVERY',6,1),node('answer','agent.deliver','DELIVERY',8,1),node('finish','finish','STAGE',10,1),
  ])
  assert.deepEqual(p.cards.map(c=>c.cardKind),['model','report_delivery','delivery','finish'])
  assert.equal(p.cards[0].totalTokens,13);assert.equal(p.cards[0].model,'flash')
})

test('interleaved spans do not push a delayed parallel sibling into another column', () => {
  const p = buildTracePresentation([
    node('faq', 'agent.prepare.faq', 'STAGE', 0, 1),
    node('intent', 'agent.prepare.intent', 'MODEL', 2, 9, { parentId: 'entry' }),
    node('other', 'agent.tool', 'TOOL', 3, 0.2, { parentId: 'other-scope' }),
    node('emotion', 'agent.prepare.emotion', 'MODEL', 4, 8, { parentId: 'entry' }),
    node('clarify', 'agent.prepare.clarification', 'MODEL', 5, 8, { parentId: 'entry' }),
  ])
  const g = layoutPresentation(p)
  const branches = g.nodes.filter(n => ['intent','emotion','clarify'].includes(n.id))
  assert.equal(new Set(branches.map(n => n.position.x)).size, 1)
  assert.equal(new Set(branches.map(n => n.position.y)).size, 3)
  assert.equal(branches.find(n => n.id === 'emotion').data.offsetMs, 4)
})

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
  assert.equal(Math.abs(second.position.y - first.position.y), 76)
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


test('memory restore is not another recall and consolidation reports accepted decisions',()=>{
  const nodes=[{id:'recall',name:'agent.memory.recall',offsetMs:0,durationMs:2,status:'SUCCEEDED',attributes:{'memory.selected_count':2}},{id:'restore',name:'agent.memory.restore',offsetMs:3,durationMs:2,status:'SUCCEEDED',attributes:{'memory.selected_count':2,'memory.reused':true}},{id:'write',name:'agent.memory.extract',offsetMs:6,durationMs:10,status:'SUCCEEDED',attributes:{'memory.candidate_count':4,'memory.added_count':1,'memory.updated_count':1,'memory.duplicate_count':2}}]
  const p=buildTracePresentation(nodes)
  assert.equal(p.cards.filter(c=>c.cardKind==='memory').length,1)
  assert.equal(p.allCards.get('restore').cardKind,'memory_restore')
  assert.match(p.allCards.get('restore').summary,/未重新检索/)
  assert.match(p.allCards.get('write').summary,/新增 1 · 更新 1 · 重复 2/)
})

test('nested parallel tool batch remains linked to its actual model round',()=>{
  const base={status:'SUCCEEDED',turn:1,attributes:{'run.id':'r','run.turn_seq':1}}
  const nodes=[{...base,id:'loop',name:'agent.loop',offsetMs:0,durationMs:20},{...base,id:'m',name:'agent.model',kind:'MODEL',parentId:'loop',offsetMs:1,durationMs:2,output:{call:{name:'query_video_metrics',arguments:{video_id:1}},additionalCalls:[{name:'query_video_metrics',arguments:{video_id:2}}]}},{...base,id:'batch',name:'agent.tool.batch',parentId:'loop',offsetMs:4,durationMs:10,attributes:{...base.attributes,'tool.execution_mode':'PARALLEL'}},...[1,2].map(i=>({...base,id:`t${i}`,name:'agent.tool',kind:'TOOL',tool:'query_video_metrics',parentId:'batch',offsetMs:4,durationMs:i+3,input:{name:'query_video_metrics',arguments:{video_id:i}}}))]
  const p=buildTracePresentation(nodes),calls=modelInvocations(p.allCards.get('m'),p)
  assert.deepEqual(calls.map(c=>c.toolId),['t1','t2'])
  assert.equal(calls.some(c=>c.id.startsWith('planned:')),false)
  const graph=layoutPresentation(p),t=graph.nodes.filter(n=>n.data.cardKind==='tool')
  assert.equal(t.length,2);assert.equal(t[0].position.x,t[1].position.x);assert.notEqual(t[0].position.y,t[1].position.y)
  assert.equal((t[0].position.y+t[1].position.y)/2,graph.nodes.find(n=>n.id==='m').position.y)
})
