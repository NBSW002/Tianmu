<template>
  <button v-if="allowedPage && !visible && !fullPage" title="打开个人助手" class="assistant-launcher" :class="{ 'assistant-launcher--home': isHome, 'assistant-launcher--expanded': isHome && !pageScrolled }" @click="assistant.show()" aria-label="打开个人助手"><EchoMark /><span v-if="isHome" class="assistant-launcher__label">个人助手</span></button>
  <Transition name="assistant-panel" :css="allowedPage"><section ref="panel" v-show="visible" class="assistant-host" :style="fullPage ? undefined : drawerStyle" :class="{ 'assistant-full': fullPage, 'assistant-drawer': !fullPage }" aria-label="个人助手" @keydown.esc="dismiss">
    <template v-if="!users.isLogin"><div class="assistant-login"><h2>你的个人助手</h2><p>{{ users.sessionExpired ? '登录状态已失效，请重新登录后继续。' : '登录后开始对话，历史记录仅对你可见。' }}</p><button @click="users.updateDialogVisible(true)">登录</button><button @click="assistant.open = false">收起</button></div></template>
    <template v-else>
      <p v-if="capabilityError" class="assistant-access-note" role="alert">{{ capabilityError }} <button @click="refreshCapabilities">重试</button></p>
      <p v-else-if="pageVideo && !readable && !checking" class="assistant-access-note">当前视频不可供助手读取，可继续通用对话。</p>
      <AgentWorkspace v-if="mounted" :key="ownerKey" managed :embedded="!fullPage" :creator="creator" :context-pending="checking || !!capabilityError" :video-title="pageMetadata.title || ''" :playback-ms="pageMetadata.playbackMs" :video-id="readable ? pageVideo : ''" @close="assistant.open = false" @expand="router.push({ path: '/agent', query: pageVideo ? { videoId: pageVideo } : {} })" />
    </template>
  </section></Transition>
