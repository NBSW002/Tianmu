<script setup>
import { ref, computed, watch, onBeforeUnmount, onMounted } from "vue";
import { Clock, ArrowRight, VideoPlay } from "@element-plus/icons-vue";
import { useRouter } from "vue-router";
import { useUserStore } from "@/store/user";
import request from "@/lib/request";
import { mediaUrl } from "@/lib/media";
import {
  groupHistory,
  playbackTime,
  playbackProgress,
  historyClock,
} from "@/lib/history-view.mjs";

const users = useUserStore(),
  router = useRouter();
const root = ref(null),
  open = ref(false),
  busy = ref(false),
  error = ref(""),
  items = ref([]);
const groups = computed(() => groupHistory(items.value.slice(0, 6)));
let controller,
  generation = 0,
  timer,
  loadedAt = 0;
async function load() {
  if (!users.isLogin || busy.value || Date.now() - loadedAt < 15000) return;
  controller?.abort();
  controller = new AbortController();
  const version = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await request({
      url: "/history",
      params: { page: 1 },
      signal: controller.signal,
    });
    if (version === generation) {
      items.value = result.data.items;
      loadedAt = Date.now();
    }
  } catch (failure) {
    if (version === generation) error.value = "历史记录加载失败";
  } finally {
    if (version === generation) busy.value = false;
  }
}
function show() {
  clearTimeout(timer);
  open.value = true;
  load();
}
function leave() {
  timer = setTimeout(() => {
    open.value = false;
  }, 140);
}
function closeOnEscape(event) {
  if (event.key === "Escape") open.value = false;
}
function closeOutside(event) {
  if (!root.value?.contains(event.target)) open.value = false;
}
function all() {
  open.value = false;
  if (!users.isLogin) {
    users.updateDialogVisible(true);
    return;
  }
  router.push("/history");
}
function focusLeft(event) {
  if (!root.value?.contains(event.relatedTarget)) open.value = false;
}
watch(
  () => [users.user.userId, users.isLogin],
  () => {
    generation++;
    controller?.abort();
    items.value = [];
    busy.value = false;
    error.value = "";
    loadedAt = 0;
    if (open.value) load();
  }
);
onMounted(() => {
  document.addEventListener("pointerdown", closeOutside);
  document.addEventListener("keydown", closeOnEscape);
});
onBeforeUnmount(() => {
  generation++;
  clearTimeout(timer);
  controller?.abort();
  document.removeEventListener("pointerdown", closeOutside);
  document.removeEventListener("keydown", closeOnEscape);
});
</script>

<template>
  <div
    ref="root"
    class="history-menu"
    @mouseenter="show"
    @mouseleave="leave"
    @focusout="focusLeft"
    @keydown.esc="open = false"
  >
    <button
      type="button"
      class="creator-entry history-trigger header-action"
      aria-label="观看历史"
      :aria-expanded="open"
      @focus="show"
      @keydown.down.prevent="show"
      @click="all"
    >
      <Clock aria-hidden="true" /><span>历史</span>
    </button>
    <section
      v-if="open"
      class="history-popover"
      aria-label="最近观看"
      @mouseenter="show"
    >
      <header>
        <span class="active-tab"><VideoPlay aria-hidden="true" />视频</span
        ><button type="button" @click="all">
          全部历史<ArrowRight aria-hidden="true" />
        </button>
      </header>
      <div class="history-popover-scroll">
        <div v-if="!users.isLogin" class="history-popover-empty">
          <Clock />
          <p>登录后查看观看历史</p>
          <button type="button" @click="all">立即登录</button>
        </div>
        <div
          v-else-if="busy && !items.length"
          class="history-skeleton"
          aria-label="正在加载"
        >
          <div v-for="index in 3" :key="index"><i /><span /></div>
        </div>
        <div v-else-if="error" class="history-popover-empty">
          <p>{{ error }}</p>
          <button type="button" @click="load">重新加载</button>
        </div>
        <div v-else-if="!items.length" class="history-popover-empty">
          <Clock />
          <p>还没有观看记录</p>
          <small>看过的视频会出现在这里</small>
        </div>
        <section
          v-for="group in groups"
          :key="group.label"
          class="history-day-group"
        >
          <h3>{{ group.label }}</h3>
          <router-link
            v-for="item in group.items"
            :key="item.videoId"
            class="recent-video"
            :to="`/video/${item.videoId}?t=${Math.floor(item.positionSeconds)}`"
            @click="open = false"
          >
            <div class="recent-cover">
              <img
                v-if="item.coverUrl"
                :src="mediaUrl(item.coverUrl, 320)"
                alt=""
                loading="lazy"
              /><VideoPlay v-else class="cover-fallback" /><span
                class="position-tag"
                >{{ playbackTime(item.positionSeconds)
                }}<template v-if="item.duration > 0">
                  / {{ playbackTime(item.duration) }}</template
                ></span
              ><i
                v-if="
                  playbackProgress(item.positionSeconds, item.duration) != null
                "
                class="watch-progress"
                :style="{
                  width:
                    playbackProgress(item.positionSeconds, item.duration) + '%',
                }"
              />
            </div>
            <div class="recent-info">
              <h4>{{ item.title }}</h4>
              <span class="recent-time"
                ><Clock />{{ historyClock(item.watchedAt) }}</span
              ><span v-if="item.creatorName" class="recent-author">{{
                item.creatorName
              }}</span>
            </div>
          </router-link>
        </section>
      </div>
      <button
        v-if="users.isLogin"
        type="button"
        class="history-popover-footer"
        @click="all"
      >
        查看全部观看历史<ArrowRight />
      </button>
    </section>
  </div>
