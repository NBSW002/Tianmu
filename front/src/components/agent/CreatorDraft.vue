<template>
  <section class="creator-draft" aria-label="作者互动草稿">
    <header><strong>{{ kindLabel }}预览</strong><span>{{ stateLabel }}</span></header>
    <p class="draft-hint">请逐条核对内容{{ draft?.kind === 'DANMAKU' ? '和时间点' : '' }}，确认后以作者身份发送。</p>
    <p v-if="loading" role="status">正在读取草稿…</p>
    <template v-if="draft">
      <div v-if="displayTitle || authorName" class="draft-meta">{{ displayTitle }}<template v-if="displayTitle && authorName"> · </template><template v-if="authorName">作者：{{ authorName }}</template></div>
      <div class="draft-selection"><span>已选 {{ selected.length }} / {{ items.length }} 条</span><button v-if="!locked" type="button" class="text-button" @click="toggleAll">{{ selected.length === items.length ? '取消全选' : '全选' }}</button></div>
      <div v-for="(item, index) in items" :key="item.id" class="draft-item">
        <label class="draft-item-heading"><input v-model="item.selected" type="checkbox" :disabled="locked" /> 第 {{ index + 1 }} 条 <span>{{ item.text.length }} / {{ draft.kind === 'COMMENT' ? 500 : 80 }}</span></label>
        <textarea v-model="item.text" :maxlength="draft.kind === 'COMMENT' ? 500 : 80" :disabled="locked" :aria-label="`第${index + 1}条${kindLabel}`" rows="2" />
        <div v-if="draft.kind === 'DANMAKU'" class="draft-time"><label>播放时间（秒）<input v-model.number="item.seconds" type="number" min="0" step="0.001" :disabled="locked" :aria-label="`第${index + 1}条弹幕的播放时间`" /></label><button v-if="onVideo" type="button" class="text-button" @click="seek(item)">跳转查看</button><button v-if="onVideo && !locked" type="button" class="text-button" @click="useCurrentTime(item)">使用当前时间</button></div>
      </div>
      <div v-if="draft.state !== 'PUBLISHED'" class="draft-actions">
        <span>已选 {{ selected.length }} 条</span>
        <button type="button" :disabled="publishing || !selected.length" @click="publish">{{ publishing ? '正在发送…' : draft.state === 'PUBLISHING' ? '重试确认发送结果' : `确认发送 ${selected.length} 条${kindLabel}` }}</button>
      </div>
      <p v-if="draft.state === 'PUBLISHING'" class="draft-hint">发送结果尚未确认。内容已锁定，请使用上面的重试按钮，避免重新生成后重复发送。</p>
    </template>
    <p v-if="error" role="alert">{{ error }}</p>
    <button v-if="!draft && !loading" type="button" @click="load">重新读取草稿</button>
  </section>
</template>

