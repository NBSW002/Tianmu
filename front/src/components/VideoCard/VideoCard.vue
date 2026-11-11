<template>
  <div class="video-card">
    <!-- 骨架屏 -->
    <div
      class="video-card__skeleton"
      :class="isLoading ? 'loading_animation' : 'hide'"
    >
      <div class="video-card__skeleton--cover"></div>
      <div class="video-card__skeleton--info">
        <div class="video-card__skeleton--right">
          <p class="video-card__skeleton--text"></p>
          <p class="video-card__skeleton--text short"></p>
          <p class="video-card__skeleton--light"></p>
        </div>
      </div>
    </div>
    <!-- 实体内容——视频卡片 -->
    <div class="video-card__wrap" v-if="!isLoading">
      <!-- 视频上半部分——视频封面、播放数、弹幕数、时长 -->
      <a :href="`/video/${item.videoId}`" target="_blank" rel="noopener" :aria-label="item.title">
        <div class="video-card__image">
          <span class="video-card__play" aria-hidden="true"><TraceIcon name="play" :size="22" /></span>
          <!-- 视频封面 -->
          <div class="video-card__image--wrap">
            <picture class="video-card__cover">
              <img :src="mediaUrl(item.coverUrl, 640)" :alt="item.title" loading="lazy" decoding="async" @error="useFallbackImage" />
            </picture>
          </div>
          <!-- 播放数、弹幕数、时长 -->
          <div class="video-card__mask">
            <div class="video-card__stats">
              <div class="video-card__stats--left">
                <span class="video-card__stats--item" :title="`播放 ${item.viewCount ?? 0} 次`" :aria-label="`播放 ${item.viewCount ?? 0} 次`">
                  <i class="iconfont icon-bofangshu" aria-hidden="true"></i>
                  <span class="video-card__stats--text">
                    {{ handleNum(item.viewCount) }}
                  </span>
                </span>
                <span class="video-card__stats--item video-card__stats--danmaku" :title="`弹幕 ${item.bulletCount ?? 0} 条`" :aria-label="`弹幕 ${item.bulletCount ?? 0} 条`">
                  <i class="iconfont icon-danmushu" aria-hidden="true"></i>
                  <span class="video-card__stats--text">
                    {{ handleNum(item.bulletCount) }}
                  </span>
                </span>
              </div>
              <div class="video-card__stats__duration">
                {{ handleDuration(item.duration) }}
              </div>
            </div>
          </div>
        </div>
      </a>
      <!-- 视频下半部分——视频标题、是否关注、视频发布者用户名、上传日期 -->
      <div class="video-card__info">
        <div class="video-card__info--right">
          <!-- 视频标题 -->
          <h3 class="video-card__info--tit">
            <a
              v-if="dataType === 'search'"
              :href="`/video/${item.videoId}`"
              target="_blank"
              rel="noopener"
              :title="item.title"
              v-html="highlightKeyword(item.title)"
            >
            </a>
            <a v-else :href="`/video/${item.videoId}`" target="_blank" rel="noopener" :title="item.title">
              {{ item.title }}
            </a>
          </h3>
          <!-- 是否关注、视频发布者用户名、上传日期 -->
          <div class="video-card__info--bottom">
            <div class="video-card__info--icon-text" :style="'display: none;'">
              已关注
            </div>
            <a
              class="video-card__info--owner"
              :href="`/user/${item.userId}`"
              target="_blank"
              rel="noopener"
              :title="item.nickName"
            >
              <i class="iconfont icon-uper" :style="''"></i>
              <span class="video-card__info--author">{{ item.nickName }}</span>
              <span class="video-card__info--date">
                · {{ handleDate(item.createTime) }}
              </span>
            </a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {
  handleNum,
  handleDuration,
  handleDate,
  highlightKeyword,
} from "@/lib/utils";
import TraceIcon from '@/components/trace/TraceIcon.vue';
import { mediaUrl } from "@/lib/media";
import videoPlaceholder from "@/assets/img/bilibili/video-placeholder.png";

export default {
  components: { TraceIcon },
  props: {
    item: {
      type: [Object,Number],
      required: true,
    },
    isLoading: {
      type: Boolean,
      default() {
        return true;
      },
    },
    dataType: {
      type: String,
      default() {
        return "";
      },
    },
  },
  methods: {
    mediaUrl,
    useFallbackImage(event) {
      event.target.onerror = null;
      event.target.src = videoPlaceholder;
    },
    // 处理大于1万的数字
    handleNum(number) {
      return handleNum(number);
    },
    handleDuration(duration) {
      return handleDuration(duration);
    },
    handleDate(createTime) {
      return handleDate(createTime);
    },
    highlightKeyword(title) {
      return highlightKeyword(this.$route.query.keyword, title);
    },
  },
};
</script>

<style>
</style>
