<template>
  <div class="videoUpload">
    <!-- 选择文件界面 -->
    <div
      class="upload-body-content"
      :style="selectedVideo ? 'display: none;' : ''"
    >
      <div class="upload-wrp">
        <div class="video-input">
          <div class="video-input-wrapper" @click.stop="selectVideo">
            <el-icon size="40"><UploadFilled /></el-icon>
            <div class="upload-tips-text">点击此处可上传</div>
            <div class="upload-btn">上传视频</div>
            <input
              type="file"
              accept=".mp4"
              ref="videoInput"
              @change="handleVideoChange"
              @click.stop="handleVideoInputClick"
              style="display: none"
            />
            <video ref="videoElement" style="display: none"></video>
          </div>
        </div>
      </div>
      <div class="footer-item">
        <span class="i-list i-1">
          <span class="title">视频大小</span>
          <span class="title-block">
            <span>网页端上传的文件大小上限为3GB</span><br />
          </span>
        </span>
        <span class="i-list i-1">
          <span class="title">视频格式</span>
          <span class="title-block">
            <span>网页端、桌面客户端推荐上传的格式为：mp4</span><br />
            <span>暂不允许上传其他格式</span><br />
          </span>
        </span>
        <span class="i-list i-1">
          <span class="title">视频码率</span>
          <span class="title-block">
            <span>推荐视频分辨率：1280*720 或者 1920*1080</span><br />
            <span>网站不提供转码、压缩服务</span><br />
          </span>
        </span>
      </div>
      <div class="footer-item">
        <span
          >上传视频，即表示您已同意 <a target="_blank">EchoFrame使用协议</a> 与
          <a target="_blank">EchoFrame社区公约</a>
          ，请勿上传色情，反动等违法视频。</span
        >
      </div>
    </div>
    <!-- 填写投稿信息界面 -->
    <div class="info-content" v-if="selectedVideo">
      <div class="info-header">
        <span>发布视频</span>
      </div>
      <div class="info-body">
        <!-- 上传文件的进度显示 -->
        <div class="video-name">{{ videoName }}</div>
        <div class="video-file">
          <div class="video-icon-wrp"><i class="iconfont icon-video"></i></div>
          <div class="file-detail">
            <!-- 上传状态 -->
            <div class="file-status">
              <div class="file-status-text" v-if="uploadProgress >= 100">
                <i class="iconfont icon-wancheng success"></i>
                <span class="success">上传完成</span>
              </div>
              <div class="file-status-text" v-else-if="isFailed">
                <i class="iconfont icon-shibai failed"></i>
                <span class="failed">上传失败</span>
                <button v-if="fileHash" type="button" @click="upload">重试上传</button>
              </div>
              <div
                class="file-status-text"
                v-else-if="!isFailed && uploadProgress < 100"
              >
                <i class="iconfont icon-shangchuanzhong"></i>
                <span style="margin-right: 8px">上传中</span>
                <span>{{ uploadProgress }}%</span>
              </div>
              <div
                class="file-status-manage refresh"
                @click.stop="selectVideo"
              >
                <i class="iconfont icon-genghuan"></i>
                <span>更换视频</span>
              </div>
            </div>
            <!-- 上传进度 -->
            <div class="file-progress">
              <div class="file-progress-outer">
                <div
                  class="file-progress-inner"
                  :class="
                    uploadProgress >= 100
                      ? 'success-bg'
                      : false
                      ? 'failed-bg'
                      : 'doing-bg'
                  "
                  :style="`width: ${uploadProgress}%;`"
                ></div>
              </div>
            </div>
          </div>
        </div>
        <!-- 投稿信息 -->
        <div class="upload-form">
          <div class="form-title">基本信息</div>
          <!-- 封面 -->
          <div class="form-item cover">
            <div class="section-title">
              <span class="section-title-deg">*</span>
              <span class="section-title-main">封面</span>
            </div>
            <div class="cover-container">
              <el-upload
                ref="upload"
                class="avatar-uploader"
                action=""
                :auto-upload="false"
                list-type="picture"
                :show-file-list="false"
                :on-change="handleFileChange"
              >
                <img v-if="videoCover" :src="videoCover" class="avatar" />
                <el-icon v-else class="avatar-uploader-icon">
                  <Plus />
                </el-icon>
                <template #tip>
                  <div class="el-upload__tip">
                    只能上传jpg/png格式的文件，且大小不超过1MB
                  </div>
                </template>
              </el-upload>
            </div>
          </div>
          <!-- 标题 -->
          <div class="form-item title">
            <div class="section-title">
              <span class="section-title-deg">*</span>
              <span class="section-title-main">标题</span>
            </div>
            <el-input
              v-model="videoForm.title"
              maxlength="80"
              placeholder="起个吸引人的标题吧"
              show-word-limit
              type="text"
            />
          </div>
          <!-- 类型 -->
          <div class="form-item type">
            <div class="section-title">
              <span class="section-title-deg">*</span>
              <span class="section-title-main">类型</span>
            </div>
            <div class="type-radio">
              <div class="type-radio-item" @click="videoForm.type = 1">
                <div class="radio-box">
                  <div
                    class="radio-box-selected"
                    :style="videoForm.type === 1 ? '' : 'display: none;'"
                  ></div>
                </div>
                <span
                  class="radio-name"
                  :class="videoForm.type === 1 ? 'radio-selected' : ''"
                  >自制</span
                >
              </div>
              <div class="type-radio-item" @click="videoForm.type = 2">
                <div class="radio-box">
                  <div
                    class="radio-box-selected"
                    :style="videoForm.type === 2 ? '' : 'display: none;'"
                  ></div>
                </div>
                <span
                  class="radio-name"
                  :class="videoForm.type === 2 ? 'radio-selected' : ''"
                  >转载</span
                >
              </div>
            </div>
          </div>
          <!-- 分区 -->
          <div class="form-item category">
            <div class="section-title">
              <span class="section-title-deg">*</span>
              <span class="section-title-main">分区</span>
            </div>
            <el-select
              v-model="videoForm.categoryId"
              placeholder="请选择分区"
              size="large"
              style="width: 240px"
            >
              <el-option
                v-for="item in categoryList"
                :key="item.categoryId"
                :label="item.categoryName"
                :value="item.categoryId"
              />
            </el-select>
          </div>
          <!-- 标签 -->
          <div class="form-item tag">
            <div class="section-title">
              <span class="section-title-deg">*</span>
              <span class="section-title-main">标签</span>
            </div>
            <!-- 用v-if重新渲染不留缓存 -->
            <TagInput
              style="width: 80%"
              v-model:sendTag="sendTag"
              @updateTags="
                (val) => {
                  videoForm.tags = val;
                }
              "
            ></TagInput>
          </div>
          <!-- 推荐标签 -->
          <div class="tag-wrp" v-if="true">
            <p class="tag-label">推荐标签:</p>
            <div class="tag-list">
              <div
                class="hot-tag-container"
                :class="
                  videoForm.tags.findIndex((curr) => curr == item.name) != -1
                    ? 'isTagSelected'
                    : ''
                "
                @click="handleSendTag(item.name)"
                v-for="item in rcmTags"
                :key="item.id"
              >
                <div class="hot-tag-item">
                  <span>{{ item.name }}</span>
                </div>
              </div>
            </div>
          </div>
          <!-- 简介 -->
          <div class="form-item descr" style="align-items: flex-start">
            <div class="section-title" style="padding-top: 35px">
              <span class="section-title-deg"></span>
              <span class="section-title-main">简介</span>
            </div>
            <el-input
              class="desc-text"
              type="textarea"
              maxlength="2000"
              show-word-limit
              v-model="videoForm.description"
              placeholder="填写更全面的相关信息，让更多的人能找到你的视频吧:)"
            ></el-input>
          </div>
          <!-- 投稿按钮 -->
          <div class="form-item submit">
            <div class="submit-container">
              <span class="submit-add" @click="submit">立即投稿</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ElMessage } from "element-plus";
