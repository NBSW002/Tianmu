<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'
import request from '@/lib/request'
const props = defineProps({ itemId: { type: [String, Number], required: true }, source: { type: String, default: 'COMMENT' } })
const users = useUserStore(), likes = ref(null), liked = ref(false), busy = ref(false)
let controller, generation = 0
watch(() => [String(props.itemId), props.source, users.user.userId, users.isLogin], async () => {
  controller?.abort(); controller = new AbortController(); const version = ++generation
  likes.value = null; liked.value = false; busy.value = users.isLogin
  if (!users.isLogin) return
  try {
    const value = await request({ url: '/feedback/reactions', params: { source: props.source, itemId: String(props.itemId) }, signal: controller.signal })
    if (version === generation) { likes.value = value.data.likes; liked.value = value.data.liked }
  } catch { /* Older deployments can omit reactions; do not display a fabricated count. */ }
  finally { if (version === generation) busy.value = false }
}, { immediate: true })
async function toggle() {
  if (!users.isLogin) { ElMessage.warning('请先登录后点赞'); return }
  if (busy.value) return
  const version = generation; busy.value = true
  try {
    const value = await request({ url: '/feedback/reactions', method: 'PUT', data: { source: props.source, itemId: String(props.itemId), liked: !liked.value }, signal: controller.signal })
    if (version === generation) { likes.value = value.data.likes; liked.value = value.data.liked }
  } catch (error) { if (version === generation) ElMessage.error(typeof error === 'string' ? error : '点赞未完成，请重试') }
  finally { if (version === generation) busy.value = false }
}
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>
<template><button type="button" class="feedback-like" :class="{ liked }" :aria-pressed="liked" :disabled="busy" @click="toggle"><span aria-hidden="true">♡</span> {{ liked ? '已赞' : '赞' }}<span v-if="likes != null"> {{ likes }}</span></button></template>
<style scoped>.feedback-like{border:0;padding:0 8px 0 0;background:none;color:inherit;font:inherit;cursor:pointer}.feedback-like.liked{color:#c96442}.feedback-like:disabled{cursor:wait;opacity:.6}.feedback-like:focus-visible{outline:2px solid #c96442;outline-offset:3px}</style>
