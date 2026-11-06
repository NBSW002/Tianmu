<template>
  <el-carousel v-if="carousels.length" class="featured-carousel" height="100%" trigger="click" arrow="always"
    :interval="5000" :autoplay="carousels.length > 1 && !reducedMotion" :pause-on-hover="true" aria-label="推荐视频轮播" @change="prepareSlide">
    <el-carousel-item v-for="(item, index) in carousels" :key="item.videoId">
      <router-link class="carousel-inner" :to="`/video/${item.videoId}`">
        <img v-if="preparedSlides.has(index)" :src="mediaUrl(item.coverUrl, 960)" :alt="item.title" decoding="async" :fetchpriority="index === 0 ? 'high' : 'low'" @error="useFallbackImage" />
        <div class="carousel-footer"><span class="carousel-label">精选推荐</span><div class="carousel-title">{{ item.title }}</div></div>
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
.featured-carousel :deep(.el-carousel__container) { height: 100%; }
.carousel-inner { display: block; position: relative; width: 100%; height: 100%; overflow: hidden; background: #202332; }
.carousel-inner img { display: block; width: 100%; height: 100%; object-fit: cover; transition: transform .4s; }
.carousel-inner:hover img { transform: scale(1.025); }
.carousel-footer { position: absolute; inset: auto 0 0; padding: 64px 100px 52px 20px; color: #fff; background: linear-gradient(transparent, rgba(0,0,0,.78)); pointer-events: none; }
.carousel-label { display: inline-block; margin-bottom: 8px; font-size: 12px; opacity: .85; }
.carousel-title { font-size: clamp(16px, 1.3vw, 22px); line-height: 1.45; font-weight: 600; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.featured-carousel :deep(.el-carousel__arrow) { top: auto; bottom: 14px; transform: none; width: 30px; height: 30px; border-radius: 8px; background: rgba(255,255,255,.22); }
.featured-carousel :deep(.el-carousel__arrow--right) { right: 16px; }
.featured-carousel :deep(.el-carousel__arrow--left) { left: auto; right: 54px; }
.featured-carousel :deep(.el-carousel__indicators--horizontal) { left: 14px; bottom: 16px; transform: none; }
.featured-carousel :deep(.el-carousel__button) { width: 7px; height: 7px; border-radius: 50%; }
.featured-carousel :deep(.is-active .el-carousel__button) { width: 9px; height: 9px; transform: translateY(-1px); background: #fff; }
@media (max-width:699px) { .carousel-footer { padding: 45px 84px 40px 15px; } .carousel-title { font-size: 17px; } .carousel-label { margin-bottom: 5px; font-size: 11px; } }
@media (prefers-reduced-motion:reduce) { .carousel-inner img { transition: none; } .featured-carousel :deep(.el-carousel__item) { transition: none; } }
.carousel-empty { display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #e9e9f1, #f8eef4); color: #777b8a; font-size: 18px; }
</style>
