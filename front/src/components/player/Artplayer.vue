<template>
  <div class="video-player">
    <div class="player-viewport" :aria-busy="loading">
      <div ref="artRef" class="player-mount"></div>
      <div v-if="playbackError" class="player-status player-error" role="alert">
        <p>{{ playbackError }}</p>
        <button type="button" @click="retryPlayback">重新加载</button>
      </div>
      <div v-else-if="loading" class="player-status" role="status"><span class="player-loading-mark" aria-hidden="true"></span><span>正在加载视频…</span></div>
    </div>
    <div class="player-sending-area">
      <div class="player-video-info">
        <div class="player-video-info-text" :title="`${fullInteractionCount(onlineUserCount)} 人正在观看，已装填 ${fullInteractionCount(danmuCount)} 条弹幕`">
          <span class="player-info-wide">{{ compactInteractionCount(onlineUserCount) }} 人正在观看，已装填 {{ compactInteractionCount(danmuCount) }} 条弹幕</span>
          <span class="player-info-compact">{{ compactInteractionCount(onlineUserCount) }} 人在看 · {{ compactInteractionCount(danmuCount) }} 条弹幕</span>
        </div>
      </div>
      <button type="button" class="danmu-settings-link" @click="openDanmuSettings">弹幕设置</button>
      <div ref="artDanmuRef" class="danmu-panel"></div>
    </div>
  </div>
</template>

