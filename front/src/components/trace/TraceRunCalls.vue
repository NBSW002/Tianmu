<script setup>
/* global defineProps,defineEmits */
import TraceIcon from './TraceIcon.vue'
import { statusLabel } from '@/trace/trace-data.mjs'
defineProps({ calls:{type:Array,default:()=>[]} })
defineEmits(['select'])
</script>
<template>
  <section class="run-calls" aria-label="调用轨迹">
    <h3>调用轨迹</h3>
    <p v-if="!calls.length">暂无调用记录</p>
    <ol v-else>
      <li v-for="call in calls" :key="`${call.kind}-${call.id}`">
        <span class="step" :class="{success:call.kind === 'tool' && call.status === 'SUCCEEDED' && !call.businessError}"><TraceIcon :name="call.kind === 'model' ? 'model' : call.status === 'SUCCEEDED' && !call.businessError ? 'check' : 'tool'" :size="14" /></span>
        <div><button type="button" @click="$emit('select',call.id)">{{ call.label }}</button><small>{{ call.name }}</small><span v-if="call.kind === 'tool'" class="call-status" :class="{failed:call.businessError || ['FAILED','TIMED_OUT'].includes(call.status)}">{{ call.businessError ? '业务错误' : call.status ? statusLabel(call.status) : '未记录' }}</span></div>
      </li>
    </ol>
  </section>
</template>
<style scoped>
.run-calls{padding:16px;min-width:0;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{margin:0;font-size:13px;font-weight:600;}p{font-size:12px;color:var(--workbench-muted);}ol{list-style:none;margin:20px 0 0;padding:0;}li{position:relative;display:flex;gap:8px;padding-bottom:18px;}li:last-child{padding-bottom:0;}li:not(:last-child):before{content:'';position:absolute;left:10px;top:22px;bottom:0;width:1px;background:var(--workbench-border);}.step{display:grid;place-items:center;flex-shrink:0;width:22px;height:22px;background:var(--workbench-paper);border-radius:5px;color:var(--workbench-muted);}.step.success{color:var(--workbench-success);}li>div{min-width:0;}button{font:500 12px/1.6 var(--workbench-ui);color:var(--workbench-ink);padding:0;border:0;background:none;text-align:left;cursor:pointer;}button:hover{text-decoration:underline;}small,.call-status{display:block;font-size:11px;color:var(--workbench-muted);margin-top:3px;overflow-wrap:anywhere;}.call-status{color:var(--workbench-success);}.failed{color:var(--workbench-error);}
</style>
