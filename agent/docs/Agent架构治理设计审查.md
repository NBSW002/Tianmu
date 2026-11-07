# Agent 架构治理设计独立审查

审查日期：2026-11-07。对象：[设计草案 v1](Agent架构与工程治理技术设计.md)。基线提交：`5cd3cc84a4fe4e02e97ee0870b3747241637d554`；审查期间目录由 `tianmu-agent` 改为 `agent`，下文使用新路径。设计引用行号按本次约1140行版本。角色：独立审查 Agent，未修改被审设计、运行时代码、配置或部署。

## 结论

**不通过实施准入；可以继续作为设计讨论稿。** 方向基本合理，但尚不足以成为可执行的重构规格。发现4项 P1、3项 P2，无已证实 P0。主要问题不是缺少更多模式名，而是恢复交接点、预算和策略恢复、业务完成协议尚无闭合契约，配置盘点仍把可从本地源码确定的重要事实推迟到实施阶段。

“不通过”不表示应推翻固定槽位、现有权限复核、终态仲裁或 Java/Spring 选型。无需引入新框架解决以下问题。应先在同一主文档修订契约和基线，再复审；批准文档方向不能自动视为批准运行时代码改动。

严重级解释：P1 为实施前需要关闭的高影响设计缺口；P2 为对应批次之前必须明确、否则容易破坏可验证性的事项。已证实事实与目标设计不足分别说明，未把故障推演当作已发生事故。

## R1 — [P1] 重启恢复基线遗漏实际失败规则及恢复交接崩溃窗口

分类：源码基线遗漏 + 设计缺失。设计位置：69–91、172、181、326行。

源码依据：

