<script setup>
/* global defineProps */
defineProps({ details:{type:Object,required:true} })
const percent = value => value == null ? '—' : `${value.toFixed(1)}%`
const speed = value => value == null ? '—' : `${value.toFixed(1)} tok/s`
</script>
<template>
  <section class="model-details" aria-label="模型情况">
    <h3>模型情况</h3>
    <dl>
      <div><dt>缓存命中率<small v-if="details.cacheRate != null && details.cachePartial"> · 部分</small></dt><dd>{{ percent(details.cacheRate) }}</dd></div>
      <div><dt title="输出 Token / 完整调用秒数">平均 TPS<small v-if="details.tps != null && details.tpsPartial"> · 部分</small></dt><dd>{{ speed(details.tps) }}</dd></div>
      <div><dt>模型</dt><dd>{{ details.models.join(' / ') || '—' }}</dd></div>
      <div><dt>调用类型</dt><dd>{{ details.types.join(' / ') || '—' }}</dd></div>
      <div><dt>模型调用</dt><dd>{{ details.count }} 次</dd></div>
    </dl>
  </section>
</template>
<style scoped>
.model-details{padding:16px;min-width:0;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{font-size:13px;font-weight:500;margin:0 0 12px;}dl{margin:0;}dl>div{display:flex;justify-content:space-between;align-items:baseline;gap:10px;padding:6px 0;font-size:11px;}dt{color:var(--workbench-muted);flex-shrink:0;}dd{margin:0;text-align:right;overflow-wrap:anywhere;min-width:0;font-variant-numeric:tabular-nums;}small{font-size:10px;}
</style>
