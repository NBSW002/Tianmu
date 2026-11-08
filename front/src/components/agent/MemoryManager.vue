<template>
  <section ref="panel" class="memory-panel" role="dialog" aria-modal="true" aria-label="个人记忆管理" @keydown.esc.stop.prevent="$emit('close')" @keydown.tab="trap">
    <header><div><h2>你的记忆</h2><p>保留有用的事实、偏好和目标，也随时可以改变。</p></div><button ref="closeButton" type="button" aria-label="关闭记忆管理" @click="$emit('close')">×</button></header>
    <div class="memory-body">
      <p v-if="notice" role="status">{{ notice }}</p>
      <template v-if="settings">
        <div class="memory-settings"><label>助手记忆 <select :value="settings.mode" :disabled="busy" @change="mode($event.target.value)"><option value="ON">使用并允许保存</option><option value="READ_ONLY">仅使用已有记忆</option><option value="OFF">暂停使用和保存</option></select></label>
          <label><input type="checkbox" :checked="settings.autoExtract" :disabled="busy || settings.mode !== 'ON'" @change="automatic($event.target.checked)">后台整理有用的事实、偏好和目标</label>
          <small>暂停后仍可在这里查看和删除。自动整理在后台进行，不会逐条打断确认。</small></div>
        <div class="memory-filters"><label>范围 <select v-model="scopeType" :disabled="busy" @change="changeScope"><option value="USER">个人记忆</option><option v-if="videoId" value="VIDEO">当前视频</option><option v-for="topic in topics" :key="topic.id" :value="topic.id">{{ topic.name }}</option></select></label><input v-model="query" aria-label="搜索记忆" placeholder="搜索记忆" maxlength="128" @keyup.enter="refresh"><button :disabled="busy" @click="refresh">查找</button></div>
        <form class="memory-editor" @submit.prevent="save"><label>{{ editing ? '修改记忆' : '新增记忆' }}<textarea v-model="text" rows="3" maxlength="1024" placeholder="例如：解释概念时，请先举一个简单例子。" :disabled="busy || settings.mode !== 'ON'" /></label>
          <div><select v-model="kind" aria-label="记忆类型" :disabled="busy"><option value="PREFERENCE">偏好</option><option value="GOAL">持续目标</option><option value="PROFILE">个人事实</option></select><button type="submit" :disabled="busy || !text.trim() || settings.mode !== 'ON'">{{ editing ? '保存修改' : '记住这条' }}</button><button v-if="editing" type="button" :disabled="busy" @click="cancelEdit">取消</button></div>
        </form>
        <p v-if="!items.length && !busy" class="memory-empty">这里还没有相关记忆。你可以主动添加，也可以在对话里说“请记住”。</p>
        <article v-for="item in items" :key="item.id" class="memory-item"><p>{{ item.content.text }}</p><small>{{ kindNames[item.content.kind] || item.content.kind }} · {{ new Date(item.createdAt).toLocaleDateString('zh-CN') }} · 版本 {{ item.version }}</small>
          <div><button :disabled="busy || settings.mode !== 'ON'" @click="edit(item)">修改</button><button :disabled="busy" @click="forget(item)">忘记</button></div>
        </article>
        <button v-if="nextBefore" :disabled="busy" @click="more">加载更早记忆</button>
        <footer><button v-if="!resetConfirm" :disabled="busy" @click="resetConfirm = true">清空全部记忆</button><div v-else><p>清空后，助手将不再使用这些记忆；旧聊天不会自动重新生成它们。</p><button :disabled="busy" @click="reset">确认清空</button><button :disabled="busy" @click="resetConfirm = false">取消</button></div></footer>
      </template>
    </div>
  </section>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useUserStore } from '@/store/user'