- [MysqlRunRepository.java:442](../src/main/java/com/tianmu/agent/adapters/storage/MysqlRunRepository.java#L442)：`recoverInterrupted` 把持久 RUNNING 收敛为 FAILED/RUNNER_LOST（443–446），只接管 QUEUED、WAITING_OPERATION（449）。不是任意运行步骤可续跑。
- [RunState.java:155](../src/main/java/com/tianmu/agent/runtime/RunState.java#L155)：`completeOperations` 先替换结果、转 RUNNING、清空 operation/continuation，再保存（158–160）。
- [RunService.java:104](../src/main/java/com/tianmu/agent/runtime/RunService.java#L104)：完成上述保存之后才提交 `orchestrator.resume`；提交拒绝转 QUEUE_FULL。

触发：后台结果完成并已持久转 RUNNING，但尚未重新排队或运行时进程崩溃。按当前代码重启后会 RUNNER_LOST，无法从已清空 continuation 按等待路径继续。主文档只说“重启恢复与进程内 continuation 分别验收”，却没有列出已知终止规则，读者无法判断目标到底保留失败行为还是增加恢复能力。

影响：重构可能把“保留行为”误解释成自动重放，也可能宣称恢复成功却丢失这个窗口。外部副作用是否重试与账本事实是否完整无法据图判断。

修改要求：补当前持久状态机及启动恢复表；列 `WAITING → 已绑定结果待恢复 → RUNNING → 终态` 每次事务提交与排队的边界。明确选择：保留 RUNNER_LOST 为基线，或另批新增持久 resume-ready/dispatch 机制；不要求此刻就采用 outbox。若新增恢复，说明结果与预算如何一起保存、派发如何重试、重复派发如何 fencing。

验证标准：在等待提交后、结果绑定提交后、排队前后、恢复首调用前分别注入崩溃；重启后状态与选择的契约一致，副作用不重复，工具结果配对完整。基线图先能准确解释现有 RUNNER_LOST，无需运行模型才能补齐这个事实。

## R2 — [P1] 预算分账及执行窗口尚未定义，无法承诺保持有效行为

分类：设计缺失。设计位置：157、225–227、240、256行。

源码依据：

- [RunState.java:194](../src/main/java/com/tianmu/agent/runtime/RunState.java#L194)：循环、准备、外部调用各自计数；209行判断的是三者之和，不是循环单独16次。
- [RunState.java:196](../src/main/java/com/tianmu/agent/runtime/RunState.java#L196)：后台计数用于汇总，准入由独立持久分析预算控制，不加入209行调用准入。
- [FeedbackAnalysisService.java:106](../src/main/java/com/tianmu/agent/feedback/FeedbackAnalysisService.java#L106)：后台模型用 FeedbackBudget 和后台 deadline；[CompactionService.java:98](../src/main/java/com/tianmu/agent/memory/CompactionService.java#L98)则另用65536/8192 Token、25秒预算。
- [RunState.java:139](../src/main/java/com/tianmu/agent/runtime/RunState.java#L139)：executionWindow 先 checkpoint，再延长为 createdAt+timeout；[TaskAssembler.java](../src/main/java/com/tianmu/agent/application/TaskAssembler.java)是在准备完成、符合反馈任务条件后才应用。

触发：默认前置三次模型调用后进入循环；或耗时准备接近120秒后进入一小时报告任务。把“调用上限”仅绑定到 loop 会增加总调用；把报告一小时窗口提前到接收时又会改变原来前置总时限内必须完成的行为。压缩/自动记忆后台模型也不属于反馈分析那一项预算。

影响：成本、超时和准入行为可以在所有配置数字不变时发生变化。文档当前用“分别计量关系需说明”“准备/后台另核对”没有给出必须保持的计算式。

修改要求：在前置设计内给出计账矩阵：调用类型→调用数/Token账本→谁预留与结算→失败是否计费→持久化位置→总展示计数；至少覆盖准备、loop、工具内模型、反馈、提取、压缩。给有效调用剩余额公式和报告执行窗口切换时点。对超时定义 acceptedAt/dispatchAt/phaseStartedAt、排队是否计入、延长条件和上限。调整旧行为只能作为显式独立设计决定。

验证标准：三路识别后剩余额准确；未知usage保留预留；工具嵌套和后台计数不重复；恢复不归零；同一报告分别在前置窗口到期前后组装，结果符合明确规则。

## R3 — [P1] 有效配置快照缺少可恢复的数据契约与版本支持策略

分类：设计缺失。设计位置：168、173、181、216、227行。

源码依据：

- [PendingOperation.java:13](../src/main/java/com/tianmu/agent/core/PendingOperation.java#L13)：continuation 保存 Token budget 和执行记录，不包含整份 LoopPolicy/上下文/工具超时策略。
- [RunState.java:183](../src/main/java/com/tianmu/agent/runtime/RunState.java#L183)：是否重新配置 Token budget 由 controlBound/continuation 决定；不是所有运行控制都已有任务快照。
- [RuntimeHarness.java:63](../src/main/java/com/tianmu/agent/harness/RuntimeHarness.java#L63)、70：调用时读取当前 policy 的模型/收尾/工具超时。
- [AgentRuntime.java:137](../src/main/java/com/tianmu/agent/runtime/AgentRuntime.java#L137)：轮数读取当前 harness policy 并结合业务规则。模型目录的版本恢复不能自动解决这些普通配置的恢复。

触发：等待任务由旧配置创建，重启时轮数、单调用时限、上下文预算或完成策略代码已变化。文档说“保存版本与可恢复引用”，但没有说明保留哪些实际值、如何找到旧版本或找不到时怎样处理；181行还说按“当前投影协议”装配，与任务资源版本固定的关系不明。

影响：同一任务跨重启的行为可能改变；也可能把目录模型的 fail-closed 策略误套到所有配置，导致普通部署更新让全部等待任务失败。

修改要求：列出最小持久 ExecutionPolicySnapshot 字段与 schemaVersion、与PreparedTask/continuation的关系；逐项选择保存值/引用版本/实时读。分别定义策略内容哈希与实现兼容版本，明确旧策略至少支持多久、升级/降级读取边界、缺失如何处理。权限和来源撤销仍实时，不纳入放行快照。上下文投影明确按兼容协议版本恢复或明确失败。

验证标准：旧任务等待期间改配置并重启，新任务取新值、旧任务按既定契约继续；旧策略/模型缺失分别报正确原因；撤销权限即时阻止；兼容读取器的回滚演练通过。

## R4 — [P1] CompletionPolicy 协议不足以迁出现有业务决定，且缺事务边界

分类：设计缺失。设计位置：125、128、151–155、300–302行。

源码依据：

- [DeliveryService.java:51](../src/main/java/com/tianmu/agent/application/DeliveryService.java#L51)：正式 answer 入口可因 ResourceSelection 转澄清。
- [DeliveryService.java:53](../src/main/java/com/tianmu/agent/application/DeliveryService.java#L53)：报告、阅读报告、creator.draft 分别校验 TOOL_DELIVERY；67–72行再验证证据/业务完整性及阅读覆盖。
- [AgentRuntime.java:104](../src/main/java/com/tianmu/agent/runtime/AgentRuntime.java#L104)：直接报告交付有专用资格检查；140–146行上下文仍引用 ReportTaskStateView。
- [PendingOperation.java:11](../src/main/java/com/tianmu/agent/core/PendingOperation.java#L11)：当前通用等待类型仍硬编码三个反馈 kind，不只循环里有业务名。

触发：一次多工具结果同时包含已就绪报告和需继续读取的数据；资源搜索需要用户选择；已产生互动草稿而非报告；业务策略评估后到交付提交前被取消。只有 `Continue/AwaitOperation/Deliver` 尚不能解释这些情况如何选择、谁持有最终响应 reservation、何时验证事实和何时提交。

影响：实施者只能把现有 if/switch 搬到一个新的万能 CompletionPolicy，或在内核/DeliveryService继续留特例。文档承诺“新增能力不改内核”无法形成完整的依赖检查。

修改要求：为协议补处理范围（单结果/批结果/完整Run）、调用时机、输入只读事实、唯一选择规则、澄清/失败路径、reservation归属、纯评估与事务提交分离。把 PendingOperation kind 注册、ReportTaskStateView 和现有交付校验一起纳入迁移表。至少用“报告直接交付”“资源选择澄清”“互动草稿”“多结果继续”四个实例走通目标契约，而非先堆接口。

验证标准：同批结果顺序改变不影响业务终局；重复完成事件不重复交付；取消赢得终态后策略无法提交；新增一个非反馈后台能力无需修改通用kind枚举或内核分支；旧完整性与阅读覆盖校验保持。

## R5 — [P2] 配置附录是定位证据，不是满足要求的完整配置清单

分类：审计未完成。设计位置：237、244、249–252、附录A.3。

源码依据：

- [CompactionService.java:66](../src/main/java/com/tianmu/agent/memory/CompactionService.java#L66)、98–101：输入32768字节、25秒、65536/8192 Token、摘要3500字节等均可从本地确定。
- [MemoryBackground.java:68](../src/main/java/com/tianmu/agent/memory/MemoryBackground.java#L68)–74：心跳10秒、租约30秒、过期attempts>=5、退避10秒；111行提取输出1024 Token、15秒、8192/4096预算。
- [CompactionPlanner.java:27](../src/main/java/com/tianmu/agent/memory/CompactionPlanner.java#L27)：至少4条消息才触发；这既可能是结构不变量，也可能是策略阈值，必须分类而不能统称窗口。

触发：后续开发按配置清单搜索某个超时或摘要上限，只找到“候选/逐项迁移”及整段源码；SQL的INTERVAL、plusSeconds、LIMIT等还可能落在扫描规则之外。

影响：用户最明确要求之一被推迟到实施；无法评审抽取范围、默认数值保持和交叉校验。14类声明和134个命中数量不能替代消费点语义。

修改要求：把候选收敛成逐项条目，补当前值、单位、消费点、目标键、所有者、校验、生效/恢复和保留常量理由。先完成本地可确定项，线上有效值采集可另列待办。未知可保留，但须写缺哪项证据及阻塞哪一批，不能整体打包“lease/retry/cleanup/memory-window”。

验证标准：每个配置条目有消费位置；每个预算、时限、租约、窗口等消费点均有配置条目或已审查的常量理由。检查SQL和日期算术等扫描漏网形式；以映射覆盖验收，不以正则命中数验收。

## R6 — [P2] 状态矩阵只有目标角色，缺少当前持久权威与迁移映射

分类：基线交付缺失。设计位置：161–179行。

源码依据：[PreparationService.java:115](../src/main/java/com/tianmu/agent/preparation/PreparationService.java#L115)–120 直接更新Run内memory.focus；[RunState.java:147](../src/main/java/com/tianmu/agent/runtime/RunState.java#L147)把memory视图部分放入continuation；[MysqlRunRepository.java:218](../src/main/java/com/tianmu/agent/adapters/storage/MysqlRunRepository.java#L218)–225 从行/snapshot/continuation分别materialize。现有权威事实并非矩阵列出的每个目标服务都已经持久拥有。

触发：拆 Focus/PreparedRequest/执行账本时，开发者不知道当前哪张记录决定恢复值、写入何时提交，以及新增CAS是否改变当前会话并发行为。

影响：虽然文档明确“目标”，因此不是虚报实现，但用户要求的现状状态读写矩阵尚未交付。没有旧→新映射就难以保障逐批迁移和回滚。

修改要求：为每项增加当前写方法、持久字段/表或仅内存、读取/恢复入口、提交事务、目标所有者与迁移批次。区分Run内派生Focus与会话权威Focus；新增持久CAS如有必要，单列数据兼容和并发规则。

验证标准：从数据库记录到恢复对象每项可追踪；回放一条等待任务能说明每个字段来源；新旧写者不同时生效；回滚读取旧字段/新字段的策略明确。

## R7 — [P2] 中间件契约声明了时间隔离，但未定义超时后仍在运行的处理

分类：设计缺失。设计位置：185–194、256行。

源码依据：[HookDispatcher.java:43](../src/main/java/com/tianmu/agent/harness/hook/HookDispatcher.java#L43)当前同步调用，异常隔离不等于时限隔离；[ToolBatchExecutor.java:14](../src/main/java/com/tianmu/agent/runtime/ToolBatchExecutor.java#L14)–15 已明确“实际worker在IO停止前占用容量”，65–66行取消Future并不保证外部执行已停止。

触发：Observer或Around阻塞且忽略中断；调用方到期后释放许可并不断提交新任务；Around已经发出外部请求后才超时，重试执行器又发一次。仅写“共享取消”“各自有预算”无法处理晚到和僵尸任务。

影响：新时间隔离可能破坏现有有界并发，或造成超时后副作用继续提交。法定/业务审计与普通观察者的分类方向正确，但没有项目内现有订阅者的迁移分类。

修改要求：明确超时代表停止等待还是确认停止；实际工作结束前资源归属；晚到结果只允许收敛不得重新驱动终态；Around的next调用生命周期和重试身份；队列满时观察丢弃与强制策略拒绝的不同规则。列当前Hook/监听器到普通Observer、强制策略、事务审计的映射，避免异步化必需提交动作。

验证标准：使用忽略中断的测试插件验证实际在途数量不越界；超时/关闭后晚到事件无法提交；持续满队列有确定退化及计数；必须审计随事务成功存在、回滚不存在；普通观察者失败不改变业务结果。

## 修订与复审门槛

实施前关闭R1–R4；配置迁移前关闭R5；状态拆分前关闭R6；任何Hook异步化/超时隔离前关闭R7。分批计划应把这些前置产物对应到明确批次入口，而非放到最后真实模型验收。

第一轮修订建议先交付三件具体产物：①当前/目标状态转移与崩溃窗口表；②预算、有效策略及恢复版本的数据契约；③业务完成协议的四场景演算。随后补逐项配置与旧新状态映射，复审每个发现的验收标准。

文档治理部分的方向可保留，但实施前应把逐篇文档的目标去向落表。周期巡检仍是建议，没有创建自动任务；无需把未设自动化当作本次缺陷。外部研究正确区分教学复刻与官方契约，没有把451仓库冒充已读；本次不据此声称还原了Claude私有核心。

## 审查方法与局限

本轮独立读取设计主文档、调研文档、请求/恢复编排、Run状态和存储恢复、模型预算、业务交付、PendingOperation、并行执行、Hook、压缩/后台记忆相关源码并核对行号。未运行付费模型、数据库故障注入、生产探测，也没有把静态故障推演宣称为线上已复现。发现针对设计可实施性；并非把所有当前代码局限都扩展成本轮必须修复的功能需求。

## 第二轮复审：v2 首次复核

审查范围：新增15.1–15.8及对应原文修订；以下行号指v2新增配置扩展前的约1410行版本。R5参数扩展尚在进行，本小节不提前给最终R5结论。未修改主设计。

| 原发现 | v2评价 | 剩余事项 |
|---|---|---|
| R1 | 实质恢复边界已补齐，可保持现有RUNNER_LOST而不新增续跑能力 | 355行“同次save替换执行结果”需纠正：替换发生在内存，save没有持久化完整exchanges |
| R2 | 设计层面关闭 | 366–383行已给共享公式、独立后台账本、失败计费、Token持久局限和延长时点；仍需按计划执行测试 |
| R3 | 部分关闭 | 最小快照、计量分离、schema/语义支持和回滚读取已明确；403行legacy恢复仍需确定唯一默认、何时绑定和谁选择严格模式 |
| R4 | 部分关闭，保留P1 | 唯一Owner、完整批次、五类输出和组合目标规则已补；425行将成功提交失败归为现有pending-terminal重试，仍与源码不符 |
| R5 | 等待最终目录 | 不以新增表格数量认定关闭 |
| R6 | 当前映射基本关闭 | 原169行CAS目标与552行保留turn_seq upsert尚有冲突，应统一 |
| R7 | 超时与在途容量的设计已闭合；分类尚待收口 | 581行将当前completionListeners整体留待实现分类；当前两个实现可在设计时直接确认 |

### V2-1：成功/澄清提交失败与pending-terminal不能混称

位置：主文档425行。级别：P1，R4未关闭的具体原因。

[RunState.publish:269](../src/main/java/com/tianmu/agent/runtime/RunState.java#L269) 对SUCCEEDED/NEEDS_INPUT直接调用 backend.commit（293行），没有先建立PendingTerminal。只有 FAILED/CANCELLED 走stop→requestStop，318行创建失败/取消意图。若交付commit抛异常，[RequestOrchestrator](../src/main/java/com/tianmu/agent/runtime/RequestOrchestrator.java)会捕获并run.fail，后者才形成错误收敛意图。因此现状不是“原成功草案由pending-terminal自动重试”。

必须选定并写清：首轮保留现有行为时，交付事务异常后重新确认持久终态；如已成功则采纳，不重复交付；如未成功则按既有错误路径收敛；不能再请求一次模型或重复副作用。若改成可持久重试原交付意图，应另设计schema、reservation和不确定提交结果的协调规则，不能称为既有机制。

验证分别覆盖事务明确回滚、数据库已提交但返回异常、失败收敛也不可用、交付与取消竞争。每种都要求唯一正式终态；不可从“无成功响应”推断“数据库未成功”。

### V2-2：恢复结果替换的持久化描述仍过强

位置：主文档355行。级别：P2，R1事实修正。

[RunState.completeOperations:158](../src/main/java/com/tianmu/agent/runtime/RunState.java#L158)在内存替换exchanges，随后清空continuation；[MysqlRunRepository.Backend.save:235](../src/main/java/com/tianmu/agent/adapters/storage/MysqlRunRepository.java#L235)保存snapshot/task/continuation，而Snapshot不包含exchanges。因此该次save持久化的是RUNNING和清空continuation，并没有另存完整替换后的调用记录。后台资产/证据独立持久与Run恢复记录完整是不同事实。建议改为“内存替换结果后，持久转RUNNING并清空continuation；完整配对执行记录不由此save保留”。

### V2-3：legacy策略分支需有确定默认和绑定规则

位置：主文档403行。级别：P2，R3剩余契约。

“按legacy-v0现有行为”“可显式绑定兼容策略”“需要严格重现则拒绝”同时存在，尚不清楚谁可以选模式、是否首次恢复后仍随配置漂移、恢复重试是否绑定同一策略。建议默认保留既有legacy-current行为且标明来源；如绑定，则记录兼容模式、绑定时点、有效值与版本，并保证一次绑定重试不漂移；严格模式应有受控适用条件，不能由执行器自行决定。此项无需实现历史不存在的快照，也不要求伪造旧值。

### V2-4：当前监听器分类可以直接完成

位置：主文档581行。级别：P2，R7分类收口。

当前实现只有 [FeedbackJobCompletionListener](../src/main/java/com/tianmu/agent/application/FeedbackJobCompletionListener.java)和[QaCatalog](../src/main/java/com/tianmu/agent/faq/QaCatalog.java)。前者在Run终态时取消非READY后台反馈任务，属于业务生命周期清理，不能直接改成可丢弃普通Observer；后者写稳定Q&A观察，可按当前容错要求单列。应分别确定保持同步应用调用或持久补偿，不必等到实施才知道分类。原169行Focus CAS与新增552行不引入CAS的口径也需同步消歧，防止正文与整改节再次相互矛盾。

本次初复核结论：**仍不通过实施准入**，主要因V2-1仍为P1，R5尚未最终结案。已关闭项不因尚未执行运行测试而重新判失败；但不能把测试计划当成测试已通过。

### 第二轮补充：上述四项修订后的复核

主设计随后在同一v2稿修订，独立核对结果如下；保留上文首次复核作为审查轨迹，不再以其判断最新稿。

- R1 / V2-2：已明确completeOperations仅在内存替换执行结果，持久保存RUNNING并清空continuation；保留RUNNER_LOST窗口，现状与目标一致。**设计层面关闭。**
- R2：共享前台公式与后台独立账本、报告窗口切换保持明确，未发现新的高影响矛盾。**设计层面关闭。**
- R3 / V2-3：无快照旧Run唯一模式为LEGACY_CURRENT_POLICY，全生命周期不自动写成v1；新Run才进入2B新快照协议。兼容承诺覆盖仍在途旧记录，避免任意“两版”淘汰。**设计层面关闭。**
- R4 / V2-1：文档明确成功/澄清commit抛错没有成功PendingTerminal；未知提交结果先refresh，若已有终态则采用，否则走失败仲裁，不重新模型调用或副作用。可靠重试成功意图另批设计；唯一Owner、批次评估及组合目标规则仍保留。**设计层面关闭。**
- R6：原Focus矩阵改为turn_seq单调upsert，与新增当前权威映射一致；真正CAS不再被暗中纳入机械迁移。**设计层面关闭。**
- R7 / V2-4：分别将QaCatalog学习记录列为可丢Observer、FeedbackJobCompletionListener列为必需业务生命周期动作；后者保留显式调用，异步化前要求持久取消意图/对账。与忽略中断时不提前释放实际worker容量的契约共同构成可实现约束。**设计层面关闭。**

此时未发现残留P1。**整体结论暂不提前发布：等待R5最终配置目录完成后复核。** 设计层面关闭只说明契约足以进入相应实现与验证，不意味着数据库故障注入、兼容读取或真实模型场景已通过。

## 最终复审结论：v2通过设计评审

最终审查文件SHA256：`045DBCAE1142A24BA31EAA153B73AF9687FC917BD0EB261C696013C9C903DD70`。最终通过后仅同步页首版本/待用户对齐状态及15.8审查状态元数据，独立核对两处与文件哈希，设计契约未变，结论保持。本节是对该版本的最新有效结论；上文初审及中间复审保留为历史，不再表示最终稿仍有四项P1。

**结论：通过本轮设计评审，可提交用户对齐并准备分批实施。原R1–R7均在设计层面关闭，当前没有未关闭P0/P1设计阻塞项。** 这不是对代码实施完成、生产可用或部署的批准；按既定顺序，用户对齐设计后才开始相应代码批次。

### R5最终核对

新增15.5两张逐项目录已覆盖本轮确认的重要运行参数域：压缩/记忆的模型和输入预算、来源窗口、后台lease/心跳/失败与过期重试；反馈分析、准备、缓存及清理；视频索引、在途容量、模型和媒体IO边界；会话历史、运行记录/事件/审计保留；MySQL/Redis/业务凭据/向量适配器；Kafka、OTLP、spool退避及ClickHouse查询限制。每项给出当前值/单位、消费类或方法、目标键和校验/生效/恢复要求；协议安全边界与普通容量参数已区分。

最终抽查发现的三处本地明确消费点也已修正：

1. ToolBatchLedger的SQL超时3秒单列，避免误用Run存储5秒。
2. MysqlPersonalMemoryStore查询/事务均5秒，与MemoryLedger等查询3秒分开；默认1000项配额也有独立条目，未将降低配额解释为自动删除既有记录。
3. RuntimeModelCatalog目录实例的连接3秒和内部preparation输出构造值384单列，明确不改变全局前置模型路由，且在目录revision构建时固定。

同时核对CompactionService/Planner、MemoryBackground/Consolidation、RunState终态退避、模型目录、视频缓存与OutboxSpanExporter等消费点；未再发现足以阻止本轮设计的已知配置遗漏或数值混用。目录尚未成为运行时实现，目标键不能在此刻当作可用环境变量；实施时必须逐项验证绑定与消费，不能因设计评审通过省略这些测试。本结论基于明确范围的源码审查，不宣称数学意义上穷尽整个仓库每一个数字。

### 仍然有效的实施准入约束

- 批次1先建立恢复崩溃窗口、预算分账、组合完成协议及权限/配对的行为基线；测试尚未执行不影响设计关闭，但实施不得跳过。
- 批次2A仅配置归属迁移，保持原值与读取时点；2B快照冻结是显式语义变化，新旧读取先兼容，旧Run全程走LEGACY_CURRENT_POLICY。
- 成功/澄清提交异常必须区分未知提交与明确回滚；原成功意图不会由现有PendingTerminal重试，不能在实现中改回错误假设。
- RUNNING重启RUNNER_LOST及结果绑定交接窗口仍是保留的现有限制，不能用“可恢复架构”概括为任意步骤续跑。
- 真实数据库故障注入、旧记录升级/回滚可读性、真实模型场景和线上有效配置采集仍需按批次形成证据；外部上游不可用不能被记录成场景通过。

文档的状态分层、唯一设计入口和独立审查轨迹可以保留。后续若改变本节对应的关键契约、运行预算或持久格式，应对变更部分重新审查，而不是沿用此次通过结论覆盖新设计。

## 实施批次1与2A首片审查

范围：GovernanceArchitectureTest、GovernanceConfigurationBaselineTest、GovernanceBehaviorBaselineTest，MySQL结果绑定前后重启参数化用例、CompactionOnlyIntegrationTest环境门禁；ContextPolicy/Builder近期窗口、ExecutionProperties/Configuration调度线程、ToolParallelPolicy/ToolBatchExecutor强类型绑定及对应YAML、消费测试。此次只审查，不编辑运行时代码，不运行与主Agent争用target的构建；实际回归以主Agent完成的新运行类全量结果为准。

### 实现核对

- ContextPolicy保留二参数构造兼容，新增字段显式默认4条/8192字节；canonical构造标注ConstructorBinding，避免多构造绑定歧义。Builder继续使用min(recentMaxBytes,maxHistoryBytes)，按同runId整回合移除；默认行为不变，0只移除历史，不移除本轮请求。新增UTF-8序列化字节与奇数条数测试覆盖了实际消费，且校验原历史未被修改。
- scheduler容量仍在Spring创建bean时固定，默认deadline=1/maintenance=2；消费测试检查实际ScheduledThreadPoolExecutor，能防止仅绑定而未消费。两个调度器仍独立。
- ToolParallelPolicy默认false/2、范围1..4与原构造校验一致；@EnableConfigurationProperties配合唯一@Autowired生产构造，测试四参数构造委托同策略。未改实际worker容量持有、取消或有界排队实现，没有引入策略快照冻结。
- 配置测试覆盖默认/profile、环境别名、显式Spring属性及非法值；不把配置提取称为热更新。
- 新行为测试覆盖共享前台预算/独立后台展示、WAITING JSON恢复未知usage预留、报告窗口以acceptedAt计算、报告快捷交付资格和成功commit失败不自动重试。MySQL重启用例使用真实持久存储分支验证WAITING恢复与结果绑定后RUNNER_LOST；memory用例没有冒充SQL恢复。
- CompactionOnlyIntegrationTest增加与专用DB要求对应的环境门禁，属于显式标注可运行条件；默认跳过不能被汇报成该集成行为已通过。

### I1 — [P2] 架构依赖ratchet漏掉具体基础设施适配器

位置：[GovernanceArchitectureTest.java:28](../src/test/java/com/tianmu/agent/GovernanceArchitectureTest.java#L28)–30。

当前只将application、feedback、video、interaction、tool.builtin前缀加入待比较集合。新增runtime执行类直接依赖`adapters.business.BusinessAdapters`或`adapters.storage.MysqlRunRepository`不进入actual，测试仍可通过；这与批准的“内核依赖稳定端口，由Spring装配具体适配器”边界不一致。该测试能阻止列举包的直接依赖增长，但不是完整内核边界保护。

当前已有具体债务：[ToolBatchLedger.java:25](../src/main/java/com/tianmu/agent/runtime/ToolBatchLedger.java#L25)调用adapters.storage.FileObjectStore.hash。不能通过整个豁免adapters包掩盖它。

建议为adapters.*增加禁止增长检查，将现有ToolBatchLedger→FileObjectStore登记为精确旧债，随账本/哈希工具归属迁移移除；继续对实际集合与例外集合做相等断言。增加最小规则验证：任意runtime类引入具体BusinessAdapters/MysqlRunRepository的依赖被捕获，已有例外移除后名单也必须收缩。无需此批迁移原有运行代码来修测试边界。

初步结论：未发现本批运行配置改动的P0/P1缺陷；I1应修复后关闭本批架构约束验收。新全量回归和门控集成测试的实际执行状态仍待主Agent汇总，不能沿用旧运行类结果。

### 本批修订复核与测试证据

I1已关闭：最终规则将整个`adapters.*`目标族纳入依赖集合；仅登记`runtime.ToolBatchLedger → adapters.storage.FileObjectStore`现有精确债务，未豁免整个适配器包。复核源码后读取最终专项日志`var/governance-batch2a-architecture-final.log`：2 tests，0 failures/errors/skipped，BUILD SUCCESS。

另读取新运行类全量日志`var/governance-batch2a-verify.log`：749 tests，0 failures/errors，77 skipped，BUILD SUCCESS，并完成jar打包。这77项跳过仍是明确的未执行范围，不能将专用数据库/真实模型场景算作通过。两个日志均由主Agent执行，本审查Agent只读核对。

补充静态检查根目录`scripts/smoke_demo.py`的修正：默认启动显式deterministic-routing=false，断言实际三路准备计数3及对应MODEL/EXECUTED Trace；`--rule-only`显式启用回退并断言计数0、规则意图及识别跳过。它修复旧冒烟脚本与当前默认行为的冲突，没有修改运行时代码。Demo使用模拟模型，MODEL节点类型不等于外部付费模型调用。两条打包冒烟的实际结果在此刻尚待主Agent回传。

当前实现审查结论：**本批代码与测试修订静态复核通过，I1关闭，无剩余P0/P1/P2发现；全量回归及最终架构专项通过。** 打包Demo冒烟结果另行补证；不扩展为数据库故障注入、生产发布或真实模型验收通过。

### 本批最终收口

已只读核对`var/governance-batch2a-smoke-parallel.log`与`var/governance-batch2a-smoke-rule.log`，两条实际打包Demo冒烟均PASS，分别标识parallel/rule-only，均为paidModelCalls=0；覆盖鉴权、FAQ、普通执行、私有报告/SSE、Trace关联与输入脱敏。准备计数与MODEL/RULE细分由修订后的脚本强断言。主Agent另报告脚本相关19项Python单测通过；该项为执行者报告，不冒充本审查Agent独立执行。

**实施批次1与2A首片独立审查最终通过，I1已修复并经专项复测，无未关闭发现。** 通过范围限于已审配置提取、行为/架构基线和上述验证；77项门控跳过、专用数据库集成、真实模型场景、后续快照/恢复增强及部署仍不在本次通过范围内。

## 批次2A扩展配置迁移：进行中检查

该范围仍在持续修改，以下是中途检查清单，不覆盖已通过首片结论，也不把暂未迁移消费点判为最终缺陷。审查不编辑运行代码。

已提醒主Agent：新增构造上的@Autowired对配置类内手工new无效，FeedbackConfiguration中旧构造创建Analysis/Preparation服务、TraceConfiguration中旧构造创建OutboxSpanExporter必须显式传入已绑定Properties；否则生产会继续使用defaults()。Compaction、Storage、MemoryBackground SQL、FeedbackBudget及Coordinator、Video cache/flight/model、Trace查询等剩余域需在稳定点核对实际消费。预算关系需覆盖输出上限与总预算，不能仅验证reservation≤maxTokens。

最终审查将在实现稳定、工厂注入与SQL参数迁移完成后进行；当前不宣称这批扩展配置已通过。

### 扩展配置与内核SPI稳定点复核

配置接线修订已核对：FeedbackConfiguration与TraceConfiguration手工工厂传入绑定策略；Compaction/Memory增加输出Token不得超过总预算的校验。抽查MemoryBackground租约/重试SQL参数顺序，以及MysqlRunRepository清理/认领SQL，暂未发现新增参数错位。完整配置消费验收仍随迁移进行。

#### I2 — [P1] 完成SPI尚未成为统一业务裁决入口

本次稳定点位置：[AgentRuntime.java:109](../src/main/java/com/tianmu/agent/runtime/AgentRuntime.java#L109)、168、201、281–285；[BusinessCompletionEvaluator.java:12](../src/main/java/com/tianmu/agent/application/BusinessCompletionEvaluator.java#L12)–23。

CompletionEvaluator纯输入不直接持有RunState，当前evaluate实现也没有副作用，这是有效改进。但evaluate仅在runLoopBody入口用于原恢复快捷路径；模型ANSWER直接调用DeliveryService，工具terminalAnswer/terminalClarification直接终态提交，整批完成后不统一调用evaluate。Input没有模型回答提案；业务实现不读取task，仅扫描pending后检查最后一个create_feedback_report结果。新增SPI因此尚未落实已批准15.4的三个调用时机及唯一Owner规则。

触发与影响：存在多个已提交结果时，交换最后一项顺序会改变是否产生Deliver；组合任务没有在Owner层裁决未满足目标，工具终态提案及模型回答可以绕过该Owner。下游既有证据校验仍能阻止部分错误交付，所以本条不是已证明可越权，而是本轮必须完成的“唯一业务裁决”仍留有多条旁路。不能以AgentRuntime已无业务字符串就判解耦目标全部通过。

要求：将模型回答及工具终态表示为只读提案，在模型提案、整批结算、后台绑定三个时点共用评估/应用决定入口；只有整批完成后决定，源组pending仍保持原生配对与一次等待；Owner依据PreparedTask完整目标及已提交证据选择Continue/Await/Clarify/Deliver/Reject。如果仅先做搬迁，应标为过渡未完成，不将此稳定点视为协议治理完成。

验收：模型提早回答、工具terminalAnswer但目标未完成、交换同批结果顺序、多结果一pending、一报告READY但组合目标不足，以及终态reservation竞争；证明都经过同一Owner且仍复用交付事务/实时权限校验。角色元数据目前仍走registry admission和dispatch，没有发现仅因Role声明就新增权限旁路。

#### I2修订后的稳定点复审

已复核AgentRuntime.complete的当前调用：模型ANSWER、单工具终态提案、串行/并行完整批次、恢复入口与收尾回答统一经过CompletionEvaluator；串行batch使用deferWait避免部分结果先终态，完整结算后才收集至多一个终态提案。Input新增回答/澄清提案，运行器只应用决定；BusinessCompletionEvaluator按taskType注册唯一CompletionOwner，重复注册拒绝，不按顺序抢答。静态嵌套@Component Owner可以由Spring正常发现，显式测试组合工厂与生产@Autowired路径分开。

本次检查未见新增reservation旁路：Deliver/Clarify继续调用ResponseDelivery取得或复用reservation，最终由现有交付验证与持久终态仲裁；最终收尾沿用提前占有的reservation，不因新增Owner重占一次。共享源操作在全批结果settle后取唯一pending，调用/结果仍按原生标识记录。Role与suspensionGroup只提供元数据，实际调用继续受snapshot、Skill和业务授权准入限制，没有因声明角色就授予权限。

已只读核对`var/governance-completion-routing-tests.log`：CompletionRoutingTest与准备/原生批次/行为基线等合计35 tests、0 failures/errors/skipped，BUILD SUCCESS。新增四项分别覆盖模型提案可转澄清、工具澄清不能绕过Owner、串行批次不能部分抢答、重复Owner注册拒绝。

**I2统一裁决入口缺口在此稳定点关闭。** 此处通过不把简单ReportOwner当作完整多目标规划：当前ReportOwner按reportRequired筛选就绪回执并选最后匹配，任务source与阅读完整性仍由DeliveryService内业务验证负责；新的多目标类型及部分交付语义必须显式建模并验收，不能宣称当前已泛化支持所有组合目标。现有交付验证不是重复权限，应保留。

扩展配置接线再核对：生产FeedbackConfiguration、TraceConfiguration、PersonalMemoryConfiguration显式传入绑定策略；VideoAnalysisService生产构造注入Vision/Transcription/Flights，VideoModelClient/Flights/Cache生产构造消费新策略，旧defaults构造仅兼容直接构造调用。CompactionService/Planner和FeedbackBudget亦消费新值；检查到的新增SQL金额/期限/批量参数顺序没有错位。未运行构建或SQL集成，尚未将45项配置测试的报告摘要扩展为所有消费点及数据库运行验收。

后续准备/上下文所有权、持久策略快照、旧Run兼容和剩余配置域仍在实施，当前只对这个稳定点作范围内判断；它们不能因内核已无业务工具字符串而被标记完成。

## 文档体系与交付范围复审（最终回归前）

检查新增docs/README、配置目录、开发维护规范、运维验收手册及agent/AGENTS.md、旧页分类/替代说明、项目进度矩阵、治理检查脚本与CI接入。当前材料明确区分目标/实现/本地验证/线上，未把持久策略快照、Preparation/Context拆分、RunState收敛、kind注册、Hook隔离或完整错误治理写成已实施。就地逻辑归档保留旧版本证据与链接，较复制多套当前规范可维护。

配置目录明确只生成本轮六组、旧配置参考主设计与YAML，不冒充线上有效值。时间/大小单位及Spring去连字符的环境变量规则有说明；显式YAML别名另以YAML为准。治理检查只证明声明与引用，不证明实际非默认消费，这一限制已说明。维护节奏未被写成已创建自动任务。

parallel分支现从transform后已接纳的execution exchanges调用completeBatch，避免raw结果成为提案旁路；ContextAuthority为窄复验SPI，MemoryRecallService实现并保留实际verify行为。已只读核对`var/governance-final-fixes-tests.log`：29 tests、0 failures/errors/skipped、BUILD SUCCESS。完整新回归r2尚在执行，本节不提前判最终通过。

### I3 — [P2] 当前入口链接检查遗漏最重要的权威页面

`scripts/check_agent_governance.py`的链接检查仅白名单README.md、配置目录.md、开发与维护规范.md、运维与验收手册.md；没有检查唯一权威Agent架构与工程治理技术设计.md、项目进度.md及模块根agent/README.md。文档索引即使正确链接这些文件，也无法发现其内部指向失效源码或新旧目录的链接；脚本仍可能PASS。主设计16.5宣称检查当前入口链接，覆盖范围存在缺口。

要求：至少加入上述当前权威页面；为模块根README用正确目录解析相对路径，避免套docs目录。加入针对当前页坏链接的反例检查，不能只验证当前仓库恰好全绿。历史页可继续保留原路径且不因历史失效链接一律失败，但当前规范必须覆盖。

范围提醒：最终若交付当前批次，应明确只完成已列批次。主设计16.6把原路线剩余项列为后续，说明它们仍未实施；不能据此将用户最初全部治理目标标为已完成。本审查的范围通过结论也不能替代剩余任务的完成证据。

### I3与ContextLifecycle修订检查

I3的源码修正已复核：当前链接检查加入主设计、项目进度及模块根README，从各自文件所在目录解析相对链接。新增`scripts/test_agent_governance.py`在临时目录逐一注入三个入口坏链接，并验证目录漂移和未分类文档能失败，避免仅测试当前仓库全绿。**I3静态修正关闭**；脚本通过结果由主Agent报告，最终实际证据仍随收口日志记录。

ContextLifecycle承担应用顺序，未改变当前路径：RequestOrchestrator首先begin来源/压缩边界；Preparation开头load，先会话历史，再ledger继承（或recall兼容继承），最后compaction.apply；只有HARNESS分支再recall。恢复时direct分支保持verify，其他restore后apply压缩，均在TaskAssembler.verify前；生产注入同一Spring生命周期实例，兼容直接构造明确用默认实例。准备服务仍保留页面/Focus/语义条件职责，本次不能说它们已全部拆开。

默认并行路径的merge Span现包住实际await及条件合并，不再在计算后补Span。分支仍基于同一输入快照提交，结果顺序和截止时间读取保持原逻辑。非阻塞建议：将branch dispose最终放到包住整个stage调用的外层finally，可覆盖stage入口checkpoint先抛错的路径；当前失败/取消仍会经Run cancellation回调取消分支，未据此认定发生资源泄漏。

本检查未发现新增P0/P1；不提前将后续定向/最终回归算作已通过。

## 当前已列批次的最终交付复审

本节是对当前已实施范围的最新有效结论，不覆盖先前设计版本哈希，不将尚未实施的路线记为完成。范围包含配置提取、当前SPI/完成裁决、ContextAuthority与ContextLifecycle首步、真实merge Span、文档分类/索引/配置生成与检查机制。I1、I2、I3均已关闭；未发现新的阻塞问题。

已独立只读核对以下实际日志，未运行并行构建或修改运行代码：

| 证据 | 实际结果 | 限制 |
|---|---|---|
| `var/governance-final-verify-r3.log` | 754 tests、0 failures/errors、77 skipped，BUILD SUCCESS；2026-11-07 17:39:36（Asia/Shanghai）完成jar打包 | 跳过项不算通过；测试中的预期错误日志不是失败统计 |
| `var/governance-final-smoke-parallel.log` | PASS，parallel，paidModelCalls=0；FAQ/对话/私有报告/SSE/Trace关联与脱敏检查通过 | 模拟模型，不证明真实提供方质量 |
| `var/governance-final-smoke-rule.log` | PASS，rule-only，paidModelCalls=0；同类HTTP/SSE/报告检查通过 | 显式回退路径，不代替默认并行路径 |
| `var/governance-final-fixes-tests.log` | 29 tests、0 failures/errors/skipped，BUILD SUCCESS | 对应当时修订；最终源码以r3全量为准 |

另据主Agent执行报告：治理检查与新增负例通过，相关19项Python脚本测试通过，前端85项测试及构建通过。本审查没有独立执行这些命令，区分“执行者报告”与“本Agent直接读取的日志”。

Context定向测试的旧断言“分支结束≤merge开始”已改为“分支结束≤merge结束”，与merge包含真实fan-in等待的定义一致；原同快照、同parent及进入loop前汇合断言保留。它不是为掩盖迟到结果而删除顺序保证，最终r3也完整通过。

**结论：当前已列批次通过独立实现与文档交付复审，无未关闭阻塞发现。** 本结论不表示最初批准的整套治理路线已经实施完毕。2B持久策略快照及兼容写入、准备/上下文剩余职责分离、RunState写者收敛、操作kind注册、通用组合目标和Hook隔离/完整错误治理，仍按主设计记录为未完成；剩余配置消费点也不能被源码目录生成检查替代。门控数据库故障注入、真实模型验收、线上有效配置与生产发布仍没有本轮通过证据。

收口补充核对：主设计16.6现已记录与本节一致的r3、双冒烟和未部署边界；77项按30+2+5+7+10+10+9+2+2细分合计准确。随后又直接读取`governance-final-front-tests.log`（85 pass/0 fail/0 skipped）、`governance-final-front-build.log`（Build complete），以及publication/deployment/trace-script三日志（4/11/4 tests、均OK），据此将上述前端及19项脚本证据升级为本审查Agent已直接只读核对的执行日志。仍未独立重跑命令，结论范围不变。
