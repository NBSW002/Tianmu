<script setup>
/* global defineProps */
defineProps({ details:{type:Object,required:true} })
const tokens = value => value == null ? '—' : `${new Intl.NumberFormat('zh-CN').format(value)} Token`
</script>
<template>
  <section class="model-details" aria-label="模型情况">
    <h3>模型情况</h3>
    <dl>
      <div><dt>缓存命中<small v-if="details.cacheHits != null && details.cacheHitPartial"> · 部分</small></dt><dd>{{ tokens(details.cacheHits) }}</dd></div>
      <div><dt>缓存未命中<small v-if="details.cacheMisses != null && details.cacheMissPartial"> · 部分</small></dt><dd>{{ tokens(details.cacheMisses) }}</dd></div>
      <div><dt>模型</dt><dd :title="(details.modelIds || []).join(' / ')">{{ details.models.join(' / ') || '—' }}</dd></div>
      <div><dt>调用类型</dt><dd>{{ details.types.join(' / ') || '—' }}</dd></div>
      <div><dt>模型调用</dt><dd>{{ details.count }} 次</dd></div>
      <div v-if="details.skillCalls" class="named-calls"><dt>Skill 调用<span>{{ details.skillCalls }} 次</span></dt><dd v-if="details.skillNames?.length"><ul><li v-for="call in details.skillNames" :key="call.name"><span>{{ call.name }}</span><small v-if="call.count > 1">×{{ call.count }}</small></li></ul></dd></div>
      <div v-if="details.toolCalls" class="named-calls"><dt>Tool 调用<span>{{ details.toolCalls }} 次</span></dt><dd v-if="details.toolNames?.length"><ul><li v-for="call in details.toolNames" :key="call.name"><span>{{ call.name }}</span><small v-if="call.count > 1">×{{ call.count }}</small></li></ul></dd></div>
      <div v-if="details.compression !== '—'"><dt>上下文压缩</dt><dd>{{ details.compression }}</dd></div>
    </dl>
  </section>
</template>
<style scoped>
.model-details{padding:16px;min-width:0;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{font-size:13px;font-weight:600;margin:0 0 12px;}dl{margin:0;}dl>div{display:flex;justify-content:space-between;align-items:baseline;gap:10px;padding:6px 0;font-size:11px;}dt{color:var(--workbench-muted);flex-shrink:0;}dd{margin:0;text-align:right;overflow-wrap:anywhere;min-width:0;font-variant-numeric:tabular-nums;}small{font-size:10px;}
dl>.named-calls{display:block;border-top:1px solid var(--workbench-border-subtle);margin-top:6px;padding-top:10px;}.named-calls dt{display:flex;justify-content:space-between;gap:10px;}.named-calls dt>span{color:var(--workbench-ink);}.named-calls dd{text-align:left;margin-top:6px;}ul{list-style:none;margin:0;padding:0;}li{display:flex;justify-content:space-between;gap:8px;padding:3px 0;font-size:11px;line-height:1.5;}li>span{overflow-wrap:anywhere;}li>small{white-space:nowrap;color:var(--workbench-muted);}
</style>