</template>
<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import EchoMark from './EchoMark.vue'
import '@/assets/css/assistant-theme.css'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { useAssistantStore } from '@/store/assistant'
import { principalKey } from '@/lib/auth-session.mjs'
import { watchAssistantIdentity } from '@/lib/assistant-session.mjs'
import { getAssistantCapabilities } from '@/api/agent'
const AgentWorkspace = defineAsyncComponent(() => import('@/views/agent/AgentWorkspace.vue'))
function dismiss(event) { if (!event.isComposing && event.keyCode !== 229) assistant.open = false }
const route = useRoute(), router = useRouter(), users = useUserStore(), assistant = useAssistantStore()
const readable = ref(false), creator = ref(false), checking = ref(false), capabilityError = ref(''), mounted = ref(false)
const fullPage = computed(() => route.name === 'AgentWorkspace')
const isHome = computed(() => route.name === 'Index')
const pageScrolled = ref(window.scrollY > 360)
function measureScroll() { pageScrolled.value = window.scrollY > 360 }
const allowedPage = computed(() => route.name !== 'TraceWorkspace')
const visible = computed(() => allowedPage.value && (fullPage.value || assistant.open))
const ownerKey = computed(() => principalKey(users.user))
const accountKey = computed(() => String(users.user.userId || '') + ':' + String(users.user.token || ''))
const pageVideo = computed(() => String(route.params.videoId || (fullPage.value ? route.query.videoId || '' : '')))
const pageMetadata = computed(() => assistant.page?.videoId === pageVideo.value ? assistant.page : {})
const panel = ref(null), drawerStyle = ref({})
let playerObserver, pageObserver, geometryFrame
function measureDrawer() {
  if (!visible.value || fullPage.value) return
  cancelAnimationFrame(geometryFrame)
  geometryFrame = requestAnimationFrame(() => {
    const player = document.querySelector('.videoDetail .player-viewport')
    const available = player ? document.documentElement.clientWidth - player.getBoundingClientRect().right - 16 : 0
    const width = Math.min(available, Math.max(640, window.innerWidth * 0.38))
    drawerStyle.value = player && available >= 520 && window.innerWidth >= 1000
      ? { width: `${Math.floor(width)}px`, '--assistant-history-width': '176px' }
      : {}
    if (player && !playerObserver) {
      pageObserver?.disconnect()
      playerObserver = new ResizeObserver(measureDrawer)
      playerObserver.observe(player)
    }
  })
}
function observePage() {
  playerObserver?.disconnect(); playerObserver = null
  pageObserver?.disconnect()
  if (!visible.value || fullPage.value) return
  measureDrawer()
  if (pageVideo.value) {
    pageObserver = new MutationObserver(measureDrawer)
    pageObserver.observe(document.getElementById('app'), { childList: true, subtree: true })
  }
}
function outside(event) {
  if (visible.value && !fullPage.value && !panel.value?.contains(event.target)
      && !event.target.closest('.assistant-launcher,.el-overlay,.el-popper')) assistant.open = false
}
onMounted(() => { document.addEventListener('pointerdown', outside); window.addEventListener('resize', measureDrawer); window.addEventListener('scroll', measureScroll, { passive: true }) })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', measureDrawer); window.removeEventListener('scroll', measureScroll); playerObserver?.disconnect(); pageObserver?.disconnect(); cancelAnimationFrame(geometryFrame) })
watch([visible, pageVideo], () => nextTick(observePage), { immediate: true })
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
watchAssistantIdentity(users, assistant, () => { mounted.value = false })
watch([pageVideo, accountKey], refreshCapabilities, { immediate: true })
watch([visible, accountKey], () => { if (visible.value && users.isLogin) mounted.value = true }, { immediate: true })
watch(() => route.query.assistant, value => { if (value === '1') assistant.show() }, { immediate: true })
</script>
<style scoped>
.assistant-launcher { position:fixed;right:var(--page-tools-right);bottom:var(--page-tools-bottom);z-index:2100;border:0;border-radius:28px;width:var(--page-tools-size);height:var(--page-tools-size);font-size:26px;display:grid;place-items:center;background:var(--workbench-ink);color:var(--workbench-raised);box-shadow:var(--workbench-ring-shadow);cursor:pointer }
.assistant-host {position:fixed;right:24px;bottom:24px;width:440px;height:min(720px,calc(100dvh - 48px));z-index:2101;background:var(--workbench-raised);border:1px solid var(--workbench-border);border-radius:18px;box-shadow:var(--workbench-whisper);overflow:hidden;display:flex;flex-direction:column}
.assistant-host-toolbar {display:flex;gap:12px;align-items:center;padding:8px 16px;background:var(--workbench-sidebar);font-size:12px;color:var(--workbench-muted)}
.assistant-host-toolbar span {flex:1}.assistant-host button {cursor:pointer}.assistant-host-toolbar button {border:0;background:transparent;color:var(--workbench-accent)}
.assistant-access-note {margin:0;padding:8px 16px;font-size:12px;background:var(--workbench-soft);color:var(--workbench-ink)}
.assistant-host :deep(.agent-workspace) {flex:1;min-height:0;height:auto;width:100%}
.assistant-host :deep(.workspace-main) {min-width:0;min-height:0}
.assistant-drawer {right:0;bottom:0;width:min(960px,60vw);height:100dvh;border-radius:18px 0 0 18px}
.assistant-full {inset:0;width:100%;height:100dvh;border:0;border-radius:0}
.assistant-login {padding:30px}.assistant-login button {margin:12px 12px 0 0}
@media(max-width:600px){.assistant-host {inset:8px;width:auto;height:calc(100dvh - 16px)}.assistant-full{inset:0;height:100dvh}}
@media(min-width:601px) and (max-width:999px){.assistant-drawer{width:min(720px,92vw);}}
.assistant-launcher:hover {background:var(--workbench-accent)}
.assistant-panel-enter-active,.assistant-panel-leave-active {transition:transform 250ms var(--workbench-ease),opacity 250ms var(--workbench-ease)}
.assistant-panel-enter-from,.assistant-panel-leave-to {transform:translateX(24px);opacity:0}
@media(prefers-reduced-motion:reduce){.assistant-panel-enter-active,.assistant-panel-leave-active{transition:none}}
</style>
