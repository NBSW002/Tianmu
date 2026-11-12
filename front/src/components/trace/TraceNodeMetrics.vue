<script setup>
/* global defineProps */
import { computed } from 'vue'
import { formatDuration, formatValue } from '@/trace/trace-data.mjs'
const props = defineProps({ node: { type: Object, required: true }, summaryOnly:Boolean, partial:Boolean })
const timing = computed(() => {
  const a = props.node.attributes || {}, first = a['model.first_token_ms'], response = a['model.response_ms']
  if (!Number.isFinite(first) || !Number.isFinite(response) || first < 0 || response <= 0 || first > response) return null
  return { first, rest: response - first, share: first / response * 100, total: response }
})
const tokens = computed(() => {
  const { inputTokens: input, outputTokens: output } = props.node
  return Number.isFinite(input) && Number.isFinite(output) && input >= 0 && output >= 0 && input + output > 0 ? { input, output, share: input / (input + output) * 100 } : null
})
</script>

<template>
  <div class="node-visuals">
    <dl class="metric-row">
      <div><dt>本次耗时</dt><dd data-testid="inspector-duration">{{ node.durationLabel || formatDuration(node.durationMs) }}</dd></div>
      <div v-if="node.totalTokens != null"><dt>Token 用量 <small v-if="partial">· 部分</small></dt><dd>{{ formatValue(node.totalTokens) }}</dd></div>
      <div v-if="node.turn != null"><dt>所在轮次</dt><dd>{{ node.turn === 0 ? '前置处理' : `第 ${node.turn} 轮` }}</dd></div>
    </dl>
    <section v-if="tokens && !summaryOnly" class="allocation" aria-label="Token 分布">
      <header><h3>Token 分布</h3></header>
      <div class="allocation-bar" role="img" :aria-label="`输入 ${tokens.input} Token，输出 ${tokens.output} Token`"><span :style="{ width: `${tokens.share}%` }"></span><span :style="{ width: `${100 - tokens.share}%` }"></span></div>
      <div class="allocation-labels"><span><i></i>输入 <strong>{{ formatValue(tokens.input) }}</strong></span><span><i></i>输出 <strong>{{ formatValue(tokens.output) }}</strong></span></div>
    </section>
    <section v-if="timing && !summaryOnly" class="allocation" aria-label="耗时分布">
      <header><h3>耗时分布</h3><span>{{ formatDuration(timing.total) }}</span></header>
      <div class="allocation-bar" role="img" :aria-label="`首 Token 等待 ${formatDuration(timing.first)}，后续响应 ${formatDuration(timing.rest)}`"><span :style="{ width: `${timing.share}%` }"></span><span :style="{ width: `${100 - timing.share}%` }"></span></div>
      <div class="allocation-labels"><span><i></i>首 Token <strong>{{ formatDuration(timing.first) }}</strong></span><span><i></i>后续响应 <strong>{{ formatDuration(timing.rest) }}</strong></span></div>
    </section>
  </div>
</template>

<style scoped>
.node-visuals {margin-top:16px;--segment-a:#697fb0;--segment-b:var(--workbench-accent);}
.metric-row {display:flex;flex-wrap:wrap;gap:12px;margin:0;padding:14px 0;border-block:1px solid var(--workbench-border);}
.metric-row>div {flex:1;min-width:64px;}
.metric-row dt {font-size:11px;color:var(--workbench-muted);}
.metric-row dd {margin:5px 0 0;font-size:18px;font-weight:500;font-variant-numeric:tabular-nums;overflow-wrap:anywhere;}
.allocation {margin-top:18px;}
header {display:flex;justify-content:space-between;align-items:center;gap:8px;}
h3 {font-size:12px;font-weight:500;margin:0;}
header>span {font-size:11px;color:var(--workbench-muted);}
.allocation-bar {display:flex;gap:2px;height:10px;border-radius:3px;overflow:hidden;margin:12px 0 9px;}
.allocation-bar span {background:var(--segment-a);min-width:0;}
.allocation-bar span+span {background:var(--segment-b);}
.allocation-labels {display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px 12px;font-size:11px;color:var(--workbench-muted);}
.allocation-labels>span {display:flex;align-items:center;gap:5px;}
.allocation-labels i {width:7px;height:7px;background:var(--segment-a);border-radius:2px;}
.allocation-labels>span+span i {background:var(--segment-b);}
.allocation-labels strong {font-weight:500;color:var(--workbench-ink);font-variant-numeric:tabular-nums;}
</style>
