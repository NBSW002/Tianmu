<template>
  <section class="content-index" aria-label="视频内容索引">
    <div class="index-heading"><div class="index-context"><strong :title="videoTitle || '当前视频'">{{ videoTitle || '当前视频' }}</strong><span role="status">{{ label }}</span></div>
      <button :disabled="loading" @click="refresh" aria-label="刷新视频内容状态" title="刷新状态">↻</button>
      <button v-if="active" :disabled="loading" @click="change('DELETE')">取消处理</button>
      <button v-else-if="state.status !== 'READY'" :disabled="loading" @click="change('POST')">{{ state.status === 'NOT_INDEXED' ? '准备内容' : '重新处理' }}</button>
      <button type="button" aria-label="移除当前视频上下文" @click="$emit('remove')">×</button>
    </div>
    <progress v-if="active" :max="state.total || 1" :value="state.completed || 0" />
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="state.errorCode">{{ failureLabel }}</p>
    <details v-if="state.status === 'READY'"><summary>查看 {{ state.chunks?.length || 0 }} 个时间片段</summary>
      <article v-for="chunk in state.chunks" :key="chunk.ordinal"><strong>{{ time(chunk.startMs) }}–{{ time(chunk.endMs) }}</strong>
        <small>{{ chunk.transcript ? '画面采样与语音转写' : '仅画面采样 · 无语音转写' }}</small><p>{{ chunk.text }}</p>
        <details v-if="chunk.transcript"><summary>本段转写（时间范围对应整段）</summary><p>{{ chunk.transcript }}</p></details>
      </article>
    </details>
  </section>
</template>

<script setup>
/* global defineEmits, defineProps */
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { videoContentIndex } from '@/api/agent';
defineEmits(['remove']);
const props = defineProps({ videoId: { type: String, required: true }, videoTitle: { type: String, default: '' } });
const state = ref({ status: 'NOT_INDEXED' }), loading = ref(false), error = ref('');
let controller, timer, generation = 0;
const active = computed(() => ['QUEUED', 'RUNNING'].includes(state.value.status));
const label = computed(() => ({ NOT_INDEXED: '尚未处理', QUEUED: '等待处理', RUNNING: `处理中 ${state.value.completed}/${state.value.total}`, READY: '内容已就绪', FAILED: '处理失败，可重试', STALE: '视频已更新，需重新处理', REVOKED: '视频已不可访问', CANCELLED: '已取消' }[state.value.status] || '读取状态中'));
const failureLabel = computed(() => ({ INDEX_INTERRUPTED: '上次处理被中断，可以重新处理。', VIDEO_CONTENT_UNAVAILABLE: '视频理解或媒体服务暂不可用，请检查服务配置后重试。', AUTH_CONTEXT_EXPIRED: '登录已过期，请重新登录后重试。', INDEX_SOURCE_CHANGED: '视频在处理期间发生变化，请重新处理。', QUEUE_FULL: '处理队列已满，请稍后重试。' }[state.value.errorCode] || '本次处理未完成，请稍后重试。'));
function time(ms) { const sec = Math.floor(ms / 1000); return `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')}`; }
async function change(method = 'GET') {
  clearTimeout(timer); controller?.abort(); controller = new AbortController(); const current = ++generation;
  loading.value = true; error.value = '';
  try { const result = await videoContentIndex(props.videoId, method, controller.signal); if (current === generation) state.value = result; }
  catch (e) { if (current === generation && e.name !== 'AbortError') error.value = e.message; }
  finally { if (current === generation) { loading.value = false; if (active.value && !error.value) timer = setTimeout(refresh, 5000); } }
}
function refresh() { return change('GET'); }
watch(() => props.videoId, () => { state.value = { status: 'NOT_INDEXED' }; refresh(); }, { immediate: true });
onBeforeUnmount(() => { generation++; clearTimeout(timer); controller?.abort(); });
</script>

<style scoped>
.content-index{margin:12px 0;padding:12px 16px;border:1px solid var(--workbench-border);border-radius:12px;background:var(--workbench-raised);color:var(--workbench-ink);font-size:13px}
.index-heading{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.index-heading span{flex:1;color:var(--workbench-muted)}.content-index button{background:var(--workbench-paper);border:1px solid var(--workbench-border);border-radius:6px;padding:5px 8px;cursor:pointer}.content-index small{display:block;color:var(--workbench-muted);margin-top:6px}.content-index progress{width:100%;margin-top:10px}.content-index article{border-top:1px solid var(--workbench-border);padding:12px 0}.content-index p{white-space:pre-wrap;line-height:1.7}.content-index summary{cursor:pointer;margin-top:8px}
.content-index{flex-shrink:0;margin:0;padding:8px 24px;border:0;border-bottom:1px solid var(--workbench-border);border-radius:0;background:var(--workbench-paper);font-size:12px;}
.index-heading{gap:8px;flex-wrap:nowrap;}
.index-context{display:flex;align-items:baseline;gap:10px;flex:1;min-width:0;}
.index-context strong{min-width:0;max-width:min(65%,430px);font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}
.index-context span{flex:none;font-size:11px;white-space:nowrap;}
.content-index button{border:0;background:transparent;padding:5px 6px;white-space:nowrap;}
.content-index>details{max-height:200px;overflow:auto;overscroll-behavior:contain;}
.content-index>details>summary{margin-top:2px;color:var(--workbench-muted);font-size:11px;}
@media(max-width:600px){.content-index{padding:8px 16px;}.index-context{gap:5px;}.index-context strong{max-width:55%;}}
</style>
