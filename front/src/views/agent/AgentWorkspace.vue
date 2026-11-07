<template>
  <div class="agent-workspace" :class="{ embedded, 'history-open': navigationOpen, 'is-empty': !conversationTurns.length }" @keydown.esc="closeNavigation" @keydown.tab="cycleNavigation">
    <aside ref="historyNavigation" id="assistant-conversations" class="workspace-sidebar" aria-label="会话导航" :inert="embedded && !persistentHistory && !navigationOpen || undefined">
      <button class="history-close" type="button" aria-label="关闭会话历史" @click="hideNavigation">×</button>
      <a class="workspace-brand" href="/">EchoFrame<span>个人助手</span></a>
      <button class="new-analysis" :disabled="busy" @click="newAnalysis">＋ 开启新对话</button>
      <div class="history-heading">
        最近对话 <span>{{ history.length }}</span>
      </div>
      <p v-if="!history.length" class="history-empty">聊过的内容会留在这里，随时回来继续。</p>
      <nav aria-label="最近对话" class="history-list">
        <button
          v-for="item in history"
          :key="item.runId"
          :class="{ selected: activeRunId === item.runId }"
          :disabled="busy"
          @click="selectHistory(item)"
        >
          <span class="history-title">{{ item.title }}</span>
          <span class="history-meta"
            ><time>{{ formatDate(item.createdAt) }}</time><span>{{ statusLabel(item.status) }}</span></span
          >
        </button>
      </nav>
      <div class="sidebar-footer"><span class="online-dot" /> EchoFrame AI</div>
    </aside>
    <button v-if="navigationOpen" class="history-scrim" type="button" aria-label="关闭会话历史" @click="hideNavigation" />
    <main class="workspace-main" :inert="navigationOpen || undefined">
      <header class="workspace-header">
        <div class="assistant-identity"><span class="brand-symbol"><EchoMark /></span><strong>个人助手</strong></div>
        <div class="workspace-header-actions">
          <button ref="memoryToggle" type="button" class="header-action" aria-label="管理个人记忆" title="个人记忆" @click="memoryOpen = true"><TraceIcon name="layers" :size="18" /></button>
          <button ref="historyToggle" type="button" class="history-toggle" aria-label="打开会话历史" aria-controls="assistant-conversations" :aria-expanded="navigationOpen" @click="toggleNavigation"><TraceIcon name="list" :size="18" /></button>
          <button type="button" class="header-action" aria-label="新对话" title="新对话" :disabled="busy" @click="newAnalysis"><TraceIcon name="plus" :size="18" /></button>
          <button v-if="embedded" type="button" class="header-action" aria-label="展开个人助手" title="展开助手" @click="$emit('expand')"><TraceIcon name="fit" :size="18" /></button>
          <button v-if="embedded" type="button" class="header-action close-assistant" aria-label="关闭个人助手" @click="$emit('close')">×</button>
          <router-link v-else class="back-video" :to="form.videoId ? '/video/' + form.videoId : '/'">返回{{ form.videoId ? '视频' : '网站' }} ↗</router-link>
        </div>
      </header>
      <MemoryManager v-if="memoryOpen" :video-id="form.videoId" @close="closeMemory" />
      <VideoContentIndex v-if="form.videoId && !contextPending" :key="form.videoId" :video-id="form.videoId" :video-title="contextTitle" @remove="form.videoId = ''" />
      <div ref="conversationElement" class="conversation-scroll" :style="fadeStyle" @scroll.passive="trackConversationScroll" @wheel.passive="markConversationIntent" @touchstart.passive="markConversationIntent" @keydown="markConversationIntent">
        <div ref="conversationContent" class="workspace-content">
          <button v-if="historyCursor" class="text-button" :disabled="loadingHistory" @click="loadOlderMessages">{{ loadingHistory ? '正在读取…' : '加载更早的消息' }}</button>
          <section v-if="!conversationTurns.length" class="workspace-intro">
            <div class="welcome-symbol" aria-hidden="true"><EchoMark /></div>
            <h1>{{ form.videoId ? '这个视频，下一步怎么做？' : '今天想聊些什么？' }}</h1>
            <p>{{ creator ? '分析自己的视频、整理观众反馈，或直接提问。' : '解释概念、整理思路，或一起制定计划。' }}</p>
            <div class="suggestion-grid">
              <button
                v-for="item in suggestions"
                :key="item.text"
                @click="useSuggestion(item)"
              >
                <span class="suggestion-icon">{{ item.icon }}</span
                ><strong>{{ item.title }}</strong
                ><span>{{ item.description }}</span
                ><i>↗</i>
              </button>
            </div>
          </section>
          <details v-if="creator && form.videoId" class="workspace-tools" @toggle="toolsOpen = $event.target.open">
            <summary>更多工具<span>实时弹幕汇总 / 常见问答</span></summary>
            <template v-if="toolsOpen">
              <RoomAudience v-if="form.videoId" :video-id="form.videoId" />
              <QaKnowledge />
            </template>
          </details>
          <div v-if="notice" class="workspace-notice" role="alert">
            {{ notice
            }}<button
              v-if="(activeTurn || validRunId(currentQueryRun)) && !submitting && !loadingRun"
              @click="resume"
            >
              重新连接
            </button>
          </div>

          <section
            v-if="conversationTurns.length"
            ref="resultsElement"
            class="results"
            aria-label="个人助手对话"
          >
            <article v-for="turn in conversationTurns" :key="turn.localId || turn.runId" class="analysis-turn">
              <div class="user-question">
                <span>你</span>
                <p>{{ turn.message }}<small v-if="turn.videoId" class="turn-video"> · {{ turn.videoTitle || '视频 #' + turn.videoId }}{{ turn.playbackMs != null ? ' · ' + Math.floor(turn.playbackMs / 60000) + ':' + String(Math.floor(turn.playbackMs / 1000) % 60).padStart(2, '0') : '' }}</small></p>
              </div>
              <section v-if="turn.command === 'log'" class="assistant-result" aria-label="Trace ID 回复">
                <div class="result-heading"><span class="assistant-avatar" aria-hidden="true"><EchoMark /></span><strong>EchoFrame个人助手</strong></div>
                <p class="answer-text">{{ turn.text }}</p>
                <code v-if="turn.traceId" class="trace-id-reply">{{ turn.traceId }}</code>
                <router-link v-if="turn.href" :to="turn.href" target="_blank" rel="noopener" class="text-button">查看运行追踪 ↗</router-link>
              </section>
              <section v-else class="assistant-result">
                <div class="result-heading">
                  <span class="assistant-avatar" aria-hidden="true"><EchoMark /></span
                  ><strong>EchoFrame个人助手</strong
                  ><span
                    class="status-badge"
                    :class="(turn.snapshot?.status || '').toLowerCase()"
                    >{{ isPaused(turn) ? '已暂停' : statusLabel(turn.snapshot?.status) }}</span
                  >
                </div>
                <p v-if="turn.streamingText && !isTerminal(turn.snapshot?.status)" class="answer-text" aria-live="polite">{{ turn.streamingText }}</p>
                <MemoryUsage v-if="validRunId(turn.runId) && isTerminal(turn.snapshot?.status)" :run-id="turn.runId" />

                <div
                  v-if="!isTerminal(turn.snapshot?.status)"
                  class="working-message"
                  role="status"
                >
                  <span class="working-dot" />{{
                    turn.events.length
                      ? turn.events[turn.events.length - 1].label
                      : '正在准备回复…'
                  }}
                </div>
                <div v-if="isPaused(turn)" class="paused-actions"><button type="button" class="resume-analysis" @click="resumeAnalysis(turn)">验证登录并继续</button><button v-if="turn.runId === activeRunId" type="button" class="text-button" :disabled="cancelling" @click="cancel">{{ cancelling ? '正在结束…' : '结束任务' }}</button></div>
                <p v-if="turn.snapshot?.status === 'NEEDS_INPUT'" class="answer-text clarification">
                  {{ turn.snapshot.clarification }}
                </p>
                <p v-if="turn.snapshot?.answer" class="answer-text">
                  {{ displayAnswer(answerPresentation(turn.snapshot.answer).text) }}
                </p>
                <details v-if="answerPresentation(turn.snapshot?.answer).notes.length" class="answer-data-notes">
                  <summary>数据说明</summary>
                  <div v-for="(note, index) in answerPresentation(turn.snapshot.answer).notes" :key="index">
                    <span v-if="note.label">{{ note.label }}</span>
                    <p>{{ note.text }}</p>
                    <time v-if="formatDataUpdatedAt(note.updatedAt)">更新于 {{ formatDataUpdatedAt(note.updatedAt) }}</time>
                  </div>
                </details>
                <p v-if="turn.snapshot?.status === 'FAILED'" class="failure-message">
                  {{ failureLabel(turn.snapshot.errorCode) }}
                </p>
                <p v-if="turn.snapshot?.status === 'CANCELLED'" class="muted">
                  {{
                    turn.snapshot.stopReason === 'SUPERSEDED'
                      ? '任务已被新的请求替代。'
                      : '这次回复已停止。'
                  }}
                </p>
                <details v-if="turn.events.length" class="execution-details">
                  <summary>查看执行进度 <span>{{ turn.events.length }} 条记录</span></summary>
                <div v-if="turn.snapshot" class="execution-summary">
                  <span v-if="!isTerminal(turn.snapshot.status)"
                    >已等待 {{ elapsed(turn.waitClock) }}</span
                  ><span v-if="turn.snapshot.totalModelCalls"
                    >模型调用合计 {{ turn.snapshot.totalModelCalls }} 次</span
                  ><span v-if="turn.snapshot.steps">已执行 {{ turn.snapshot.steps }} 轮</span>
                  <span v-if="turn.events.some(event => event.analysisCalls !== undefined)">后台分析模型调用 {{ Math.max(...turn.events.map(event => event.analysisCalls || 0)) }} 次</span>
                </div>
                  <ol>
                    <li v-for="event in turn.events" :key="event.sequence">
                      <span>{{ event.label }}</span
                      ><time>{{ formatTime(event.at) }}</time>
                    </li>
                  </ol>
                </details>
                <CreatorDraft v-if="turn.snapshot?.status === 'SUCCEEDED' && turn.snapshot?.task?.specification?.type === 'creator.draft'" :run-id="turn.runId" />
                <div
                  v-if="turn.reportPath && turn.snapshot?.status === 'SUCCEEDED'"
                  class="report-panel"
                >
                  <div class="report-toolbar">
                    <div>
                      <strong>观众反馈报告</strong><span>HTML 报告 · 数据概览与内容观察</span>
                    </div>
                    <button
                      v-if="turn.reportHtml"
                      class="text-button"
                      @click="downloadReport(turn)"
                    >
                      下载报告 ↓
                    </button>
                  </div>
                  <p v-if="turn.reportLoading" class="report-state" role="status">正在加载报告…</p>
                  <div v-else-if="turn.reportError" class="report-state" role="alert">
                    {{ turn.reportError }}
                    <button class="text-button" @click="loadReport(turn)">重新加载</button>
                  </div>
                  <iframe
                    v-else-if="turn.reportHtml"
                    :srcdoc="reportDocument(turn)"
                    sandbox="allow-popups allow-popups-to-escape-sandbox"
                    referrerpolicy="no-referrer"
                    title="观众反馈报告预览"
                    class="report-preview"
                  />
                </div>
              </section>
            </article>
          </section>
        </div>
      </div>
      <div class="composer-wrap" :class="{ 'is-generating': running && !paused }">
        <Transition name="workbench-popover"><button v-if="showJump" type="button" class="jump-to-latest" @click="jumpToLatest"><TraceIcon name="down" :size="14" />回到最新</button></Transition>
        <p v-if="needsInput" class="reply-hint">助手需要你补充信息</p>
        <div v-if="turns.length && !busy && !needsInput" class="quick-suggestions" aria-label="常用提问">
          <button v-for="item in quickSuggestions" :key="item.text" type="button" @click="useSuggestion(item)">{{ item.title }} ↗</button>
        </div>
        <form class="chat-composer" @submit.prevent="submit">
          <label class="sr-only" for="assistant-message">给个人助手发消息</label>
          <textarea
            id="assistant-message"
            ref="composerInput"
            v-model="focus"
            :disabled="submitting || loadingRun"
            :maxlength="messageLimit"
            rows="2"
            :placeholder="
              needsInput
                ? '补充一下信息，我们接着聊…'
                : form.videoId ? '问问这个视频的内容、某个片段，或聊聊观众反馈…' : '提问、整理想法，或一起制定计划…'
            "
            @compositionstart="compositionGuard.start"
            @compositionend="compositionGuard.end"
            @blur="compositionGuard.reset"
            @keydown.enter.exact="composerEnter"
          />
          <div class="composer-actions">
            <span class="composer-hint"><i>✦</i><span>{{ paused ? '等待登录验证' : running ? '正在为你处理…' : form.videoId ? '当前视频' : '个人助手' }}</span></span>
            <div class="composer-controls">
              <ModelPicker v-if="modelChoices.length" v-model="selectedModel" :models="modelChoices" :disabled="submitting || running" :refreshing="modelsLoading" @refresh="refreshModels" />
              <button v-if="running && !paused" type="button" class="send-button stop-button" aria-label="停止生成" :disabled="cancelling" @click="cancel">■</button>
              <button v-else
              type="submit"
              class="send-button"
              :disabled="!focus.trim() || submitting || loadingRun || (running && !isLogCommand(focus))"
              :aria-label="submitting ? '正在发送' : '发送消息'"
            >
              {{ submitting ? '…' : '↑' }}
              </button>
            </div>
          </div>
          <p v-if="modelError" class="model-error" role="alert">{{ modelError }} <button type="button" class="text-button" :disabled="modelsLoading" @click="refreshModels">重试加载</button></p>
        </form>
        <p class="workspace-footnote">
          Enter 发送 / Shift + Enter 换行 <span>AI 内容供参考，请结合实际判断</span>
        </p>
      </div>
    </main>
  </div>