import SparkMD5 from "spark-md5";
import TagInput from "@/components/tagInput/TagInput.vue";
import { uploadApi, submitFile } from "@/api/platform";
import { uploadVideo } from '@/lib/upload-video.mjs';
import { mapState, mapActions } from "pinia";
import { useUserStore } from "@/store/user";
import { useVideoStore } from "@/store/video";

export default {
  name: "VideoUpload",
  components: {
    TagInput,
  },
  // 提前声明要使用的自定义事件，防止提示一堆警告
  emits: ["changeNavBarShow"],
  data() {
    return {
      uploadActive: false,
      uploadController: null,
      selectedVideo: null, // 选择的视频文件
      fileHash: null, // 当前视频文件的hash值
      videoURL: null, // 上传的视频的内存地址
      chunkSize: 5 * 1024 * 1024, // 每个分片的大小，单位为字节，即5MB
      videoName: "", // 视频原文件名
      videoCover: "", // 视频封面
      // 投稿信息
      videoForm: {
        fileUrl: "", // 视频地址
        file: null, //视频封面
        title: "", // 视频标题
        type: 1, // 投稿类型 1自制 2转载
        duration: 0, // 视频时长
        categoryId: 1, // 视频分类
        tags: [], // 视频标签
        description: "", // 视频描述
      },
      sendTag: "", // 点击推荐标签时发送的想要添加的标签
      // 推荐标签
      rcmTags: [
        {
          id: 1,
          name: "孤独摇滚",
        },
        {
          id: 2,
          name: "经典",
        },
        {
          id: 3,
          name: "剧场版",
        },
        {
          id: 4,
          name: "TV版",
        },
        {
          id: 5,
          name: "JOJO的奇妙冒险",
        },
        {
          id: 6,
          name: "名场面",
        },
        {
          id: 7,
          name: "动漫",
        },
        {
          id: 8,
          name: "热血",
        },
        {
          id: 9,
          name: "治愈",
        },
        {
          id: 10,
          name: "二次元",
        },
      ],
      uploadProgress: 0, // 视频上传进度
      isFailed: false, // 视频分片上传失败标识
    };
  },
  created() {
    this.getCategoryList();
  },
  beforeUnmount() {
    this.uploadController?.abort();
    if (this.videoURL) URL.revokeObjectURL(this.videoURL);
  },
  computed: {
    ...mapState(useUserStore, ["user", "isLogin"]),
    ...mapState(useVideoStore, ["categoryList"]),
  },
  methods: {
    ...mapActions(useVideoStore, ["getCategoryList"]),
    ...mapActions(useUserStore, ["updateVideoCount"]),

    // 初始化内容
    init() {
      this.uploadController?.abort();
      this.uploadController = null;
      this.uploadActive = false;
      if (this.videoURL) URL.revokeObjectURL(this.videoURL);
      this.selectedVideo = null;
      this.fileHash = null;
      this.videoURL = null;
      this.videoName = "";
      this.videoCover = null;
      this.uploadProgress = 0;
      this.videoForm = {
        fileUrl: "",
        file: null,
        title: "",
        type: 1,
        duration: 0,
        categoryId: 1,
        tags: [],
        description: "",
      };
      this.isFailed = false;
    },
    // 触发文件选择对话框
    selectVideo() {
      // debugger;
      if (!this.isLogin) {
        ElMessage.warning("您未登录");
        return;
      }

      this.$refs.videoInput.click();
    },
    handleVideoInputClick(event) {
      // 阻止事件冒泡，再次触发selectVideo方法
      event.stopPropagation();
    },
    // 处理文件选择事件
    async handleVideoChange(event) {
      const file = event.target.files[0];
      event.target.value = '';
      if (!file) return;
      this.init();
      const controller = new AbortController();
      this.uploadController = controller;
      try {
        if (!file.size || file.size > 3 * 1024 ** 3 || !/\.(mp4|mov|avi|webm)$/i.test(file.name)) {
          throw new Error('请选择 MP4、MOV、AVI 或 WebM 视频，大小不超过3GB');
        }
        this.selectedVideo = file;
        const hash = await this.calculateFileHash(file, controller.signal);
        controller.signal.throwIfAborted();
        this.fileHash = hash;
        this.$emit('changeNavBarShow', false);
        const videoElement = this.$refs.videoElement;
        this.videoURL = URL.createObjectURL(file);
        videoElement.onloadedmetadata = () => {
          if (!controller.signal.aborted) this.videoForm.duration = videoElement.duration;
        };
        videoElement.src = this.videoURL;
        this.videoName = file.name;
        this.videoForm.title = file.name.replace(/\.[^.]+$/, '');
        await this.upload();
      } catch (error) {
        if (!controller.signal.aborted) {
          this.isFailed = true;
          ElMessage.error(error.message || String(error));
        }
      }
    },
    // **计算文件的唯一 hash（MD5）**
    calculateFileHash(file, signal) {
      return new Promise((resolve, reject) => {
        const spark = new SparkMD5.ArrayBuffer();
        const reader = new FileReader();
        const chunkSize = this.chunkSize;
        let offset = 0;

        function loadNext() {
          const blob = file.slice(offset, offset + chunkSize);
          reader.readAsArrayBuffer(blob);
        }

        const abort = () => reader.abort();
        signal.addEventListener('abort', abort, { once: true });
        const cleanup = () => signal.removeEventListener('abort', abort);
        reader.onerror = () => { cleanup(); reject(new Error('读取视频文件失败，请重新选择')); };
        reader.onabort = () => { cleanup(); reject(new DOMException('已取消', 'AbortError')); };
        reader.onload = (e) => {
          spark.append(e.target.result);
          offset += chunkSize;
          if (offset < file.size) {
            loadNext();
          } else {
            cleanup();
            resolve(spark.end()); // 计算最终的 MD5
          }
        };

        loadNext();
      });
    },

    async upload() {
      if (!this.selectedVideo || !this.fileHash || this.uploadActive) return;
      this.uploadActive = true;
      const controller = this.uploadController?.signal.aborted === false
        ? this.uploadController : new AbortController();
      this.uploadController = controller;
      this.isFailed = false;
      this.videoForm.fileUrl = '';
      try {
        const url = await uploadVideo(this.selectedVideo, this.fileHash, uploadApi, controller.signal,
          progress => { this.uploadProgress = progress; });
        if (!controller.signal.aborted) this.videoForm.fileUrl = url;
      } catch (error) {
        if (!controller.signal.aborted) {
          this.isFailed = true;
          ElMessage.error(error.message || String(error));
        }
      } finally {
        if (this.uploadController === controller) this.uploadActive = false;
      }
    },

    // 处理上传封面
    handleFileChange(file) {
      const isJPGAndPNG =
        file.raw.type === "image/jpeg" || file.raw.type === "image/png";
      const isLt3M = file.size / 1024 / 1024 < 3;

      if (!isJPGAndPNG) {
        return ElMessage.error("上传图片只能是 JPG或PNG 格式!");
      }
      if (!isLt3M) {
        return ElMessage.error("上传图片大小不能超过 3MB!");
      }
      this.videoCover = file.url;
      this.videoForm.file = file.raw;
    },

    // 传递标签
    handleSendTag(tagName) {
      this.sendTag = tagName;
    },
    async submit() {
      if (!this.videoForm.fileUrl) {
        ElMessage.warning("视频还没有上传完成");
        return;
      }
      if (!this.videoForm.file) {
        ElMessage.warning("请上传视频封面");
        return;
      }
      if (this.videoForm.title.trim().length === 0) {
        ElMessage.warning("写个标题再上传吧");
        return;
      }
      if (this.videoForm.tags.length === 0) {
        ElMessage.warning("至少选一个标签哦");
        return;
      }
      try {
        const formData = new FormData();
        formData.append("fileUrl", this.videoForm.fileUrl);
        formData.append("userId", this.user.userId);
        formData.append("file", this.videoForm.file);
        formData.append("title", this.videoForm.title);
        formData.append("type", this.videoForm.type);
        formData.append("duration", this.videoForm.duration);
        formData.append("categoryId", this.videoForm.categoryId);
        formData.append("tags", JSON.stringify(this.videoForm.tags));
        formData.append("description", this.videoForm.description);
        // 请求投稿接口
        const res = await submitFile(formData);
        if (res.code === 200) {
          // 更新用户投稿数
          this.updateVideoCount();
          ElMessage.success("投稿成功");
          // 投稿成功显示导航切换栏
          this.$emit("changeNavBarShow", true);
          this.init();
        }
      } catch (error) {
        ElMessage.error("投稿失败:" + error);
      }
    },
  },
  watch: {
    'user.userId'() { this.init(); },
    isLogin(value) { if (!value) this.init(); },
    categoryList(val) {
      if (val && val.length > 0) {
        this.videoForm.categoryId = val[0].id;
      }
    },
  },
};
</script>