</template>

<style scoped>
.history-menu {
  font-family: "Harmony Font", "Microsoft YaHei", sans-serif;
  position: relative;
  color: inherit;
}
.history-trigger {
  min-width: 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  font-size: 11px;
  line-height: 16px;
  color: #4a4e54;
  cursor: pointer;
}
:global(.header-bar--hero) .history-trigger {
  color: #fff;
}
.history-trigger:focus-visible {
  outline: 2px solid #b45538;
  outline-offset: 4px;
}
.history-trigger > svg {
  width: 22px;
  height: 22px;
}
.history-popover {
  position: absolute;
  top: calc(100% + 20px);
  right: -88px;
  width: 424px;
  color: #25272b;
  background: #fff;
  border: 1px solid #eceef1;
  border-radius: 10px;
  box-shadow: 0 8px 28px #18191c14;
  text-align: left;
  z-index: 1200;
  overflow: hidden;
}
.history-popover header {
  height: 56px;
  padding: 0 20px;
  border-bottom: 1px solid #f0f1f3;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.active-tab {
  height: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  color: #b45538;
  font-size: 14px;
  font-weight: 500;
  border-bottom: 2px solid currentColor;
  padding: 0 4px;
}
.active-tab svg,
.history-popover header button svg {
  width: 16px;
  height: 16px;
}
.history-popover button {
  border: 0;
  background: none;
  cursor: pointer;
  font: inherit;
}
.history-popover header button {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #92969e;
}
.history-popover-scroll {
  max-height: 496px;
  overflow: auto;
  overscroll-behavior: contain;
  padding: 4px 0 12px;
  scrollbar-width: thin;
  scrollbar-color: #d5d8df transparent;
}
.history-day-group h3 {
  margin: 16px 20px 8px;
  font-size: 12px;
  line-height: 20px;
  font-weight: 400;
  color: #8a8f98;
}
.recent-video {
  display: flex;
  gap: 12px;
  padding: 8px 20px;
  text-decoration: none;
  color: inherit;
}
.recent-video:hover,
.recent-video:focus-visible {
  background: #f7f8fa;
}
.recent-cover {
  position: relative;
  flex: none;
  width: 144px;
  aspect-ratio: 16/9;
  background: #edf0f3;
  border-radius: 5px;
  overflow: hidden;
  display: grid;
  place-items: center;
}
.recent-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cover-fallback {
  width: 28px;
  height: 28px;
  color: #b6bcc5;
}
.position-tag {
  position: absolute;
  right: 4px;
  bottom: 5px;
  padding: 2px 4px;
  border-radius: 3px;
  background: #0009;
  color: #fff;
  font-size: 10px;
  line-height: 14px;
  font-variant-numeric: tabular-nums;
}
.watch-progress {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 3px;
  background: #b45538;
}
.recent-info {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.recent-info h4 {
  font-size: 13px;
  font-weight: 400;
  line-height: 19px;
  margin: 0 0 2px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}
.recent-time,
.recent-author {
  font-size: 11px;
  line-height: 16px;
  color: #9499a0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.recent-time {
  display: flex;
  align-items: center;
  gap: 4px;
}
.recent-time svg {
  height: 12px;
  width: 12px;
}
.history-popover .history-popover-footer {
  width: 100%;
  height: 44px;
  border-top: 1px solid #f0f1f3;
  color: #6c737e;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.history-popover-footer svg {
  width: 13px;
  height: 13px;
}
.history-popover-footer:hover {
  background: #f7f8fa;
}
.history-popover-empty {
  padding: 40px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #858b95;
}
.history-popover-empty > svg {
  width: 36px;
  height: 36px;
  color: #bec4cd;
}
.history-popover-empty p {
  font-size: 14px;
  margin: 0;
}
.history-popover-empty small {
  font-size: 12px;
  color: #a1a6ae;
}
.history-popover-empty button {
  font-size: 12px;
  color: #b45538;
}
.history-skeleton {
  padding: 20px;
}
.history-skeleton > div {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.history-skeleton i {
  width: 144px;
  height: 80px;
  border-radius: 5px;
  background: #f0f2f5;
}
.history-skeleton span {
  flex: 1;
  height: 44px;
  background: #f5f6f8;
  border-radius: 4px;
}
.history-popover a:focus-visible,
.history-popover button:focus-visible {
  outline: 2px solid #b45538;
  outline-offset: -2px;
}
@media (max-width: 699px) {
  .history-popover {
    display: none;
  }
  .history-trigger > svg {
    height: 20px;
    width: 20px;
  }
  .history-trigger span {
    display: none;
  }
}
</style>
