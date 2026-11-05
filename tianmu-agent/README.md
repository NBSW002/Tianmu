# Tianmu 运营 Agent

面向视频作者的独立 Java 17 / Spring Boot 服务。它在作者权限与模型预算内理解视频、弹幕和评论，生成观众反馈报告，并提供实时汇总与作者私有 Q&A。

本文集中说明启动、数据处理、扩展位置和运行边界，常用信息无需跳转到其他文档。

## 本地启动

从仓库根目录执行：

```sh
mvn -B -f tianmu-agent/pom.xml verify
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

默认 `demo` 模式监听 `127.0.0.1:8201`，无需模型 Key 或中间件：

```sh
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'

curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

将 `RUN_ID` 替换为创建任务返回的 `runId`。Demo 使用脚本模型与模拟指标，适合验证权限、执行和交付，不用于证明真实报告质量。

| 运行模式 | 身份与数据 | 模型 | 存储 |
| --- | --- | --- | --- |
| `demo` | 模拟 | 脚本 | 内存 |
| `demo,deepseek` | 模拟 | 真实模型，产生费用 | 内存 |
| `demo,persistent` | 模拟 | 脚本 | MySQL、Redis、私有对象存储 |
| `business,deepseek,persistent` | 业务登录、作者权限与真实数据 | 真实模型，产生费用 | MySQL、Redis、既有 MinIO 私有桶 |

## 从请求到交付

```text
鉴权与视频归属校验
  → 内置 FAQ / 作者私有 Q&A
  → 确定性任务解析，必要时模型理解与澄清
  → 固定 PreparedTask：权限、视频、范围、资源版本、预算
  → Agent loop：模型决策 → 受控工具执行 → 结果回填
  → 证据与完整性校验 → 正式记录 / SSE / 私有报告
```

规则能明确解析的任务先走代码，不调用模型识别；其他请求才使用理解与意图识别。FAQ 命中直接回答。模型输出不能授予权限，工具及返回证据不能突破已固定的任务范围。

工具通过 `AgentTool.Exposure` 分为 GENERAL（通用）与 SKILL（业务，默认值）。`list_skills`、`read_skill`、`read_result` 默认可用；业务工具必须先通过 `read_skill` 激活声明它的 Skill，多个 Skill 共享同一工具时只披露一次。

工具注册表统一负责模型目录、Skill 返回的 Schema 和执行准入。任务只冻结注册表快照，不再按任务类型维护工具白名单；快照之外的新工具不能进入已运行任务。Skill 激活不授予额外数据权限，视频归属、已确认对象、时间与证据仍在执行时校验。模型调用、工具执行与关键处理步骤均有 Trace；失败、取消和正常结束共用终态控制。

## 观众反馈报告

作者选择视频、来源和时间范围后，`audience-report` Skill 使用以下工具：

| 工具 | 职责 |
| --- | --- |
| `analyze_video_content` | 选择 VIDEO 时取得同一视频的采样画面事实 |
| `analyze_feedback` | 创建后台任务，固定快照并完成分批分类 |
| `read_result` | 分页读取主题、批次细节与原文；普通文本分析须读完上层主题后回答 |
| `create_feedback_report` | 从完整分析资产生成并保存 HTML 报告 |

### 处理过程

1. 代码读取有界快照，保留原始成员、回复语境与准确统计，默认最多 5000 条原始反馈；超限明确失败。
2. 完全相同且来源、作者身份、回复语境与播放位置相同的表达可合并，保留全部原始 ID 与次数。每批最多 80 组，并受字节预算约束。
3. 模型读取当前批次和已有主题标题目录，沿用 T1–T100 稳定主题编号。每批最多返回 10 个涉及的主题，分类成员必须完整且唯一；漏分进入有界纠错，不自动塞入“其他”。
4. 代码按稳定编号累计成员与次数，保存各批细节，不调用模型进行多层归并。后续批次必须等待前批接受，不能在重试退避时越过它。
5. 后台 READY 后恢复主任务。报告从完整资产渲染，普通分析通过 `read_result` 读取全部上层主题后回答。进度、取消、授权与执行预算继续由宿主控制。

