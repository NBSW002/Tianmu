# 网站、业务后端与真实 Agent 的单机验收部署

这套叠加部署复用既有 `tianmu-staging_default` 网络中的 Redis、Kafka 和 ClickHouse，另建业务 MySQL 与独立的业务／Agent 数据库。旧演示数据库和对象卷保留。适用于当前单机验收环境；资源有限时需要交换空间，不能据此推断生产容量。

## 目录与构建

源码发布目录为 `/opt/tianmu-site/releases/RELEASE`，私有配置为 `/opt/tianmu-site/config`。前端构建产物放在发布目录的 `front/dist/`。这些服务器路径中的真实凭据不应提交到仓库。

```sh
docker build -f deploy/Dockerfile.business -t tianmu-business:RELEASE .
docker build -f deploy/Dockerfile -t tianmu-agent:RELEASE .
docker build -f deploy/site/Dockerfile.elasticsearch -t tianmu-elasticsearch:8.18.1-ik .
docker build -f deploy/site/Dockerfile.minio -t tianmu-minio:2025-10-15 .
```

业务镜像构建会运行默认测试；外部数据库集成测试仍由原测试开关控制。Elasticsearch 安装匹配版本的 IK 插件，MinIO 使用固定官方源码版本构建。部署时记录实际镜像 digest / image ID。

## 初始化与启动

先将真实模型 Key 私密写入 `config/secrets/model-api-key`，权限 `0400`、所有者 `10001:10001`。再运行：

```sh
python3 deploy/site/prepare.py --config /opt/tianmu-site/config --release RELEASE
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d business-mysql
# 等待 MySQL 就绪后，仅初始化本部署专用数据库。
python3 deploy/site/prepare.py --config /opt/tianmu-site/config --release RELEASE --initialize-db
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d
```

`business-schema.sql` 是当前实体对应的新库初始化结构，不是既有业务库的升级迁移。`--initialize-db` 只创建验收账号，不创建验收视频或模拟弹幕；需要验收时请从网站正常上传测试视频并导入测试弹幕。该脚本不应指向其他业务库。验收账号密码仅写入私有 `acceptance.json`。

业务与 Agent 分别使用 `tianmu_site`、`tianmu_site_agent`；网站登录使用 Redis database 1。业务文件与 Agent 报告共用同一 MinIO 实例，报告和 Trace 明细放在私有 `tianmu-agent-artifacts` 桶。

### 每日上线硬币与既有数据库升级

已登录用户在可见页面上线时，每个北京时间自然日首次获得 1 枚硬币。打开网站、登录后进入页面、回到前台和每分钟可见页面检查均可触发，不要求重新登录；访客不发放，隐藏的后台页面不主动领取，不补发离线日期。余额与领取日期由服务端在同一条数据库更新中写入，同日刷新、多开页面或多实例请求不会重复发放。奖励到账后网站同步硬币余额；无需手动签到。

新库的 `business-schema.sql` 已包含 `user_stats.last_online_reward_date`。既有库须在发布应用前，由部署人员对业务库执行一次 [20261031_daily_online_coin.sql](migrations/20261031_daily_online_coin.sql)，先检查列是否已存在，不要重复执行，也不要使用新库初始化代替升级。历史用户领取日期初始为空，下次上线可领取；升级不会重置已有余额。回滚旧应用可保留该列，避免丢失当日领取记录。

`POST /api/user/online` 无需传用户 ID，只接受当前有效登录凭证，返回北京时间日期、余额及本次是否新发放。后台失败时页面稍后重试；数据库事务保证奖励日期和余额共同提交或回滚。独立数据库验证可设置 `ONLINE_REWARD_IT=true`，配合 `BUSINESS_JDBC_URL`（仅本机独立 `_it` 库）、`BUSINESS_DB_USER`、`BUSINESS_DB_PASSWORD` 执行 `mvn -B -ntp -Dtest=DailyOnlineRewardIntegrationTest test`。该测试不需要 Redis 或模型。

Agent 与业务容器共享网络命名空间，使鉴权桥接保持 `http://127.0.0.1:8101/api`，满足桥接仅允许 loopback HTTP 或外部 HTTPS 的规则。**更换或重建业务容器时，要同时重建 Agent：**

```sh
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml stop agent business
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d --no-deps --force-recreate business agent
```

对外仅开放 Nginx 80/443；业务 HTTP 和 Agent 健康检查映射到本机 18101/18202，数据库、MinIO、消息队列和 Trace 存储不映射公网端口。Nginx 拦截公网 `/api/internal/`。

## HTTPS 与续期

初始化先生成临时自签证书，正式访问使用 Let's Encrypt 的 IP `shortlived` 证书。证书与 ACME 账户保存在私有配置目录，Nginx 为 `/.well-known/acme-challenge/` 提供 webroot 验证。

