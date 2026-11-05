<template>
  <button v-if="allowedPage && !visible && !fullPage" title="打开个人助手" class="assistant-launcher" @click="assistant.show()" aria-label="打开个人助手"><EchoMark /></button>
  <Transition name="assistant-panel" :css="allowedPage"><section v-show="visible" class="assistant-host" :class="{ 'assistant-full': fullPage, 'assistant-drawer': !fullPage }" aria-label="个人助手" @keydown.esc="assistant.open = false">
    <template v-if="!users.isLogin"><div class="assistant-login"><h2>你的个人助手</h2><p>登录后开始对话，历史记录仅对你可见。</p><button @click="users.updateDialogVisible(true)">登录</button><button @click="assistant.open = false">收起</button></div></template>
    <template v-else>
      <div class="assistant-host-toolbar"><span>{{ creator ? '创作者工具已启用' : '个人助手' }}</span><button v-if="!fullPage" @click="router.push({ path: '/agent', query: pageVideo ? { videoId: pageVideo } : {} })">展开</button><button v-else @click="router.push('/')">返回首页</button></div>
      <p v-if="capabilityError" class="assistant-access-note" role="alert">{{ capabilityError }} <button @click="refreshCapabilities">重试</button></p>
      <p v-else-if="pageVideo && !readable && !checking" class="assistant-access-note">当前视频不可供助手读取，可继续通用对话。</p>
      <AgentWorkspace v-if="mounted" :key="accountKey" managed :embedded="!fullPage" :creator="creator" :context-pending="checking || !!capabilityError" :video-title="pageMetadata.title || ''" :playback-ms="pageMetadata.playbackMs" :video-id="readable ? pageVideo : ''" @close="assistant.open = false" />
    </template>
  </section></Transition>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
import EchoMark from './EchoMark.vue'
import '@/assets/css/assistant-theme.css'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useAssistantStore } from '@/store/assistant'
import { getAssistantCapabilities } from '@/api/agent'
import AgentWorkspace from '@/views/agent/AgentWorkspace.vue'
const route = useRoute(), router = useRouter(), users = useUserStore(), assistant = useAssistantStore()
const readable = ref(false), creator = ref(false), checking = ref(false), capabilityError = ref(''), mounted = ref(false)
const fullPage = computed(() => route.name === 'AgentWorkspace')
const allowedPage = computed(() => route.name !== 'TraceWorkspace')
const visible = computed(() => allowedPage.value && (fullPage.value || assistant.open))
const accountKey = computed(() => String(users.user.userId || '') + ':' + String(users.user.token || ''))
const pageVideo = computed(() => String(route.params.videoId || (fullPage.value ? route.query.videoId || '' : '')))
const pageMetadata = computed(() => assistant.page?.videoId === pageVideo.value ? assistant.page : {})
let ticket = 0
async function refreshCapabilities() {
  const current = ++ticket
  if (assistant.page?.videoId !== pageVideo.value) assistant.page = null
  readable.value = false; creator.value = false; capabilityError.value = ''; checking.value = users.isLogin
  if (!users.isLogin) return
  try {
    const result = await getAssistantCapabilities(pageVideo.value)
    if (current === ticket) { creator.value = result.creatorTools === true; readable.value = result.videoContext === true }
  } catch (error) { if (current === ticket) capabilityError.value = error.message }
  finally { if (current === ticket) checking.value = false }
}
watch(accountKey, () => { assistant.reset(); mounted.value = false }, { flush: 'sync' })
watch([pageVideo, accountKey], refreshCapabilities, { immediate: true })
watch([visible, accountKey], () => { if (visible.value && users.isLogin) mounted.value = true }, { immediate: true })
watch(() => route.query.assistant, value => { if (value === '1') assistant.show() }, { immediate: true })
</script>
<style scoped>
.assistant-launcher { position:fixed;right:24px;bottom:24px;z-index:2100;border:0;border-radius:28px;width:56px;height:56px;font-size:26px;display:grid;place-items:center;background:var(--workbench-ink);color:var(--workbench-raised);box-shadow:var(--workbench-ring-shadow);cursor:pointer }
.assistant-host {position:fixed;right:24px;bottom:24px;width:440px;height:min(720px,calc(100dvh - 48px));z-index:2101;background:var(--workbench-raised);border:1px solid var(--workbench-border);border-radius:18px;box-shadow:var(--workbench-whisper);overflow:hidden;display:flex;flex-direction:column}
.assistant-host-toolbar {display:flex;gap:12px;align-items:center;padding:8px 16px;background:var(--workbench-sidebar);font-size:12px;color:var(--workbench-muted)}
.assistant-host-toolbar span {flex:1}.assistant-host button {cursor:pointer}.assistant-host-toolbar button {border:0;background:transparent;color:var(--workbench-accent)}
.assistant-access-note {margin:0;padding:8px 16px;font-size:12px;background:var(--workbench-soft);color:var(--workbench-ink)}
.assistant-host :deep(.agent-workspace) {flex:1;min-height:0;height:auto;width:100%}
.assistant-host :deep(.workspace-main) {min-width:0;min-height:0}
.assistant-drawer {right:0;bottom:0;width:min(1120px,96vw);height:100dvh;border-radius:18px 0 0 18px}
.assistant-full {inset:0;width:100%;height:100dvh;border:0;border-radius:0}
.assistant-login {padding:30px}.assistant-login button {margin:12px 12px 0 0}
@media(max-width:600px){.assistant-host {inset:8px;width:auto;height:calc(100dvh - 16px)}.assistant-full{inset:0;height:100dvh}.assistant-launcher{right:16px;bottom:16px}}
.assistant-launcher:hover {background:var(--workbench-accent)}
.assistant-panel-enter-active,.assistant-panel-leave-active {transition:transform 250ms var(--workbench-ease),opacity 250ms var(--workbench-ease)}
.assistant-panel-enter-from,.assistant-panel-leave-to {transform:translateX(24px);opacity:0}
@media(prefers-reduced-motion:reduce){.assistant-panel-enter-active,.assistant-panel-leave-active{transition:none}}
</style>