报告保留原文引用和分类细节。采样帧不能证明连续动作或声音；弹幕播放位置不是采样画面存在的证明。统计、权限、成员覆盖与 HTML 渲染由代码负责，主题含义及建议是模型判断。

分类上下文中的主题目录由已提交批次增量维护，只保留首次出现的顺序、首次标题和累计条数，最多 100 个主题。进程内最多缓存 32 个任务目录，闲置一小时过期；重启或目录失效后，以每次最多 64 条账本记录重建已接受的前缀，再继续增量读取。目录不接受未提交结果，也不随纠错输入持久化，旧纠错输入仍可读取。正常连续处理 B 批时，构建目录只读 B−1 个历史结果对象；最终归并和报告仍读取完整批次证据。此项不改变模型请求内容、调用次数或 Token 预算，无新增迁移，也不代表模型推理本身变快。

新反馈资产保存按批次顺序排列的话题数量索引。历史报告的 `observations` 页据此只查询当前页涉及的批次账本和结果对象；子话题页也只加载所选成员，仍按 16000 字节预算返回并以 `next_offset` 续读。旧资产没有索引时按流读取到所需页，不重跑模型、不改写历史；旧资产的深页仍需扫描前面的批次。搜索及历史报告分页偏移允许到 100000，与反馈容量配置的最高值一致，默认快照上限仍为 5000。字面搜索为计算准确匹配总数，仍扫描完整快照，不代表已实现全文索引。

此优化不需要数据库迁移，使用已有 `(job_id, level_no, ordinal_no)` 索引。新资产增加 `observationCounts` 字段；当前版本兼容旧资产，但直接回滚到不认识该字段的旧读取器无法读取新资产，发布前需安排兼容读取器的回滚版本。分页优化不改变分类模型、提示词、主题正文或报告生成流程。

## 模型、缓存与成本

| 配置 / 机制 | 默认行为 |
| --- | --- |
| `HARNESS_MODEL` | `deepseek-flash`，用于 Agent loop |
| `ANALYSIS_MODEL` | `deepseek-flash`，用于后台反馈分类与观众归纳，可独立于 loop 配置 |
| `PREPARATION_MODEL` | `deepseek-flash`，处理确实需要模型判断的问题准备 |
| `AGENT_MAX_MODEL_CALLS` | 每个任务最多 16 次模型调用 |
| `AGENT_MAX_TOKENS` | 默认 64,000 Token；设为 `0` 关闭 Token 上限 |
| `AGENT_TOKEN_RESERVATION` | 用量未回传时保留预估额度，默认 8192 |
| 分类缓存 | 按授权主体、请求范围、内容快照、模型规则与视频事实隔离；进程内最长一小时，重启失效 |
| 视频缓存 | 复用相同视频版本的分析，同请求在途合并，避免重复读取和推理 |

文本、视觉和转写调用共享任务预算，金额结算尚未实现。环境变量可以覆盖默认值；直接运行 Java 不会自动加载 `.env`。

每次请求仍重新授权并读取本次快照；只有上述条件完全一致才复用分类结果。新任务重新校验成员覆盖、生成本轮证据和统计，不继承其他任务的证据 ID。MySQL 保存任务和批次检查点，租约保证同一批次只有当前 worker 能提交。后台反馈分析使用 `agent.feedback.max-model-calls`、`max-tokens` 与 `timeout` 控制自身预算。

## 作者入口与 Q&A

视频页只向登录作者展示助手。独立 `/agent?videoId=…` 入口也会检查视频访问权限；前端显隐不替代业务桥接鉴权。

