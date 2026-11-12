<template>
  <div class="platform-view">
    <header class="platform-header">
      <div class="header-content">
        <div class="left-block">
          <router-link to="/platform/manuscripts" class="creator-brand"
            ><strong>EchoFrame</strong><span>创作中心</span></router-link
          ><router-link to="/" class="entry"><ArrowLeft />返回主站</router-link>
        </div>
        <div class="right-block">
          <span v-if="isLogin" class="creator-account-name">{{
            user.nickname
          }}</span
          ><VPopover v-if="isLogin" pop-style="padding-top:12px"
            ><template #reference
              ><div class="avatar">
                <img
                  :src="platformAvatarUrl"
                  alt="账号菜单"
                  @error="platformAvatarFailed = true"
                /></div></template
            ><template #content
              ><button class="logout" type="button" @click="handleLogout">
                退出登录
              </button></template
            ></VPopover
          ><span v-else class="not-login-text">未登录</span>
        </div>
      </div>
    </header>
    <nav class="creator-navigation" aria-label="创作中心">
      <span class="navigation-section">内容管理</span
      ><router-link to="/platform/manuscripts"
        ><VideoCamera /><span>稿件管理</span></router-link
      ><router-link to="/platform/upload/video"
        ><Upload /><span>发布视频</span></router-link
      ><router-link to="/platform/comment"
        ><ChatDotRound /><span>评论管理</span></router-link
      ><router-link to="/platform/danmu"
        ><Comment /><span>弹幕管理</span></router-link
      >
      <div class="navigation-divider" />
      <router-link to="/history"><Clock /><span>观看历史</span></router-link>
    </nav>
    <main class="platform-main">
      <div class="content-body"><router-view /></div>
    </main>
  </div>
</template>

<script>
import {
  ArrowLeft,
  VideoCamera,
  Upload,
  ChatDotRound,
  Comment,
  Clock,
} from "@element-plus/icons-vue";
import VPopover from "@/components/popover/VPopover.vue";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/store/user";
import { mapState, mapActions } from "pinia";
import { fetchLogout } from "@/api/user.js";
import { mediaUrl } from "@/lib/media";
import defaultAvatar from "@/assets/img/default-avatar.svg";

export default {
  name: "PlatformView",
  components: {
    VPopover,
    ArrowLeft,
    VideoCamera,
    Upload,
    ChatDotRound,
    Comment,
    Clock,
  },
  data() {
    return {
      platformAvatarFailed: false,
    };
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
    platformAvatarUrl() {
      return this.user?.avatar && !this.platformAvatarFailed
        ? mediaUrl(this.user.avatar)
        : defaultAvatar;
    },
  },
  methods: {
    ...mapActions(useUserStore, ["logout"]),

    // 退出登录
    async handleLogout() {
      try {
        const res = await fetchLogout(this.user.userId);
        if (res.code === 200) {
          this.logout();
          ElMessage.success("退出成功");
        }
      } catch (error) {
        ElMessage.error("退出失败：" + error);
      }
    },
  },
};
</script>

