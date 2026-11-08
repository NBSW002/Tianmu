<script setup>
import { playbackChart } from '@/agent/playback-chart.mjs'
import { computed } from 'vue'
const props = defineProps({ report: { type: Object, required: true } })
const chartData = computed(() => props.report.playback ? playbackChart(props.report.playback) : null)
const videoLink = (report, ms) => `/video/${encodeURIComponent(report.scope.video_id)}${ms == null ? '' : `?t=${ms / 1000}`}`
const nearbyMoments = (report, topic) => {
  const v = report.videoDetails
  if (!v?.moments?.length) return []
  if (Array.isArray(topic.selectedFrameMs)) return topic.selectedFrameMs.map(at => v.moments.find(m => m.atMs === at)).filter(Boolean)
  const found = new Map()
  for (const item of topic.representatives || []) {
    const at = item.playbackMs
    if (at == null || at < v.startMs || at >= v.endMs) continue
    const nearest = v.moments.reduce((a, b) => Math.abs(a.atMs - at) <= Math.abs(b.atMs - at) ? a : b)
    if (Math.abs(nearest.atMs - at) > v.momentMatchMaxGapMs) continue
    found.set(nearest.atMs, nearest)
    if (found.size >= 2) break
  }
  return [...found.values()]
}
</script>

