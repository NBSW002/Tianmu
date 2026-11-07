<template>
  <nav class="header-channel" aria-label="视频频道">
    <div class="channel-shortcuts">
      <button type="button" class="channel-shortcut" @click="openFollowing"><span class="shortcut-circle following"><el-icon><Star /></el-icon></span><span>动态</span></button>
      <a class="channel-shortcut" href="/#recommended-feed"><span class="shortcut-circle recommend"><el-icon><Sunny /></el-icon></span><span>推荐</span></a>
    </div>
    <div class="channel-categories">
      <div v-if="loading && !categoryList.length" class="channel-skeleton" aria-label="频道加载中"><span v-for="i in 12" :key="i"></span></div>
      <div v-else-if="!categoryList.length" class="channel-unavailable"><span>频道暂未加载</span><button type="button" @click="loadCategories">重试</button></div>
      <div v-else class="channel-grid" :style="{ '--channel-columns': Math.ceil((visibleCategories.length + (hasMore ? 1 : 0)) / 2) }">
        <router-link v-for="item in visibleCategories" :key="item.key" :to="item.to" :title="item.hint" class="channel-link" :class="{ 'is-active': item.categoryId != null && String($route.params.categoryId) === String(item.categoryId) }">{{ item.label }}</router-link>
        <details v-if="hasMore" class="more-channels"><summary>更多<el-icon><ArrowDown /></el-icon></summary><div class="more-channel-panel"><router-link v-for="item in categoryList.slice(20)" :key="item.categoryId" :to="`/category/${item.categoryId}`">{{ item.categoryName }}</router-link></div></details>
      </div>
    </div>
    <div class="channel-utilities">
      <router-link to="/search/video"><el-icon><Compass /></el-icon><span>发现视频</span></router-link>
      <router-link to="/platform/home"><el-icon><EditPen /></el-icon><span>创作中心</span></router-link>
      <router-link to="/platform/upload"><el-icon><Upload /></el-icon><span>发布作品</span></router-link>
      <a href="/#recommended-feed"><el-icon><VideoPlay /></el-icon><span>精选推荐</span></a>
    </div>
  </nav>
