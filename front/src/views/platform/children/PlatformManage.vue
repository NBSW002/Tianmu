<script setup>
import { ref, watch, onBeforeUnmount } from "vue";
import {
  VideoCamera,
  ChatDotRound,
  EditPen,
  Delete,
  ArrowLeft,
  ArrowRight,
  Plus,
  VideoPlay,
  Clock,
} from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import request from "@/lib/request";
import { useUserStore } from "@/store/user";
import { mediaUrl } from "@/lib/media";

const props = defineProps({ kind: { type: String, default: "videos" } });
const users = useUserStore();
const items = ref([]),
  page = ref(1),
  hasMore = ref(false),
  busy = ref(false),
  error = ref(""),
  editing = ref(null);
let controller,
  generation = 0;
const statusText = (value) =>
  ({ 0: "已下架", 1: "审核中", 2: "已发布" }[value] || "状态未知");
async function load() {
  controller?.abort();
  controller = new AbortController();
  const version = ++generation;
  items.value = [];
  editing.value = null;
  error.value = "";
  busy.value = true;
  if (!users.isLogin) {
    busy.value = false;
    return;
  }
  try {
    const result = await request({
      url: props.kind === "videos" ? "/creator/videos" : "/creator/feedback",
      params: { page: page.value, source: props.kind },
      signal: controller.signal,
    });
    if (version === generation) {
      items.value = result.data.items;
      hasMore.value = result.data.hasMore;
    }
  } catch (failure) {
    if (version === generation)
      error.value = typeof failure === "string" ? failure : "加载失败，请重试";
  } finally {
    if (version === generation) busy.value = false;
  }
}
async function mutate(action, confirmation) {
  const version = generation;
  if (busy.value) return;
  if (confirmation) {
    try {
      await ElMessageBox.confirm(confirmation, "确认操作", {
        type: "warning",
        confirmButtonText: "确认",
        cancelButtonText: "取消",
      });
    } catch {
      return;
    }
  }
  if (version !== generation || !users.isLogin) return;
  busy.value = true;
  try {
    await action(controller.signal);
    if (version === generation) {
      ElMessage.success("已保存");
      await load();
    }
  } catch (failure) {
    if (version === generation)
      ElMessage.error(
        typeof failure === "string" ? failure : "操作失败，请刷新后重试"
      );
  } finally {
    if (version === generation) busy.value = false;
  }
}
function save() {
  const input = { ...editing.value };
  return mutate(
    (signal) =>
      request({
        url: `/creator/videos/${input.videoId}`,
        method: "PATCH",
        data: input,
        signal,
      }),
    input.status === 2 ? "修改已发布稿件后将重新进入审核，确认保存？" : ""
  );
}
function changeStatus(item) {
  return mutate(
    (signal) =>
      request({
        url: `/creator/videos/${item.videoId}/status`,
        method: "PUT",
        data: {
          expectedStatus: item.status,
          status: item.status === 2 ? 0 : 1,
        },
        signal,
      }),
    item.status === 2
      ? "下架后观众将无法观看，确认下架？"
      : "确认重新提交审核？"
  );
}
function remove(item) {
  const url =
    props.kind === "videos"
      ? `/creator/videos/${item.videoId}`
      : `/creator/feedback/${props.kind}/${item.itemId}`;
  return mutate(
    (signal) => request({ url, method: "DELETE", signal }),
    props.kind === "videos"
      ? "确认删除这份稿件？"
      : props.kind === "COMMENT"
      ? "确认删除这条评论及其所有回复？"
      : "确认删除这条弹幕？"
  );
}
function edit(item) {
  editing.value = {
    ...item,
    description: item.description || "",
    expectedTitle: item.title,
    expectedDescription: item.description || "",
  };
}
function navigate(delta) {
  page.value += delta;
  load();
}
watch(
  () => [props.kind, users.user.userId, users.isLogin],
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
  <section class="creator-manage">
    <header class="manage-heading">
      <div>
        <h1>
          {{
            kind === "videos"
              ? "稿件管理"
              : kind === "COMMENT"
              ? "评论管理"
              : "弹幕管理"
          }}
        </h1>
        <p>
          {{
            kind === "videos"
              ? "每一份创作，都在这里"
              : kind === "COMMENT"
              ? "看看观众留下了什么"
              : "管理视频中的即时反馈"
          }}
        </p>
      </div>
      <router-link class="publish-action" to="/platform/upload/video"
        ><Plus />发布新视频</router-link
      >
    </header>
    <div class="manage-tabbar">
      <span class="manage-current">{{
        kind === "videos"
          ? "全部稿件"
          : kind === "COMMENT"
          ? "全部评论"
          : "全部弹幕"
      }}</span
      ><span v-if="!busy && items.length" class="page-count"
        >本页 {{ items.length }} {{ kind === "videos" ? "份稿件" : "条" }}</span
      >
    </div>
    <div v-if="busy && !items.length" class="manage-loading" role="status">
      <div v-for="index in 3" :key="index"><i /><span /></div>
      <span class="loading-label">正在加载</span>
    </div>
    <div v-else-if="error" class="manage-empty" role="alert">
      <VideoCamera />
      <h2>暂时无法加载内容</h2>
      <p>{{ error }}</p>
      <button class="text-action" type="button" @click="load">重试</button>
    </div>
    <div v-else-if="!items.length" class="manage-empty">
      <VideoCamera v-if="kind === 'videos'" /><ChatDotRound v-else />
      <h2>
        {{
          kind === "videos"
            ? "还没有稿件"
            : kind === "COMMENT"
            ? "还没有收到评论"
            : "还没有收到弹幕"
        }}
      </h2>
      <p>
        {{
          page > 1
            ? "这一页没有更多内容了"
            : kind === "videos"
            ? "上传一段视频，开始你的第一份创作"
            : "观众的反馈会出现在这里"
        }}
      </p>
      <router-link
        v-if="kind === 'videos'"
        class="publish-action"
        to="/platform/upload/video"
        ><Plus />发布视频</router-link
      >
    </div>
    <template v-else>
      <div v-if="kind === 'videos'" class="manuscript-columns">
        <span>视频信息</span><span>状态</span><span>操作</span>
      </div>
      <ul class="manage-items">
        <li
          v-for="item in items"
          :key="item.videoId + ':' + (item.itemId || '')"
          :class="kind === 'videos' ? 'manuscript-row' : 'feedback-row'"
        >
          <template v-if="kind === 'videos'">
            <div class="manuscript-content">
              <div class="manuscript-cover">
                <img
                  v-if="item.coverUrl"
                  :src="mediaUrl(item.coverUrl, 480)"
                  alt=""
                  loading="lazy"
                /><VideoPlay v-else />
              </div>
              <div class="manuscript-info">
                <h2>
                  <router-link
                    v-if="item.status === 2"
                    :to="`/video/${item.videoId}`"
                    >{{ item.title }}</router-link
                  ><template v-else>{{ item.title }}</template>
                </h2>
                <p v-if="item.description" class="manuscript-description">
                  {{ item.description }}
                </p>
                <span v-if="item.updatedAt" class="manuscript-date"
                  >最近编辑 {{ item.updatedAt.slice(0, 16) }}</span
                >
              </div>
            </div>
            <div class="manuscript-state">
              <span class="status-label" :class="`status-${item.status}`"
                ><i />{{ statusText(item.status) }}</span
              >
            </div>
            <div class="manuscript-actions">
              <button class="edit-action" :disabled="busy" @click="edit(item)">
                <EditPen />编辑</button
              ><button
                v-if="item.status === 0 || item.status === 2"
                :disabled="busy"
                @click="changeStatus(item)"
              >
                {{ item.status === 2 ? "下架" : "提交审核" }}</button
              ><button
                class="delete-action"
                :disabled="busy"
                @click="remove(item)"
              >
                删除
              </button>
            </div>
          </template>
          <template v-else
            ><span class="feedback-avatar" aria-hidden="true">{{
              Array.from(item.nickname || "用户")[0]
            }}</span>
            <div class="feedback-info">
              <div class="feedback-author">
                <strong>{{ item.nickname || "用户" }}</strong
                ><time>{{ item.createdAt }}</time>
              </div>
              <p class="feedback-content">{{ item.content }}</p>
              <div class="feedback-context">
                <VideoCamera /><span>{{ item.title }}</span
                ><span
                  v-if="item.positionSeconds != null"
                  class="feedback-position"
                  ><Clock />{{ Math.floor(item.positionSeconds / 60) }}:{{
                    String(Math.floor(item.positionSeconds % 60)).padStart(
                      2,
                      "0"
                    )
                  }}</span
                >
              </div>
            </div>
            <button
              class="feedback-delete"
              :disabled="busy"
              @click="remove(item)"
            >
              <Delete /><span
                >删除{{ kind === "COMMENT" ? "评论" : "弹幕" }}</span
              >
            </button></template
          >
        </li>
      </ul>
    </template>
    <footer v-if="items.length || page > 1" class="manage-pagination">
      <button :disabled="busy || page <= 1" @click="navigate(-1)">
        <ArrowLeft />上一页</button
      ><span>{{ page }}</span
      ><button :disabled="busy || !hasMore" @click="navigate(1)">
        下一页<ArrowRight />
      </button>
    </footer>
    <el-dialog
      :model-value="!!editing"
      title="编辑稿件"
      width="min(560px, calc(100vw - 32px))"
      class="creator-edit-dialog"
      :close-on-click-modal="false"
      align-center
      @close="editing = null"
    >
      <form v-if="editing" class="creator-edit-form" @submit.prevent="save">
        <label
          >标题<input
            v-model="editing.title"
            maxlength="255"
            required
            placeholder="给视频起个标题" /></label
        ><label
          >简介<textarea
            v-model="editing.description"
            maxlength="4096"
            rows="5"
            placeholder="介绍一下这份创作"
          ></textarea>
        </label>
        <p v-if="editing.status === 2">已发布稿件修改后会重新进入审核。</p>
        <div class="edit-form-actions">
          <button :disabled="busy" type="button" @click="editing = null">
            取消</button
          ><button class="save-edit" :disabled="busy" type="submit">
            保存
          </button>
        </div>
      </form>
    </el-dialog>
  </section>
</template>
<style scoped>
.creator-manage {
  font-family: "Harmony Font", "Microsoft YaHei", sans-serif;
  padding: 28px 32px;
  color: #25272b;
  --manage-accent: #b45538;
  box-sizing: border-box;
}
.manage-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  margin-bottom: 28px;
}
.manage-heading h1 {
  font-size: 24px;
  font-weight: 500;
  line-height: 32px;
  margin: 0 0 6px;
}
.manage-heading p {
  font-size: 13px;
  line-height: 20px;
  color: #727985;
  margin: 0;
}
.publish-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: var(--manage-accent);
  color: #fff;
  text-decoration: none;
  font-size: 13px;
  line-height: 20px;
  padding: 10px 16px;
  border-radius: 6px;
  white-space: nowrap;
}
.publish-action svg {
  width: 16px;
  height: 16px;
}
.publish-action:hover {
  background: #9f472f;
}
.manage-tabbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 48px;
  border-bottom: 1px solid #e9ecf0;
}
.manage-current {
  align-self: stretch;
  display: flex;
  align-items: center;
  border-bottom: 2px solid var(--manage-accent);
  color: var(--manage-accent);
  font-weight: 500;
  font-size: 14px;
  padding: 0 4px;
}
.page-count {
  font-size: 12px;
  color: #727985;
}
.manuscript-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 100px 168px;
  gap: 24px;
  background: #fafbfc;
  padding: 12px 16px;
  margin-top: 20px;
  font-size: 12px;
  color: #727985;
  border-radius: 4px;
}
.manuscript-columns span:last-child {
  text-align: right;
}
.manage-items {
  list-style: none;
  margin: 0;
  padding: 0;
}
.manuscript-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 100px 168px;
  gap: 24px;
  align-items: center;
  padding: 24px 16px;
  border-bottom: 1px solid #f0f2f5;
}
.manuscript-content {
  display: flex;
  gap: 16px;
  min-width: 0;
}
.manuscript-cover {
  width: 168px;
  aspect-ratio: 16/9;
  display: grid;
  place-items: center;
  flex: none;
  align-self: flex-start;
  border-radius: 6px;
  overflow: hidden;
  background: #edf0f4;
}
.manuscript-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.manuscript-cover > svg {
  width: 32px;
  height: 32px;
  color: #b0bac7;
}
.manuscript-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 4px 0;
}
.manuscript-info h2 {
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
  margin: 0 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.manuscript-info h2 a {
  color: inherit;
  text-decoration: none;
}
.manuscript-info h2 a:hover {
  color: var(--manage-accent);
}
.manuscript-description {
  font-size: 12px;
  line-height: 18px;
  margin: 0 0 8px;
  color: #727985;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.manuscript-date {
  font-size: 11px;
  line-height: 18px;
  color: #727985;
}
.status-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  white-space: nowrap;
  color: #747b85;
}
.status-label i {
  height: 5px;
  width: 5px;
  border-radius: 50%;
  background: currentColor;
}
.status-2 {
  color: #348469;
}
.status-1 {
  color: #9a7637;
}
.manuscript-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px 14px;
}
.creator-manage button {
  font: inherit;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  cursor: pointer;
  background: none;
  border: 0;
  color: #66707d;
  border-radius: 5px;
  padding: 5px 0;
  font-size: 12px;
  white-space: nowrap;
}
.creator-manage button svg {
  width: 15px;
  height: 15px;
}
.creator-manage button:disabled {
  opacity: 0.4;
  cursor: default;
}
.creator-manage .edit-action {
  color: var(--manage-accent);
}
.creator-manage .delete-action:hover,
.creator-manage .feedback-delete:hover {
  color: #ae4f42;
}
.manuscript-actions button:hover {
  text-decoration: underline;
}
.manage-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 28px;
}
.manage-pagination button {
  padding: 7px 12px;
  border: 1px solid #e2e6ed;
}
.manage-pagination > span {
  font-size: 13px;
  color: #727985;
  min-width: 24px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.feedback-row {
  display: flex;
  gap: 12px;
  padding: 24px 4px;
  border-bottom: 1px solid #eef0f3;
}
.feedback-avatar {
  width: 36px;
  height: 36px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #eceff3;
  color: #727985;
  font-size: 14px;
}
.feedback-info {
  flex: 1;
  min-width: 0;
}
.feedback-author {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 12px;
  line-height: 20px;
}
.feedback-author strong {
  font-weight: 500;
  color: #49515c;
}
.feedback-author time {
  color: #727985;
  font-size: 11px;
}
.feedback-content {
  font-size: 14px;
  line-height: 23px;
  margin: 8px 0 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.feedback-context {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #727985;
  font-size: 12px;
  line-height: 20px;
}
.feedback-context > svg {
  width: 14px;
  height: 14px;
  flex: none;
}
.feedback-context > span:first-of-type {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.feedback-position {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  margin-left: 8px;
  font-variant-numeric: tabular-nums;
}
.feedback-position svg {
  height: 13px;
  width: 13px;
}
.creator-manage .feedback-delete {
  align-self: center;
  color: #8a929e;
  padding: 6px 8px;
}
.feedback-delete span {
  font-size: 12px;
}
.manage-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  min-height: 320px;
  gap: 12px;
}
.manage-empty > svg {
  width: 44px;
  height: 44px;
  color: #b8c1cc;
}
.manage-empty h2 {
  font-size: 17px;
  line-height: 24px;
  font-weight: 400;
  margin: 0;
}
.manage-empty p {
  font-size: 13px;
  color: #727985;
  margin: 0;
  line-height: 20px;
}
.manage-empty .publish-action {
  margin-top: 8px;
}
.manage-loading {
  padding-top: 24px;
}
.manage-loading > div {
  display: flex;
  gap: 16px;
  margin-bottom: 24px;
}
.manage-loading i {
  display: block;
  width: 168px;
  height: 94px;
  border-radius: 6px;
  background: #f0f2f6;
}
.manage-loading span:not(.loading-label) {
  height: 56px;
  flex: 1;
  background: #f7f8fa;
  border-radius: 4px;
}
.loading-label {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
.creator-edit-form label {
  display: block;
  margin: 8px 0 20px;
  color: #434b56;
  font-size: 13px;
}
.creator-edit-form input,
.creator-edit-form textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #dfe3e9;
  border-radius: 6px;
  background: #fff;
  outline: none;
  font: inherit;
  color: #25272b;
  line-height: 22px;
  resize: vertical;
}
.creator-edit-form input:focus,
.creator-edit-form textarea:focus {
  border-color: var(--manage-accent);
}
.creator-edit-form > p {
  font-size: 12px;
  line-height: 20px;
  color: #8a713c;
}
.edit-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
.edit-form-actions button {
  font-family: inherit;
  border-radius: 6px;
  cursor: pointer;
  padding: 8px 20px;
  border: 1px solid #dfe3e9;
  font-size: 13px;
}
.edit-form-actions .save-edit {
  color: #fff;
  background: var(--manage-accent);
  border-color: var(--manage-accent);
}
:global(.creator-edit-dialog) {
  background: #fff !important;
  --manage-accent: #b45538;
  border-radius: 10px !important;
}
.creator-manage :focus-visible {
  outline: 2px solid var(--manage-accent);
  outline-offset: 3px;
}
@media (max-width: 1100px) {
  .manuscript-cover {
    width: 140px;
  }
  .manuscript-row,
  .manuscript-columns {
    grid-template-columns: minmax(0, 1fr) 80px 136px;
    gap: 16px;
  }
  .manuscript-actions {
    gap: 8px;
  }
}
@media (max-width: 699px) {
  .creator-manage {
    padding: 24px 16px;
  }
  .manage-heading {
    gap: 12px;
    margin-bottom: 20px;
  }
  .manage-heading h1 {
    font-size: 22px;
  }
  .manage-heading p {
    font-size: 12px;
  }
  .publish-action {
    padding: 8px 12px;
    font-size: 12px;
  }
  .manuscript-columns {
    display: none;
  }
  .manuscript-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    padding: 20px 0;
    gap: 12px;
  }
  .manuscript-content {
    grid-column: 1/-1;
    gap: 12px;
  }
  .manuscript-cover {
    width: 124px;
  }
  .manuscript-description {
    margin-bottom: 4px;
  }
  .manuscript-info h2 {
    font-size: 13px;
    line-height: 20px;
    margin-bottom: 4px;
  }
  .manuscript-date {
    font-size: 10px;
  }
  .manuscript-state {
    padding-left: 0;
  }
  .manuscript-actions {
    justify-content: flex-end;
    gap: 12px;
  }
  .status-label {
    font-size: 11px;
  }
  .feedback-row {
    gap: 10px;
    padding: 20px 0;
    position: relative;
  }
  .feedback-avatar {
    height: 28px;
    width: 28px;
    font-size: 12px;
  }
  .feedback-author {
    flex-wrap: wrap;
    gap: 2px 12px;
    padding-right: 20px;
  }
  .feedback-content {
    font-size: 13px;
    line-height: 21px;
  }
  .feedback-delete {
    position: absolute;
    top: 20px;
    right: 0;
  }
  .feedback-delete span {
    display: none;
  }
  .feedback-context {
    font-size: 11px;
  }
  .feedback-position {
    margin-left: 2px;
  }
  .manage-empty {
    min-height: 280px;
  }
}
</style>
