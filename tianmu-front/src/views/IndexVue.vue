<template>
  <div class="index">
    <header class="home-masthead">
      <HeaderBar :isFixHeaderBar="isFixHeaderBar" hero />
      <div class="home-banner">
        <img class="home-banner__image" src="@/assets/img/home/pond-banner.webp" alt="荷塘与夏日森林插画" fetchpriority="high" />
        <div class="home-banner__shade"></div>
        <div class="home-banner__brand"><router-link to="/" aria-label="EchoFrame 首页">EchoFrame<span class="brand-spark">✦</span></router-link><span>让每一份热爱，都有回响</span></div>
      </div>
    </header>
    <HeaderChannel />
    <main id="recommended-feed" class="main__layout">
      <div class="feed-heading">
        <div class="feed-heading__title"><span class="feed-heading__mark"><el-icon><VideoPlay /></el-icon></span><h1>为你推荐</h1><span class="feed-heading__subtitle">发现有趣，遇见热爱</span></div>
        <button class="refresh-feed" type="button" :disabled="isLoadingCumulative" @click="refreshRecommendations"><el-icon :class="{ 'is-spinning': refreshing }"><Refresh /></el-icon>{{ refreshing ? '更新中' : '换一换' }}</button>
      </div>
      <section class="recommended-container" aria-label="推荐视频" :aria-busy="loadingRecm">
        <div class="container" v-infinite-scroll="loadMoreVideo" :infinite-scroll-disabled="!hasMoreVideo || isLoadingCumulative || loadError" :infinite-scroll-delay="300" :infinite-scroll-distance="420" :infinite-scroll-immediate="true">
          <div class="recommended-swipe"><CarouselIndex :videos="feedVideos" /></div>
          <VideoCard v-for="(item,index) in (loadingRecm ? 15 : feedVideos)" :key="loadingRecm ? `initial-${index}` : `feed-${item.videoId}`" :item="item" :isLoading="loadingRecm" />
          <VideoCardSkeleton v-for="index in (isLoadingCumulative && !loadingRecm ? 15 : 0)" :key="`skeleton-${current}-${index}`" :hasMoreVideo="true" />
        </div>
        <div v-if="!loadingRecm && !loadError && !recmVideoList.length" class="feed-empty"><el-icon><Film /></el-icon><h2>第一份精彩，等你分享</h2><p>这里还没有视频，来发布你的第一条作品吧。</p><router-link to="/platform/upload">去投稿</router-link></div>
        <div v-else-if="!hasMoreVideo && !loadError" class="no-more"><span></span>已经到底啦，去看看别的精彩吧<span></span></div>
        <div v-if="loadError" class="feed-load-error" role="status"><span>视频暂时没有加载出来</span><button type="button" :disabled="isLoadingCumulative" @click="retryLoadMore">重新加载</button></div>
      </section>
    </main>
    <aside class="home-tools" aria-label="页面工具"><button type="button" :disabled="isLoadingCumulative" @click="refreshRecommendations" aria-label="换一批推荐视频"><el-icon><Refresh /></el-icon><span>换一换</span></button><button v-if="showBackToTop" type="button" @click="backToTop" aria-label="回到顶部"><el-icon><Top /></el-icon><span>顶部</span></button></aside>
  </div>
</template>

<script>
import HeaderBar from "@/components/headerBar/HeaderBar.vue";
import HeaderChannel from "@/components/headerChannel/HeaderChannel.vue";
import CarouselIndex from "@/components/carousel/CarouselIndex.vue";
import VideoCard from "@/components/VideoCard/VideoCard.vue";
import VideoCardSkeleton from "@/components/VideoCardSkeleton/VideoCardSkeleton.vue";

import { fetchVideoList } from "@/api/video";

