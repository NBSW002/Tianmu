import { useUserStore } from '@/store/user'
import { reportPath, sseDecoder, validRunId } from '@/agent/protocol.mjs'
import { readRunSnapshot } from '@/agent/run-clock.mjs'
import { notifyMemoryChanged } from '@/lib/memory-events.mjs'

export function agentUser() {
  return useUserStore().user
}

function authorization() {
  const token = agentUser().token
  if (!token || typeof token !== 'string')
    throw new Error('请先登录网站后再使用个人助手')
  return token.startsWith('Bearer ') ? token : `Bearer ${token}`
}

async function request(path, options = {}) {
  const headers = { Authorization: authorization(), ...options.headers }
  let response
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      cache: 'no-store',
      ...options,
      headers,
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new Error('无法连接助手服务，请检查网络后重试')
  }
  if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    if (options.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    const changedSession = response.status === 401 && !useUserStore().expireSession(headers.Authorization)
    const code = changedSession ? 'AUTH_SESSION_CHANGED' : data?.code
    const messages = {
      MODEL_CATALOG_INVALID: '模型配置更新无效，请联系管理员检查配置',
      MODEL_NOT_AVAILABLE: '所选模型已停用，请刷新模型列表后重新选择',
      MODEL_REVISION_UNAVAILABLE: '任务使用的模型版本已失效，请重新发起提问',
      VIDEO_CONTENT_UNAVAILABLE: '视频内容服务暂不可用，请稍后重试',
      UNAUTHORIZED: '登录已失效，请重新登录',
      AUTH_SESSION_CHANGED: '登录信息已更新，请重试本次操作',
      AUTH_CONTEXT_EXPIRED: '登录已失效，请重新登录',
      FORBIDDEN: '你没有权限访问这项内容或执行此操作',
      RUN_NOT_FOUND: '任务不存在或已过期',
      TRACE_NOT_FOUND: '未找到可访问的 Trace，日志可能尚未归档或已过期',
      TRACE_UNAVAILABLE: 'Trace 查询服务尚未配置或暂不可用',
      TRACE_TOO_LARGE: 'Trace 超出查看上限，请按任务拆分导出',
      RESULT_NOT_FOUND: '报告不存在或已过期',
      DATE_OUT_OF_RANGE: '请选择最近 30 天内已经结束的时段',
      CAPACITY_EXCEEDED: '当前分析任务较多，请稍后重试',
      EVENTS_EXPIRED: '进度记录已过期，正在同步任务状态',
      QA_NOT_FOUND: '这条常见问答不存在',
      INVALID_DRAFT: '请检查草稿：内容不能空白或重复，弹幕时间须在视频范围内',
      DRAFT_CONFLICT: '草稿状态已变化，请刷新；已开始发送的内容不能修改',
      DRAFT_NOT_READY: '草稿尚未生成完成',
      DRAFT_NOT_FOUND: '草稿不存在或已过期',
      PUBLICATION_UNAVAILABLE: '发送服务暂不可用，请稍后重试确认发送结果',
      QA_CONFLICT: '这条问答已经更新，请刷新后再操作',
      QA_UNAVAILABLE: '常见问答暂时不可用，请稍后重试',
      MEMORY_CONTEXT_INVALIDATED: '本轮使用的记忆已变化，请重新提问',
      MEMORY_DISABLED: '记忆已暂停，仍可在管理页查看和删除',
      MEMORY_VERSION_CONFLICT: '记忆已经更新，请刷新后重试',
      MEMORY_ACTION_DENIED: '请明确要保存或忘记的内容，也可以在记忆管理中操作',
      MEMORY_QUOTA_EXCEEDED: '记忆数量已达上限，请先整理不再需要的内容',
      MEMORY_UNAVAILABLE: '记忆服务暂时不可用，请稍后重试',
      MEMORY_SOURCE_UNAVAILABLE: '相关记忆来源已失效，请刷新后再试',
      MEMORY_JOB_NOT_RETRYABLE: '这项整理已结束或没有待处理内容，请刷新进度',
      MEMORY_RECEIPT_REQUIRED: '尚无保存成功的记录，请在记忆管理中查看',
    }
    const error = new Error(
      messages[code] ||
        (response.status === 401
          ? '登录已失效，请重新登录'
          : '暂时没能完成请求，请稍后重试')
    )
    error.code = code
    error.status = response.status
    throw error
  }
  return response
}

function runPath(id) {
  if (!validRunId(id)) throw new Error('无效的任务 ID')
  return `/api/agent/runs/${id}`
}

