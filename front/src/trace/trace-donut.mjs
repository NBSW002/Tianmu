// Arc offsets use SVG pathLength=100. Round caps extend by half the stroke,
// so shorten each large arc to keep its visible ends inside its own sector.
export function donutArcs(segments, radius = 42, strokeWidth = 9) {
  const positive = segments.filter(segment => Number.isFinite(segment.value) && segment.value > 0)
  const total = positive.reduce((sum, segment) => sum + segment.value, 0)
  if (!total) return []
  if (positive.length === 1) return [{ ...positive[0], full: true }]
  const gap = 0.35
  const halfCap = strokeWidth / (4 * Math.PI * radius) * 100
  let offset = 0
  return positive.map(segment => {
    const share = segment.value / total * 100
    const rounded = share > 2 * halfCap + gap
    // Tiny sectors use a flat cap; a round cap would visually inflate them.
    const inset = rounded ? halfCap + gap / 2 : Math.min(gap / 2, share / 4)
    const arc = { ...segment, start:offset + inset, length:Math.max(0, share - 2 * inset), rounded, full:false }
    offset += share
    return arc
  })
}
