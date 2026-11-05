// Render authorized server messages without refetching private reports or rerunning tools.
export function mergeMessages(existing, incoming) {
  const messages = new Map(existing.map(message => [message.id, message]))
  for (const message of incoming) {
    if (!message || typeof message.id !== 'string' || typeof message.runId !== 'string' || !Number.isSafeInteger(message.sequence) || !['user', 'assistant'].includes(message.role) || typeof message.text !== 'string') throw new Error('会话历史格式不正确')
    messages.set(message.id, message)
  }
  return [...messages.values()].sort((a, b) => a.sequence - b.sequence)
}
export function historicalTurns(messages, liveTurns) {
  const live = new Set(liveTurns.map(turn => turn.runId))
  const firstLive = messages.find(message => live.has(message.runId))?.sequence ?? Infinity
  const turns = new Map()
  for (const message of messages) {
    if (live.has(message.runId) || message.sequence >= firstLive) continue
    let turn = turns.get(message.runId)
    if (!turn) {
      turn = {runId: message.runId, message: '', events: [], historical: true, snapshot: {runId: message.runId, status: 'SUCCEEDED', createdAt: message.at}}
      turns.set(message.runId, turn)
    }
    if (message.role === 'user') turn.message = message.text
    else turn.snapshot.answer = {text: message.text}
  }
  return [...turns.values()]
}
