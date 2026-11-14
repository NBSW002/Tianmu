export function restoreDraftItems(next, previousDraft, previousItems) {
  const selected = new Set(previousItems.filter(item => item.selected).map(item => item.id))
  const sameDraft = previousDraft?.runId === next?.runId
  return (next?.items || []).map(item => ({ ...item, seconds: item.playbackMs == null ? null : item.playbackMs / 1000,
    selected: next?.state === 'PUBLISHING' || next?.state === 'PUBLISHED' || (sameDraft && selected.has(item.id)) }))
}