</template>
<script>
import { mapActions, mapState } from 'pinia';
import { useVideoStore } from '@/store/video';
import { useUserStore } from '@/store/user';
export default {
  name: 'HeaderChannel', data: () => ({ loading: false }),
  computed: {
    ...mapState(useVideoStore, ['categoryList']), ...mapState(useUserStore, ['user', 'isLogin']),
    visibleCategories() {
      const categories = this.categoryList.map(item => ({ key: `category-${item.categoryId}`, categoryId: item.categoryId, label: item.categoryName, to: `/category/${item.categoryId}`, hint: `进入${item.categoryName}分区` }));
      const names = new Set(categories.map(item => item.label));
      const topics = ['游戏','音乐','影视','知识','科技','数码','美食','生活','动物','运动','旅行','舞蹈','国创','娱乐','Vlog','资讯','二次元','摄影','手工','日常'].filter(label => !names.has(label)).map(label => ({ key: `topic-${label}`, label, to: `/search/video?keyword=${encodeURIComponent(label)}`, hint: `搜索与“${label}”相关的视频` }));
      return [...categories, ...topics].slice(0, 20);
    },
    hasMore() { return this.categoryList.length > 20; },
  },
  created() { if (!this.categoryList.length) this.loadCategories(); },
  methods: {
    ...mapActions(useVideoStore, ['getCategoryList']), ...mapActions(useUserStore, ['updateDialogVisible']),
    async loadCategories() { if (this.loading) return; this.loading = true; try { await this.getCategoryList(); } finally { this.loading = false; } },
    openFollowing() { if (!this.isLogin) return this.updateDialogVisible(true); this.$router.push(`/user/${this.user.userId}/upload`); },
  },
};
</script>
<style scoped>
.header-channel { --channel-gutter: clamp(20px,4vw,96px); display:flex; align-items:center; gap:28px; max-width:2160px; width:100%; padding:26px var(--channel-gutter) 25px; margin:0 auto; color:#18191c; background:#fff; }
.channel-shortcuts { display:flex; flex:0 0 auto; gap:22px; }
.channel-shortcut { display:flex; flex-direction:column; align-items:center; gap:8px; min-width:48px; background:none; border:0; padding:0; font:inherit; color:inherit; text-decoration:none; cursor:pointer; }
.shortcut-circle { display:grid; place-items:center; width:48px; height:48px; border-radius:50%; color:#fff; font-size:27px; transition:transform .18s; }
.channel-shortcut:hover .shortcut-circle { transform:translateY(-3px); }
.following { background:#f3b369; } .recommend { background:#f77d82; }
.channel-categories { flex:1; min-width:0; }
.channel-grid { display:grid; grid-template-columns:repeat(var(--channel-columns),minmax(0,1fr)); grid-template-rows:repeat(2,32px); grid-auto-flow:column; gap:9px; }
.channel-link,.more-channels summary { display:flex; justify-content:center; align-items:center; height:32px; padding:0 7px; border:1px solid #f0f1f2; border-radius:6px; background:#f6f7f8; color:#61666d; font-size:13px; text-decoration:none; white-space:nowrap; cursor:pointer; transition:background .18s,color .18s; }
.channel-link:hover,.channel-link.is-active { background:#e8f7fd; color:#00aeec; border-color:#d9f1fb; }
.more-channels { position:relative; } .more-channels summary { list-style:none; gap:4px; } .more-channels summary::-webkit-details-marker { display:none; }
.more-channel-panel { position:absolute; z-index:50; top:calc(100% + 8px); right:0; display:grid; grid-template-columns:repeat(3,minmax(70px,1fr)); gap:6px; padding:16px; border-radius:10px; background:#fff; box-shadow:0 6px 30px #18191c1c; border:1px solid #f1f2f3; }
.more-channel-panel a { padding:8px; color:#61666d; text-decoration:none; white-space:nowrap; border-radius:4px; } .more-channel-panel a:hover { color:#00aeec; background:#f6f7f8; }
.channel-utilities { display:grid; grid-template-columns:repeat(2,max-content); gap:19px 20px; padding-left:24px; border-left:1px solid #e3e5e7; }
.channel-utilities a { display:flex; align-items:center; gap:7px; color:#61666d; text-decoration:none; font-size:13px; } .channel-utilities a:hover { color:#00aeec; } .channel-utilities .el-icon { font-size:18px; }
.channel-skeleton { display:grid; grid-template-columns:repeat(6,1fr); gap:9px; } .channel-skeleton span { height:32px; border-radius:6px; background:#f6f7f8; }
.channel-unavailable { display:flex; gap:10px; color:#9499a0; font-size:13px; } .channel-unavailable button { color:#00aeec; background:none; border:0; cursor:pointer; }
@media(max-width:1399px) { .header-channel { gap:22px; } .channel-utilities { gap:18px 14px; padding-left:18px; } .channel-utilities a { font-size:12px; } .channel-grid { gap:9px 7px; } }
@media(max-width:1199px) { .channel-utilities { display:none; } .channel-link { font-size:12px; } }
@media(max-width:959px) { .header-channel { --channel-gutter:24px; gap:20px; } .channel-shortcuts { gap:14px; } .shortcut-circle { width:40px; height:40px; font-size:23px; } }
@media(max-width:699px) {
  .header-channel { --channel-gutter:16px; gap:16px; padding-top:20px; padding-bottom:24px; align-items:flex-start; }
  .channel-shortcuts { gap:12px; padding-top:1px; } .channel-shortcut { min-width:32px; font-size:11px; } .shortcut-circle { width:34px; height:34px; font-size:20px; }
  .channel-categories { overflow-x:auto; padding-bottom:4px; scrollbar-width:thin; }
  .channel-grid { width:max-content; grid-template-columns:repeat(var(--channel-columns),62px); grid-template-rows:repeat(2,28px); gap:6px; }
  .channel-link,.more-channels summary { height:28px; font-size:11px; } .more-channel-panel { position:fixed; top:275px; left:16px; right:16px; }
}
@media(prefers-reduced-motion:reduce) { .shortcut-circle { transition:none; } }
</style>