export default {
  name: "IndexVue",
  components: {
    HeaderBar,
    HeaderChannel,
    CarouselIndex,
    VideoCard,
    VideoCardSkeleton,
  },
  data() {
    return {
      // 是否是固钉导航栏
      isFixHeaderBar: false,
      // 文档根元素
      el: null,
      // 是否正在加载随机推荐
      loadingRecm: true,
      refreshing: false,
      recommendationOffset: 0,
      showBackToTop: false,
      // 推荐视频列表
      recmVideoList: [],
      current: 0,
      hasMoreVideo: true,
      isLoadingCumulative: false, // 添加加载状态锁，防止无限加载出现多余的请求，当响应数据为空时，就阻止再请求
      loadError: false,
      failedPage: null,
      checkingViewport: false,
      resizeTimer: null,
    };
  },
  async mounted() {
    this.el = document.documentElement;
    window.addEventListener("scroll", this.handleScroll, { passive: true });
    window.addEventListener("resize", this.handleResize, { passive: true });
    const loaded = await this.getVideoList(1);
    if (loaded) await this.ensureViewportFilled();
  },
  beforeUnmount() {
    window.removeEventListener("scroll", this.handleScroll);
    window.removeEventListener("resize", this.handleResize);
    if (this.resizeTimer) window.clearTimeout(this.resizeTimer);
  },
  computed: {
    feedVideos() {
      const first = this.recmVideoList.slice(0, 15);
      const offset = first.length ? this.recommendationOffset % first.length : 0;
      return [...first.slice(offset), ...first.slice(0, offset), ...this.recmVideoList.slice(15)];
    },
  },
  methods: {
    async refreshRecommendations() {
      if (this.isLoadingCumulative) return;
      this.refreshing = true;
      try {
        if (await this.getVideoList(1)) {
          this.recommendationOffset += 6;
          await this.ensureViewportFilled();
        }
      } finally { this.refreshing = false; }
    },
    backToTop() {
      window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    },
    async getVideoList(page) {
      // Keep page size stable so page-based APIs do not skip rows between pages.
      const pageSize = 15;
      this.isLoadingCumulative = true;
      this.loadError = false;
      if (page === 1) this.loadingRecm = true;
      try {
        const res = await fetchVideoList(page, pageSize);
        if (res.code !== 200 || !Array.isArray(res.data)) throw new Error("视频列表不可用");
        this.recmVideoList = page === 1 ? res.data : this.recmVideoList.concat(res.data);
        this.current = page;
        this.hasMoreVideo = res.data.length >= pageSize;
        this.failedPage = null;
        return true;
      } catch (_) {
        this.loadError = true;
        this.failedPage = page;
        return false;
      } finally {
        this.loadingRecm = false;
        this.isLoadingCumulative = false;
        await this.$nextTick();
        this.handleScroll();
      }
    },
    async loadMoreVideo() {
      if (!this.hasMoreVideo || this.isLoadingCumulative || this.loadError || this.current === 0) return;
      const loaded = await this.getVideoList(this.current + 1);
      if (loaded) await this.ensureViewportFilled();
    },
    async retryLoadMore() {
      if (this.isLoadingCumulative) return;
      const page = this.failedPage ?? (this.current === 0 ? 1 : this.current + 1);
      if (page === 1 && this.current > 0) return this.refreshRecommendations();
      const loaded = await this.getVideoList(page);
      if (loaded) await this.ensureViewportFilled();
    },
    async ensureViewportFilled() {
      if (this.checkingViewport) return;
      this.checkingViewport = true;
      try {
        let pagesLoaded = 0;
        await this.$nextTick();
        while (this.hasMoreVideo && !this.loadError && pagesLoaded < 4 &&
          document.documentElement.scrollHeight <= window.innerHeight + 120) {
          const loaded = await this.getVideoList(this.current + 1);
          if (!loaded) break;
          pagesLoaded += 1;
          await this.$nextTick();
        }
      } finally {
        this.checkingViewport = false;
      }
    },
    handleResize() {
      if (this.resizeTimer) window.clearTimeout(this.resizeTimer);
      this.resizeTimer = window.setTimeout(async () => {
        this.handleScroll();
        await this.ensureViewportFilled();
      }, 100);
    },
    handleScroll() {
      if (!this.el) return;
      const scrollTop = window.scrollY || this.el.scrollTop || document.body.scrollTop || 0;
      this.isFixHeaderBar = scrollTop >= 96;
      this.showBackToTop = scrollTop > 360;
    },
  },
};
</script>

<style scoped>

