// Flat sector ends keep the boundaries crisp at every segment size.
export function donutArcs(segments) {
  const positive = segments.filter(segment => Number.isFinite(segment.value) && segment.value > 0)
  const total = positive.reduce((sum, segment) => sum + segment.value, 0)
  if (!total) return []
  if (positive.length === 1) return [{ ...positive[0], full: true }]
  let offset = 0
  return positive.map(segment => {
    const share = segment.value / total * 100
    const gap = Math.min(0.4, share / 2)
    const arc = { ...segment, start:offset + gap / 2, length:share - gap, rounded:false, full:false }
    offset += share
    return arc
  })
}
