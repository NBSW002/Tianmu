import test from 'node:test'
import assert from 'node:assert/strict'
import { sessionFixture } from './helpers/session-fixture.mjs'

const rejected = () => new Response(JSON.stringify({ code: 'UNAUTHORIZED' }), { status: 401, headers: { 'Content-Type': 'application/json' } })

test('expired send clears private state but keeps the assistant open for login', async () => {
  const f = sessionFixture(); globalThis.fetch = async () => rejected()
  try {
    await assert.rejects(f.api.createAgentRun({ message: 'test' }, 'same-request'), { code: 'UNAUTHORIZED' })
    assert.equal(f.users.isLogin, false); assert.equal(f.users.sessionExpired, true)
    assert.equal(f.assistant.open, true); assert.equal(f.assistant.turns.length, 0); assert.equal(f.assistant.focus, '')
  } finally { f.close() }
})

test('late 401 from an old token cannot log out the new session or erase its conversation', async () => {
  const f = sessionFixture(); let reply; const requests = []
  globalThis.fetch = (path, options) => { requests.push(options); return new Promise(resolve => { reply = resolve }) }
  try {
    const pending = f.api.createAgentRun({ message: 'test' }, 'same-request')
    f.users.login({ userId: 'alice', token: 'fixture-new-token' })
    assert.equal(f.assistant.open, true); assert.equal(f.assistant.turns.length, 1)
    reply(rejected()); await assert.rejects(pending, { code: 'AUTH_SESSION_CHANGED' })
    assert.equal(f.users.user.token, 'fixture-new-token'); assert.equal(f.assistant.focus, '尚未发送的内容')
    assert.equal(requests.length, 1); assert.equal(requests[0].headers.Authorization, 'Bearer fixture-old-token')
  } finally { f.close() }
})

test('identity change clears the previous account data while leaving the panel available', () => {
  const f = sessionFixture()
  try {
    f.users.login({ userId: 'bob', token: 'fixture-other-token' })
    assert.equal(f.assistant.open, true); assert.equal(f.assistant.turns.length, 0); assert.equal(f.assistant.focus, '')
    assert.equal(f.users.expireSession('Bearer fixture-old-token'), false); assert.equal(f.users.user.userId, 'bob')
  } finally { f.close() }
})

test('aborted old send never expires the current session', async () => {
  const f = sessionFixture(), controller = new AbortController(); let reply
  globalThis.fetch = () => new Promise(resolve => { reply = resolve })
  try {
    const pending = f.api.createAgentRun({ message: 'test' }, 'request', controller.signal)
    controller.abort(); reply(rejected()); await assert.rejects(pending, { name: 'AbortError' })
    assert.equal(f.users.isLogin, true); assert.equal(f.assistant.turns.length, 1)
  } finally { f.close() }
})

test('business response with expired old credentials cannot invalidate a refreshed login', async () => {
  const f = sessionFixture(); let reply, captured
  try {
    const pending = f.business({ url: '/user/online', adapter: config => { captured = config; return new Promise(resolve => { reply = resolve }) } })
    await new Promise(resolve => setImmediate(resolve))
    f.users.login({ userId: 'alice', token: 'fixture-new-token' })
    reply({ data: { code: 50005, message: '未登录' }, status: 200, statusText: 'OK', headers: {}, config: captured })
    await pending.then(() => assert.fail('should reject old request'), reason => assert.equal(reason, '登录信息已更新，请重试本次操作'))
    assert.equal(f.users.user.token, 'fixture-new-token'); assert.equal(f.assistant.open, true)
  } finally { f.close() }
})

test('business HTTP 401 expires only its current credential and leaves the login panel open', async () => {
  const f = sessionFixture()
  try {
    const pending = f.business({ url: '/user/online', adapter: config => {
      const error = Object.assign(new Error('Unauthorized'), { config, response: { status: 401 } })
      return Promise.reject(error)
    } })
    await assert.rejects(pending, /Unauthorized/)
    assert.equal(f.users.isLogin, false); assert.equal(f.users.sessionExpired, true)
    assert.equal(f.assistant.open, true); assert.equal(f.assistant.turns.length, 0)
  } finally { f.close() }
})

test('cross-tab storage rotation cannot be erased by an older request before state synchronization', async () => {
  const f = sessionFixture(); let reply
  globalThis.fetch = () => new Promise(resolve => { reply = resolve })
  try {
    const pending = f.api.createAgentRun({ message: 'test' }, 'request')
    const current = JSON.stringify({ userId: 'alice', token: 'fixture-other-tab-token' })
    localStorage.setItem('TIANMU_USER', current)
    reply(rejected()); await assert.rejects(pending, { code: 'AUTH_SESSION_CHANGED' })
    assert.equal(JSON.parse(localStorage.getItem('TIANMU_USER')).token, 'fixture-other-tab-token')
    f.users.syncStoredUser(current)
    assert.equal(f.users.user.token, 'fixture-other-tab-token'); assert.equal(f.assistant.turns.length, 1)
    f.users.syncStoredUser(null)
    assert.equal(f.assistant.turns.length, 0); assert.equal(f.assistant.open, true)
  } finally { f.close() }
})