<script setup>
/* global defineProps */
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { getCreatorDraft, confirmCreatorDraft } from '@/api/agent'
import { useUserStore } from '@/store/user'
import { useAssistantStore } from '@/store/assistant'
import { useRoute } from 'vue-router'
const props = defineProps({ runId: { type: String, required: true }, videoTitle: { type: String, default: '' } })
const user = useUserStore()
const assistant = useAssistantStore()
const route = useRoute()
const draft = ref(null), items = ref([]), loading = ref(false), publishing = ref(false), error = ref('')
let controller, epoch = 0
const kindLabel = computed(() => draft.value?.kind === 'DANMAKU' ? '弹幕' : '评论')
const stateLabel = computed(() => ({ DRAFT: '尚未发送', PUBLISHING: '待确认发送结果', PUBLISHED: '已发送' })[draft.value?.state] || '')
const locked = computed(() => publishing.value || draft.value?.state !== 'DRAFT')
const selected = computed(() => items.value.filter(item => item.selected).map(item => ({ id: item.id, text: item.text, playbackMs: draft.value?.kind === 'DANMAKU' ? Math.round(Number(item.seconds) * 1000) : null })))
const onVideo = computed(() => draft.value && String(route.params.videoId || '') === String(draft.value.videoId) && assistant.page?.videoId === String(draft.value.videoId))
const displayTitle = computed(() => props.videoTitle || (onVideo.value ? assistant.page?.title : '') || '')
const authorName = computed(() => draft.value && String(user.user.userId) === String(draft.value.userId) ? (user.user.nickname || '') : '')
function toggleAll() { const next = selected.value.length !== items.value.length; items.value.forEach(item => { item.selected = next }) }
function seek(item) { const seconds = Number(item.seconds); if (onVideo.value && Number.isFinite(seconds) && seconds >= 0) assistant.seekRequest = { videoId: String(draft.value.videoId), seconds } }
function useCurrentTime(item) { const time = assistant.page?.playbackMs; if (onVideo.value && Number.isFinite(time) && time >= 0) item.seconds = time / 1000 }
function stop() { epoch++; controller?.abort(); controller = null }
function apply(next) {
  draft.value = next
  items.value = (next?.items || []).map(item => ({ ...item, seconds: item.playbackMs == null ? null : item.playbackMs / 1000, selected: true }))
}
async function load() {
  stop(); const current = epoch; controller = new AbortController(); loading.value = true; error.value = ''
  try { const next = await getCreatorDraft(props.runId, controller.signal); if (current === epoch) apply(next) }
  catch (e) { if (current === epoch && e.name !== 'AbortError') error.value = e.message }
  finally { if (current === epoch) loading.value = false }
}
async function publish() {
  if (publishing.value || !selected.value.length) return
  const payload = selected.value
  stop(); const current = epoch; controller = new AbortController(); publishing.value = true; error.value = ''
  try { const next = await confirmCreatorDraft(props.runId, payload, controller.signal); if (current === epoch) { apply(next) } }
  catch (e) {
    if (current !== epoch || e.name === 'AbortError') return
    error.value = e.message
    // A timeout may occur after publication. Read the frozen server payload before enabling any further action.
    try { const next = await getCreatorDraft(props.runId, controller.signal); if (current === epoch) { apply(next) } }
    catch { if (current === epoch && draft.value) apply({ ...draft.value, state: 'PUBLISHING', items: payload }) }
  } finally { if (current === epoch) publishing.value = false }
}
watch(() => [props.runId, user.user.token], () => { apply(null); publishing.value = false; load() }, { immediate: true })
onBeforeUnmount(stop)
</script>

<style scoped>
.creator-draft{margin-top:18px;padding:16px 16px 0;border:1px solid var(--workbench-border,#e7e3db);border-radius:12px;background:var(--workbench-raised,#faf9f5);color:var(--workbench-text,#292925)}.creator-draft header,.draft-actions,.draft-selection,.draft-item-heading,.draft-time{display:flex;align-items:center;gap:10px}.creator-draft header,.draft-selection,.draft-actions{justify-content:space-between}.creator-draft header span,.draft-hint,.draft-meta,.draft-selection,.draft-item-heading,.draft-time{font-size:12px;color:var(--workbench-muted,#77766f)}.draft-hint{line-height:1.5;margin:8px 0}.draft-meta{overflow-wrap:anywhere}.draft-selection{padding:10px 0;border-bottom:1px solid var(--workbench-border,#e7e3db)}.draft-item{display:grid;gap:7px;padding:10px 0;border-bottom:1px solid var(--workbench-border,#e7e3db)}.draft-item-heading span{margin-left:auto}.draft-item textarea{box-sizing:border-box;width:100%;resize:vertical;padding:9px;border:1px solid var(--workbench-border,#e2ded6);border-radius:8px;font:inherit;line-height:1.5;background:var(--workbench-bg,#fff);color:inherit}.draft-time{flex-wrap:wrap}.draft-time input{width:76px;margin-left:6px;padding:5px;border:1px solid var(--workbench-border,#ddd);border-radius:6px}.draft-actions{position:sticky;bottom:0;margin:0 -16px;padding:12px 16px;background:var(--workbench-raised,#faf9f5);border-top:1px solid var(--workbench-border,#e7e3db);z-index:2}.creator-draft button{cursor:pointer}.draft-actions button{padding:9px 15px;border:0;border-radius:8px;background:#c96442;color:#fff;font-weight:600}.creator-draft button:disabled{opacity:.5;cursor:default}.text-button{padding:3px 0;border:0;background:transparent;color:#ad573b}.creator-draft [role=alert]{color:#ab4c47;font-size:13px}
</style>