<style scoped>
.videoUpload {
  padding-top: 8px;
  overflow: auto;
}

.cover-cut-content-pick-bar {
  margin-top: 20px;
  margin-left: 12px;
  width: 704px;
  height: 60px;
  user-select: none;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
}

.cover-slider {
  position: relative;
  background: #e7e7e7;
  box-shadow: 0 0 2px rgba(0, 0, 0, 0.04), 0 4px 10px rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  height: 60px;
}

.slider-handle {
  position: absolute;
  z-index: 5;
  width: 12px;
  height: 66px;
  cursor: pointer;
  background: url("@/assets/img/icon_hot.png");
  box-sizing: border-box;
  background-size: cover;
  border-radius: 4px;
  top: -3px;
  transform: translateX(-50%);
}

.cover-slider-image-list {
  display: flex;
  position: absolute;
  top: 0;
  width: 100%;
  justify-content: center;
}

.cover-slider-image {
  height: 60px;
  width: 100px;
}

.upload-body-content {
  max-width: 830px;
  margin: 0 auto;
  position: relative;
  width: 80%;
}

.upload-wrp {
  margin-top: 20px;
  position: relative;
  border: 2px dashed #ccc;
}

.video-input {
  margin-top: 62px;
  margin-bottom: 32px;
  display: flex;
  justify-content: center;
  position: relative;
  color: #999;
  font-size: 14px;
  text-align: center;
}