</template>
<script setup>
/* global defineProps, defineEmits */
import { mergeMessages, historicalTurns } from '@/agent/session-history.mjs';
import { storeToRefs } from 'pinia';
import { useAssistantStore } from '@/store/assistant';
import EchoMark from '@/components/agent/EchoMark.vue';
import TraceIcon from '@/components/trace/TraceIcon.vue';
import ModelPicker from '@/components/agent/ModelPicker.vue';
import { useConversationViewport } from '@/composables/useConversationViewport';
import { themeReportHtml } from '@/agent/report-theme.mjs';
import VideoContentIndex from '@/components/agent/VideoContentIndex.vue';
import { getAssistantModels } from '@/api/agent';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import RoomAudience from '@/components/agent/RoomAudience.vue';
import QaKnowledge from '@/components/agent/QaKnowledge.vue';
import MemoryManager from '@/components/agent/MemoryManager.vue';
import MemoryUsage from '@/components/agent/MemoryUsage.vue';
import CreatorDraft from '@/components/agent/CreatorDraft.vue';
import { createCompositionGuard } from '@/agent/composer-input.mjs';
import { isLogCommand, logReply } from '@/agent/local-commands.mjs';
import { useRoute, useRouter } from 'vue-router';
import { useUserStore } from '@/store/user';
import { useAgentRunMonitor } from '@/composables/useAgentRunMonitor';
import { useAgentHistory } from '@/composables/useAgentHistory';
import { runWaitClock, formatRunWait } from '@/agent/run-clock.mjs';
import {
  getSessionMessages,
  cancelAgentRun,
  createAgentRun,
  getAgentReport,
  getAgentRun,
  resumeAgentAnalysis,
} from '@/api/agent';
import {
  answerReport,
  answerPresentation,
  formatDataUpdatedAt,
  buildChatRequest,
  terminal,
  validRunId,
  yesterdayRange,
} from '@/agent/protocol.mjs';