<script>
import Artplayer from "artplayer";
import artplayerPluginDanmuku from "artplayer-plugin-danmuku";
import { markRaw, toRaw } from "vue";
import { ElMessage } from "element-plus";
import { useUserStore } from "@/store/user";
import { mapState } from "pinia";
import { compactInteractionCount, fullInteractionCount } from '@/lib/interaction-count.mjs';
export default {
  data() {
    return {
      instance: null,
      loading: true,
      playbackError: "",
      emitterRoot: null,
      option: {
        autoSize: false, // 固定播放区域，宽屏素材也不会将控制栏挤成一条
        autoMini: true, //当播放器滚动到浏览器视口以外时，自动进入 迷你播放 模式
        fullscreen: true, //显示全屏按钮
        fullscreenWeb: true, //显示在网页全屏
        hotkey: true, //开启快捷键
        pip: true, //  是否在底部控制栏里显示 画中画 的开关按钮
        playbackRate: true, // 是否显示视频播放速度功能，会出现在 设置面板 和 右键菜单 里
        aspectRatio: true, //是否显示视频长宽比功能，会出现在 设置面板 和 右键菜单 里
        setting: true, // 显示设置按钮
        theme: "#c96442",
        cssVar: {
          '--art-font-color': '#faf9f5',
          '--art-background-color': '#141413',
          '--art-widget-background': 'rgba(48,48,46,.97)',
          '--art-tip-background': 'rgba(20,20,19,.94)',
          '--art-border-radius': '12px',
          '--art-control-height': '42px',
          '--art-control-icon-size': '22px',
          '--art-control-icon-scale': 1.06,
          '--art-progress-height': '3px',
          '--art-progress-color': 'rgba(232,230,220,.22)',
          '--art-loaded-color': 'rgba(232,230,220,.45)',
          '--art-hover-color': 'rgba(232,230,220,.08)',
          '--art-transition-duration': '180ms',
          '--art-padding': '16px',
        },
        plugins: [
          artplayerPluginDanmuku({
            danmuku: [], // 弹幕数据

            // 以下为非必填
            speed: 5, // 弹幕持续时间，范围在[1 ~ 10]
            margin: [10, "25%"], // 弹幕上下边距，支持像素数字和百分比
            opacity: 1, // 弹幕透明度，范围在[0 ~ 1]
            color: "#FFFFFF", // 默认弹幕颜色，可以被单独弹幕项覆盖
            mode: 0, // 默认弹幕模式: 0: 滚动，1: 顶部，2: 底部
            modes: [0, 1, 2], // 弹幕可见的模式
            fontSize: 25, // 弹幕字体大小，支持像素数字和百分比
            antiOverlap: true, // 弹幕是否防重叠
            synchronousPlayback: false, // 是否同步播放速度
            mount: undefined, // 弹幕发射器挂载点, 默认为播放器控制栏中部
            heatmap: true, // 是否开启热力图
            width: 512, // 当播放器宽度小于此值时，弹幕发射器置于播放器底部
            points: [], // 热力图数据
            filter: (danmu) => danmu.text.length <= 100, // 弹幕载入前的过滤器
            beforeVisible: () => true, // 弹幕显示前的过滤器，返回 true 则可以发送
            visible: true, // 弹幕层是否可见
            emitter: true, // 是否开启弹幕发射器
            maxLength: 200, // 弹幕输入框最大长度, 范围在[1 ~ 1000]
            lockTime: 5, // 输入框锁定时间，范围在[1 ~ 60]
            theme: "light", // 弹幕主题，支持 dark 和 light，只在自定义挂载时生效
            OPACITY: {}, // 不透明度配置项
            FONT_SIZE: {}, // 弹幕字号配置项
            MARGIN: {}, // 显示区域配置项
            SPEED: {}, // 弹幕速度配置项
            COLOR: [], // 颜色列表配置项

            // 手动发送弹幕前的过滤器，返回 true 则可以发送，可以做存库处理
            beforeEmit: (danmu) => this.sendDanmu(danmu),
          }),
        ],
      },
    };
  },
  props: {
    poster: { type: String, default: '' },
    videoUrl: {
      type: String,
      required: true,
    },
    videoId: {
      type: String,
      required: true,
    },
    onlineUserCount: {
      type: Number,
      default() {
        return 0;
      },
    },
    danmuList: {
      type: Array,
      default() {
        return [];
      },
    },

    seekTimePoint: {
      type: Number,
      default() {
        return 0;
      },
    },
    isSelfDanmu: {
      type: Boolean,
      default() {
        return false;
      },
    },
  },
  async mounted() {
    // 初始化播放器
    this.initArtPlayer();
  },
  beforeUnmount() {
    if (this.instance && this.instance.destroy) {
      if (this.instance.fullscreenWeb) document.body.style.overflow = "";
      this.instance.destroy();
    }
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
    danmuCount() {
      return this.danmuList.length;
    },
  },
  methods: {
    compactInteractionCount,
    fullInteractionCount,
    openDanmuSettings() { this.emitterRoot?.querySelector('.apd-config')?.click(); },
    initArtPlayer() {
      this.$nextTick(() => {
        this.instance = markRaw(new Artplayer({
          url: this.videoUrl,
          contextmenu: [],
          ...this.option,
          moreVideoAttr: { preload: 'metadata', playsInline: true },
          poster: this.poster,
          container: this.$refs.artRef,
        }));

        this.instance.once("video:loadedmetadata", () => {
          if (Number.isFinite(this.seekTimePoint) && this.seekTimePoint > 0) {
            this.instance.seek = Math.min(this.seekTimePoint, this.instance.duration || 0);
          }
        });
        this.instance.on("video:timeupdate", () => {
          const milliseconds = Math.round(this.instance.currentTime * 1000);
          if (Number.isSafeInteger(milliseconds) && milliseconds >= 0) {
            this.$emit('playback-position', milliseconds);
            if (!this.instance.video.paused && !this.instance.video.ended) this.$emit('watch-progress', milliseconds);
          }
        });
        this.instance.on("video:loadstart", () => {
          this.loading = true;
          this.playbackError = "";
        });
        this.instance.on("video:canplay", () => {
          this.loading = false;
          this.playbackError = "";
        });
        this.instance.on("video:error", () => {
          this.loading = false;
          this.playbackError = "视频加载失败，请检查网络或视频地址后重试。";
        });

        Artplayer.CONTEXTMENU = false; // 关闭右键菜单
        Artplayer.LOG_VERSION = false; // 关闭控制台日志，设置取消打印播放器版本

        // 绑定网页全屏事件，当处于网页全屏时禁止页面滚动以及控制弹幕发射器的显示与隐藏
        this.instance.on("fullscreenWeb", (state) => {
          if (state) {
            document.body.style.overflow = "hidden"; // 禁止滚动
          } else {
            document.body.style.overflow = ""; // 允许滚动
          }
        });
        // 加载弹幕数据
        this.instance.on("ready", () => {
          toRaw(this.instance).plugins.artplayerPluginDanmuku.load(
            this.danmuList
          );
          this.seekFromRoute();
        });
        // 挂载弹幕发射器在播放器外部。在初始化弹幕插件的时候，是可以指定弹幕发射器的挂载位置的，默认是挂载在控制栏的中部，你也可以把它挂载在播放器以外的地方。 当播放器全屏的时候，发射器会自动回到控制栏的中部。
        toRaw(this.instance).plugins.artplayerPluginDanmuku.mount(
          this.$refs.artDanmuRef
        );
        this.prepareEmitter();
      });
    },
    prepareEmitter() {
      const root = this.$refs.artDanmuRef.querySelector('.artplayer-plugin-danmuku');
      if (!root) return;
      this.emitterRoot = root;
      const controls = [['.apd-toggle', '弹幕'], ['.apd-config', '弹幕设置'], ['.apd-style', '样式'], ['.apd-send', '发送弹幕']];
      for (const [selector, label] of controls) {
        const control = root.querySelector(selector);
        control.setAttribute('role', 'button');
        control.setAttribute('tabindex', '0');
        control.setAttribute('aria-label', label);
        control.setAttribute('title', label);
        if (selector === '.apd-config' || selector === '.apd-style') {
          control.setAttribute('aria-expanded', 'false');
          this.instance.proxy(control, 'click', event => {
            if (event.target.closest('.apd-config-panel,.apd-style-panel')) return;
            const open = !control.classList.contains('emitter-panel-open');
            this.closeEmitterPanels();
            control.classList.toggle('emitter-panel-open', open);
            control.setAttribute('aria-expanded', String(open));
          });
        }
        this.instance.proxy(control, 'keydown', event => {
          if (event.target !== control) return;
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            if (control.getAttribute('aria-disabled') !== 'true') control.click();
          }
          if (event.key === 'Escape') this.closeEmitterPanels();
        });
      }
      this.instance.proxy(document, 'pointerdown', event => {
        if (!event.target.closest('.apd-config,.apd-style')) this.closeEmitterPanels();
      });
      this.instance.on('artplayerPluginDanmuku:show', this.updateEmitterState);
      this.instance.on('artplayerPluginDanmuku:hide', this.updateEmitterState);
      this.updateEmitterState();
    },
    closeEmitterPanels() {
      this.emitterRoot?.querySelectorAll('.emitter-panel-open').forEach(control => {
        control.classList.remove('emitter-panel-open');
        control.setAttribute('aria-expanded', 'false');
      });
    },
    updateEmitterState() {
      if (!this.emitterRoot) return;
      const input = this.emitterRoot.querySelector('.apd-input');
      input.setAttribute('aria-label', '弹幕内容');
      input.placeholder = this.playbackError ? '视频暂不可用，请重试' : this.loading ? '视频准备中…' : this.isLogin ? '发条弹幕，一起聊聊' : '登录后发送弹幕';
      input.disabled = this.loading || !!this.playbackError;
      const send = this.emitterRoot.querySelector('.apd-send');
      send.setAttribute('aria-disabled', String(input.disabled));
      const toggle = this.emitterRoot.querySelector('.apd-toggle');
      const visible = !this.instance.plugins.artplayerPluginDanmuku.isHide;
      toggle.setAttribute('aria-label', visible ? '关闭弹幕' : '打开弹幕');
      toggle.setAttribute('title', visible ? '关闭弹幕' : '打开弹幕');
      toggle.setAttribute('aria-pressed', String(visible));
    },
    retryPlayback() {
      if (!this.instance) return;
      this.playbackError = "";
      this.loading = true;
      this.instance.template.$video.load();
    },
    seekFromRoute() {
      const raw = this.$route.query.t;
      const position = typeof raw === 'string' && /^\d+(?:\.\d{1,3})?$/.test(raw) ? Number(raw) : NaN;
      if (this.instance && Number.isFinite(position) && position >= 0 && position < this.instance.duration) this.instance.seek = position;
    },
    sendDanmu(danmu) {
      return new Promise((resolve) => {
        if (this.loading || this.playbackError) { resolve(false); return; }
        // 判断网络是否连接
        // if (!navigator.onLine) {
        //   resolve(false);
        //   return ElMessage.warning("网络未连接，请检查网络！");
        // }
        if (!this.isLogin) {
          resolve(false);
          return ElMessage.warning("请先登录！");
        }
        const danmuObj = {
          videoId: this.videoId.toString(),
          userId: this.user.userId,
          content: danmu.text,
          playbackTime: danmu.time,
        };
        this.$emit("sendDanmu", danmuObj);
        setTimeout(() => {
          if (this.isSelfDanmu) {
            resolve(true);
          } else {
            resolve(false);
          }
        }, 1000);
      });
    },
  },
  watch: {
    isLogin() { this.updateEmitterState(); },
    loading() { this.updateEmitterState(); },
    playbackError() { this.updateEmitterState(); },
    '$route.query.t'() { this.seekFromRoute(); },
    poster(value) { if (this.instance) this.instance.poster = value || ''; },
    videoUrl(newVal) {
      if (this.instance && newVal) {
        this.loading = true;
        this.playbackError = "";
        this.instance.url = newVal;
      }
    },
    danmuList(newVal) {
      if (this.instance && this.instance.isReady) {
        this.instance.plugins.artplayerPluginDanmuku.load(newVal);
      }
    },
    // 监听弹幕秒数进行视频进度跳转
    seekTimePoint(newVal) {
      if (this.instance && Number.isFinite(newVal) && newVal >= 0) {
        this.instance.seek = newVal;
      }
    },
  },
};
</script>