.pic-input {
  display: flex;
  justify-content: center;
  position: relative;
  color: #999;
  font-size: 14px;
  text-align: center;
}

.video-input-wrapper {
  flex: 1;
  cursor: pointer;
  padding-bottom: 20px;
  display: inline-block;
}

.pic-input-wrapper {
  flex: 1;
  cursor: pointer;
  padding: 70px 0;
  display: inline-block;
}

.upload-tips-text {
  font-size: 13px;
  color: #999;
  margin-top: 6px;
}

.upload-btn {
  color: #fff;
  margin: 20px auto;
  width: 200px;
  height: 44px;
  cursor: pointer;
  background: var(--brand_blue);
  border-radius: 4px;
  text-align: center;
  line-height: 44px;
  white-space: nowrap;
}

.footer-item {
  margin-top: 18px;
  text-align: center;
  color: #99a2aa;
  fill: #99a2aa;
  font-size: 12px;
  line-height: 20px;
}

.footer-item .i-list {
  margin: 0 10px;
}

.footer-item .i-1 {
  cursor: pointer;
  position: relative;
}

.footer-item .i-1 a {
  color: #99a2aa;
}

.footer-item .i-1 .title:hover {
  color: var(--brand_blue);
}

.footer-item .i-1 .title-block {
  position: absolute;
  font-size: 12px;
  border: 1px solid #f9d199;
  color: #666;
  text-align: left;
  padding: 20px;
  background-color: #fcfae0;
  white-space: nowrap;
  z-index: 10;
  bottom: 28px;
  right: -5px;
  display: none;
}