<template>
  <section class="feedback-report" aria-label="结构化观众反馈报告">
    <header class="report-header"><h2>这条视频，观众怎么说</h2><p v-if="report.scope" class="topic-count">{{ report.scope.start }} 至 {{ report.scope.end }}（北京时间，结束时刻不计入）</p><div class="metrics"><template v-if="report.method.raw_comments != null && report.method.raw_danmaku != null"><div><strong>{{ report.method.raw_danmaku }}</strong><span>条弹幕</span></div><div><strong>{{ report.method.raw_comments }}</strong><span>条评论及回复</span></div><div><strong>{{ report.method.raw_unique_audience }}</strong><span>位可识别观众</span></div></template><div v-else><strong>{{ report.method.raw }}</strong><span>条原始反馈</span></div><div><strong>{{ report.method.topics ?? report.method.clusters }}</strong><span>个主题</span></div></div><p>{{ report.summary }}</p></header>
    <details v-if="report.videoSummary"><summary>视频语境</summary><p>{{ report.videoSummary }}</p><small>{{ report.videoCoverage }}<template v-if="report.videoDetails"> · 采样 {{ report.videoDetails.sampledAtMs.length }} 个位置 · {{ report.videoDetails.transcriptAvailable ? '有音频转录' : '未提供音频转录' }}</template></small></details>
    <h3 v-if="report.topics?.length" class="section-heading">观众关注的 {{ report.topics.length }} 个话题</h3>
    <article v-for="(topic, index) in report.topics" :key="topic.id">
      <div class="topic-heading"><span>{{ String(index + 1).padStart(2, '0') }}</span><h3>{{ topic.title }}</h3><span class="badge">{{ topic.commentCount + topic.danmakuCount }} 条桶内反馈</span></div>
      <p v-if="topic.recommendation" class="action-preview"><span>建议</span> {{ topic.recommendation }}</p>
      <details><summary>观众原话与完整建议</summary>
        <p class="topic-count">评论 {{ topic.commentCount }} 条 · 弹幕 {{ topic.danmakuCount }} 条（近似分桶统计）</p>
        <p>{{ topic.observation }}</p><p class="label">原话与来源</p>
        <blockquote v-for="item in topic.representatives" :key="item.id">
          <p>{{ item.text }}</p>
          <small>{{ item.source === 'COMMENT' ? '评论' : '弹幕' }} · 点赞 {{ item.likes ?? '未知' }} · 相同语境表达 {{ item.exactMessages }} 条<span v-if="item.playbackMs != null"> · 播放 {{ (item.playbackMs / 1000).toFixed(1) }} 秒</span><span v-if="item.excerpt"> · 节选</span></small>
          <p v-if="item.context" class="context">回复语境：{{ item.context }}</p>
          <a v-if="item.playbackMs != null && report.scope?.video_id" :href="videoLink(report, item.playbackMs)" target="_blank" rel="noopener noreferrer">查看视频对应位置 ↗</a>
        </blockquote>
        <div v-if="nearbyMoments(report, topic).length" class="context-box"><p class="label">{{ Array.isArray(topic.selectedFrameMs) ? "与主题相关的采样画面" : "原话播放位置附近的采样画面" }}</p><p v-for="moment in nearbyMoments(report, topic)" :key="moment.atMs"><a :href="videoLink(report, moment.atMs)" target="_blank" rel="noopener noreferrer">{{ (moment.atMs / 1000).toFixed(1) }} 秒 ↗</a> {{ moment.observation }}</p><small>仅供画面语境参考，不证明声音或连续动作。</small></div>
        <div v-if="topic.recommendation" class="action"><p class="label">可以怎么做</p><p>{{ topic.recommendation }}</p></div>
      </details>
    </article>
    <details v-if="report.otherTopics?.length"><summary>其他话题 · {{ report.otherTopics.length }} 项</summary><p v-for="topic in report.otherTopics" :key="topic.id">{{ topic.title }} · 评论 {{ topic.commentCount }} 条 · 弹幕 {{ topic.danmakuCount }} 条</p></details>
    <details v-if="report.unclassifiedExamples?.length" open><summary>尚未成组的反馈 · {{ report.method.unclassified }} 条（展示最多 3 条）</summary><blockquote v-for="item in report.unclassifiedExamples" :key="item.id"><p>{{ item.text }}</p><small>{{ item.source === 'COMMENT' ? '评论' : '弹幕' }} · 点赞 {{ item.likes ?? '未知' }}</small><p v-if="item.context" class="context">回复语境：{{ item.context }}</p></blockquote></details>
    <section v-if="report.playback" class="chart"><p class="label">播放位置</p><h3>弹幕集中在哪些片段</h3><p class="topic-count">按视频播放位置统计，包含作者弹幕，与发送日期不同。</p><div v-if="chartData" class="trend-plot"><div class="y-axis" aria-hidden="true"><span>{{ chartData.ceiling }}</span><span>{{ chartData.ceiling / 2 }}</span><span>0</span></div><svg class="playback-chart" viewBox="0 0 640 180" preserveAspectRatio="none" role="img" aria-label="弹幕播放位置分布，完整分段数据见下方"><path class="plot-grid" d="M0 8H640 M0 90H640 M0 172H640"/><path class="trend-area" :d="chartData.area"/><path class="trend-line" vector-effect="non-scaling-stroke" :d="chartData.line"/></svg><div class="x-axis" aria-hidden="true"><span v-for="(tick, i) in chartData.ticks" :key="i">{{ tick }}</span></div></div><p v-else>暂无可绘制的弹幕播放位置。</p><p class="topic-count">{{ report.playback.omitted }} 条位置缺失或超出范围，未绘制。<span v-if="!report.playback.knownRange">未取得视频时长，范围截至已记录的最晚位置。</span></p><details v-if="report.playback.counts.length"><summary>查看分段数据</summary><p v-for="(count, i) in report.playback.counts" :key="i">{{ ((report.playback.startMs + i * report.playback.bucketMs) / 1000).toFixed(1) }}–{{ (Math.min(report.playback.endMs, report.playback.startMs + (i + 1) * report.playback.bucketMs) / 1000).toFixed(1) }} 秒：{{ count }} 条</p></details></section>
    <details v-if="report.method.raw_audience && report.topics?.length" class="chart"><summary>查看话题占比</summary><p class="topic-count">按全部观众反馈为分母，桶内数量为近似分配；未归类和过滤记录不属于任何主题。</p><div v-for="topic in report.topics" :key="topic.id"><p>{{ topic.title }} · {{ topic.commentCount + topic.danmakuCount }} 条 · {{ (100 * (topic.commentCount + topic.danmakuCount) / report.method.raw_audience).toFixed(1) }}%</p><div class="bar-track"><span :style="{ width: `${100 * (topic.commentCount + topic.danmakuCount) / report.method.raw_audience}%` }"></span></div></div></details>
    <details v-if="report.daily && Object.keys(report.daily).length"><summary>按发送日期查看反馈</summary><p class="topic-count">按反馈发送日期统计，与视频播放位置不同；未记录日期的反馈不计入。</p><p v-for="(count, date) in report.daily" :key="date">{{ date }}：{{ count }} 条</p></details>
    <p v-if="report.method.statistics_available === false" class="topic-count">本次材料未保存完整时间统计，无法展示时间图表。</p>
    <footer><strong>数据说明</strong><p>内容根据代表反馈归纳，相关数量为近似统计。<template v-if="report.videoSummary"> 视频分析依据采样画面。</template></p></footer>
  </section>