const props = defineProps({
  managed: { type: Boolean, default: false },
  creator: { type: Boolean, default: false },
  contextPending: { type: Boolean, default: false },
  playbackMs: { type: Number, default: null },
  embedded: { type: Boolean, default: false },
  videoId: { type: String, default: '' },
  videoTitle: { type: String, default: '' },
});
const emit = defineEmits(['close', 'expand']);
const compositionGuard = createCompositionGuard();
const persistentHistory = ref(window.matchMedia('(min-width:1000px)').matches);
const navigationOpen = ref(false), historyToggle = ref(null), historyNavigation = ref(null);
function toggleNavigation() { navigationOpen.value = !navigationOpen.value; if (navigationOpen.value) nextTick(() => historyNavigation.value?.querySelector('button')?.focus()); }
function cycleNavigation(event) {
  if (!navigationOpen.value) return;
  const items = [...historyNavigation.value.querySelectorAll('a,button:not(:disabled)')].filter(el => el.getClientRects().length);
  const first = items[0], last = items[items.length - 1];
  if (event.shiftKey && (document.activeElement === first || !historyNavigation.value.contains(document.activeElement))) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && (document.activeElement === last || !historyNavigation.value.contains(document.activeElement))) { event.preventDefault(); first?.focus(); }
}
function hideNavigation() { navigationOpen.value = false; nextTick(() => historyToggle.value?.focus()); }
function closeNavigation(event) { if (navigationOpen.value) { event.stopPropagation(); hideNavigation(); } }
function resizeComposer() {
  const input = composerInput.value;
  if (!input || CSS.supports('field-sizing', 'content')) return;
  const style = getComputedStyle(input);
  input.style.height = 'auto';
  const max = parseFloat(style.maxHeight) || 200, min = parseFloat(style.minHeight) || 56;
  input.style.height = Math.min(max, Math.max(min, input.scrollHeight)) + 'px';
}
let composerObserver, navigationMedia, historyMedia;
function updateHistoryLayout(event) { persistentHistory.value = event.matches; if (event.matches) navigationOpen.value = false; }
function resetWideNavigation(event) { if (!event.matches) navigationOpen.value = false; }
onMounted(() => {
  historyMedia = window.matchMedia('(min-width:1000px)');
  historyMedia.addEventListener('change', updateHistoryLayout);
  navigationMedia = window.matchMedia('(max-width: 760px)');
  navigationMedia.addEventListener('change', resetWideNavigation);
  resizeComposer();
  if (composerInput.value && !CSS.supports('field-sizing', 'content')) {
    let previousWidth = 0;
    composerObserver = new ResizeObserver(([entry]) => { if (entry.contentRect.width !== previousWidth) { previousWidth = entry.contentRect.width; resizeComposer(); } });
    composerObserver.observe(composerInput.value);
  }
});
const composerInput = ref(null),
  conversationElement = ref(null), conversationContent = ref(null);
const { showJump, fadeStyle, scroll: trackConversationScroll, intent: markConversationIntent, jumpToLatest } = useConversationViewport(conversationElement, conversationContent);
const generalSuggestions = [
  { icon: '✧', title: '你能帮我做什么', description: '先了解助手的能力', text: '你能做什么？' },
  { icon: '↗', title: '整理思路', description: '把想法变成清晰的步骤', text: '我想学习一项新技能，你可以怎么帮助我制定计划？' },
  { icon: '✎', title: '解释概念', description: '用容易理解的例子说明', text: '用日常生活的例子解释什么是机器学习。' },
  { icon: '▷', title: '制定计划', description: '按时间和目标安排步骤', text: '帮我制定每天二十分钟的摄影入门学习计划。' },
];
const viewerSuggestions = [
  { icon: '▷', title: '概括视频', description: '根据可用画面了解内容', text: '概括这个视频的画面内容。' },
  { icon: '◷', title: '解释当前片段', description: '结合当前播放位置提问', text: '解释这个视频当前播放位置附近的画面。' },
];
const videoSuggestions = [
  { icon: '▤', title: '观众反馈报告', description: '结合视频画面，分析全部弹幕与评论', text: '请分析这个视频全部的弹幕和评论，结合实际视频画面生成观众反馈报告，包含主要话题、观众原话和改进建议。' },
  { icon: '✎', title: '智能评论', description: '生成作者评论草稿，预览确认后发送', text: '请为这个视频生成3条智能评论草稿，以作者身份补充说明或引导讨论，先给我预览。' },
  { icon: '▷', title: '智能弹幕', description: '结合画面生成弹幕，核对内容与时间点', text: '请为这个视频生成5条智能弹幕草稿，以作者身份自然引导互动，标明播放时间点，先给我预览。' },
  { icon: '◷', title: '互动统计', description: '查看弹幕、评论数量与活跃观众', text: '请统计这个视频全部的弹幕数量和评论数量，并列出互动最活跃的观众。' },
];
const modelChoices = ref([]), selectedModel = ref(''), modelError = ref(''), modelsLoading = ref(false);
async function refreshModels() {
  modelsLoading.value = true; modelError.value = '';
  try { modelChoices.value = await getAssistantModels(); if (selectedModel.value && !modelChoices.value.some(item => item.id === selectedModel.value)) selectedModel.value = ''; }
  catch (error) { modelError.value = error.message; }
  finally { modelsLoading.value = false; }
}
refreshModels();
const toolsOpen = ref(false);
const memoryOpen=ref(false), memoryToggle=ref(null);
function closeMemory(){memoryOpen.value=false;nextTick(()=>memoryToggle.value?.focus());}

const router = useRouter(),
  route = useRoute(),
  userStore = useUserStore();
const form = reactive({ videoId: '', ...yesterdayRange() });
const suggestions = computed(() => form.videoId ? (props.creator ? videoSuggestions : viewerSuggestions) : generalSuggestions);
const quickSuggestions = computed(() => form.videoId ? (props.creator ? videoSuggestions.slice(0, 3) : viewerSuggestions) : [generalSuggestions[1], generalSuggestions[2]]);
const contextTitle = computed(() => (props.videoId === form.videoId ? props.videoTitle : ''));
const currentQueryRun = computed(() =>
  props.managed ? assistant.runId : props.embedded ? route.query.assistantRunId : route.query.runId
);
const messageLimit = 2000;
const assistant = useAssistantStore();
const { focus, turns, history, notice, commandReplies, historyMessages, historyCursor, historySession } = storeToRefs(assistant);
const submitting = ref(false),
  cancelling = ref(false),
  loadingRun = ref(false),
  clock = ref(performance.now()),
  resultsElement = ref(null);
const activeTurn = computed(() => turns.value[turns.value.length - 1]);

const loadingHistory = ref(false);
let historyController;
const conversationTurns = computed(() => [
  ...historicalTurns(historyMessages.value, turns.value),
  ...commandReplies.value.filter(reply => !reply.afterRunId),
  ...turns.value.flatMap(turn => [turn, ...commandReplies.value.filter(reply => reply.afterRunId === turn.runId)]),
]);
const activeRunId = computed(() => activeTurn.value?.runId);
const isPaused = turn => turn?.snapshot?.status === 'WAITING_OPERATION' && turn.events?.some(event => event.waitingAuth);
const paused = computed(() => isPaused(activeTurn.value));
const running = computed(() => !!activeTurn.value && !terminal(activeTurn.value.snapshot?.status));
const busy = computed(() => props.contextPending || submitting.value || running.value || loadingRun.value);
const needsInput = computed(() => activeTurn.value?.snapshot?.status === 'NEEDS_INPUT');
const ownerId = computed(() => String(userStore.user.userId || ''));
let submitController,
  reportControllers = new Set(),
  pendingRequest,
  restoreTicket;
const { readHistory, saveHistory } = useAgentHistory({ ownerId, history, turns });
const runMonitor = useAgentRunMonitor({ applySnapshot, notice });
const clockTimer = setInterval(() => {
  clock.value = performance.now();
}, 1000);

function statusLabel(status) {
  return (
    {
      QUEUED: '排队中',
      RUNNING: '思考中',
      WAITING_OPERATION: '后台分析中',
      SUCCEEDED: '已完成',
      NEEDS_INPUT: '等你补充',
      FAILED: '回复未完成',
      CANCELLED: '已取消',
    }[status] || '连接中'
  );
}
function failureLabel(code) {
  return (
    {
      DATE_OUT_OF_RANGE: '分析时段超出范围，请选择最近 30 天内已经结束的时段。',
      FORBIDDEN: '你没有权限分析这项内容，请确认视频或直播间 ID。',
      DEADLINE_EXCEEDED: '这次回复耗时较长，请稍后重试。',
      MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后重试。',
      MODEL_QUOTA_EXHAUSTED: '模型服务额度不可用，本次分析已停止。请联系管理员检查模型账户。',
      MODEL_TOKEN_BUDGET_EXCEEDED: '本次分析已达到用量上限，已停止后续模型请求。',
      MODEL_CALL_BUDGET_EXCEEDED: '本次分析已达到调用次数上限，已停止后续模型请求。',
      REPORT_BUDGET_INSUFFICIENT: '当前互动量超出本次报告的用量预算，分析已提前停止。请缩小时间范围。',
      REPORT_IN_PROGRESS: '相同内容的报告正在生成，请查看最近对话里的任务，避免重复分析。',
      INVALID_ANSWER: '这次没能整理出合适的回复，请重新发送一下。',
      INTERNAL_ERROR: '助手暂时遇到了一点问题，请稍后重试。',
    }[code] || '暂时没能完成回复，请稍后重试。'
  );
}
const isTerminal = terminal;
function formatDate(value) {
  return value
    ? new Date(value).toLocaleDateString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        month: '2-digit',
        day: '2-digit',
      })
    : '';
}
function formatTime(value) {
  return value
    ? new Date(value).toLocaleTimeString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        hour12: false,
      })
    : '';
}
function elapsed(value) {
  return formatRunWait(value, clock.value);
}
function displayAnswer(text) {
  return text.replace(
    /\[查看 HTML 报告\]\(\/api\/agent\/runs\/[^)]+\)（需登录鉴权）/g,
    '报告预览见下方。'
  );
}