```sh
docker run --rm --network host \
  -v /opt/tianmu-site/config/letsencrypt:/etc/letsencrypt \
  -v /opt/tianmu-site/config/acme-webroot:/var/www/acme \
  certbot/certbot:v5.4.0 certonly --non-interactive --agree-tos \
  --register-unsafely-without-email --preferred-profile shortlived \
  --webroot --webroot-path /var/www/acme --ip-address 120.53.45.251
sh deploy/site/renew-tls.sh /opt/tianmu-site/config
```

验证发布后，将 `/opt/tianmu-site/current` 指向该发布目录，安装 `tianmu-site-tls.service` 和 `tianmu-site-tls.timer`，启用 timer。它每天检查两次续期，更新证书并重载 Nginx。证书文件通过目录挂载，续期替换文件会被 Nginx 看到。

## 验收

使用真实网站密码登录，先通过网站上传专用测试视频并准备测试弹幕，再点击「分析弹幕」提交。验证实际模型生成、SSE 进度、HTML 图表、样本去重和刷新恢复。跨账号访问应使用隔离测试账号上传的视频验证。

按返回的 `runId` / `traceId` 查询 ClickHouse `tianmu_trace.trace_spans_current`，核对节点类型、父子关系、耗时、工具调用、模型 token 用量及私有明细引用。Trace 明细需通过带网站身份鉴权的接口读取，不应由 Nginx 直接公开对象桶。

### Trace 页面查询

`/trace` 支持本地日志导入和按完整 Trace ID 查询。在线入口为 `GET /api/agent/traces/{traceId}`，使用网站 Bearer 身份，并逐个核对链路涉及任务的归属和当前资源权限；节点输入输出复用原有私有正文接口。查询不会执行 Agent 或调用模型。

网站 Compose 已挂载 Trace 查询私有密码文件，默认连接容器网络内的 `http://clickhouse:8123`。发布前运行 `python3 deploy/site/prepare_trace_query.py --config /opt/tianmu-site/config`，创建独立 `trace_reader` 账号、密码文件及两张 Trace 表的 SELECT 授权；脚本复用密码文件，不打印凭据。不要把密码写入前端或仓库。其他部署需配置 `TRACE_QUERY_ENDPOINT`、`TRACE_QUERY_USER`、`TRACE_QUERY_PASSWORD_FILE`；endpoint 留空时接口明确返回 503，导入日志仍可使用。

