export const terminal = (status) =>
  ['SUCCEEDED', 'NEEDS_INPUT', 'FAILED', 'CANCELLED'].includes(status)
export const validRunId = (value) =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)

export function buildChatMessage(text) {
  const message = text.trim()
  if (!message) throw new Error('先写下你想聊的问题吧')
  if (message.length > 2000) throw new Error('消息太长了，请缩短后再发送')
  return message
}

// Page selection travels separately; the server validates it before any data query.
export function buildChatRequest(text, videoId = '', videoTitle = '') {
  const body = { message: buildChatMessage(text) }
  if (videoId) {
    if (!/^[1-9]\d{0,18}$/.test(videoId) || (videoId.length === 19 && videoId > '9223372036854775807'))
      throw new Error('当前视频 ID 无效，请重新打开视频页面')
    body.context = { videoId, ...(videoTitle ? { videoTitle: videoTitle.slice(0,160) } : {}) }
  }
  return body
}

export function answerPresentation(answer) {
  let text = typeof answer?.text === 'string' ? answer.text : ''
  const notes = Array.isArray(answer?.dataNotes)
    ? answer.dataNotes.filter(note => note && typeof note.text === 'string').map(note => ({ ...note }))
    : []
  let legacyTime
  // Compatibility for stored replies produced before structured data notes existed.
  text = text.replace(/[（(]已持久化保留口径[，,]\s*数据截至([^）)]+)[）)]/g, (_, time) => {
    legacyTime = time.trim()
    return ''
  })
  text = text.replace(/(?:^|\r?\n)统计口径：([^\r\n]+)/g, (_, scope) => {
    if (!notes.length) notes.push({ label: '统计范围', text: scope.trim(), updatedAt: legacyTime })
    return ''
  })
  if (legacyTime && !notes.length) notes.push({ label: '统计范围', text: '按当前保留的已入库记录统计。', updatedAt: legacyTime })
  return { text: text.trim(), notes }
}

export function formatDataUpdatedAt(value) {
  if (!value || typeof value !== 'string' || !Number.isFinite(Date.parse(value))) return ''
  return new Date(value).toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }) + '（北京时间）'
}

export function reportPath(runId, path) {
  if (!validRunId(runId) || typeof path !== 'string') return ''
  const prefix = `/api/agent/runs/${runId}/reports/`
  return path.startsWith(prefix) &&
    /^rp_[0-9a-f-]{36}$/i.test(path.slice(prefix.length))
    ? path
    : ''
}

export function answerReport(runId, text = '') {
  const paths =
    text.match(/\/api\/agent\/runs\/[0-9a-f-]+\/reports\/rp_[0-9a-f-]+/gi) || []
  return paths.map((path) => reportPath(runId, path)).find(Boolean) || ''
}

export function chinaTime(date = new Date()) {
  return new Date(date.getTime() + 8 * 3600000).toISOString().slice(0, 16)
}

export function yesterdayRange(now = new Date()) {
  const end = `${chinaTime(now).slice(0, 10)}T00:00`
  const start = chinaTime(new Date(Date.parse(`${end}+08:00`) - 86400000))
  return { start, end }
}

export function sevenDayRange(now = new Date()) {
  return { start: chinaTime(new Date(now.getTime() - 7 * 86400000)), end: chinaTime(now) }
}

export function buildReportMessage(
  { videoId, start, end, focus = '' },
  now = new Date()
) {
  if (
    !/^[1-9]\d{0,18}$/.test(videoId) ||
    (videoId.length === 19 && videoId > '9223372036854775807')
  ) {
    throw new Error('请输入有效的视频或直播间 ID')
  }
  const parse = (value) => {
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) return NaN
    const ms = Date.parse(`${value}+08:00`)
    return Number.isFinite(ms) && new Date(ms + 8 * 3600000).toISOString().slice(0, value.length) === value ? ms : NaN
  }
  const from = parse(start),
    to = parse(end)
  if (
    !Number.isFinite(from) ||
    !Number.isFinite(to) ||
    to <= from ||
    to - from > 30 * 86400000
  ) {
    throw new Error('请选择有效的起止时间，分析时长最多 30 天')
  }
  if (
    to > now.getTime() ||
    start.slice(0, 10) <
      chinaTime(new Date(now.getTime() - 30 * 86400000)).slice(0, 10)
  ) {
    throw new Error('请选择最近 30 天内已经结束的时段')
  }
  const message = `请分析视频${videoId}从${start.replace(
    'T',
    ' '
  )}到${end.replace('T', ' ')}的弹幕和评论，生成一份观众反馈报告。${
    focus.trim() ? `重点关注：${focus.trim()}` : ''
  }`
  if (message.length > 2000) throw new Error('问题不能超过 2000 字')
  return message
}

// Incremental SSE framing: CRLF, UTF-8 decoding and chunk boundaries are independent.
export function sseDecoder(consume) {
  let buffer = ''
  return {
    push(text) {
      buffer += text
      if (buffer.length > 1048576)
        throw new Error('进度消息过大，请刷新任务状态')
      let match
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const block = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        let event = 'message',
          id = '',
          data = []
        for (const line of block.split(/\r?\n/)) {
          const at = line.indexOf(':')
          if (at < 0 || line.startsWith(':')) continue
          const key = line.slice(0, at),
            value = line.slice(at + 1).replace(/^ /, '')
          if (key === 'event') event = value
          if (key === 'id') id = value
          if (key === 'data') data.push(value)
        }
        if (data.length)
          consume({ id, type: event, payload: JSON.parse(data.join('\n')) })
      }
    },
  }
}

export function progressLabel(data = {}) {
  const stage = {
    queued: '收到啦，我马上开始 (｡•̀ᴗ-)✧',
    running: '我先看看你的问题～',
    preparing: '让我确认一下，这次要分析哪些内容呀～',
    harness: '范围清楚啦，开始认真干活 (๑•̀ㅂ•́)و✧',
    finalizing: '快好啦，我再把结果整理一下～',
    waiting_operation: '分析还在进行中，稍等我一下呀～',
    analysis_waiting_auth: '需要重新登录一下哦，验证后我会接着处理～',
  }
  if (data.stage === 'feedback_analysis') {
    return { SNAPSHOTTING:'我在收集这次范围内的反馈～', COLLECTING:'我在收集这次范围内的反馈～', CLASSIFYING:'我在认真读大家的反馈，记好小笔记 φ(゜▽゜*)♪', ANALYZING:'我在认真读大家的反馈，记好小笔记 φ(゜▽゜*)♪', MERGING:'我来把相关观点整理到一起～', VALIDATING:'分析好啦，再让我核对一下数据 ( •̀ ω •́ )✧' }[data.phase] || '我在分析大家的反馈啦，数据多时会稍久一点～'
  }
  if (data.stage === 'tool_completed') return null
  if (data.stage === 'tool_failed')
    return '这一步遇到一点小问题，我试着调整一下～'
  if (data.stage === 'tool') {
    if (data.tool === 'analyze_video_content') return '我在认真看视频，稍等我一下呀～'
    if (['create_feedback_report','create_video_report'].includes(data.tool)) return '我来把分析结果整理成报告啦 (๑•̀ㅂ•́)و✧'
    return null
  }
  return stage[data.stage] || null
}