function stopMonitoring(cancelReports = true) {
  runMonitor.stop();
  restoreTicket?.abort();
  if (cancelReports) {
    reportControllers.forEach((controller) => controller.abort());
    reportControllers.clear();
  }
}

async function loadReport(turn) {
  if (turn.reportLoading || turn.reportHtml) return;
  const controller = new AbortController();
  reportControllers.add(controller);
  turn.reportLoading = true;
  turn.reportError = '';
  try {
    turn.reportHtml = await getAgentReport(turn.runId, turn.reportPath, controller.signal);
  } catch (error) {
    if (error.name !== 'AbortError') turn.reportError = error.message;
  } finally {
    turn.reportLoading = false;
    reportControllers.delete(controller);
  }
}
function applySnapshot(turn, snapshot) {
  turn.waitClock = runWaitClock(snapshot, turn.waitClock);
  turn.snapshot = snapshot;
  turn.reportPath = answerReport(turn.runId, snapshot.answer?.text) || turn.reportPath;
  saveHistory(turn);
  if (snapshot.status === 'SUCCEEDED' && turn.reportPath) loadReport(turn);
}


function createKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}
function useSuggestion(item) {
  focus.value = item.text;
  nextTick(() => {
    const input = composerInput.value;
    input?.focus();
    if (input && item.selection) {
      const start = item.text.indexOf(item.selection);
      if (start >= 0) input.setSelectionRange(start, start + item.selection.length);
    }
  });
}
function composerEnter(event) {
  if (compositionGuard.blocksEnter(event)) return;
  event.preventDefault();
  submit();
}
function replaceRun(id, videoId = form.videoId) {
  if (props.managed) {
    assistant.runId = id || '';
    try { sessionStorage.setItem(`assistant_run_${ownerId.value}`, id || ''); } catch { /* storage is optional */ }
    return;
  }
  return props.embedded
    ? router.replace({ query: { ...route.query, assistant: '1', assistantRunId: id || undefined } })
    : router.replace({
        name: 'AgentWorkspace',
        query: { runId: id || undefined, videoId: videoId || undefined },
      });
}
async function submit() {
  if (isLogCommand(focus.value) && !submitting.value && !loadingRun.value) {
    commandReplies.value.push({ localId: createKey(), afterRunId: activeRunId.value,
      command: 'log', message: '/log', ...logReply(activeTurn.value?.snapshot) });
    focus.value = '';
    await nextTick();
    jumpToLatest();
    return;
  }
  if (busy.value) return;
  notice.value = '';
  let body;
  try {
    body = buildChatRequest(focus.value, form.videoId, contextTitle.value);
    if (body.context && Number.isSafeInteger(props.playbackMs) && props.playbackMs >= 0 && props.playbackMs <= 3600000) body.context.playbackMs = props.playbackMs;
  } catch (error) {
    notice.value = error.message;
    return;
  }
  if (selectedModel.value) body.modelId = selectedModel.value;
  if (activeTurn.value?.snapshot?.sessionId) body.sessionId = activeTurn.value.snapshot.sessionId;
  if (needsInput.value) body.replyToRunId = activeRunId.value;
  const fingerprint = JSON.stringify(body);
  if (!pendingRequest || pendingRequest.fingerprint !== fingerprint)
    pendingRequest = { fingerprint, key: createKey() };
  submitting.value = true;
  submitController = new AbortController();
  try {
    const snapshot = await createAgentRun(body, pendingRequest.key, submitController.signal);
    if (submitController.signal.aborted) return;
    pendingRequest = null;
    const turn = reactive({
      runId: snapshot.runId,
      videoId: body.context?.videoId || '',
      videoTitle: body.context?.videoTitle || '',
      playbackMs: body.context?.playbackMs,
      message: body.message,
      snapshot,
      events: [],
      reportPath: '',
      reportHtml: '',
      reportLoading: false,
      reportError: '',
    });
    turns.value.push(turn);
    focus.value = '';
    applySnapshot(turn, snapshot);
    runMonitor.start(turn);
    await replaceRun(snapshot.runId);
    await nextTick();
    jumpToLatest();
  } catch (error) {
    if (error.name !== 'AbortError') notice.value = error.message;
  } finally {
    submitting.value = false;
  }
}