.index { --home-gutter: clamp(20px, 4vw, 96px); background: #fff; color: #18191c; min-height: 100vh; }
.home-masthead { position: relative; }
.home-banner { height: clamp(180px, 10vw, 240px); position: relative; overflow: hidden; background: #376950; }
.home-banner__image { display: block; width: 100%; height: 100%; object-fit: cover; object-position: center; }
.home-banner__shade { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(0, 20, 16, .42), transparent 65%); pointer-events: none; }
.home-banner__brand { position: absolute; left: var(--home-gutter); bottom: 23px; display: flex; flex-direction: column; gap: 6px; color: #fff; text-shadow: 0 2px 10px #164b4070; }
.home-banner__brand a { color: inherit; font-family: 'Trebuchet MS', sans-serif; font-weight: 900; letter-spacing: -2px; font-size: clamp(32px, 3vw, 49px); text-decoration: none; }
.brand-spark { display: inline-block; color: #d6fba4; font-size: .58em; margin-left: 8px; transform: translateY(-12px); }
.home-banner__brand > span { font-size: 12px; letter-spacing: 3px; }
.main__layout { width: 100%; max-width: 2160px; padding: 0 var(--home-gutter) 64px; margin: 0 auto; }
.feed-heading { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 20px; }
.feed-heading__title { display: flex; align-items: center; gap: 10px; }
.feed-heading__mark { display: grid; place-items: center; background: #ffedf3; width: 29px; height: 29px; border-radius: 9px; color: #fb7299; font-size: 21px; }
.feed-heading h1 { font-size: 19px; font-weight: 600; margin: 0; }
.feed-heading__subtitle { margin-left: 5px; color: #9499a0; font-size: 12px; }
.refresh-feed { display: flex; align-items: center; gap: 7px; border: 1px solid #e3e5e7; border-radius: 8px; height: 32px; padding: 0 12px; background: #fff; color: #61666d; cursor: pointer; font: inherit; font-size: 12px; }
.refresh-feed:hover { background: #f6f7f8; color: #00aeec; }
.refresh-feed:disabled { cursor: wait; opacity: .55; }
.container { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 28px 20px; width: 100%; align-items: start; }
.container > * { min-width: 0; }
.recommended-swipe { grid-column: span 2; grid-row: span 2; height: 100%; min-height: 280px; overflow: hidden; border-radius: 10px; background: #eff3f3; }
.recommended-container { position: relative; }
.no-more { display: flex; align-items: center; justify-content: center; gap: 18px; padding: 48px 0 10px; font-size: 12px; color: #9499a0; }
.no-more span { height: 1px; width: 42px; background: #e3e5e7; }
.feed-load-error { display: flex; gap: 14px; justify-content: center; align-items: center; padding: 32px 0; color: #61666d; }
.feed-load-error button, .feed-empty a { border: 0; border-radius: 6px; padding: 8px 14px; background: #e8f7fc; color: #00a2d9; cursor: pointer; text-decoration: none; }
.feed-empty { text-align: center; padding: 56px 12px; color: #9499a0; }
.feed-empty > .el-icon { color: #c9dbe3; font-size: 50px; }
.feed-empty h2 { color: #61666d; font-size: 18px; margin: 12px 0; }
.feed-empty p { font-size: 13px; margin-bottom: 24px; }
.home-tools { position: fixed; right: 6px; bottom: 70px; display: flex; flex-direction: column; gap: 8px; z-index: 30; }
.home-tools button { display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 10px 5px; width: 44px; border: 1px solid #e3e5e7; border-radius: 9px; background: #fff; color: #61666d; cursor: pointer; box-shadow: 0 3px 10px #18191c05; font: inherit; font-size: 11px; }
.home-tools button:hover { color: #00aeec; background: #f6f7f8; }
.home-tools .el-icon { font-size: 20px; }
.is-spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (min-width: 2200px) { .container { grid-template-columns: repeat(6,minmax(0,1fr)); } }
@media (max-width: 1199px) { .container { grid-template-columns: repeat(4,minmax(0,1fr)); gap: 24px 16px; } .home-tools { right: 5px; } }
@media (max-width: 1299px) { .home-tools { display: none; } }
@media (max-width: 959px) { .index { --home-gutter: 24px; } .container { grid-template-columns: repeat(3,minmax(0,1fr)); } .home-tools { display: none; } }
@media (max-width: 699px) {
  .index { --home-gutter: 16px; } .home-banner { height: 196px; } .home-banner__image { object-position: 58% center; }
  .home-banner__brand { bottom: 19px; } .home-banner__brand > span { font-size: 10px; letter-spacing: 2px; }
  .feed-heading { margin-bottom: 16px; } .feed-heading h1 { font-size: 17px; } .feed-heading__subtitle { display: none; }
  .container { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 24px 12px; }
  .recommended-swipe { grid-row: auto; height: auto; min-height: 0; aspect-ratio: 16 / 9; margin-bottom: 2px; }
  .no-more { font-size: 11px; gap: 10px; }
}
@media (prefers-reduced-motion: reduce) { .is-spinning { animation: none; } }
</style>
