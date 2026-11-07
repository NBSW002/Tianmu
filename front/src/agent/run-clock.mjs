// Server timestamps and browser wall clocks may differ. Only compare timestamps
// from the server; advance the resulting duration with the browser's monotonic clock.
const timing = Symbol('run-response-timing');

export async function readRunSnapshot(response, now = () => performance.now()) {
  const sampledAt = now();
  const serverNow = Date.parse(response.headers.get('date'));
  const snapshot = await response.json();
  const createdAt = Date.parse(snapshot.createdAt);
  const elapsedMs = Number.isFinite(serverNow) && Number.isFinite(createdAt)
    ? Math.max(0, serverNow - createdAt) : null;
  Object.defineProperty(snapshot, timing, { value: Object.freeze({ sampledAt, elapsedMs }) });
  return snapshot;
}

export function runWaitClock(snapshot, previous, now = performance.now()) {
  const sameRun = previous?.runId === snapshot.runId;
  const sample = snapshot[timing];
  const priorElapsed = sameRun ? Math.max(0, previous.elapsedMs + now - previous.sampledAt) : 0;
  const serverElapsed = sample?.elapsedMs == null ? 0 : sample.elapsedMs + Math.max(0, now - sample.sampledAt);
  return { runId: snapshot.runId, sampledAt: now, elapsedMs: Math.max(priorElapsed, serverElapsed) };
}

export function formatRunWait(timer, now) {
  const seconds = timer ? Math.max(0, Math.floor((timer.elapsedMs + now - timer.sampledAt) / 1000)) : 0;
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}
