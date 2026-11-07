import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@babel/parser'
import { terminal } from '../src/agent/protocol.mjs'

test('same-user credential rotation preserves a late terminal report response', async () => {
  const component = fs.readFileSync(new URL('../src/views/agent/AgentWorkspace.vue', import.meta.url), 'utf8')
  const source = component.match(/<script setup>([\s\S]*?)<\/script>/)[1]
  const nodes = parse(source, { sourceType: 'module' }).program.body
  const declaration = name => {
    const node = nodes.find(n => n.type === 'FunctionDeclaration' && n.id.name === name)
    assert.ok(node); return source.slice(node.start, node.end)
  }
  const watcher = nodes.find(n => n.type === 'ExpressionStatement' && n.expression.callee?.name === 'watch'
    && source.slice(n.expression.arguments[0].start, n.expression.arguments[0].end).includes('userStore.user.token')).expression.arguments[1]
  const reports = new Set(), restore = new AbortController(), turn = { runId: 'run', reportPath: '/report', snapshot: { status: 'SUCCEEDED' }, reportHtml: '' }
  let complete, reportSignal, stopped = 0
  const fetchReport = (runId, path, signal) => {
    reportSignal = signal
    return new Promise((resolve, reject) => {
      complete = resolve; signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    })
  }
  const fixture = new Function('props', 'runMonitor', 'activeTurn', 'terminal', 'restoreTicket', 'reportControllers', 'getAgentReport',
    `${declaration('stopMonitoring')}\n${declaration('loadReport')}\nreturn {loadReport,rotate:(${source.slice(watcher.start, watcher.end)})}`)(
    { managed: true }, { stop() { stopped++ }, start() { assert.fail('terminal task must not restart') } }, { value: turn }, terminal, restore, reports, fetchReport)
  const pending = fixture.loadReport(turn)
  fixture.rotate('new-token', 'old-token')
  assert.equal(stopped, 1); assert.equal(reportSignal.aborted, false); assert.equal(restore.signal.aborted, false)
  complete('<html>已完成的报告</html>'); await pending
  assert.equal(turn.reportHtml, '<html>已完成的报告</html>'); assert.equal(turn.reportLoading, false); assert.equal(reports.size, 0)
})