</template>

<style scoped>
.feedback-report{padding:24px;color:var(--workbench-text,#242321);background:var(--workbench-raised,#faf9f5);font-size:14px;line-height:1.8;overflow-wrap:anywhere}.feedback-report h2{font-size:24px;margin:0 0 12px}.feedback-report h3{font-size:18px;margin:0}.feedback-report p{white-space:pre-wrap}.report-header{padding:24px;background:#e8e6dc;border-radius:18px}.metrics{display:flex;flex-wrap:wrap;gap:28px}.metrics div{display:grid}.metrics strong{font-size:23px}.metrics span{font-size:12px;color:#5e5d59}.section-heading{margin-top:24px!important}.feedback-report article{border-top:1px solid var(--workbench-border,#e8e6dc);padding:20px 0}.topic-heading{display:flex;gap:12px;align-items:baseline;flex-wrap:wrap}.topic-heading>span:first-child{font-size:12px;color:var(--workbench-accent,#c96442)}.topic-heading h3{flex:1}.badge{font-size:12px;background:#e8e6dc;border-radius:20px;padding:4px 9px}.action-preview span,.label{color:#a55237;font-size:12px}.action{padding:16px 20px;border-radius:12px;background:#f1e2d6}.context-box{padding:16px 20px;border-radius:12px;background:#e9eee4;margin:18px 0}.chart{padding:20px;border:1px solid #e8e6dc;border-radius:16px;margin:24px 0}.trend{height:150px;display:flex;align-items:end;gap:3px;border-bottom:1px solid #ccc}.trend span{flex:1;background:#c96442;border-radius:3px 3px 0 0}.bar-track{height:6px;background:#e8e6dc;border-radius:4px}.bar-track span{display:block;height:100%;background:#c96442;border-radius:4px}.topic-count,small,.context,footer{color:var(--workbench-muted,#727168);font-size:12px}.feedback-report summary{cursor:pointer;color:var(--workbench-accent,#a55237)}blockquote{border-left:2px solid var(--workbench-accent,#c96442);padding-left:14px;margin:18px 0}blockquote p{margin:6px 0}a{color:#a55237}footer{border-top:1px solid var(--workbench-border,#e8e6dc);padding-top:18px}
.trend-plot{position:relative;padding-left:34px;margin-top:20px}.playback-chart{display:block;width:100%;height:150px;overflow:visible}.plot-grid{fill:none;stroke:#e8e6dc;stroke-width:1;stroke-dasharray:3 5;vector-effect:non-scaling-stroke}.trend-area{fill:#c96442;fill-opacity:.14}.trend-line{fill:none;stroke:#c96442;stroke-width:2.5;stroke-linecap:round;stroke-linejoin:round}.y-axis{position:absolute;left:0;top:7px;height:136px;width:26px;display:flex;flex-direction:column;justify-content:space-between;text-align:right}.x-axis{display:flex;justify-content:space-between;margin-top:8px}.x-axis,.y-axis{font-size:10px;color:#87867f;font-variant-numeric:tabular-nums;line-height:1}
</style>