.footer-item .i-1:hover .title-block {
  display: block;
}

.footer-item .i-1 .title-block:after {
  content: "";
  position: absolute;
  width: 14px;
  height: 14px;
  transform: rotate(45deg);
  border: 1px solid;
  border-color: transparent #f9d199 #f9d199 transparent;
  background-color: #fcfae0;
  right: 10px;
  bottom: -8px;
}

.icon-xiazai {
  margin-right: 2px;
}

.info-content {
  padding: 2px 30px;
}

.info-header {
  position: relative;
  font-size: 16px;
  font-weight: 600;
  color: #212121;
  line-height: 28px;
  height: 70px;
  box-shadow: 0 1px 0 #e7e7e7;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cancel-btn {
  font-size: 14px;
  line-height: 1;
  padding: 12px 16px;
  color: var(--text2);
  cursor: pointer;
  border-radius: 4px;
  box-sizing: border-box;
  display: inline-block;
  text-align: center;
  border: 1px solid #e7e7e7;
  background-color: #fff;
  margin-right: 30px;
}

.cancel-btn:hover {
  background-color: #f4f4f4;
}

.video-name {
  padding-top: 25px;
  font-size: 14px;
  color: #212121;
}

.video-file {
  padding-top: 15px;
  display: flex;
  width: 80%;
}

