export const isLogCommand = (message) => typeof message === 'string' && message.trim().toLowerCase() === '/log'

export function logReply(snapshot) {
  const traceId = snapshot?.traceId
  if (typeof traceId === 'string' && /^[a-f0-9]{32}$/.test(traceId) && !/^0+$/.test(traceId))
    return { traceId, text: '当前任务的 Trace ID', href: `/trace?traceId=${traceId}` }
  return { traceId: null, text: snapshot ? '当前任务尚未生成 Trace ID，请稍后再输入 /log。' : '当前对话暂无任务记录，暂时没有 Trace ID。', href: null }
}