<style lang="scss">
.video-player {
  width: 100%;
  min-width: 0;
}

.player-viewport {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #000;
}

.player-mount {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.player-status {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: #fff;
  background: rgba(0, 0, 0, 0.65);
  pointer-events: none;
  z-index: 200; // 高于播放器的播放按钮、弹幕与设置层，确保重试按钮可点击
}

.player-error {
  pointer-events: auto;
  button {
    margin-top: 12px;
    padding: 8px 18px;
    border: 0;
    border-radius: 6px;
    background: #00aeec;
    color: #fff;
    cursor: pointer;
  }
}

.player-sending-area {
  -webkit-box-flex: 0;
  -webkit-box-pack: justify;
  -ms-flex-pack: justify;
  background: #fff;
  display: -webkit-box;
  display: -ms-flexbox;
  display: flex;
  align-items: center;
  -ms-flex: none;
  flex: none;
  font-size: 12px;
  height: 46px;
  justify-content: space-between;
  padding: 0 12px;
  font-size: 13px;
  box-shadow: 0 2px 6px #ddd;
  height: 56px;
  border-top: none;

  .player-video-info {
    -ms-flex-negative: 1;
    -webkit-box-align: center;
    -ms-flex-align: center;
    align-items: center;
    color: var(--text2);
    display: -webkit-box;
    display: -ms-flexbox;
    display: flex;
    flex-shrink: 1;
    height: 16px;
    line-height: 18px;
    margin-right: 24px;
    overflow: hidden;
    position: relative;
    -webkit-user-select: none;
    -moz-user-select: none;
    -ms-user-select: none;
    user-select: none;
    white-space: nowrap;

    .player-video-info-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .danmu-panel {
    flex: 1;
    .artplayer-plugin-danmuku .apd-emitter {
      background-color: #f1f2f3;
    }
    .artplayer-plugin-danmuku .apd-icon {
      fill: #333;
    }
    .artplayer-plugin-danmuku .apd-input {
      color: #212121;
      font-size: 13px;
      &::placeholder {
        color: #818181;
      }
    }
  }
  .apd-send {
    background-color: #00aeec;
  }
}
.danmu-settings-link { flex: 0 0 auto; border: 0; background: transparent; color: #77766f; font: inherit; font-size: 12px; cursor: pointer; padding: 6px; }
.danmu-settings-link:hover { color: #c96442; }
</style>
