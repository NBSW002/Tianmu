import test from 'node:test'
import assert from 'node:assert/strict'
import { isEmail, validLoginAccount } from '../src/lib/account-input.mjs'
import { createHistoryRecorder } from '../src/lib/watch-history.mjs'

test('email is required for registration and code login while legacy phone password login remains available', () => {
  assert.equal(isEmail(' user@example.com '), true)
  assert.equal(isEmail('13812345678'), false)
  assert.equal(validLoginAccount('13812345678', 1), true)
  assert.equal(validLoginAccount('13812345678', 2), false)
  assert.equal(validLoginAccount(' user@example.com ', 2), true)
  assert.equal(validLoginAccount('', 1), false)
})

test('history throttles playback events, bounds concurrent writes, and preserves account and video identity', async () => {
  let time = 0, release
  const calls = []
  const record = createHistoryRecorder((video, seconds, owner) => {
    calls.push({ video, seconds, owner })
    return new Promise(resolve => { release = resolve })
  }, () => time)
  record(null, '11', 0)
  record('1', '11', NaN)
  record('1', '11', 5)
  await new Promise(resolve => setImmediate(resolve))
  const first = release
  time = 20000
  record('1', '11', 25)
  assert.equal(calls.length, 1)
  record('2', '9007199254740993', 10)
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls[1], { video: '9007199254740993', seconds: 10, owner: '2' })
  first(); release()
  await new Promise(resolve => setImmediate(resolve))
  time = 25000
  record('2', '9007199254740993', 15)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls.length, 2)
  time = 40000
  record('2', '9007199254740993', 30)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls.length, 3)
  release()
})

test('failed history writes are bounded and can retry after the next interval', async () => {
  let time = 0, calls = 0
  const record = createHistoryRecorder(async () => { calls++; throw new Error('offline') }, () => time)
  record('1', '11', 1)
  await new Promise(resolve => setImmediate(resolve))
  record('1', '11', 2)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls, 1)
  time = 16000
  record('1', '11', 17)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(calls, 2)
})