评论分析使用 `comment-analysis` Skill 与只读工具 `query_video_comments`，复用现有执行、视频理解和证据存储。分页固定同一证据和区间，未读完全部分页时不能交付完整分析。父评论追溯最多 64 轮，超限明确失败。

作者私有 Q&A 默认要求 5 个独立会话提供相同稳定回答，且答案占比至少 90%；达到阈值后自动加入，命中不消耗模型调用。内置 FAQ 优先，作者可查看积累、停用或重新学习。默认观察窗口 30 天，有效期 7 天；动态业务结果不应当作稳定问答复用。

### 人工维护的固定问答

生产默认读取 `src/main/resources/faq/business.json`，预置 15 条常见问答，覆盖使用入门、全量报告口径、视频采样限制、作者草稿确认、权限、对话承接及基础运营建议。这是人工预置内容，不表示已从线上日志统计出真实提问频率；不提供任何实时业务数值或个性化分析结论。

每项包含 `id`、`version`、`questions`（匹配问法数组）、`text`（批准回答）。新增或修改内容只需编辑 JSON；修改回答时更新版本。匹配只去除输入首尾空白，其余必须与某个配置问法完全一致，因此常见改写及中英文问号应明确列入数组。不做关键词模糊命中，未命中时继续原有处理流程。删除条目即可停用；重复 ID、重复问法、空配置或无效 JSON 会在启动时拒绝加载，避免悄悄选错回答。固定问答优先于自动积累问答，命中后不调用准备模型或 Harness 模型。

可用 `AGENT_FAQ_LOCATION` 或 `agent.faq.location` 指定外部文件，替换默认整份配置，例如 Linux 使用 `file:/opt/tianmu/faq/business.json`，Windows 使用 `file:C:/tianmu/config/business-faq.json`。文件须为 UTF-8 JSON 数组，大小不超过 64 KiB；缺失或错误时启动失败，不静默回退。Docker 中应将该文件只读挂载到容器并使用容器内路径，环境变量可写入现有 `agent.env`。配置在启动时加载，更新外部文件后需要重启 Agent；修改内置文件则需要重新打包部署。

房间汇总复用视频、播放器与弹幕表，由作者主动开启。前端每 15 秒读取，服务端每个用户、视频和区间最多每 30 秒刷新；失败时保留上一次成功摘要并标明时间。当前没有独立直播接入或持久化的实时摘要历史。

## 代码分层与扩展位置

Java 包位于 `src/main/java/com/tianmu/agent/`，资源位于 `src/main/resources/`。

| 位置 | 职责 / 扩展方式 |
| --- | --- |
| `api/` | HTTP、SSE 与鉴权接入 |
| `preparation/` | FAQ、任务解析、问题理解、澄清与条件校验 |
| `application/` | 任务组装、报告文档、分类缓存与统一交付 |
| `runtime/`、`runtime/control/` | 循环、状态、调度、超时、取消与终态竞争 |
| `tool/` | 实现 `AgentTool`，注册 Bean，并配置 `tools/<name>/tool.json` |
| `skill/`、`skills/` | 能力目录；每项使用 `skill.json` 和 `SKILL.md` 声明 |
| `prompt/`、`prompts/` | Prompt 读取与正文；在 `index.json` 注册 ID 和版本 |
| `access/`、`query/`、`model/` | 身份、业务读取与模型端口 |
| `adapters/`、`demo/` | 外部服务和本地模拟实现 |
| `harness/` | Hook、Transform 与 Trace |
| `core/`、`resource/` | 共享契约和有界资源读取 |

业务 SQL 放在根工程的 `src/main/java/com/orangecode/tianmu/agentbridge/`，模型协议放在适配器，不向主循环堆叠业务分支。工具不能直接发送 SSE 或决定任务终态。

新增能力时同时提供参数 Schema、Skill 声明、权限负例、口径说明与必要的回归验证。资源启动时校验并形成版本快照，修改后需要重新构建与启动。Hook 支持观察与执行前拦截，Transform 按类型串联；Java 插件是可信代码，不是隔离沙箱。

