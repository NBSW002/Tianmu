const clock = ms => {
  const seconds = Math.floor(ms / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

// Match the downloadable report: each point represents the midpoint of a playback bin.
export function playbackChart(playback) {
  const { counts, startMs, endMs, bucketMs } = playback
  if (!counts?.length || !counts.some(count => count > 0) || endMs <= startMs) return null
  const peak = Math.max(...counts)
  const ceiling = Math.max(2, peak + peak % 2)
  const points = counts.map((count, i) => {
    const start = startMs + i * bucketMs
    const end = Math.min(endMs, start + bucketMs)
    return [640 * ((start + end) / 2 - startMs) / (endMs - startMs), 172 - 164 * count / ceiling]
  })
  if (points.length === 1) points.splice(0, 1, [0, points[0][1]], [640, points[0][1]])
  const line = points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)} ${y.toFixed(2)}`).join(' ')
  return { line, area: `${line} L${points.at(-1)[0].toFixed(2)} 172 L${points[0][0].toFixed(2)} 172 Z`, ceiling,
    ticks: Array.from({ length: 5 }, (_, i) => clock(startMs + (endMs - startMs) * i / 4)) }
}
