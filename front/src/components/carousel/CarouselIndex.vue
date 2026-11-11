<template>
  <el-carousel v-if="carousels.length" class="featured-carousel" height="100%" trigger="click" arrow="always"
    :interval="5000" :autoplay="carousels.length > 1 && !reducedMotion" :pause-on-hover="true" aria-label="推荐视频轮播" @change="prepareSlide">
    <el-carousel-item v-for="(item, index) in carousels" :key="item.videoId">
      <router-link class="carousel-inner" :to="`/video/${item.videoId}`">
        <img v-if="preparedSlides.has(index)" :src="mediaUrl(item.coverUrl, 960)" :alt="item.title" decoding="async" :fetchpriority="index === 0 ? 'high' : 'low'" @error="useFallbackImage" />
        <div class="carousel-footer"><div class="carousel-title" :title="item.title">{{ item.title }}</div></div>
      </router-link>
    </el-carousel-item>
  </el-carousel>
  <div v-else class="carousel-empty" aria-live="polite">精彩内容，即将呈现</div>
</template>
<script>
import { mediaUrl } from "@/lib/media";
import videoPlaceholder from "@/assets/img/bilibili/video-placeholder.png";
export default {
  name: "CarouselIndex",
  data: () => ({ reducedMotion: window.matchMedia('(prefers-reduced-motion: reduce)').matches, preparedSlides: new Set([0, 1]) }),
  props: { videos: { type: Array, default: () => [] } },
  computed: {
    carousels() {
      const seen = new Set();
      return this.videos.filter(item => {
        if (!item || !item.videoId || seen.has(item.videoId)) return false;
        seen.add(item.videoId);
        return true;
      }).slice(0, 6);
    },
  },
  methods: {
    mediaUrl,
    prepareSlide(index) {
      this.preparedSlides.add(index);
      this.preparedSlides.add((index + 1) % this.carousels.length);
    },
    useFallbackImage(event) { event.target.onerror = null; event.target.src = videoPlaceholder; },
  },
};
</script>
<style scoped>
.featured-carousel, .carousel-empty { width: 100%; height: 100%; min-width: 0; }
.featured-carousel { overflow: hidden; isolation: isolate; border-radius: inherit; }
.featured-carousel :deep(.el-carousel__container) { height: 100%; overflow: hidden; }
.carousel-inner { display: grid; grid-template-columns: minmax(0,1fr); grid-template-rows: minmax(0,63fr) minmax(0,17fr); position: relative; width: 100%; height: 100%; overflow: hidden; background: var(--workbench-dark); }
.carousel-inner img { display: block; width: 100%; height: 100%; min-height: 0; object-fit: contain; }
.carousel-footer { grid-row: 2; position: relative; min-width: 0; min-height: 0; padding: 8px clamp(12px,3cqw,20px) 28px; color: #faf9f5; background: var(--workbench-dark); }
.carousel-footer::before { content: ''; position: absolute; left: 0; right: 0; bottom: 100%; height: 80%; background: linear-gradient(180deg, transparent 0%, color-mix(in srgb, var(--workbench-dark) 16%, transparent) 36%, color-mix(in srgb, var(--workbench-dark) 64%, transparent) 72%, var(--workbench-dark) 100%); pointer-events: none; }
.carousel-title { font-size: clamp(14px,3.3cqw,20px); line-height: 1.4; font-weight: 500; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.carousel-inner:hover .carousel-title { text-decoration: underline; text-underline-offset: 4px; }
.featured-carousel :deep(.el-carousel__arrow) { top: auto; bottom: 12px; transform: none; width: 32px; height: 32px; border-radius: 8px; background: rgba(250,249,245,.18); border: 1px solid rgba(250,249,245,.16); }
.featured-carousel :deep(.el-carousel__arrow:hover) { background: rgba(250,249,245,.32); }
.featured-carousel :deep(.el-carousel__arrow--right) { right: 16px; }
.featured-carousel :deep(.el-carousel__arrow--left) { left: auto; right: 54px; }
.featured-carousel :deep(.el-carousel__indicators--horizontal) { left: 16px; bottom: 8px; transform: none; display: flex; align-items: center; }
.featured-carousel :deep(.el-carousel__indicator--horizontal) { padding: 8px 4px; }
.featured-carousel :deep(.el-carousel__button) { width: 6px; height: 6px; border-radius: 6px; background: #faf9f5; opacity: .45; }
.featured-carousel :deep(.is-active .el-carousel__button) { width: 16px; opacity: 1; }
@container (max-width:480px) { .featured-carousel :deep(.el-carousel__arrow) { bottom: 8px; width: 28px; height: 28px; } .featured-carousel :deep(.el-carousel__indicators--horizontal) { left: 12px; bottom: 4px; } }
@media (prefers-reduced-motion:reduce) { .carousel-inner img { transition: none; } .featured-carousel :deep(.el-carousel__item) { transition: none; } }
.carousel-empty { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #e9e9f1, #f8eef4); color: #777b8a; font-size: 18px; }
</style>