## 数据与运行约定

每轮上下文由原生 `system/user/assistant/tool` 消息组成。当前视频由页面提供 ID、服务端校验归属并补全标题，单独放入 `environment`；不再把视频信息拼进用户原话。当前目标存为会话级 `current_focus`，只保存用户请求与视频引用，不保存模型猜测或授权结论。

默认载入最近两轮完整问答，并受字符、字节预算约束。更早的对话通过通用 `read_history` 按需读取；不额外调用模型生成历史摘要。对话与目标持久化在 MySQL，Redis 缓存可重建的授权视图。V7 迁移新增会话目标表，跨作者、会话、视频权限边界仍由服务端检查。

通用工具为 `list_skills`、`read_skill`、`read_result`、`read_history`、`ask_clarification`；业务工具由 Skill 逐步开放，展示与执行共用注册表校验。Harness 使用 API 原生工具调用，保留模型实际返回的调用 ID；自然语言正文转为回答并由代码附加本轮实际读取的证据，澄清保留结构化状态，不执行正文中的 JSON 工具指令或 DSML。报告和互动草稿仍须由相应工具完成后交付。播放器位置是可选上下文字段，当前页面尚未自动上报。

`resource-discovery` 提供三个共享只读业务工具，分析和互动 Skill 按需复用：

| 工具 | 输入与边界 |
| --- | --- |
| `search_my_videos` | 本人视频的标题关键词、发布时间和分页；只返回元数据候选。唯一目标在冻结任务前解析，多候选需澄清，不能自行选择第一条。 |
| `search_feedback` | 已完成反馈或历史报告的 `result_id`、字面关键词和分页；在保留快照定位原话，不新增模型调用，匹配子集不代表整体分布。 |
| `read_report` | 已知历史 `run_id`、`artifact_id` 和章节分页；校验作者、当前视频归属与冻结条件，返回历史结构及数据时间，后续使用 `read_result`。不重新生成报告。 |

历史报告必须能唯一追溯结构化证据；不能可靠还原时明确失败。部署需运行 Flyway `V11__report_source_evidence.sql`，为新报告保存来源证据；旧报告仅在最终引用唯一时兼容读取。反馈原文仍受原有快照留存期限约束。查找链路不增加模型分析阶段，搜索采用字面匹配而非语义检索。

视频工具使用固定提示词提取通用画面事实，Harness 再结合用户问题解释。缓存按租户、作者、视频版本、播放区间、采样策略及模型配置隔离；自由文本 `focus` 不改变事实提取或缓存键。v4 命名空间不复用旧的定向分析结果，命中缓存仍重新校验视频访问权限；需要新细节时可缩小播放区间获取更密集的采样。

网关返回同轮多次调用时，最多接受四个独立读取，并在一次模型轮次内串行执行、完整回传各调用 ID。只有工具实现显式声明可批量读取时才放行；Skill 激活、分页工作流、报告归类和草稿生成默认不能批量执行，不增加新的业务工具或模型调用。

- MySQL 保存正式记录，终态及关联交付记录在同一事务中提交。
- Redis 保存可重建的授权历史视图；报告与证据使用私有对象存储，真实业务默认复用既有 MinIO。
- Trace 使用独立的本地文件 Outbox，异步投递 JSONL 或 Kafka，与业务存储 Outbox 分开。
- 持久化目前支持单个 Agent 写实例。重启后可查询已完成任务；运行中的任务按 `RUNNER_LOST` 结束，不承诺自动续跑。
- 普通任务默认最多 6 轮，报告可使用最多 32 轮；实际仍受模型次数、Token 和任务截止时间约束。
- 业务指标中的按日弹幕数限最近 30 个已结束自然日；报告默认全量，明确指定的普通区间最长 30 天。统一北京时间，结束时刻不计入。
- 互动排行基于弹幕用户字段和活跃情况，不证明关注关系或真实身份；播放位置无效时保留未知位置说明。
- 智能评论暂只支持作者独立留言，不支持指定评论回复、置顶或定时发布。