<style scoped>
#app .platform-view .platform-header,
#app .platform-view .content-body {
  background: #fff;
}
#app .platform-view {
  font-family: "Harmony Font", "Microsoft YaHei", sans-serif;
  min-height: 100vh;
  background: #f7f8fa;
  color: #25272b;
}
.platform-header {
  height: 64px;
  position: fixed;
  inset: 0 0 auto;
  z-index: 900;
  background: #fff;
  border-bottom: 1px solid #edf0f3;
}
.header-content {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  gap: 24px;
  box-sizing: border-box;
}
.left-block,
.right-block {
  display: flex;
  align-items: center;
  gap: 28px;
}
.right-block {
  gap: 12px;
}
.creator-brand {
  display: flex;
  align-items: center;
  gap: 16px;
  text-decoration: none;
  color: #25272b;
  white-space: nowrap;
}
.creator-brand strong {
  font-family: Georgia, serif;
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
}
.creator-brand span {
  font-size: 14px;
  color: #666d78;
  border-left: 1px solid #e3e7ed;
  padding-left: 16px;
  line-height: 20px;
}
.entry {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #727985;
  text-decoration: none;
  font-size: 12px;
}
.entry svg {
  height: 15px;
  width: 15px;
}
.entry:hover {
  color: #b45538;
}
.creator-account-name {
  font-size: 12px;
  color: #666d78;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.avatar {
  height: 32px;
  width: 32px;
  border-radius: 50%;
  overflow: hidden;
  background: #edf0f4;
  cursor: pointer;
}
.avatar img {
  height: 100%;
  width: 100%;
  object-fit: cover;
}
.logout {
  border: 0;
  background: #fff;
  font: inherit;
  font-size: 13px;
  color: #666d78;
  min-width: 112px;
  padding: 12px 20px;
  cursor: pointer;
}
.logout:hover {
  color: #b45538;
}
.not-login-text {
  font-size: 12px;
  color: #727985;
}
.creator-navigation {
  position: fixed;
  top: 64px;
  bottom: 0;
  left: 0;
  width: 184px;
  padding: 28px 12px;
  background: #fff;
  border-right: 1px solid #edf0f3;
  box-sizing: border-box;
  z-index: 800;
}
.navigation-section {
  display: block;
  color: #8b929d;
  font-size: 11px;
  line-height: 20px;
  padding: 0 16px;
  margin-bottom: 12px;
}
.creator-navigation a {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  margin-bottom: 4px;
  padding: 0 16px;
  border-radius: 6px;
  color: #606874;
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
}
.creator-navigation a svg {
  height: 18px;
  width: 18px;
  flex: none;
}
.creator-navigation a:hover {
  background: #f6f7f9;
  color: #25272b;
}
.creator-navigation .router-link-active {
  background: #fcf2ed;
  color: #a94d31;
  font-weight: 500;
}
.navigation-divider {
  height: 1px;
  background: #eef0f3;
  margin: 20px 16px;
}
.platform-main {
  margin-left: 184px;
  padding: 88px 28px 32px;
  box-sizing: border-box;
}
.content-body {
  max-width: 1280px;
  margin: 0 auto;
  background: #fff;
  border: 1px solid #e9ecf1;
  border-radius: 10px;
  min-height: calc(100vh - 120px);
  box-sizing: border-box;
}
.platform-view :focus-visible {
  outline: 2px solid #b45538;
  outline-offset: 3px;
}
@media (max-width: 1100px) {
  .creator-navigation {
    width: 160px;
    padding: 24px 8px;
  }
  .platform-main {
    margin-left: 160px;
    padding-left: 20px;
    padding-right: 20px;
  }
  .creator-brand strong {
    font-size: 22px;
  }
  .header-content {
    padding: 0 24px;
  }
}
@media (max-width: 699px) {
  .header-content {
    padding: 0 16px;
    gap: 12px;
  }
  .left-block {
    gap: 12px;
  }
  .creator-brand {
    gap: 10px;
  }
  .creator-brand strong {
    font-size: 21px;
  }
  .creator-brand span {
    font-size: 12px;
    padding-left: 10px;
  }
  .entry {
    display: none;
  }
  .creator-account-name {
    display: none;
  }
  .creator-navigation {
    position: relative;
    inset: auto;
    width: 100%;
    margin-top: 64px;
    height: 52px;
    display: flex;
    align-items: center;
    gap: 20px;
    overflow-x: auto;
    scrollbar-width: none;
    padding: 0 16px;
    border-right: 0;
    border-bottom: 1px solid #edf0f3;
  }
  .navigation-section,
  .navigation-divider {
    display: none;
  }
  .creator-navigation a {
    height: 52px;
    min-height: 0;
    margin: 0;
    padding: 0;
    gap: 6px;
    border-radius: 0;
    font-size: 12px;
    flex: none;
  }
  .creator-navigation a svg {
    height: 15px;
    width: 15px;
  }
  .creator-navigation .router-link-active {
    border-bottom: 2px solid #b45538;
    background: none;
    box-sizing: border-box;
  }
  .platform-main {
    margin: 0;
    padding: 16px 12px 24px;
  }
  .content-body {
    border-radius: 8px;
    min-height: calc(100vh - 156px);
  }
}
</style>
