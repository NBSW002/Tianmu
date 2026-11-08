import test from 'node:test'
import assert from 'node:assert/strict'
import { playbackChart } from '../src/agent/playback-chart.mjs'

test('plots bin midpoints and preserves the full playback time axis', () => {
  const chart = playbackChart({ counts: [2, 0, 4], startMs: 0, endMs: 45000, bucketMs: 15000 })
  assert.equal(chart.ceiling, 4)
  assert.deepEqual(chart.ticks, ['00:00', '00:11', '00:22', '00:33', '00:45'])
  assert.equal(chart.line, 'M106.67 90.00 L320.00 172.00 L533.33 8.00')
})

test('renders one bin as a horizontal line and skips empty data', () => {
  assert.equal(playbackChart({ counts: [1], startMs: 0, endMs: 15000, bucketMs: 15000 }).line, 'M0.00 90.00 L640.00 90.00')
  assert.equal(playbackChart({ counts: [0], startMs: 0, endMs: 15000, bucketMs: 15000 }), null)
})
