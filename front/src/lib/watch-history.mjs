export function createHistoryRecorder(save, now = Date.now) {
  let lastKey = "",
    lastAt = -Infinity;
  const pending = new Set();
  return (userId, videoId, seconds) => {
    if (!userId || !videoId || !Number.isFinite(seconds) || seconds < 0) return;
    const key = String(userId) + ":" + String(videoId);
    if (key !== lastKey) {
      lastKey = key;
      lastAt = -Infinity;
    }
    if (pending.has(key) || now() - lastAt < 15000) return;
    lastAt = now();
    pending.add(key);
    Promise.resolve()
      .then(() => save(String(videoId), seconds, String(userId)))
      .catch(() => {})
      .finally(() => pending.delete(key));
  };
}
