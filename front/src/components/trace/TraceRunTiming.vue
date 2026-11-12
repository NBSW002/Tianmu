<script setup>
/* global defineProps */
import { formatDuration } from '@/trace/trace-data.mjs'
defineProps({ duration:Number, modelCalls:Number, toolCalls:Number, foreground:Number, preparation:Number, background:Number })
</script>
<template>
  <section class="run-timing" aria-label="运行耗时"><h3>运行耗时</h3><p>{{ formatDuration(duration) }}<small>本次运行</small></p><dl><div><dt>记录的模型调用</dt><dd>{{ modelCalls }}</dd></div><div v-if="foreground != null"><dt>前台模型调用</dt><dd>{{ foreground }}</dd></div><div v-if="preparation != null"><dt>前置模型调用</dt><dd>{{ preparation }}</dd></div><div v-if="background != null"><dt>后台模型调用</dt><dd>{{ background }}</dd></div><div><dt>记录的工具调用</dt><dd>{{ toolCalls }}</dd></div></dl></section>
</template>
<style scoped>
.run-timing{padding:16px;min-width:0;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{font-size:13px;font-weight:500;margin:0;}p{font-size:26px;font-weight:500;margin:24px 0;font-variant-numeric:tabular-nums;}p small{display:block;margin-top:4px;font-size:11px;font-weight:400;color:var(--workbench-muted);}dl{margin:0;}dl>div{display:flex;justify-content:space-between;gap:8px;padding:7px 0;font-size:12px;}dt{color:var(--workbench-muted);}dd{margin:0;font-variant-numeric:tabular-nums;}
</style>
