<script setup>
/* global defineProps,defineEmits */
import TraceIcon from './TraceIcon.vue'
import { statusLabel } from '@/trace/trace-data.mjs'
defineProps({ calls:{type:Array,default:()=>[]},decision:String })
defineEmits(['select'])
</script>
<template>
  <section class="trail-card" aria-label="调用轨迹"><h3>调用轨迹</h3><ol><li><span class="step"><TraceIcon name="model" :size="14" /></span><div><strong>模型决策</strong><small>{{ decision || '未记录' }}</small></div></li><li v-for="call in calls" :key="call.id"><span class="step"><TraceIcon :name="call.status === 'SUCCEEDED' && !call.businessError ? 'check' : 'tool'" :size="14" /></span><div><button v-if="call.toolId" type="button" @click="$emit('select',call.toolId)">{{ call.label }}</button><strong v-else>{{ call.label }}</strong><small>{{ call.skill || call.name }}</small><span class="call-status" :class="{failed:call.businessError || ['FAILED','TIMED_OUT'].includes(call.status)}">{{ call.businessError ? '业务错误' : call.status ? statusLabel(call.status) : '未找到执行记录' }}</span></div></li></ol><p v-if="!calls.length">{{ ['ANSWER','FINAL_ANSWER'].includes(decision) ? '直接生成回答' : '暂无调用记录' }}</p></section>
</template>
<style scoped>
.trail-card{min-width:0;padding:16px;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{margin:0;font-size:13px;font-weight:500;}ol{list-style:none;margin:20px 0 0;padding:0;}li{position:relative;display:flex;gap:8px;padding-bottom:18px;}li:last-child{padding-bottom:0;}li:not(:last-child):before{content:'';position:absolute;left:10px;top:22px;bottom:0;width:1px;background:var(--workbench-border);}.step{display:grid;place-items:center;flex-shrink:0;width:22px;height:22px;background:var(--workbench-paper);border-radius:5px;color:var(--workbench-muted);}li>div{min-width:0;}strong,button{font:500 12px/1.6 var(--workbench-ui);color:var(--workbench-ink);}button{padding:0;border:0;background:none;text-align:left;cursor:pointer;}button:hover{text-decoration:underline;}small,.call-status{display:block;font-size:11px;color:var(--workbench-muted);margin-top:3px;overflow-wrap:anywhere;}.failed{color:var(--workbench-error);}p{font-size:12px;color:var(--workbench-muted);}
</style>
