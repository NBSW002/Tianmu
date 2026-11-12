<script setup>
import { ref, watch, computed, onBeforeUnmount } from "vue";
import {
  Clock,
  VideoPlay,
  Delete,
  ArrowLeft,
  ArrowRight,
  Close,
} from "@element-plus/icons-vue";
import {
  groupHistory,
  playbackTime,
  playbackProgress,
  historyClock,
} from "@/lib/history-view.mjs";
import { ElMessage, ElMessageBox } from "element-plus";
import request from "@/lib/request";
import HeaderBar from "@/components/headerBar/HeaderBar.vue";
import { useUserStore } from "@/store/user";
import { mediaUrl } from "@/lib/media";
const users = useUserStore(),
  items = ref([]),
  page = ref(1),
  more = ref(false),
  busy = ref(false),
  error = ref("");
const groups = computed(() => groupHistory(items.value));
let controller,
  generation = 0;
async function load() {
  controller?.abort();
  controller = new AbortController();
  const version = ++generation;
  items.value = [];
  error.value = "";
  busy.value = true;
  if (!users.isLogin) {
    busy.value = false;
    return;
  }
  try {
    const result = await request({
      url: "/history",
      params: { page: page.value },
      signal: controller.signal,
    });
    if (version === generation) {
      items.value = result.data.items;
      more.value = result.data.hasMore;
    }
  } catch (failure) {
    if (version === generation)
      error.value =
        typeof failure === "string" ? failure : "历史记录加载失败，请重试";
  } finally {
    if (version === generation) busy.value = false;
  }
}
async function remove(item) {
  const version = generation;
  if (!item) {
    try {
      await ElMessageBox.confirm("确认清空全部观看历史？", "清空历史", {
        confirmButtonText: "清空",
        cancelButtonText: "取消",
      });
    } catch {
      return;
    }
  }
  if (version !== generation || busy.value) return;
  busy.value = true;
  try {
    await request({
      url: item ? `/history/${item.videoId}` : "/history",
      method: "DELETE",
      signal: controller.signal,
    });
    if (version === generation) {
      if (!item) page.value = 1;
      await load();
    }
  } catch (failure) {
    if (version === generation)
      ElMessage.error(
        typeof failure === "string" ? failure : "删除失败，请重试"
      );
  } finally {
    if (version === generation) busy.value = false;
  }
}
function navigate(delta) {
  page.value += delta;
  load();
}
watch(
  () => [users.user.userId, users.isLogin],
  () => {
    page.value = 1;
    load();
  },
  { immediate: true }
);
onBeforeUnmount(() => {
  generation++;
  controller?.abort();
});
</script>
<template>
  <HeaderBar :is-fix-header-bar="true" hero />
  <main class="watch-history">
    <header class="history-page-heading">
      <div>
        <h1>观看历史</h1>
        <p>接着上次看到的地方</p>
      </div>
      <button class="clear-history" :disabled="busy" @click="remove(null)">
        <Delete />清空历史
      </button>
    </header>
    <div class="history-page-tabs">
      <span class="selected"><VideoPlay />视频</span
      ><span class="history-privacy">仅自己可见</span>
    </div>
    <div class="history-page-content">
      <div
        v-if="busy && !items.length"
        class="history-page-skeleton"
        aria-label="正在加载观看历史"
      >
        <div v-for="index in 3" :key="index"><i /><span /></div>
      </div>
      <div v-else-if="error" class="history-state" role="alert">
        <Clock />
        <h2>暂时无法加载历史</h2>
        <p>{{ error }}</p>
        <button @click="load">重新加载</button>
      </div>
      <div v-else-if="!items.length" class="history-state">
        <Clock />
        <h2>还没有观看记录</h2>
        <p>看过的视频会出现在这里</p>
        <router-link to="/">去首页看看<ArrowRight /></router-link>
      </div>
      <section
        v-for="group in groups"
        :key="group.label"
        class="history-page-group"
      >
        <h2 class="history-date-heading"><i />{{ group.label }}</h2>
        <ul>
          <li
            v-for="item in group.items"
            :key="item.videoId"
            class="history-video-row"
          >
            <router-link
              :to="`/video/${item.videoId}?t=${Math.floor(
                item.positionSeconds
              )}`"
              class="history-cover"
            >
              <img
                v-if="item.coverUrl"
                :src="mediaUrl(item.coverUrl, 480)"
                alt=""
                loading="lazy"
              /><VideoPlay v-else class="history-cover-fallback" />
              <span v-if="item.duration > 0" class="history-duration">{{
                playbackTime(item.duration)
              }}</span>
              <i
                v-if="
                  playbackProgress(item.positionSeconds, item.duration) != null
                "
                class="history-cover-progress"
                :style="{
                  width:
                    playbackProgress(item.positionSeconds, item.duration) + '%',
                }"
              />
              <span class="resume-overlay"><VideoPlay />继续观看</span>
            </router-link>
            <div class="history-video-info">
              <router-link
                :to="`/video/${item.videoId}?t=${Math.floor(
                  item.positionSeconds
                )}`"
                class="history-video-title"
                ><h3>{{ item.title }}</h3></router-link
              >
              <span v-if="item.creatorName" class="history-video-author"
                ><span class="up-mark">UP</span>{{ item.creatorName }}</span
              >
              <div class="history-video-details">
                <span>上次看到 {{ playbackTime(item.positionSeconds) }}</span
                ><span class="detail-divider" /><span
                  ><Clock />{{ historyClock(item.watchedAt) }}</span
                >
              </div>
            </div>
            <button
              class="remove-history"
              :aria-label="`移除《${item.title}》观看记录`"
              title="移除记录"
              :disabled="busy"
              @click="remove(item)"
            >
              <Close />
            </button>
          </li>
        </ul>
      </section>
    </div>
    <footer v-if="items.length || page > 1" class="history-pagination">
      <button :disabled="busy || page <= 1" @click="navigate(-1)">
        <ArrowLeft />上一页</button
      ><span>{{ page }}</span
      ><button :disabled="busy || !more" @click="navigate(1)">
        下一页<ArrowRight />
      </button>
    </footer>
  </main>
</template>
<style scoped>
:global(html.creator-workspace-page #app) {
  background: #f7f8fa;
  min-height: 100vh;
}
:global(html.creator-workspace-page #app .header-bar) {
  background: #fff;
  border-bottom: 1px solid #eceef2;
  box-shadow: none;
}
:global(html.creator-workspace-page #app .header-bar #nav-searchform) {
  background: #f4f5f7;
  border-color: #eceef1;
}
:global(html.creator-workspace-page body) {
  background: #f7f8fa;
}
.watch-history {
  font-family: "Harmony Font", "Microsoft YaHei", sans-serif;
  max-width: 1120px;
  margin: 104px auto 48px;
  padding: 0 32px;
  color: #25272b;
  box-sizing: border-box;
}
.history-page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 28px;
}
.history-page-heading h1 {
  font-size: 26px;
  line-height: 36px;
  font-weight: 500;
  margin: 0 0 4px;
}
.history-page-heading p {
  font-size: 13px;
  color: #727985;
  line-height: 20px;
  margin: 0;
}
.watch-history button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font: inherit;
  border: 0;
  background: none;
  cursor: pointer;
  border-radius: 6px;
  color: #666d78;
}
.watch-history button:disabled {
  opacity: 0.4;
  cursor: default;
}
.watch-history button svg {
  width: 16px;
  height: 16px;
}
.clear-history {
  font-size: 13px !important;
  padding: 8px 12px;
}
.clear-history:hover {
  background: #edeff2;
}
.history-page-tabs {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e8ebef;
  background: #fff;
  border-radius: 10px 10px 0 0;
  padding: 0 28px;
}
.history-page-tabs .selected {
  display: flex;
  align-items: center;
  gap: 8px;
  align-self: stretch;
  border-bottom: 2px solid #b45538;
  color: #b45538;
  font-size: 14px;
  font-weight: 500;
  padding: 0 4px;
}
.selected svg {
  width: 18px;
  height: 18px;
}
.history-privacy {
  font-size: 12px;
  color: #727985;
}
.history-page-content {
  background: white;
  padding: 8px 28px 24px;
  border-radius: 0 0 10px 10px;
  min-height: 280px;
}
.history-page-group {
  padding-top: 24px;
}
.history-date-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  line-height: 24px;
  font-weight: 500;
  margin: 0 0 16px;
  color: #404650;
}
.history-date-heading i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #bbc1cb;
}
ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.history-video-row {
  display: flex;
  align-items: flex-start;
  gap: 24px;
  position: relative;
  padding: 0 0 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid #f0f2f5;
}
.history-video-row:last-child {
  margin-bottom: 0;
}
.history-cover {
  display: grid;
  place-items: center;
  position: relative;
  flex: none;
  width: 208px;
  aspect-ratio: 16/9;
  border-radius: 6px;
  overflow: hidden;
  background: #eef0f4;
}
.history-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.history-cover-fallback {
  width: 36px;
  height: 36px;
  color: #b4bdc8;
}
.history-duration {
  position: absolute;
  right: 6px;
  bottom: 6px;
  color: #fff;
  background: #0009;
  border-radius: 3px;
  font-size: 11px;
  padding: 2px 5px;
  line-height: 16px;
  font-variant-numeric: tabular-nums;
}
.history-cover-progress {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 3px;
  background: #b45538;
}
.resume-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 13px;
  color: #fff;
  background: #0005;
  opacity: 0;
  transition: opacity 0.15s;
}
.resume-overlay svg {
  width: 24px;
  height: 24px;
}
.history-cover:hover .resume-overlay,
.history-cover:focus-visible .resume-overlay {
  opacity: 1;
}
.history-video-info {
  min-width: 0;
  flex: 1;
  padding-top: 4px;
}
.history-video-title {
  display: block;
  color: inherit;
  text-decoration: none;
  max-width: 640px;
}
.history-video-title h3 {
  font-size: 16px;
  line-height: 24px;
  font-weight: 400;
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.history-video-title:hover {
  color: #b45538;
}
.history-video-author {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  line-height: 20px;
  color: #727985;
  margin-bottom: 12px;
}
.up-mark {
  font-size: 9px;
  border: 1px solid #a3aab5;
  border-radius: 2px;
  padding: 0 2px;
  line-height: 11px;
  font-weight: 500;
}
.history-video-details {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  line-height: 20px;
  color: #727985;
  font-variant-numeric: tabular-nums;
}
.history-video-details > span {
  display: flex;
  align-items: center;
  gap: 4px;
}
.history-video-details svg {
  width: 13px;
  height: 13px;
}
.detail-divider {
  width: 1px;
  height: 10px;
  background: #d9dde4;
}
.watch-history .remove-history {
  width: 32px;
  height: 32px;
  color: #9098a4;
  opacity: 0;
  flex: none;
}
.history-video-row:hover .remove-history,
.remove-history:focus-visible {
  opacity: 1 !important;
}
.remove-history:hover {
  background: #f3f4f6;
  color: #b45538;
}
.history-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 24px 0;
}
.history-pagination button {
  padding: 8px 12px;
  background: #fff;
  border: 1px solid #e4e7ec;
}
.history-pagination > span {
  min-width: 28px;
  text-align: center;
  font-size: 13px;
  color: #727985;
  font-variant-numeric: tabular-nums;
}
.history-state {
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
  min-height: 280px;
  text-align: center;
}
.history-state > svg {
  width: 48px;
  height: 48px;
  color: #b9c1cb;
}
.history-state h2 {
  font-size: 17px;
  font-weight: 400;
  margin: 0;
}
.history-state p {
  font-size: 13px;
  line-height: 20px;
  color: #727985;
  margin: 0;
}
.history-state a,
.history-state button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  text-decoration: none;
  color: #b45538;
  font-size: 13px;
  margin-top: 8px;
}
.history-state a svg {
  width: 14px;
  height: 14px;
}
.history-page-skeleton {
  padding-top: 24px;
}
.history-page-skeleton > div {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
}
.history-page-skeleton i {
  width: 208px;
  height: 116px;
  background: #f0f2f5;
  border-radius: 6px;
}
.history-page-skeleton span {
  height: 64px;
  flex: 1;
  background: #f6f7f9;
  border-radius: 4px;
}
.watch-history :focus-visible {
  outline: 2px solid #b45538;
  outline-offset: 3px;
}
@media (max-width: 699px) {
  .watch-history {
    margin-top: 140px;
    padding: 0 16px;
  }
  .history-page-heading {
    margin-bottom: 20px;
  }
  .history-page-heading h1 {
    font-size: 23px;
    line-height: 32px;
  }
  .history-page-heading p {
    font-size: 12px;
  }
  .clear-history {
    padding: 8px !important;
    font-size: 12px !important;
  }
  .history-page-tabs {
    padding: 0 16px;
  }
  .history-page-content {
    padding: 0 16px 16px;
  }
  .history-video-row {
    gap: 12px;
    padding-bottom: 16px;
    margin-bottom: 16px;
  }
  .history-cover {
    width: 124px;
  }
  .history-video-title h3 {
    font-size: 13px;
    line-height: 19px;
    margin-bottom: 6px;
  }
  .history-video-info {
    padding: 0;
  }
  .history-video-author {
    font-size: 11px;
    line-height: 16px;
    margin-bottom: 6px;
  }
  .history-video-details {
    flex-wrap: wrap;
    gap: 4px 8px;
    font-size: 10px;
    line-height: 16px;
  }
  .detail-divider {
    display: none !important;
  }
  .history-video-details > span:last-child {
    display: none;
  }
  .watch-history .remove-history {
    width: 20px;
    height: 24px;
    opacity: 1;
  }
  .history-date-heading {
    font-size: 13px;
    margin-bottom: 12px;
  }
  .history-video-row:last-child {
    border-bottom: 0;
  }
  .history-page-skeleton i {
    width: 124px;
    height: 70px;
  }
  .history-page-skeleton > div {
    gap: 12px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .resume-overlay {
    transition: none;
  }
}
</style>
