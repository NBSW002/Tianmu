import test from 'node:test';
import assert from 'node:assert/strict';
import { readRunSnapshot, runWaitClock, formatRunWait } from '../src/agent/run-clock.mjs';

const createdAt = '2026-11-01T14:00:00Z';
const response = (date, runId = 'run', start = createdAt) => ({
  headers: { get: () => date }, json: async () => ({ runId, createdAt: start }),
});

test('a new run starts at zero even when browser wall time is a minute ahead or behind', async () => {
  const original = Date.now;
  try {
    for (const skew of [-87000, 87000]) {
      Date.now = () => Date.parse(createdAt) + skew;
      const snapshot = await readRunSnapshot(response('Sun, 01 Nov 2026 14:00:00 GMT'), () => 100);
      const timer = runWaitClock(snapshot, null, 100);
      assert.equal(formatRunWait(timer, 100), '0 秒');
      assert.equal(formatRunWait(timer, 5100), '5 秒');
      assert.equal(formatRunWait(timer, 60100), '1 分 0 秒');
    }
  } finally { Date.now = original; }
});

test('restoring a running task preserves its actual server age', async () => {
  const snapshot = await readRunSnapshot(response('Sun, 01 Nov 2026 14:01:27 GMT'), () => 400);
  assert.equal(formatRunWait(runWaitClock(snapshot, null, 400), 1400), '1 分 28 秒');
});

test('polling never resets or moves the visible duration backwards, and a new run starts separately', async () => {
  const first = await readRunSnapshot(response('Sun, 01 Nov 2026 14:00:00 GMT'), () => 100);
  const timer = runWaitClock(first, null, 100);
  const polled = await readRunSnapshot(response('Sun, 01 Nov 2026 14:00:02 GMT'), () => 2800);
  const updated = runWaitClock(polled, timer, 2800);
  assert.equal(formatRunWait(updated, 3100), '3 秒');
  const next = await readRunSnapshot(response('Sun, 01 Nov 2026 14:01:00 GMT', 'next', '2026-11-01T14:01:00Z'), () => 5000);
  assert.equal(formatRunWait(runWaitClock(next, updated, 5000), 5000), '0 秒');
});

test('missing or malformed response time falls back to local duration without NaN or poll resets', async () => {
  for (const date of [null, 'invalid']) {
    const snapshot = await readRunSnapshot(response(date), () => 10);
    const timer = runWaitClock(snapshot, null, 10);
    const polled = await readRunSnapshot(response(date), () => 2010);
    assert.equal(formatRunWait(runWaitClock(polled, timer, 2010), 3010), '3 秒');
    assert.equal(formatRunWait(undefined, 3010), '0 秒');
  }
  const snapshot = await readRunSnapshot(response('Sun, 01 Nov 2026 14:00:00 GMT', 'run', 'invalid'), () => 0);
  assert.equal(formatRunWait(runWaitClock(snapshot, null, 0), 1000), '1 秒');
});

test('response timing is transient and does not alter serialized snapshots', async () => {
  const snapshot = await readRunSnapshot(response('Sun, 01 Nov 2026 14:00:00 GMT'), () => 0);
  assert.deepEqual(JSON.parse(JSON.stringify(snapshot)), { runId: 'run', createdAt });
});