export async function memoryRequest(path = '', options = {}) {
  const routes = /^\/(?:memories(?:\/[a-f0-9-]{36}|\/reset)?|memory-settings(?:\/automatic)?|memory-topics|runs\/[a-f0-9-]{36}\/memory(?:-jobs(?:\/[a-f0-9-]{36}\/retry)?)?)(?:\?[^#]*)?$/
  if (!routes.test(path)) throw new Error('无效的记忆接口')
  const result = await (await request(`/api/agent${path}`, { ...options, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers } })).json()
  if (options.method && options.method.toUpperCase() !== 'GET') notifyMemoryChanged()
  return result
}

export async function getAgentTrace(traceId, signal) {
  if (!/^[a-f0-9]{32}$/.test(traceId)) throw new Error('Trace ID 应为 32 位十六进制字符')
  return (await request(`/api/agent/traces/${traceId}`, { signal })).json()
}
export async function getTraceRecords(traceId, signal) {
  if (!/^[a-f0-9]{32}$/.test(traceId)) throw new Error('Trace ID 应为 32 位十六进制字符')
  return (await request(`/api/agent/traces/${traceId}/records`, { signal })).json()
}

export async function getTracePayload(runId, reference, signal) {
  if (!/^[a-f0-9]{16}\/(input|output|error|provider_output|reasoning|prompt|code|execution)\/[1-9]\d{0,6}\/[a-f0-9]{64}$/.test(reference))
    throw new Error('节点正文引用无效')
  return (await request(`${runPath(runId)}/trace-payloads/${reference}`, { signal })).json()
}

export async function getQaCatalog(signal) {
  return (await request('/api/agent/qa', { signal })).json()
}

export async function changeQaEntry(id, version, reset, signal) {
  if (!/^[a-f0-9]{64}$/.test(id)) throw new Error('无效的问答 ID')
  return (await request(`/api/agent/qa/${id}/${reset ? 'reset' : 'disable'}`, {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version }),
  })).json()
}

export async function checkCreatorAccess(videoId, signal) {
  if (typeof videoId !== 'string' || !/^[1-9]\d{0,18}$/.test(videoId) ||
      (videoId.length === 19 && videoId > '9223372036854775807')) throw new Error('视频 ID 无效')
  return (await request(`/api/agent/videos/${videoId}/access`, { signal })).json()
}

export async function getRoomSummary(videoId, start, signal) {
  if (!/^[1-9]\d{0,18}$/.test(videoId) || (videoId.length === 19 && videoId > '9223372036854775807'))
    throw new Error('视频 ID 无效')
  const query = start ? `?start=${encodeURIComponent(start)}` : ''
  const response = await request(`/api/agent/rooms/${videoId}/summary${query}`, { signal })
  return response.json()
}

export async function createAgentRun(body, key, signal) {
  const response = await request('/api/agent/runs', {
    method: 'POST',
    signal,
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    body: JSON.stringify(body),
  })
  return readRunSnapshot(response)
}
export async function getAgentRun(id, signal) {
  return readRunSnapshot(await request(runPath(id), { signal }))
}
export async function resumeAgentAnalysis(id, signal) {
  return (await request(`${runPath(id)}/analysis/resume`, { method: 'POST', signal })).json()
}
export async function getCreatorDraft(id, signal) {
  const response = await request(`${runPath(id)}/creator-draft`, { signal })
  const body = await response.text()
  return body ? JSON.parse(body) : null
}
export async function confirmCreatorDraft(id, items, signal) {
  return (await request(`${runPath(id)}/creator-draft/confirm`, {
    method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }),
  })).json()
}
export async function cancelAgentRun(id, signal) {
  return readRunSnapshot(
    await request(`${runPath(id)}/cancel`, { method: 'POST', signal })
  )
}

export async function readAgentEvents(id, after, signal, consume) {
  const response = await request(`${runPath(id)}/events`, {
    signal,
    headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(after) },
  })
  if (
    !response.headers.get('content-type')?.includes('text/event-stream') ||
    !response.body
  )
    throw new Error('进度连接不可用，正在同步任务状态')
  const reader = response.body.getReader(),
    decoder = new TextDecoder()
  const parser = sseDecoder(consume)
  try {
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) {
        parser.push(decoder.decode())
        break
      }
      parser.push(decoder.decode(value, { stream: true }))
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export async function getAgentReport(runId, path, signal) {
  if (!reportPath(runId, path)) throw new Error('无效的报告链接')
  const response = await request(path, { signal })
  if (!response.headers.get('content-type')?.includes('text/html'))
    throw new Error('报告格式不正确')
  return response.text()
}
export async function getAgentReportJson(runId, path, signal) {
  if (!reportPath(runId, path)) throw new Error('无效的报告链接')
  const response = await request(`${path}/json`, { signal })
  const value = await response.json()
  if (value?.schemaVersion !== 'feedback-report-json-v1' || !Array.isArray(value.topics) || value.topics.length > 5 || !value.method)
    throw new Error('报告数据格式不正确')
  return value
}

export async function getAssistantCapabilities(videoId, signal) {
  const query = videoId ? `?videoId=${encodeURIComponent(videoId)}` : ''
  return (await request(`/api/agent/capabilities${query}`, { signal })).json()
}

export async function getSessionMessages(sessionId, before, signal) {
  if (!validRunId(sessionId)) throw new Error('无效的会话 ID')
  const query = before ? `?before=${encodeURIComponent(before)}` : ''
  const body = await (await request(`/api/agent/sessions/${sessionId}/messages${query}`, { signal })).json()
  if (!Array.isArray(body)) throw new Error('会话历史格式不正确')
  return body
}

export async function getAssistantModels(signal) { return (await request('/api/agent/models', {signal})).json() }

export async function videoContentIndex(videoId, method = 'GET', signal) {
  if (!/^[1-9][0-9]{0,18}$/.test(videoId)) throw new Error('无效的视频 ID');
  return (await request('/api/agent/videos/' + videoId + '/index', { method, signal })).json();
}


export async function getAgentModelMetadata(signal) {
  return (await request('/api/agent/models/metadata', { signal })).json()
}
