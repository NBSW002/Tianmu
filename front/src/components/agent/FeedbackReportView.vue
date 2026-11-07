<script setup>
defineProps({ report: { type: Object, required: true } })
</script>

<template>
  <section class="feedback-report" aria-label="结构化观众反馈报告">
    <header><h2>{{ report.title }}</h2><p>{{ report.summary }}</p><p v-if="report.scope" class="topic-count">{{ report.scope.start }} 至 {{ report.scope.end }}（北京时间，结束时刻不计入）</p></header>
    <details v-if="report.videoSummary"><summary>视频语境</summary><p>{{ report.videoSummary }}</p><small>{{ report.videoCoverage }}</small></details>
    <article v-for="(topic, index) in report.topics" :key="topic.id">
      <div class="topic-heading"><span>{{ String(index + 1).padStart(2, '0') }}</span><h3>{{ topic.title }}</h3></div>
      <p class="topic-count">评论 {{ topic.commentCount }} 条 · 弹幕 {{ topic.danmakuCount }} 条（簇成员统计）</p>
      <p>{{ topic.observation }}</p><p>{{ topic.recommendation }}</p>
      <details><summary>代表反馈与证据</summary>
        <blockquote v-for="item in topic.representatives" :key="item.id">
          <p>{{ item.text }}</p>
          <small>{{ item.source === 'COMMENT' ? '评论' : '弹幕' }} · 点赞 {{ item.likes ?? '未知' }} · 相同语境表达 {{ item.exactMessages }} 条<span v-if="item.playbackMs != null"> · 播放 {{ (item.playbackMs / 1000).toFixed(1) }} 秒</span><span v-if="item.excerpt"> · 节选</span></small>
          <p v-if="item.context" class="context">回复语境：{{ item.context }}</p>
        </blockquote>
      </details>
    </article>
    <details v-if="report.otherTopics?.length"><summary>其他话题 · {{ report.otherTopics.length }} 项</summary><p v-for="topic in report.otherTopics" :key="topic.id">{{ topic.title }} · 评论 {{ topic.commentCount }} 条 · 弹幕 {{ topic.danmakuCount }} 条</p></details>
    <details v-if="report.unclassifiedExamples?.length" open><summary>尚未成组的反馈 · {{ report.method.unclassified }} 条（展示最多 3 条）</summary><blockquote v-for="item in report.unclassifiedExamples" :key="item.id"><p>{{ item.text }}</p><small>{{ item.source === 'COMMENT' ? '评论' : '弹幕' }} · 点赞 {{ item.likes ?? '未知' }}</small><p v-if="item.context" class="context">回复语境：{{ item.context }}</p></blockquote></details>
    <footer><strong>处理口径</strong><p>原始 {{ report.method.raw }} 条，排除 {{ report.method.excluded }} 条，纳入 {{ report.method.eligible }} 条；联合聚类 {{ report.method.clusters }} 簇，未归类 {{ report.method.unclassified ?? 0 }} 条，模型读取 {{ report.method.representatives }} 条代表表达及有界语境。</p><p v-if="report.method.bucket_metric">相似度：{{ report.method.bucket_metric.mode === 'TASK_CENTERED_COSINE' ? '任务内中心化余弦' : '原始余弦' }}，门槛 {{ report.method.bucket_metric.threshold }}。</p><p>{{ report.method.interpretation }}</p><p>点赞未知时按频次及代表性排序。视频采样不等于完整逐帧或音频理解。</p></footer>
  </section>
</template>

<style scoped>
.feedback-report{padding:24px;color:var(--workbench-text,#242321);background:var(--workbench-raised,#faf9f5);font-size:14px;line-height:1.8;overflow-wrap:anywhere}.feedback-report h2{font-size:24px;margin:0 0 12px}.feedback-report h3{font-size:18px;margin:0}.feedback-report p{white-space:pre-wrap}.feedback-report article{border:1px solid var(--workbench-border,#e8e6dc);border-radius:14px;padding:20px;margin:22px 0}.topic-heading{display:flex;gap:12px;align-items:baseline}.topic-heading>span{font-size:12px;color:var(--workbench-accent,#c96442)}.topic-count,small,.context,footer{color:var(--workbench-muted,#727168);font-size:12px}.feedback-report summary{cursor:pointer;color:var(--workbench-accent,#a55237)}blockquote{border-left:2px solid var(--workbench-accent,#c96442);padding-left:14px;margin:18px 0}blockquote p{margin:6px 0}footer{border-top:1px solid var(--workbench-border,#e8e6dc);padding-top:18px}
</style>
