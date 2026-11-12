import test from 'node:test'
import assert from 'node:assert/strict'
import { playbackTime, playbackProgress, groupHistory, historyDay, historyClock } from '../src/lib/history-view.mjs'

test('history uses Beijing calendar boundaries and preserves record order and IDs', () => {
  const now = Date.parse('2026-11-12T00:00:00Z')
  assert.equal(historyDay('2026-11-11T16:05:00Z', now), '今天')
  assert.equal(historyDay('2026-11-11T15:59:00Z', now), '昨天')
  assert.equal(historyClock('2026-11-11T16:05:00Z'), '00:05')
  assert.equal(historyDay('invalid', now), '更早')
  const groups = groupHistory([
    { videoId: '9007199254740993', watchedAt: '2026-11-11T16:05:00Z' },
    { videoId: '2', watchedAt: '2026-11-11T15:59:00Z' },
  ], now)
  assert.deepEqual(groups.map(group => group.label), ['今天', '昨天'])
  assert.equal(groups[0].items[0].videoId, '9007199254740993')
})

test('playback labels support long videos and progress never invents unavailable duration', () => {
  assert.equal(playbackTime(13), '0:13')
  assert.equal(playbackTime(3723), '1:02:03')
  assert.equal(playbackTime(Infinity), '0:00')
  assert.equal(playbackProgress(13, 224), 13 / 224 * 100)
  assert.equal(playbackProgress(300, 224), 100)
  assert.equal(playbackProgress(-1, 224), 0)
  assert.equal(playbackProgress(13, undefined), null)
  assert.equal(playbackProgress(13, 0), null)
})
