<script setup>
/* global defineProps */
import { computed } from 'vue'
import { formatValue } from '@/trace/trace-data.mjs'
const props = defineProps({ node:{type:Object,required:true}, partial:Boolean })
const share = computed(() => {
  const { inputTokens:input,outputTokens:output } = props.node
  return Number.isFinite(input) && input>=0 && Number.isFinite(output) && output>=0 && input+output>0 ? (input === 0 ? 0 : output === 0 ? 100 : 100 * Math.sqrt(input) / (Math.sqrt(input) + Math.sqrt(output))) : null
})
</script>
<template>
  <section class="token-card" aria-label="Token 分布"><header><h3>Token 分布<small v-if="partial" class="partial"> · 部分</small></h3><span v-if="share != null">示意 · 非比例</span></header><p>{{ formatValue(node.totalTokens) }}<small>Token</small></p>
    <div v-if="share != null" class="token-bar" role="img" :aria-label="`示意图，分段长度不代表比例；输入 ${formatValue(node.inputTokens)}，输出 ${formatValue(node.outputTokens)} Token`"><i v-if="share > 0" :style="{width:`${share}%`}" /><i v-if="share < 100" :class="{ 'only-output': share === 0 }" :style="{width:`${100-share}%`}" /></div>
    <dl><div><dt><i />输入</dt><dd>{{ formatValue(node.inputTokens) }}</dd></div><div><dt><i />输出</dt><dd>{{ formatValue(node.outputTokens) }}</dd></div></dl>
  </section>
</template>
<style scoped>
.token-card{min-width:0;padding:16px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{margin:0;font-size:13px;font-weight:500;}p{font-size:28px;font-weight:500;margin:24px 0 20px;font-variant-numeric:tabular-nums;overflow-wrap:anywhere;}p small{font-size:12px;color:var(--workbench-muted);font-weight:400;margin-left:6px;}.token-bar{display:flex;gap:3px;height:14px;border-radius:4px;overflow:hidden;}.token-bar i,dt i{background:var(--trace-clay);}.token-bar i+i,dl>div+div dt i{background:var(--trace-charcoal);}dl{display:flex;justify-content:space-between;gap:12px;margin:16px 0 0;}dt{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--workbench-muted);}dt i{width:7px;height:7px;border-radius:2px;}dd{margin:6px 0 0;font-size:17px;font-weight:500;font-variant-numeric:tabular-nums;}
</style>
<style scoped>
header{display:flex;align-items:center;justify-content:space-between;gap:8px;}
header>span{font-size:11px;color:var(--workbench-muted);white-space:nowrap;}
.partial{font-size:11px;font-weight:400;color:var(--workbench-muted);}
.token-bar i.only-output{background:var(--trace-charcoal);}
</style>