.video-icon-wrp {
  padding-right: 10px;
  width: 50px;
  height: 50px;
  display: flex;
  align-items: center;
}

.icon-video {
  font-size: 40px;
  color: var(--brand_blue);
}

.file-detail {
  flex: 1;
}

.success {
  color: var(--success_green);
}

.failed {
  color: var(--stress_red);
}

.file-status {
  display: flex;
  padding-top: 5px;
}

.file-status .iconfont {
  font-size: 20px;
  margin-right: 8px;
}

.file-status-text {
  flex: 1;
  height: 20px;
  font-size: 14px;
  color: #999;
  line-height: 28px;
  display: flex;
  align-items: center;
}

.file-status-manage {
  color: var(--brand_blue);
  cursor: pointer;
  user-select: none;
  font-size: 14px;
  display: flex;
  align-items: center;
  margin-left: 10px;
}

.file-progress {
  padding-top: 12px;
}

.file-progress-outer {
  height: 3px;
  width: 100%;
  border-radius: 4px;
  background-color: #e7e7e7;
}

.file-progress-inner {
  height: 3px;
  border-radius: 4px;
  transition: width 0.7s ease;
}

.doing-bg {
  background-color: var(--brand_blue);
}

.success-bg {
  background-color: var(--success_green);
}

.failed-bg {
  background-color: var(--stress_red);
}

.form-title {
  font-size: 16px;
  font-weight: 600;
  color: #222;
  margin-top: 30px;
}

.form-item {
  margin-top: 24px;
  color: #212121;
  display: flex;
  align-items: center;
}

.section-title {
  display: inline-flex;
  align-items: center;
  position: relative;
  flex-wrap: wrap;
  width: 134px;
}

.section-title-deg {
  font-size: 16px;
  color: #ff3b30;
  line-height: 16px;
  width: 12px;
}

.section-title-main {
  font-size: 14px;
  color: #212121;
  line-height: 21px;
  font-weight: 400;
}

.tag-wrp {
  display: flex;
  margin-top: 15px;
  width: 80%;
}

.tag-label {
  font-size: 14px;
  color: #222;
  padding: 14px 0 0 134px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  flex: 1;
}

.hot-tag-container {
  text-align: center;
  padding: 0 15px 0 16px;
  margin: 6px 0 6px 12px;
  height: 30px;
  color: #6d757a;
  cursor: pointer;
  border-radius: 4px;
  background: #f6f6f6;
  font-size: 12px;
  color: #212121;
  line-height: 14px;
  border: none;
}

.isTagSelected {
  background: var(--brand_blue);
  color: #fff;
  cursor: not-allowed;
  border: 0;
}

.hot-tag-item {
  font-size: 12px;
  line-height: 30px;
}

.type-radio {
  display: flex;
  align-items: center;
}

.type-radio-item {
  cursor: pointer;
  margin-right: 20px;
  display: flex;
  align-items: center;
  position: relative;
}

.radio-box {
  border: 1px solid #bec3cc;
  height: 18px;
  width: 18px;
  border-radius: 50%;
  margin-right: 6px;
  padding: 3px;
}

.radio-box-selected {
  height: 100%;
  width: 100%;
  border-radius: 50%;
  background-color: var(--brand_blue);
}

.radio-name {
  font-size: 14px;
  color: var(--text2);
  line-height: 14px;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
}

.radio-selected {
  color: var(--brand_blue);
}

.auth {
  margin-left: 40px;
  color: #505050;
  font-size: 14px;
  display: flex;
  align-items: center;
  cursor: pointer;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
}