## 验证与部署

从仓库根目录执行：

```sh
mvn -B -f tianmu-agent/pom.xml verify
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
git diff --check
```

默认测试不需要模型 Key，也不调用付费模型。真实存储测试需隔离 MySQL / Redis，并显式设置 `AGENT_STORAGE_IT=true`。

模型成本核对使用每次请求的 `model.request` 子 Span，以 `run.id`、父 Span 和反馈工作的 `analysis.job_id/work_id/batch_ordinal` 串联准备、主循环和后台分类。只有 `model.dispatched=true` 表示已经进入网络调用；拒绝于预算或上下文检查的请求单独统计。失败请求也记录网络等待时间，供应商未返回 usage 时标记未知，不按免费处理。根 Span 的 usage 是当前进程段汇总（`RUN_PROCESS_SEGMENT`），不能再与请求级用量相加，也不能代替跨重启账单。

`context.*_bytes` 记录实际请求、原生工具 Schema、各角色消息及重复工具结果的 JSON 字节数，不是 Token，也不能直接相加还原请求大小。当前策略保留本轮完整工具交互，只对可省略的历史轮次裁剪；Skill 结果中重复的相同工具 Schema 去重，策略正文和业务证据仍保留。原生 `tools` 仍随每次无状态模型请求发送。超出最终请求预算时明确失败，不用截断证据换取成功。

`execution.queue_ms` 是后台工作入队到执行的时间，`analysis.retry_wait_ms` 是安排的重试间隔；`model.response_ms` 包括网络和供应商等待，不能解释为纯推理耗时。`io.feedback_object_*` 计量反馈对象读写，`io.feedback_database_*` 只计量已埋点的反馈账本操作，包含连接池、锁和事务等待，不覆盖全部业务 SQL。不同阶段可能重叠，不能将这些时长简单相加当作端到端耗时；缺失字段表示未测量。

离线费用汇总不访问模型或加载请求正文：

```sh
python scripts/summarize_model_trace.py /private/trace/archive/spans.jsonl --prices /private/prices.json --output /private/cost-summary.json
python scripts/test_model_trace_summary.py
```

价格文件显式保存日期、来源和每百万 Token 的费率范围，避免把历史运行套入隐含的实时价格。例如（数值仅展示结构，实际运行前核对供应商价格）：

```json
{"currency":"USD","source":"https://api-docs.deepseek.com/quick_start/pricing/","checked_at":"YYYY-MM-DD","models":{"deepseek-flash":{"minimum":{"cache_hit":0.003,"cache_miss":0.15,"output":0.6},"maximum":{"cache_hit":0.006,"cache_miss":0.3,"output":1.2}}}}
```

汇总按 Trace/Span ID 去重，只统计传入文件内的请求；进程退出时尚未投递的 Trace Outbox `.pending` 文件也可作为输入，不能只读 archive 就认定完整。需与任务及后台账本调用计数对账，不能仅凭 usage 齐全认定无丢失。输出费用是估算而非供应商账单，未定价调用单独列出。

付费验收 `PaidContextCostEvaluationTest` 默认跳过。显式设置 `AGENT_PAID_EVAL=true`、`PAID_EVAL_DIR`（仓库外目录）、`DEEPSEEK_API_KEY` 后，可单独以 `-Dtest=PaidContextCostEvaluationTest` 执行。该测试只发送 243 条合成评论，主循环与后台均使用 `deepseek-flash`，覆盖报告生成、历史读取及歧义澄清；最多三个任务各 8 次调用、128,000 Token，后台最多 8 次调用、120,000 Token。测试预算独立于应用默认 64,000 Token；后者可能在已用 Token 加下一请求保守预留超过上限时提前拒绝，即使已结算用量尚未达到上限。测试保存脱敏的合成请求和响应快照用于诊断，使用 Demo 权限和内存账本、本地私有对象目录，不验证真实 MySQL/MinIO 性能，也不发布任何内容。默认全量测试和免 Key Demo 不运行此验收。