import { memoryRequest } from '@/api/agent'
import { onMemoryChanged } from '@/lib/memory-events.mjs'
const props=defineProps({videoId:{type:String,default:''}})
defineEmits(['close'])
const user=useUserStore(), panel=ref(null), closeButton=ref(null)
const settings=ref(null), topics=ref([]), items=ref([]), busy=ref(false), notice=ref(''), scopeType=ref('USER'), query=ref(''), nextBefore=ref(0)
const text=ref(''), kind=ref('PREFERENCE'), editing=ref(null), resetConfirm=ref(false)
const kindNames={PREFERENCE:'偏好',GOAL:'持续目标',PROFILE:'个人事实',EPISODE:'历史经历',EXPERIENCE:'经验',SESSION_SUMMARY:'会话摘要'}
let generation=0, controller=new AbortController(), pending=null
const stopMemoryChanges=onMemoryChanged(source=>{if(source==='local')return;generation++;controller.abort();controller=new AbortController();items.value=[];busy.value=false;cancelEdit();if(user.isLogin)load()})
const scope=()=>scopeType.value==='USER'?{type:'USER',key:'self'}:scopeType.value==='VIDEO'?{type:'VIDEO',key:props.videoId}:{type:'TOPIC',key:scopeType.value}
const request=(path,options={})=>memoryRequest(path,{...options,signal:controller.signal})
function key(signature){if(pending?.signature!==signature)pending={signature,key:crypto.randomUUID()};return pending.key}
async function act(operation){if(busy.value)return;busy.value=true;notice.value='';const current=generation;try{await operation(current)}catch(e){if(current===generation && e.name!=='AbortError')notice.value=e.status===404?'记忆服务暂未启用。':e.code==='MEMORY_VERSION_CONFLICT'?'这条记忆已经变化，请刷新后再修改。':e.message}finally{if(current===generation)busy.value=false}}
async function list(before=Number.MAX_SAFE_INTEGER,append=false,current=generation){const target=scope();const result=await request('/memories?'+new URLSearchParams({scopeType:target.type,scopeKey:target.key,limit:'20',beforeSequence:String(before),query:query.value}));if(current!==generation)return;items.value=append?[...items.value,...result.items]:result.items;nextBefore.value=result.moreAvailable?result.nextBeforeSequence:0}
async function load(){await act(async current=>{const [state,groups]=await Promise.all([request('/memory-settings'),request('/memory-topics')]);if(current!==generation)return;settings.value=state;topics.value=groups;await list(undefined,false,current)})}
const refresh=()=>act(current=>list(undefined,false,current))
const more=()=>act(current=>list(nextBefore.value,true,current))
function cancelEdit(){editing.value=null;text.value='';kind.value='PREFERENCE';pending=null}
function edit(item){editing.value=item;text.value=item.content.text;kind.value=['PREFERENCE','GOAL','PROFILE'].includes(item.content.kind)?item.content.kind:'PREFERENCE'}
function changeScope(){cancelEdit();refresh()}
async function save(){await act(async current=>{const body={expectedVersion:editing.value?.version || 0,content:{scope:scope(),kind:kind.value,text:text.value}};const path='/memories'+(editing.value?'/'+editing.value.id:'');const signature=path+JSON.stringify(body);await request(path,{method:editing.value?'PATCH':'POST',headers:{'Idempotency-Key':key(signature)},body:JSON.stringify(body)});if(current!==generation)return;cancelEdit();notice.value='已保存。';await list(undefined,false,current)})}
async function forget(item){await act(async current=>{const path=`/memories/${item.id}?expectedVersion=${item.version}`;await request(path,{method:'DELETE',headers:{'Idempotency-Key':key(path)}});if(current!==generation)return;pending=null;if(editing.value?.id===item.id)cancelEdit();notice.value='已忘记。';await list(undefined,false,current)})}
async function mode(value){await act(async current=>{const result=await request('/memory-settings',{method:'PATCH',body:JSON.stringify({expectedVersion:settings.value.version,mode:value})});if(current===generation)settings.value=result})}
async function automatic(value){await act(async current=>{const result=await request('/memory-settings/automatic',{method:'PUT',body:JSON.stringify({expectedVersion:settings.value.version,enabled:value})});if(current===generation)settings.value=result})}
async function reset(){await act(async current=>{await request('/memories/reset',{method:'POST',headers:{'Idempotency-Key':key('reset')}});if(current!==generation)return;pending=null;resetConfirm.value=false;cancelEdit();notice.value='已清空全部记忆。';await list(undefined,false,current)})}
function trap(event){const controls=[...panel.value.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled)')];const first=controls[0],last=controls.at(-1);if(event.shiftKey && document.activeElement===first){event.preventDefault();last?.focus()}else if(!event.shiftKey && document.activeElement===last){event.preventDefault();first?.focus()}}
watch(()=>user.user?.token,()=>{generation++;controller.abort();controller=new AbortController();items.value=[];topics.value=[];settings.value=null;busy.value=false;cancelEdit();if(user.isLogin)load()})
watch(()=>props.videoId,()=>{if(scopeType.value==='VIDEO'){generation++;controller.abort();controller=new AbortController();busy.value=false;items.value=[];cancelEdit();refresh()}})
onMounted(()=>{closeButton.value?.focus();load()})
onBeforeUnmount(()=>{stopMemoryChanges();generation++;controller.abort()})
</script>

<style scoped>
.memory-panel{position:absolute;inset:62px 12px 12px;z-index:45;display:flex;flex-direction:column;background:#faf9f5;color:#141413;border:1px solid #e8e6dc;border-radius:16px;box-shadow:0 4px 24px #1414130d;font-family:inherit;text-align:left}.memory-panel header{display:flex;justify-content:space-between;align-items:flex-start;padding:20px 24px;border-bottom:1px solid #e8e6dc}.memory-panel h2{font-size:22px;font-weight:500;margin:0}.memory-panel p{line-height:1.6;margin:8px 0}.memory-panel header p,.memory-panel small{color:#87867f;font-size:12px}.memory-body{padding:16px 24px;overflow:auto;flex:1;min-height:0}.memory-settings{display:grid;gap:12px;padding-bottom:18px;border-bottom:1px solid #e8e6dc}.memory-filters{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:18px 0}.memory-panel label{font-size:14px;color:#5e5d59}.memory-panel select,.memory-panel input:not([type=checkbox]),.memory-panel textarea{box-sizing:border-box;border:1px solid #d1cfc5;background:#f5f4ed;border-radius:8px;color:#141413;font:inherit;padding:9px 10px;max-width:100%}.memory-panel textarea{display:block;width:100%;margin:8px 0;resize:vertical}.memory-panel button{background:#e8e6dc;border:0;border-radius:8px;color:#4d4c48;min-height:36px;padding:7px 12px;cursor:pointer;font:inherit;font-size:13px}.memory-panel button:disabled{opacity:.5;cursor:default}.memory-editor>div{display:flex;gap:8px;align-items:center}.memory-editor button[type=submit]{background:#c96442;color:#faf9f5}.memory-item{padding:16px 0;border-bottom:1px solid #e8e6dc;overflow-wrap:anywhere}.memory-item>div{display:flex;gap:8px;margin-top:10px}.memory-empty{padding:24px 0;color:#87867f}.memory-panel footer{padding:24px 0}.memory-panel footer>div{padding:12px;background:#f3e2d7;border-radius:12px}.memory-panel :focus-visible{outline:2px solid #3898ec;outline-offset:2px}@media(max-width:480px){.memory-panel{inset:58px 6px 6px}.memory-body,.memory-panel header{padding:16px}}
</style>