.auth-box {
  position: relative;
  display: inline-flex;
  width: 14px;
  height: 14.5px;
  border: 1px solid silver;
  border-radius: 2px;
  align-items: center;
  justify-content: center;
}

.auth-box:hover {
  border: 1px solid var(--brand_blue);
}

.icon-gou {
  color: #fff;
  font-size: 16px;
}

.auth-selected {
  background-color: var(--brand_blue);
}

.auth-main {
  padding-left: 8px;
}

.cover-container {
  width: 192px;
  /* height: 108px; */
  border-radius: 4px;
  overflow: hidden; /* 隐藏超出容器的部分 */
}

.cover-container img {
  height: 100%;
  width: 100%;
  object-fit: cover; /* 居中填充容器，不会变形 */
}

.cover-right {
  display: flex;
  flex-direction: column;
  margin-left: 20px;
}

.change-cover-btn {
  margin-bottom: 4px;
}

.reselect {
  margin-top: 8px;
  width: 94px;
}

.reselect .iconfont {
  font-size: 20px;
  margin-right: 8px;
}

.cover-dialog-header,
.cover-dialog-body,
.cover-dialog-footer {
  width: 800px;
}

.cover-dialog-header {
  border-bottom: 1px solid #e7e7e7;
}

.header-tab {
  padding: 0 32px;
  height: 61px;
  display: flex;
}

.header-tab-item {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-right: 29px;
}

.tab-text {
  color: #18191c;
  font-weight: 500;
  font-size: 16px;
  padding: 20px 0 18px 0;
}

.tab-line {
  height: 3px;
  width: 66px;
}

.currentTab .tab-text {
  color: var(--brand_blue);
}

.currentTab .tab-line {
  background-color: var(--brand_blue);
}

.desc-text {
  width: 80%;
  margin-top: 12px;
}

.submit-container {
  padding: 32px 0 32px 134px;
  position: relative;
}

.submit-add {
  margin-left: 16px;
  line-height: 40px;
  color: #fff;
  background-color: var(--brand_blue);
}

.submit-add:hover {
  background-color: var(--v_brand_blue_hover);
}

.submit-add {
  display: inline-block;
  height: 40px;
  font-size: 14px;
  border-radius: 4px;
  text-align: center;
  vertical-align: middle;
  width: 120px;
  cursor: pointer;
  -webkit-user-select: none;
  -moz-user-select: none;
  -ms-user-select: none;
  user-select: none;
  transition: all 0.3s ease-in-out;
}

.pick-version {
  padding: 0 30px;
  height: 450px;
  transition: height 0.5s;
}

.upload-version {
  padding: 0 30px;
  height: 372px;
  transition: height 0.5s;
}

.cover-dialog-footer {
  height: 86px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cover-dialog-submit {
  width: 128px;
  font-size: 16px;
  padding: 12px 16px;
  color: #fff;
  background-color: var(--brand_blue);
  display: inline-block;
  text-align: center;
  cursor: pointer;
  border-radius: 4px;
  position: relative;
  line-height: 1;
}

/* element 元素 */
.cover-dialog /deep/ .el-dialog__body {
  margin: -30px 0 0 0;
  padding: 0;
}

.el-input {
  width: 80%;
  --el-input-hover-border-color: var(--brand_blue);
}

.el-input /deep/ .el-input__wrapper {
  padding: 4px 11px;
}

.el-textarea {
  --el-input-focus-border-color: #dcdfe6;
  --el-input-hover-border-color: #dcdfe6;
}

.el-textarea /deep/ .el-textarea__inner {
  height: 160px !important;
  resize: none;
  font-size: 13px;
  padding: 12px 15px;
  padding-right: 60px;
  line-height: 1.42;
  border-radius: 8px;
}

.el-textarea /deep/ .el-textarea__inner::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.el-textarea /deep/ .el-textarea__inner::-webkit-scrollbar-thumb {
  border-radius: 10px;
  background-color: #ccc;
}

::v-deep .avatar-uploader .el-upload {
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: 0.2s;
}
::v-deep .el-icon.avatar-uploader-icon {
  font-size: 28px;
  color: #8c939d;
  width: 178px;
  height: 178px;
  text-align: center;
}
</style>
