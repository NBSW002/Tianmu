<template><details ref="container" class="memory-usage" @toggle="opened"><summary>本轮记忆<span v-if="loaded"> · {{ items.length }} 条</span></summary><p v-if="notice" role="status">{{ notice }}</p><p v-else-if="loaded && !items.length">本轮没有使用长期记忆。</p><ul v-else><li v-for="item in items" :key="`${item.id}:${item.version}`">{{ item.available ? item.content?.text : '这条记忆已失效或被遗忘，不再展示正文。' }}<small>版本 {{ item.version }} · {{ item.initial ? '本轮召回' : '历史回答或工具读取' }}</small></li></ul><div v-if="jobs.length" class="memory-jobs"><div v-for="job in jobs" :key="job.job_id">自动整理 · {{ jobStates[job.state] || '等待更新' }} <router-link v-if="validTrace(job.trace_id)" :to="{path:'/trace',query:{traceId:job.trace_id}}">查看执行记录 ↗</router-link></div><button type="button" @click="reload">刷新整理进度</button></div></details></template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { memoryRequest } from '@/api/agent'
import { onMemoryChanged } from '@/lib/memory-events.mjs'
import { useUserStore } from '@/store/user'
const props=defineProps({runId:{type:String,required:true}})
const container=ref(null), jobs=ref([])
const jobStates={PENDING:'等待处理',RUNNING:'整理中',SUCCEEDED:'已完成',CANCELLED:'已取消',DEAD:'未完成'}
const validTrace=id=>typeof id==='string' && /^[a-f0-9]{32}$/.test(id)
const items=ref([]), notice=ref(''), loaded=ref(false)
const user=useUserStore()
let controller=null,generation=0
async function opened(event){if(!event.target.open || loaded.value)return;controller?.abort();controller=new AbortController();const current=++generation;try{const [result,tasks]=await Promise.all([memoryRequest(`/runs/${props.runId}/memory`,{signal:controller.signal}),memoryRequest(`/runs/${props.runId}/memory-jobs`,{signal:controller.signal})]);if(current!==generation)return;items.value=result.items || [];jobs.value=tasks || [];loaded.value=true;notice.value=''}catch(e){if(current===generation && e.name!=='AbortError')notice.value=e.status===404?'本轮没有可查看的记忆记录。':e.message}}
const stopMemoryChanges=onMemoryChanged(()=>{generation++;controller?.abort();items.value=[];jobs.value=[];loaded.value=false;notice.value='';if(container.value?.open)opened({target:{open:true}})})
function reload(){loaded.value=false;opened({target:{open:true}})}
watch(()=>[user.user?.token,props.runId],()=>{generation++;controller?.abort();items.value=[];jobs.value=[];loaded.value=false;notice.value=''})
onBeforeUnmount(()=>{stopMemoryChanges();generation++;controller?.abort()})
</script>
<style scoped>.memory-usage{margin-top:12px;font-size:12px;color:#87867f}.memory-usage summary{cursor:pointer}.memory-usage ul{list-style:none;padding:0}.memory-usage li{padding:10px 0;border-bottom:1px solid #e8e6dc;line-height:1.6;color:#5e5d59;overflow-wrap:anywhere}.memory-usage small{display:block;color:#87867f}.memory-jobs{display:grid;gap:8px;padding:10px 0}.memory-jobs a{color:#c96442;margin-left:12px}.memory-jobs button{justify-self:start;border:1px solid #d1cfc5;border-radius:8px;background:#faf9f5;color:#5e5d59;font:inherit;padding:6px 10px;cursor:pointer}</style>