隔离测试库须以 `utf8mb4 / utf8mb4_bin` 初始化，与 `deploy/site/compose.yaml` 中生产 MySQL 的配置一致；例如创建专用 `_it` 库后执行 `ALTER DATABASE tianmu_ci_it CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;`，再执行 Flyway。MySQL 8.4 的默认 `utf8mb4_0900_ai_ci` 会导致 V7 新表和 V1 会话表的外键列排序规则不兼容。CI 在迁移前显式设置临时库默认值，保留已经上线的 V7 文件及其校验和；不要对生产库运行测试初始化或盲目执行 Flyway repair。

真实业务启用 `business,deepseek,persistent`，配置 `BUSINESS_BASE_URL`、模型凭据、Agent 数据库和私有对象存储；业务桥接使用与网站登录一致的 JWT 和 Redis 会话配置。完整环境变量模板在根目录 `.env.example`。

单独部署 Agent 使用根目录 `deploy/manage.py`；同时部署网站与业务后端使用 `deploy/site/`。凭据放在源码之外。网站部署中 Agent 与业务容器共享网络命名空间，重建业务容器时要同时重建 Agent；上线前核对健康状态、作者权限、真实数据口径和回退版本。

HTTP / SSE、存储、Trace 和模型适配的详细契约分别保存在本模块 `docs/` 中；本文覆盖常用启动与开发信息，只有修改对应模块时才需要查阅专题文档。

## 智能评论与智能弹幕

`smart-comment`、`smart-danmaku` 两个 Skill 共用 `analyze_video_content` ；草稿分别使用 `prepare_comment_draft`、`prepare_danmaku_draft`。任务由代码分流为 `creator.draft`，固定视频、类型和用户指定片段；先复用视频分析缓存，再一次生成原生对象数组，避免嵌套 JSON 字符串。默认评论 3 条、弹幕 5 条，每批最多 10 条。工具保存草稿并直接交付预览，不再调用模型总结，也不发送内容。

作者可在对话卡片中编辑、勾选条目，点击一次“确认发送”即发布。发送仅经过 `POST /api/agent/runs/{runId}/creator-draft/confirm`，不暴露为 Harness 工具；服务端重验登录身份、任务所有者和当前视频归属。确认后冻结具体内容与时间点；网络超时保留待确认状态，只允许使用相同内容重试。

草稿由 Agent 的 V6 迁移保存至专用表，随会话删除和过期任务清理；内存模式仅供开发。业务侧确认接口复用现有 `CommentService`、`BulletIngestionService`，以作者本人身份发送，以稳定记录 ID 防重。整个批次在同一事务内完成，失败回滚。普通报告和评论分析保持只读。

升级需同时构建业务后端、Agent 与前端；业务后端启用 `agent.bridge.enabled`，Agent 持久化环境执行 V6 迁移。独立只读桥接部署不提供发送能力。发送不调用 LLM；测试使用模拟模型，不向生产视频发布测试内容。

执行中确需用户补充条件时，调用 `ask_clarification(question)`。宿主记录工具调用，并通过与准备阶段相同的交付逻辑进入 `NEEDS_INPUT`、结束本轮；用户补充后重新准备并校验权限。该工具不能与其他工具合批，也不改变已冻结的任务范围。无工具收尾阶段仍使用宿主内部澄清决策。

各批 observation / recommendation 只分析本批内容，代码不可覆盖地保留历史观点；主题标题固定为首次接受的名称。完整的不同批次观察在长度允许时直接去重展示，超长时明确提示展开，不裁切观点句子，也不采用最后一批代替整体结论。报告按批次读取保存的全部观点，原话例子仍只用于说明，不代表全部原文或不同意见的人数。
