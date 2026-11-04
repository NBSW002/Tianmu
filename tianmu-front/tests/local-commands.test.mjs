import test from 'node:test'
import assert from 'node:assert/strict'
import { isLogCommand, logReply } from '../src/agent/local-commands.mjs'

test('/log is an exact local command, never ordinary prompt content', () => {
  assert.equal(isLogCommand('  /log\n'), true)
  assert.equal(isLogCommand('/LOG'), true)
  for (const text of ['/login', '/log 帮我分析', '解释 /log', '']) assert.equal(isLogCommand(text), false)
})
test('log replies use the current run snapshot, including restored or failed runs', () => {
  const traceId = '6fc87cfedc8592cb9c6afe7417d6d921'
  for (const status of ['RUNNING', 'WAITING_OPERATION', 'FAILED', 'SUCCEEDED']) {
    const result = logReply({ status, traceId })
    assert.equal(result.traceId, traceId)
    assert.equal(result.href, `/trace?traceId=${traceId}`)
  }
})
test('empty or invalid trace IDs cannot produce a stale or unsafe link', () => {
  for (const snapshot of [undefined, {}, { traceId: '0'.repeat(32) }, { traceId: '<script>' }, { traceId: 'previous-run' }]) {
    assert.equal(logReply(snapshot).href, null)
    assert.equal(logReply(snapshot).traceId, null)
  }
  assert.match(logReply(undefined).text, /暂无任务/)
  assert.match(logReply({ status: 'QUEUED' }).text, /尚未生成/)
})