async function loadOlderMessages(reset = false) {
  const session = activeTurn.value?.snapshot?.sessionId;
  if (!session || loadingHistory.value) return;
  loadingHistory.value = true;
  historyController = new AbortController();
  const controller = historyController;
  try {
    const messages = await getSessionMessages(session, reset ? '' : historyCursor.value, controller.signal);
    if (controller.signal.aborted || activeTurn.value?.snapshot?.sessionId !== session) return;
    historyMessages.value = mergeMessages(reset ? [] : historyMessages.value, messages);
    historySession.value = session;
    historyCursor.value = messages.length ? String(Math.min(...messages.map(message => message.sequence))) : '';
    if (historyCursor.value === '1') historyCursor.value = '';
  } catch (error) {
    if (error.name !== 'AbortError') { notice.value = '历史消息读取失败：' + error.message; historyCursor.value ||= '9223372036854775807'; }
  } finally { if (historyController === controller) loadingHistory.value = false; }
}
async function restoreRun(id) {
  historyController?.abort(); loadingHistory.value = false;
  historyMessages.value = []; historyCursor.value = ''; historySession.value = '';
  commandReplies.value = [];
  stopMonitoring();
  notice.value = '';
  loadingRun.value = true;
  const controller = new AbortController();

  restoreTicket = controller;
  try {
    const snapshot = await getAgentRun(id, controller.signal);
    if (controller.signal.aborted) return;
    const saved = history.value.find((item) => item.runId === id);
    const turn = reactive({
      runId: id,
      videoId: '',
      message:
        snapshot.task?.originalQuery?.replace(/\n\n\[页面上下文：[\s\S]*\]$/, '') ||
        saved?.title ||
        '已恢复的对话',
      snapshot,
      events: [],
      reportPath: '',
      reportHtml: '',
      reportLoading: false,
      reportError: '',
    });
    const conditions = snapshot.task?.specification?.conditions;
    const originalId = snapshot.task?.originalQuery?.match(
      /(?:视频|直播间|videoId)\s*([1-9]\d{0,18})/i
    )?.[1];
    if (!props.managed && saved?.videoId) form.videoId = saved.videoId;
    else if (!props.managed && originalId) form.videoId = originalId;
    else if (!props.managed && !form.videoId && Number.isSafeInteger(conditions?.video_id))
      form.videoId = String(conditions.video_id);
    if (conditions?.start) form.start = conditions.start.slice(0, 16);
    if (conditions?.end) form.end = conditions.end.slice(0, 16);
    turn.videoId = saved?.videoId || originalId || '';
    turns.value = [turn];
    await loadOlderMessages(true);
    if (controller.signal.aborted) return;
    applySnapshot(turn, snapshot);
    runMonitor.start(turn);
  } catch (error) {
    if (error.name !== 'AbortError') {
      notice.value = error.message;
      turns.value = [];
    }
  } finally {
    if (restoreTicket === controller) loadingRun.value = false;
  }
}
function selectHistory(item) {
  hideNavigation();
  replaceRun(item.runId, item.videoId || '');
}
function newAnalysis() {
  navigationOpen.value = false;
  historyController?.abort(); loadingHistory.value = false;
  historyMessages.value = []; historyCursor.value = ''; historySession.value = '';
  commandReplies.value = [];
  stopMonitoring();
  turns.value = [];
  focus.value = '';
  notice.value = '';
  pendingRequest = null;
  form.videoId = props.managed ? props.videoId : props.videoId || form.videoId;
  replaceRun();
}
function resume() {
  notice.value = '';
  if (activeTurn.value) runMonitor.start(activeTurn.value);
  else if (validRunId(currentQueryRun.value)) restoreRun(currentQueryRun.value);
}
async function resumeAnalysis(turn) {
  try { await resumeAgentAnalysis(turn.runId); turn.events.forEach(event => { event.waitingAuth = false; }); notice.value = ''; }
  catch (error) { notice.value = error.message; }
}
async function cancel() {
  if (!activeTurn.value || cancelling.value) return;
  cancelling.value = true;
  const turn = activeTurn.value;
  try {
    applySnapshot(turn, await cancelAgentRun(turn.runId, runMonitor.signal));
  } catch (error) {
    if (error.name !== 'AbortError') notice.value = error.message;
  } finally {
    cancelling.value = false;
  }
}
function reportDocument(turn) {
  return `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><base href="${window.location.origin}/">${themeReportHtml(turn.reportHtml)}`;
}
function downloadReport(turn) {
  const url = URL.createObjectURL(new Blob([reportDocument(turn)], { type: 'text/html;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `观众反馈-${turn.runId}.html`;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

readHistory();
if (props.managed) {
  try { assistant.runId = route.query.runId || route.query.assistantRunId || sessionStorage.getItem(`assistant_run_${ownerId.value}`) || ''; } catch { /* optional */ }
}
watch(() => props.videoId, id => { if (props.managed) form.videoId = id || ''; }, { immediate: true });
watch(() => route.query.runId || route.query.assistantRunId, id => { if (props.managed && validRunId(id)) assistant.runId = id; });
watch(
  () => [currentQueryRun.value, props.videoId || route.query.videoId],
  ([id, videoId]) => {
    if (!props.managed && typeof videoId === 'string' && /^\d{1,19}$/.test(videoId)) form.videoId = videoId;
    if (validRunId(id) && id !== activeRunId.value) restoreRun(id);
    else if (id && !validRunId(id)) notice.value = '任务链接不正确，请从最近任务重新打开。';
    else if (!id && activeTurn.value) {
      commandReplies.value = [];
      stopMonitoring();
      turns.value = [];
      focus.value = '';
      notice.value = '';
    }
  },
  { immediate: true }
);
watch(
  () => userStore.user.token,
  () => {
    commandReplies.value = [];
    stopMonitoring();
    submitController?.abort();
    turns.value = [];
    if (props.managed) return;
    if (props.embedded) emit('close');
    else router.replace('/');
  }
);
watch(focus, () => nextTick(resizeComposer));
onBeforeUnmount(() => {
  composerObserver?.disconnect();
  navigationMedia?.removeEventListener('change', resetWideNavigation);
  historyMedia?.removeEventListener('change', updateHistoryLayout);
  historyController?.abort();
  stopMonitoring();
  submitController?.abort();
  clearInterval(clockTimer);
});
</script>

<style scoped>
.model-select {max-width:150px;min-height:32px;border:1px solid var(--workbench-border);border-radius:8px;background:var(--workbench-raised);color:var(--workbench-ink);padding:4px 8px;}
.trace-id-reply { display: block; overflow-wrap: anywhere; margin: 12px 0; user-select: all; }
.agent-workspace {
  --ink: var(--workbench-ink);
  --muted: var(--workbench-muted);
  --line: var(--workbench-border);
  --accent: var(--workbench-error);
  display: flex;
  height: 100dvh;
  min-height: 600px;
  background: var(--workbench-raised);
  color: var(--ink);
  font: 14px/1.6 'Harmony Font', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.agent-workspace * {
  box-sizing: border-box;
}
.agent-workspace button,
.agent-workspace input,
.agent-workspace textarea {
  font: inherit;
}
.agent-workspace button {
  cursor: pointer;
}
.agent-workspace button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.agent-workspace a {
  color: inherit;
  text-decoration: none;
}
.agent-workspace button:focus-visible,
.agent-workspace a:focus-visible {
  outline: 3px solid var(--workbench-muted);
  outline-offset: 3px;
}
.workspace-sidebar {
  width: 214px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 18px 20px;
  background: var(--workbench-raised);
  border-right: 1px solid var(--line);
}
.workspace-brand {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: 2px;
  padding: 0 10px;
}
.workspace-brand span {
  display: block;
  font-size: 11px;
  color: var(--workbench-muted);
  font-weight: 400;
  letter-spacing: 4px;
  margin-top: 2px;
}
.new-analysis {
  margin: 28px 0;
  background: var(--workbench-raised);
  border: 1px solid var(--workbench-border);
  border-radius: 11px;
  padding: 11px;
  color: var(--workbench-accent);
  box-shadow: 0 3px 9px rgba(41,39,34,0.016);
}
.history-heading {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--workbench-muted);
  padding: 0 9px;
  margin-bottom: 12px;
}
.history-empty {
  color: var(--muted);
  font-size: 12px;
  padding: 0 9px;
  line-height: 1.9;
}
.history-list {
  overflow: auto;
  flex: 1;
}
.history-list button {
  display: block;
  text-align: left;
  width: 100%;
  border: 0;
  background: none;
  padding: 12px 10px;
  border-radius: 9px;
  margin-bottom: 5px;
}
.history-list button:hover,
.history-list button.selected {
  background: var(--workbench-raised);
}
.history-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--workbench-ink);
}
.history-meta {
  display: block;
  font-size: 10px;
  color: var(--workbench-muted);
  margin-top: 5px;
}
.sidebar-footer {
  font-size: 10px;
  color: var(--workbench-muted);
  padding-top: 18px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.online-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--workbench-success);
}
.workspace-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--workbench-paper);
}
.workspace-header {
  height: 78px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  border-bottom: 1px solid var(--workbench-raised);
  background: rgba(41,39,34,0.678);
}
.assistant-identity {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-symbol {
  display: grid;
  place-items: center;
  width: 37px;
  height: 37px;
  border-radius: 12px;
  color: var(--workbench-error);
  background: var(--workbench-paper);
  font-size: 26px;
}
.assistant-identity strong {
  display: block;
  font-size: 14px;
  font-weight: 650;
}
.assistant-identity div > span {
  display: block;
  font-size: 10px;
  color: var(--workbench-muted);
  letter-spacing: 1px;
}
.close-assistant {
  border: 1px solid var(--workbench-border);
  background: var(--workbench-raised);
  border-radius: 50%;
  width: 32px;
  height: 32px;
  font-size: 23px !important;
  line-height: 1;
  color: var(--workbench-muted);
}
.back-video {
  font-size: 12px;
  color: var(--workbench-muted) !important;
}
.conversation-scroll {
  flex: 1;
  overflow: auto;
  min-height: 0;
  overscroll-behavior: contain;
}
.workspace-content {
  max-width: 900px;
  margin: 0 auto;
  padding: 26px 36px;
}
.workspace-tools { margin: 12px 0 20px; color: var(--workbench-accent); font-size: 12px; }
.workspace-tools > summary { cursor: pointer; padding: 10px 0; }
.workspace-tools > summary span { margin-left: 12px; color: var(--workbench-muted); font-size: 11px; }
.workspace-tools > summary:focus-visible, .suggestion-grid button:focus-visible { outline: 2px solid var(--workbench-muted); outline-offset: 3px; }
.workspace-intro {
  text-align: center;
  padding: 32px 0 20px;
}
.welcome-symbol {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  border: 1px solid var(--workbench-border);
  border-radius: 24px;
  margin: 0 auto 23px;
  background: var(--workbench-paper);
  color: var(--workbench-error);
  font-size: 54px;
  box-shadow: 0 12px 30px rgba(41,39,34,0.063);
}
.welcome-symbol i {
  position: absolute;
  right: -9px;
  top: -10px;
  font-size: 25px;
  font-style: normal;
  color: var(--workbench-muted);
}
.intro-tag {
  font-size: 11px;
  color: var(--workbench-muted);
  letter-spacing: 3px;
}
.workspace-intro h1 {
  font-size: 29px;
  letter-spacing: 1px;
  font-weight: 650;
  margin: 10px 0 13px;
}
.workspace-intro p {
  font-size: 12px;
  line-height: 1.9;
  color: var(--workbench-muted);
  margin: 0 0 22px;
}
.conversation-context {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 7px 11px;
  border: 1px solid var(--workbench-border);
  border-radius: 8px;
  background: var(--workbench-raised);
  max-width: 100%;
  font-size: 10px;
}
.conversation-context > span {
  color: var(--workbench-muted);
  white-space: nowrap;
}
.conversation-context strong {
  font-size: 11px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}
.conversation-context small {
  color: var(--workbench-muted);
}
.quick-suggestions { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 10px; }
.quick-suggestions button { padding: 7px 11px; border: 1px solid var(--workbench-border); border-radius: 99px; background: var(--workbench-raised); color: var(--workbench-accent); font-size: 11px; cursor: pointer; }
.quick-suggestions button:hover { border-color: var(--workbench-muted); background: var(--workbench-raised); }
.suggestion-hint { margin: 12px 0 0 !important; font-size: 11px !important; color: var(--workbench-muted) !important; }
.suggestion-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px;
  margin-top: 30px;
  text-align: left;
}
.suggestion-grid button {
  position: relative;
  text-align: left;
  padding: 17px 18px 17px 49px;
  border: 1px solid var(--workbench-border);
  background: var(--workbench-raised);
  border-radius: 13px;
  transition: border-color 0.2s, transform 0.2s;
}
.suggestion-grid button:hover {
  border-color: var(--workbench-muted);
  transform: translateY(-2px);
}
.suggestion-grid strong {
  display: block;
  font-size: 13px;
  font-weight: 550;
  margin-bottom: 4px;
}
.suggestion-grid button > span:not(.suggestion-icon) {
  font-size: 11px;
  line-height: 1.5;
  color: var(--workbench-muted);
}
.suggestion-icon {
  position: absolute;
  left: 17px;
  top: 18px;
  font-size: 19px;
  color: var(--workbench-muted);
}
.suggestion-grid i {
  position: absolute;
  right: 13px;
  top: 12px;
  font-style: normal;
  color: var(--workbench-muted);
  font-size: 13px;
}
.composer-wrap {
  flex-shrink: 0;
  width: 100%;
  max-width: 900px;
  align-self: center;
  padding: 12px 36px 19px;
  background: var(--workbench-paper);
}
.chat-composer {
  border: 1px solid var(--workbench-border);
  border-radius: 17px;
  padding: 13px 17px 11px;
  background: var(--workbench-raised);
  box-shadow: 0 6px 25px rgba(41,39,34,0.027);
  transition: border-color 0.2s;
}
.chat-composer:focus-within {
  border-color: var(--workbench-muted);
  box-shadow: 0 0 0 3px var(--workbench-raised);
}
.chat-composer textarea {
  display: block;
  border: 0;
  outline: none !important;
  resize: none;
  background: none;
  width: 100%;
  min-height: 64px;
  max-height: 150px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--workbench-ink);
  padding: 0;
}
.chat-composer textarea::placeholder {
  color: var(--workbench-muted);
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 5px;
}
.composer-actions > span {
  font-size: 10px;
  color: var(--workbench-muted);
}
.composer-actions i {
  font-style: normal;
  font-size: 15px;
  color: var(--workbench-muted);
  margin-right: 5px;
}
.send-button {
  height: 34px;
  width: 34px;
  border: 0;
  border-radius: 11px;
  color: white;
  background: var(--workbench-error);
  font-size: 23px !important;
  line-height: 1;
}
.send-button:hover {
  background: var(--workbench-error);
}
.workspace-footnote {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 9px;
  color: var(--workbench-muted);
  margin: 9px 3px 0;
}
.composer-context {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 3px 9px;
  color: var(--workbench-muted);
  font-size: 10px;
}
.composer-context > span:first-child {
  max-width: 65%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.composer-context small {
  color: var(--workbench-muted);
}
.reply-hint {
  margin-left: auto;
  color: var(--workbench-accent);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.workspace-notice {
  padding: 12px 15px;
  background: var(--workbench-raised);
  color: var(--workbench-accent);
  border: 1px solid var(--workbench-border);
  border-radius: 10px;
  font-size: 12px;
  margin: 12px 0;
}
.workspace-notice button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  margin-left: 10px;
}
.analysis-turn {
  margin: 0 0 28px;
}
.user-question {
  display: flex;
  flex-direction: row-reverse;
  align-items: flex-start;
  gap: 10px;
  margin: 0 0 22px;
}
.user-question > span {
  background: var(--workbench-border);
  color: var(--workbench-muted);
  border-radius: 11px;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  font-size: 11px;
  flex-shrink: 0;
}
.user-question p {
  max-width: 85%;
  background: var(--workbench-raised);
  border: 1px solid var(--workbench-border);
  border-radius: 14px 2px 14px 14px;
  padding: 11px 15px;
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.9;
}
.assistant-result {
  background: var(--workbench-raised);
  border: 1px solid var(--workbench-border);
  border-radius: 14px;
  padding: 20px;
  min-width: 0;
}
.result-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.assistant-avatar {
  width: 27px;
  height: 27px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--workbench-raised);
  color: var(--workbench-muted);
  font-size: 20px;
}
.status-badge {
  font-size: 9px;
  margin-left: auto;
  padding: 3px 7px;
  border-radius: 20px;
  background: var(--workbench-raised);
  color: var(--workbench-muted);
}
.status-badge.succeeded {
  background: var(--workbench-paper);
  color: var(--workbench-success);
}
.status-badge.running,
.status-badge.queued {
  background: var(--workbench-raised);
  color: var(--workbench-muted);
}
.status-badge.failed {
  background: var(--workbench-raised);
  color: var(--workbench-error);
}
.status-badge.needs_input {
  background: var(--workbench-raised);
  color: var(--workbench-accent);
}
.execution-summary {
  display: flex;
  gap: 14px;
  font-size: 10px;
  color: var(--workbench-muted);
  margin: 10px 0;
}
.working-message {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--workbench-muted);
  font-size: 12px;
  padding: 18px 0;
}
.working-dot {
  height: 6px;
  width: 6px;
  border-radius: 50%;
  background: var(--workbench-muted);
  animation: pulse 1.4s infinite;
}
.answer-text {
  font-size: 13px;
  line-height: 1.95;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 16px 0;
  color: var(--workbench-ink);
}
.answer-data-notes { font-size: 11px; color: var(--workbench-muted); margin: 8px 0 14px; }
.answer-data-notes summary { cursor: pointer; width: fit-content; }
.answer-data-notes > div { margin-top: 10px; padding: 10px 12px; background: var(--workbench-raised); border: 1px solid var(--workbench-border); border-radius: 8px; line-height: 1.8; }
.answer-data-notes div > span { font-size: 10px; color: var(--workbench-muted); }
.answer-data-notes p { margin: 4px 0; }
.answer-data-notes time { font-size: 10px; color: var(--workbench-muted); }
.clarification {
  padding: 12px;
  background: var(--workbench-raised);
  border-radius: 8px;
}
.failure-message {
  color: var(--workbench-error);
  font-size: 12px;
}
.muted {
  color: var(--muted);
}
.text-button {
  border: 0;
  background: none;
  color: var(--workbench-muted);
  font-size: 11px !important;
  padding: 4px 0;
}
.execution-details {
  margin-top: 15px;
  font-size: 10px;
  color: var(--workbench-muted);
  border-top: 1px solid var(--workbench-raised);
  padding-top: 10px;
}
.execution-details summary {
  cursor: pointer;
}
.execution-details ol {
  margin: 12px 0 0;
  padding-left: 19px;
  max-height: 150px;
  overflow: auto;
}
.execution-details li {
  padding: 3px;
}
.execution-details time {
  float: right;
  font-size: 9px;
  color: var(--workbench-muted);
}
.report-panel {
  margin-top: 17px;
  border: 1px solid var(--workbench-border);
  border-radius: 10px;
  overflow: hidden;
}
.report-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px;
  background: var(--workbench-raised);
  gap: 8px;
}
.report-toolbar strong {
  display: block;
  font-size: 12px;
}
.report-toolbar div > span {
  display: block;
  color: var(--workbench-muted);
  font-size: 9px;
  margin-top: 3px;
}
.report-preview {
  width: 100%;
  height: 650px;
  border: 0;
  display: block;
  background: var(--workbench-raised);
}
.report-state {
  padding: 15px;
  color: var(--workbench-muted);
  font-size: 12px;
}
.embedded {
  height: 100%;
  min-height: 0;
}
.embedded .workspace-sidebar {
  width: 190px;
  padding: 24px 13px;
}
.embedded .workspace-content {
  padding: 22px 30px;
}
.embedded .composer-wrap {
  padding: 12px 30px 17px;
}
.embedded .workspace-intro {
  padding-top: 22px;
}
.embedded .workspace-intro h1 {
  font-size: 26px;
}
@keyframes pulse {
  50% {
    opacity: 0.3;
  }
}
@media (max-width: 760px) {
  .assistant-identity {gap:6px;min-width:0;}
  .assistant-identity .brand-symbol {display:none;}
  .assistant-identity strong {font-size:12px;white-space:nowrap;}
  .assistant-identity div > span {letter-spacing:0;}
  .model-select {max-width:110px;}
  .back-video {white-space:nowrap;}
  .workspace-sidebar {
    display: none !important;
  }
  .workspace-header {
    height: 66px;
    padding: 0 20px;
  }
  .workspace-content,
  .embedded .workspace-content {
    padding: 20px;
  }
  .composer-wrap,
  .embedded .composer-wrap {
    padding: 10px 20px 14px;
  }
  .workspace-intro h1,
  .embedded .workspace-intro h1 {
    font-size: 23px;
  }
  .suggestion-grid {
    gap: 9px;
  }
  .suggestion-grid button {
    padding: 15px 12px 15px 38px;
  }
  .suggestion-icon {
    left: 11px;
  }
  .workspace-footnote {
    font-size: 8px;
  }
  .assistant-result {
    padding: 15px;
  }
  .report-preview {
    height: 550px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .working-dot {
    animation: none;
  }
  .suggestion-grid button {
    transition: none;
  }
}

/* Paper surfaces, generous type, and a quiet conversation hierarchy. */
.agent-workspace {--ink:var(--workbench-ink);--muted:var(--workbench-muted);--line:var(--workbench-border);--accent:var(--workbench-accent);background:var(--workbench-paper);font-family:var(--workbench-ui);}
.workspace-main,.conversation-scroll {background:var(--workbench-paper);}
.workspace-sidebar {background:var(--workbench-sidebar);padding-top:32px;}
.workspace-brand {font-family:var(--workbench-display);font-weight:500;letter-spacing:-.7px;font-size:28px;}
.workspace-brand span {font:11px/1.8 var(--workbench-ui);letter-spacing:3px;color:var(--workbench-muted);}
.new-analysis {background:transparent;color:var(--workbench-ink);border-color:var(--workbench-border);border-radius:8px;box-shadow:none;}
.new-analysis:hover {background:var(--workbench-raised);}
.workspace-header {height:76px;background:var(--workbench-paper);border-color:var(--workbench-border);}
.assistant-identity {gap:12px;}
.assistant-identity strong {font-weight:500;}
.assistant-identity div > span {color:var(--workbench-muted);}
.brand-symbol,.assistant-avatar {background:transparent;color:var(--workbench-accent);border-radius:0;}
.brand-symbol {font-size:32px;}
.model-select {background:var(--workbench-raised);color:var(--workbench-ink);border-color:var(--workbench-border);font:12px var(--workbench-ui);}
.workspace-intro {text-align:left;padding:40px 0 20px;}
.welcome-symbol {display:block;width:auto;height:auto;font-size:60px;margin:0 0 24px;border:0;border-radius:0;color:var(--workbench-accent);background:none;box-shadow:none;}
.intro-tag {font-size:11px;letter-spacing:2px;color:var(--workbench-muted);}
.workspace-intro h1,.embedded .workspace-intro h1 {font:500 clamp(25px,3.4vw,52px)/1.4 var(--workbench-heading);letter-spacing:0;margin:12px 0 16px;}
.workspace-intro p {color:var(--workbench-muted);font-size:14px;line-height:1.8;}
.suggestion-grid {gap:0 24px;border-top:1px solid var(--workbench-border);margin-top:36px;}
.suggestion-grid button {border:0;border-bottom:1px solid var(--workbench-border);border-radius:0;padding:22px 26px 22px 30px;background:transparent;transition:transform 250ms var(--workbench-ease);}
.suggestion-grid button:hover {background:var(--workbench-raised);transform:translateY(-2px);}
.suggestion-icon {left:0;top:21px;color:var(--workbench-accent);}
.suggestion-grid strong {font-size:14px;color:var(--workbench-ink);font-weight:500;}
.suggestion-grid button > span:not(.suggestion-icon) {color:var(--workbench-muted);font-size:12px;}
.suggestion-grid i {color:var(--workbench-muted);top:24px;}
.composer-wrap {background:var(--workbench-paper);}
.chat-composer {background:var(--workbench-raised);border-color:var(--workbench-border);border-radius:14px;box-shadow:var(--workbench-ring-shadow);padding:16px 18px;}
.chat-composer:focus-within {border-color:var(--workbench-focus);box-shadow:var(--workbench-focus-ring);}
.chat-composer textarea {font-size:15px;line-height:1.75;color:var(--workbench-ink);}
.chat-composer textarea::placeholder,.workspace-footnote,.composer-actions > span {color:var(--workbench-muted);}
.send-button {width:40px;height:40px;background:var(--workbench-accent);color:var(--workbench-raised);border-radius:9px;}
.send-button:hover {background:var(--workbench-accent-hover);}
.quick-suggestions button {background:transparent;border-color:var(--workbench-border);color:var(--workbench-muted);border-radius:6px;}
.quick-suggestions button:hover {background:var(--workbench-raised);border-color:var(--workbench-accent);}
.user-question p {background:var(--workbench-sidebar);border:0;border-radius:10px;padding:16px 20px;font-size:15px;color:var(--workbench-ink);}
.user-question > span {color:var(--workbench-muted);background:transparent;}
.assistant-result {border:0;border-bottom:1px solid var(--workbench-border);border-radius:0;background:transparent;padding:12px 0 28px;}
.answer-text {font-size:16px;line-height:1.85;color:var(--workbench-ink);max-width:65ch;}
.result-heading {font-size:13px;}.result-heading strong {font-weight:500;}
.status-badge {font-size:11px;color:var(--workbench-muted);background:var(--workbench-sidebar);}
.status-badge.running,.status-badge.queued {color:var(--workbench-accent);background:var(--workbench-soft);}
.status-badge.failed,.failure-message {color:var(--workbench-error);}
.working-message,.text-button {color:var(--workbench-accent);}
.analysis-turn {animation:assistant-appear 400ms var(--workbench-ease) both;}
@keyframes assistant-appear {from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:760px){.workspace-intro{padding-top:24px;}.suggestion-grid{column-gap:16px;margin-top:24px;}.suggestion-grid button{padding:18px 18px 18px 26px;}.suggestion-icon{left:0;top:17px;}.suggestion-grid i{right:0;top:20px;}.workspace-header{padding:0 16px;}.model-select{max-width:100px;min-height:44px;}.close-assistant{width:44px;height:44px;}.send-button{width:44px;height:44px;}.assistant-result{padding:12px 0 24px;}}
@media(prefers-reduced-motion:reduce){.agent-workspace *{animation:none!important;transition:none!important;}.suggestion-grid button:hover{transform:none;}}


.composer-controls {display:flex;align-items:center;gap:10px;min-width:0;margin-left:auto;}
.composer-controls .model-select {max-width:220px;min-width:0;min-height:40px;border:0;background:transparent;border-radius:6px;padding:6px 4px;font-size:13px;text-overflow:ellipsis;cursor:pointer;}
.model-refresh {display:grid;place-items:center;flex-shrink:0;width:32px;height:40px;border:0;border-radius:6px;background:transparent;color:var(--workbench-muted);font-size:18px!important;}
.model-refresh:hover,.composer-controls .model-select:hover {background:var(--workbench-sidebar);}
.composer-controls .send-button {flex-shrink:0;}
.composer-hint {display:flex;align-items:center;gap:4px;}
.model-error {margin:8px 0 0;font-size:12px;line-height:1.6;color:var(--workbench-error);}
@media(max-width:760px){.composer-hint > span{display:none;}.composer-controls{gap:6px;}.composer-controls .model-select{max-width:160px;min-height:44px;}.model-refresh{width:44px;height:44px;}}

/* Desktop-agent interaction details, adapted to the existing Vue assistant. */
.agent-workspace {position:relative;min-height:0;}
.chat-composer textarea {field-sizing:content;min-height:56px;max-height:clamp(56px,26dvh,200px);height:auto;overflow-y:auto;scrollbar-width:thin;}
.history-toggle,.history-close {display:none;align-items:center;justify-content:center;flex-shrink:0;width:44px;height:44px;border:0;border-radius:6px;background:transparent;color:var(--workbench-ink);cursor:pointer;}
.history-scrim {display:none;}
.history-toggle:focus-visible,.history-close:focus-visible {outline:2px solid var(--workbench-focus);outline-offset:2px;}
.history-meta {display:flex;gap:12px;font-variant-numeric:tabular-nums;}
.workspace-brand span,.history-heading,.history-title,.history-meta,.sidebar-footer,.assistant-identity div > span,.intro-tag,.suggestion-hint,.suggestion-grid button > span:not(.suggestion-icon),.quick-suggestions button,.workspace-footnote,.composer-context,.composer-context small,.composer-actions > span,.status-badge,.execution-summary,.execution-details,.execution-details time,.answer-data-notes,.text-button,.back-video {font-size:12px!important;}
.workspace-footnote {line-height:1.6;flex-wrap:wrap;}
.execution-summary {flex-wrap:wrap;font-variant-numeric:tabular-nums;}
.execution-details summary > span {margin-left:12px;}
.workspace-intro h1 {text-wrap:balance;}
.workspace-header,.workspace-sidebar,.suggestion-grid,.suggestion-grid button,.assistant-result,.execution-details {border-width:.5px;}
@media(max-width:760px){
  .history-toggle,.history-close {display:inline-flex;}
  .history-open .workspace-sidebar {display:flex!important;position:absolute;inset:0 auto 0 0;width:min(280px,85%);z-index:4;box-shadow:var(--workbench-whisper);padding:20px;}
  .history-close {align-self:flex-end;}
  .history-open .history-scrim {display:block;position:absolute;inset:0;z-index:3;border:0;background:rgba(41,39,34,.22);}
  .assistant-identity {gap:4px;min-width:0;}
  .assistant-identity strong {font-size:12px;}
  .assistant-identity div > span {display:none;}
  .workspace-header {padding:0 10px;gap:4px;}
  .composer-wrap,.embedded .composer-wrap {padding-bottom:12px;}
  .workspace-footnote > span {display:none;}
}


/* User-supplied Claude system: serif content, quiet utility type, ring depth. */
.agent-workspace button:focus-visible,.agent-workspace a:focus-visible {outline:2px solid var(--workbench-focus);outline-offset:3px;}
.suggestion-grid {border-color:var(--workbench-border-subtle);}
.suggestion-grid button {border-color:var(--workbench-border);border-radius:8px;}
.suggestion-grid button:hover {box-shadow:var(--workbench-ring-shadow);transform:none;}
.suggestion-grid strong {color:var(--workbench-ink);}
.suggestion-icon {color:var(--workbench-muted);}
.text-button,.back-video {color:var(--workbench-link)!important;}
.text-button:hover,.back-video:hover {color:var(--workbench-ink)!important;}
.workspace-footnote,.history-meta,.sidebar-footer {color:var(--workbench-tertiary);}
.assistant-avatar,.brand-symbol,.welcome-symbol {color:var(--workbench-accent);}
.user-question p {color:var(--workbench-charcoal);}
.new-analysis:hover,.quick-suggestions button:hover {box-shadow:var(--workbench-ring-shadow);}
@media(max-width:760px){.workspace-intro h1,.embedded .workspace-intro h1 {font-size:28px;line-height:1.45;}}
/* Shared geometry for the drawer and full assistant. */
.workspace-main { min-height:0; }
.workspace-main {container-type:inline-size;}
.workspace-header {height:58px;min-height:58px;padding:0 16px;gap:8px;}
.assistant-identity {gap:8px;min-width:0;}
.assistant-identity .brand-symbol {display:grid;width:22px;height:24px;font-size:24px;}
.assistant-identity strong {font-size:14px;white-space:nowrap;font-weight:500;}
.workspace-header-actions {display:flex;align-items:center;gap:4px;margin-left:auto;}
.header-action,.workspace-header .history-toggle {display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;min-width:32px;border:0;border-radius:7px;background:transparent;color:var(--workbench-muted);cursor:pointer;}
.workspace-header .close-assistant {font-size:24px!important;}
.agent-workspace:not(.embedded) .history-toggle{display:none;}
@media(max-width:760px){.agent-workspace:not(.embedded) .history-toggle{display:inline-flex;}}
.header-action:hover,.history-toggle:hover {background:var(--workbench-sidebar);}
.header-action:disabled {opacity:.4;cursor:default;}
.embedded .workspace-sidebar {display:none;}
.embedded.history-open .workspace-sidebar {display:flex!important;position:absolute;inset:0 auto 0 0;width:min(284px,85%);z-index:4;padding:20px;box-shadow:var(--workbench-whisper);}
.embedded.history-open .history-scrim {display:block;position:absolute;inset:0;z-index:3;border:0;background:rgba(41,39,34,.16);}
.embedded .history-close {display:inline-flex;align-self:flex-end;}
.workspace-content,.embedded .workspace-content {width:100%;max-width:768px;padding:24px;margin:0 auto;box-sizing:border-box;}
.is-empty .workspace-content {min-height:100%;display:flex;flex-direction:column;justify-content:center;}
.workspace-intro,.embedded .workspace-intro {width:100%;padding:0;text-align:left;margin:auto 0;}
.workspace-intro h1,.embedded .workspace-intro h1 {font:500 26px/1.4 var(--workbench-heading);margin:12px 0 10px;}
.workspace-intro p {font-size:13px;line-height:1.7;margin:0;}
.welcome-symbol {font-size:28px;margin:0 0 16px;}
.suggestion-grid {gap:10px;border:0;margin-top:24px;}
.suggestion-grid button {min-height:76px;border:1px solid var(--workbench-border-subtle);border-radius:12px;background:var(--workbench-raised);padding:12px 10px 12px 34px;box-sizing:border-box;}
.suggestion-grid strong {font-size:13px;margin-bottom:5px;}
.suggestion-grid button>span:not(.suggestion-icon) {font-size:11px!important;}
.suggestion-icon {left:11px;top:12px;font-size:16px;}
.suggestion-grid i {display:none;}
.conversation-context {margin-top:14px;max-width:100%;box-sizing:border-box;}
.conversation-context small {display:none;}
.workspace-tools {font-size:12px;margin:10px 0 16px;}
.workspace-tools>summary {padding:5px 0;}
.workspace-tools>summary span {display:none;}
.composer-wrap,.embedded .composer-wrap {max-width:768px;padding:12px 24px 16px;box-sizing:border-box;}
.chat-composer {padding:12px;border:1px solid var(--workbench-ring);box-shadow:0 2px 8px rgba(38,34,24,.025);}
.chat-composer textarea {font-size:14px;line-height:1.6;min-height:60px;}
.composer-actions {gap:6px;}
.composer-hint {white-space:nowrap;flex:none;}
.composer-hint>span {display:inline;font-size:11px!important;}
.composer-controls {gap:6px;}
.composer-controls .model-select {font-size:12px;max-width:152px;min-height:32px;padding:4px;}
.send-button {width:32px;height:32px;min-width:32px;font-size:24px;}
.stop-button {font-size:14px;}
.workspace-footnote {display:flex;justify-content:space-between;gap:8px;margin:8px 0 0;font-size:11px!important;line-height:1.6;}
.workspace-footnote>span {font-size:11px;}
.user-question {margin:0 0 24px;}
.user-question>span {display:none;}
.user-question p {max-width:88%;margin-left:auto;padding:11px 14px;border-radius:12px 12px 3px 12px;font-size:14px;line-height:1.7;overflow-wrap:anywhere;}
.result-heading {gap:8px;flex-wrap:wrap;}
.result-heading strong {font-size:13px;}
.answer-text {font-size:14px;line-height:1.8;}
.assistant-result>small {display:block;font-size:11px;color:var(--workbench-muted);margin:0 0 10px;}
.status-badge.waiting_operation {background:var(--workbench-soft);color:var(--workbench-accent);}
.paused-actions {display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:12px 0 18px;}
.resume-analysis {border:0;border-radius:8px;background:var(--workbench-accent);color:var(--workbench-raised);padding:8px 12px;font-size:12px;cursor:pointer;}
.execution-details {margin-top:16px;padding-top:12px;border-top:1px solid var(--workbench-border);}
@media(max-width:600px){
 .workspace-content,.embedded .workspace-content {padding:20px 16px;}
 .composer-wrap,.embedded .composer-wrap {padding:10px 16px 16px;}
 .workspace-intro h1,.embedded .workspace-intro h1 {font-size:24px;}
 .composer-controls .model-select {max-width:132px;}
 .workspace-footnote>span {display:none;}
 .composer-hint>span {font-size:11px!important;}
}
@media(pointer:coarse){.header-action,.workspace-header .history-toggle{width:44px;height:44px;}.send-button{width:44px;height:44px;}.chat-composer textarea{font-size:16px;}.composer-controls .model-select{min-height:44px;}}
@media(min-width:1000px){
 .embedded .workspace-sidebar,.embedded.history-open .workspace-sidebar {display:flex!important;position:relative;inset:auto;flex:0 0 var(--assistant-history-width,208px);width:var(--assistant-history-width,208px);min-width:0;padding:28px 14px 18px;box-shadow:none;border-right:1px solid var(--workbench-border);z-index:auto;}
 .embedded .history-close,.embedded .history-scrim,.embedded .workspace-header .history-toggle {display:none!important;}
 .embedded .workspace-brand {font-size:24px;}
 .embedded .workspace-brand span {letter-spacing:1px;}
 .embedded .new-analysis {font-size:12px;padding:10px 8px;}
 .embedded .history-heading {padding:0 6px;}
 .embedded .history-list {min-height:0;overflow:auto;scrollbar-width:thin;}
 .embedded .history-list button {padding:12px 8px;}
 .embedded .history-meta {font-size:11px!important;gap:8px;}
 .embedded .workspace-sidebar .sidebar-footer {font-size:11px!important;}
}
@container(max-width:440px){.workspace-footnote>span{display:none;}.workspace-intro h1,.embedded .workspace-intro h1{font-size:24px;}.suggestion-grid{gap:8px;}}
</style>