实现遵循 [ClickHouse HTTP 接口](https://clickhouse.com/docs/interfaces/http)：固定参数化 SELECT、GET 读取、不跟随重定向；限制 4 个并发、2 秒连接超时、7 秒读超时、服务端 5 秒查询时间、2,000 条记录和 10 MiB 响应。超限报错，不将截断链路展示为完整结果。正在运行的任务可能尚未完整归档，可再次查询同一 ID 刷新。

上线验收须使用两个真实网站账号验证归属隔离，并检查已归档 Trace 的图、时间线和私有正文。本地自动化验证使用 HTTP 桩和演示模型，不能替代生产 ClickHouse 配置验收。仅更新 Agent 时可通过 `SITE_AGENT_RELEASE` 指定新镜像，同时保留 `SITE_RELEASE` 对应的业务服务镜像。

`inspect_run.py --run-id UUID --output 私有目录` 可在服务器导出该验收账号的报告、span 和经过服务端脱敏的明细，并逐份校验 SHA-256。失败任务诊断可加 `--allow-failed`。导出脚本复用该验收账号现有的 Redis 登录会话，不打印凭据。

数据库事件约束通过 `V3__artifact_ready_event.sql` 增加 `ARTIFACT_READY`；既有迁移保持不变。报告保存后的通知、终态和重建后的回放由 `MysqlStorageIntegrationTest` 覆盖。可用 Dockerfile 的 `storage-verify` target 在独立的 `_it` 库和无密码测试 Redis 上运行；凭据通过 BuildKit `storage-it-env` secret 临时注入，不拷入镜像。不要将集成测试指向网站或 Agent 的正式库。

本次针对报告保存与事件回放使用 `--build-arg STORAGE_TESTS=MysqlStorageIntegrationTest#liveEvidenceAndHtmlReportSurviveReloadAndAreDeletedWithSession`。完整存储测试还含创建触发器的故障注入用例，需要专用测试实例提供相应权限；网站部署账号不需要获得这些权限。


## 视频内容理解

Agent 仅新增 `analyze_video_content`。视频内容从业务桥接接口按当前登录用户和 videoId 解析，拒绝任意媒体 URL。业务镜像需带 FFmpeg，Agent 镜像带字体包用于给采样帧拼图添加播放时间标签。短视频读取全片，长视频概览均匀采样最多12帧；显式细查区间最多120秒。目前单个源文件限制64MiB、时长最多1小时。采样不保证捕获每个瞬时事件。

MiniMax 配置写入服务器私有 `agent.env`：

```dotenv
VIDEO_CONTENT_ENABLED=true
VIDEO_MODEL_PROVIDER=minimax
VIDEO_MODEL_BASE_URL=https://api.minimaxi.com
VIDEO_MODEL=minimax-vlm-service
```

中国区使用 `api.minimaxi.com`，国际区使用 `api.minimax.io`，必须与 Key 区域一致。Key 放私有 `secrets/video-model-api-key`，由 Docker secret 和入口脚本注入 `VIDEO_MODEL_API_KEY`，不提交进仓库。MiniMax VLM 接口不返回具体模型版本时，`minimax-vlm-service` 只是服务标识，不声称使用了某个具体模型。参考 MiniMax 官方 Coding Plan MCP 的 `/v1/coding_plan/vlm` 实现。

也可选 `VIDEO_MODEL_PROVIDER=compatible` 并配置支持图片输入的 Chat Completions 服务。可选 `VIDEO_TRANSCRIPTION_MODEL` 只适用于同时支持 `/audio/transcriptions` 的服务；未配置时明确返回 `SAMPLED_FRAMES`，不声称识别音频。MiniMax 当前部署仅启用画面理解；音轨提取不等于音频理解。

工具返回的结构化事实沿用既有 evidence → 私有 MinIO 产物持久化。摘要缓存保存在 Agent MySQL 的 `agent_video_content_cache`，按租户、用户、视频源版本、区间、关注点和模型配置隔离，24小时过期；缓存命中前仍重新校验视频权限和源版本。原始帧在本次请求中使用，临时文件随后清理，不持久化帧图片，也不写入 LLM 历史。后台预处理任务、长期帧资产和全量播放时间直方图不在此版范围内。

弹幕抽样 occurrences.playbackMs 为原始播放位置（毫秒，可空），bucket 仍是发送墙钟时间分箱，不能混用。报告可传入同一视频的 video_evidence_id 添加服务端生成的视频内容模块。Trace 包含 `video.source`、`video.extract`、`video.model.vision`；模型未报告 token 时标为未报告，整体用量标为部分统计，不估算成实际值。

### 上传会话升级（2026-11-04）

发布新版上传功能前，对业务库执行 `migrations/20261104_upload_sessions.sql`；新库初始化已包含该表。此迁移只新增表，可以保留表回滚应用，但旧版上传接口缺少用户隔离，不建议恢复对外使用。后端与前端应在同一次维护窗口切换；旧分片上传不能自动认领，新版需重新选择文件。既有已发布视频不变。

新版使用 `/api/file/uploads` 创建用户绑定会话，进度、签名与合并均使用服务端 uploadId。会话有效期一天，每用户最多三个未完成会话；最大3GiB、5MiB分片、签名窗口32、POST Policy签名15分钟且通过content-length-range绑定文件大小。存储桶须预先存在，并允许浏览器跨域POST/GET/HEAD；签名地址的主机必须可由浏览器访问。浏览器不向对象存储发送站点登录Token。

合并不持有数据库事务；租约与fence拒绝并发提交，最终对象按尝试编号隔离。数据库确认完成后才能投稿，过期清理保留已完成对象，失败清理下次重试。会话及文件记录作为归属凭证保留；容量运维仍需监控这些元数据的增长。内容MD5仅用于同用户同条件会话续接，不是内容真实性证明或跨用户秒传授权。

Loop 默认 `HARNESS_MODEL=deepseek-flash`（官方当前对应 V4.1 Flash）；后台分类默认也使用 `ANALYSIS_MODEL=deepseek-flash`，仍可独立配置。已有私有 env 中的显式 HARNESS_MODEL / ANALYSIS_MODEL 不会被源码默认值覆盖，部署时须核对后更新；本地修改不代表线上已切换。
网站部署将 TIANMU_MINIO_UPLOAD_URL 设置为 /upload-storage，Nginx 只代理业务桶根路径的 POST Policy 上传，避免向浏览器暴露内部 minio 主机名；最终媒体继续通过现有鉴权/发布检查的媒体接口读取。旧私有 business.env 也须增加该变量。

### Agent 模型与后台 Trace 发布

现有部署的私有 `agent.env` 会覆盖代码默认值。切换 Flash 时，应同时设置
`HARNESS_MODEL=deepseek-flash` 和 `ANALYSIS_MODEL=deepseek-flash`，并重新创建 Agent 容器；
只更新前端或只修改默认值不会切换正在运行的模型。部署前等待运行中及等待后台任务的 Run 清空，避免冻结模型版本的任务被中途切换。

`run.total_model_calls` 包括后台任务账本同步的累计调用数，`run.background_model_calls` 单独列出后台次数。
主循环预算仍独立执行，Trace 中 `budget.scope=FOREGROUND`；后台预算在任务账本及工作结果中保留。
重启后新建的 Trace 会话使用 `usage.scope=RUN_PROCESS_SEGMENT` 标识本进程上报的用量，不能冒充整个 Run 的历史总用量。
后台工作用 `analysis.job_id / work_id / phase / work_kind / batch_ordinal` 关联，模型调用为 `feedback.model` 子 Span。
