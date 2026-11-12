<script setup>
/* global defineProps,defineEmits */
import { statusLabel } from '@/trace/trace-data.mjs'
defineProps({ calls:{type:Array,default:()=>[]} })
defineEmits(['select'])
</script>
<template>
  <section class="run-calls" aria-label="工具调用"><h3>工具调用 <small>{{ calls.length }} 次</small></h3><p v-if="!calls.length">未记录</p><ol v-else><li v-for="call in calls" :key="call.id"><button type="button" @click="$emit('select',call.id)">{{ call.label }}</button><small>{{ call.name }}</small><span :class="{error:call.businessError || ['FAILED','TIMED_OUT'].includes(call.status)}">{{ call.businessError ? '业务错误' : statusLabel(call.status) }}</span></li></ol></section>
</template>
<style scoped>
.run-calls{padding:16px;min-width:0;border:1px solid var(--workbench-border);border-radius:9px;background:var(--trace-card,var(--workbench-raised));}h3{margin:0;font-size:13px;font-weight:500;}h3 small{font-size:11px;font-weight:400;color:var(--workbench-muted);margin-left:4px;}p{font-size:12px;color:var(--workbench-muted);}ol{padding:0;margin:16px 0 0;list-style:none;}li{padding:8px 0;border-top:1px solid var(--workbench-border-subtle);}button{display:block;padding:0;border:0;background:none;color:var(--workbench-ink);font:500 12px/1.5 var(--workbench-ui);text-align:left;cursor:pointer;}button:hover{text-decoration:underline;}li small{display:block;color:var(--workbench-muted);font-size:11px;overflow-wrap:anywhere;}li span{font-size:11px;color:var(--workbench-success);}.error{color:var(--workbench-error);}
</style>
