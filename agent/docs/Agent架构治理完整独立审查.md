# Agent架构治理完整独立审查

日期：2026-11-07。审查者：新启动的独立Agent `full_governance_review`。本次重新阅读实现与契约，不继承前次通过结论。基准为HEAD `5cd3cc84a4fe4e02e97ee0870b3747241637d554`之上的当前未提交工作区；AgentRuntime SHA256为`BBC3C744E952E238187F75A7AAEDD7A147FD60E5E99BB6AE5FA2C59A5639554A`。这不是只审HEAD，也不表示工作区所有改动均属治理任务。

## 结论

本次未确认P0/P1；发现两项P2，尚未修复。当前默认配置及已列实施范围可继续开发，但不能将可配置性与持续集成治理无条件判为完整验收通过。生产部署、非默认Kafka长超时以及完整原治理路线均不在通过范围内。下面列出可定位问题与验收条件。

## 发现

### F1 [P2] Kafka可调交付时限超过固定ACK等待，产生提前失败和重复发送

位置：[KafkaTraceSink.java:25](C:/Users/79924/Desktop/DokiTV/agent/src/main/java/com/tianmu/agent/harness/trace/KafkaTraceSink.java:25)，关联[TraceDeliveryProperties.java:34](C:/Users/79924/Desktop/DokiTV/agent/src/main/java/com/tianmu/agent/harness/trace/TraceDeliveryProperties.java:34)、[TraceConfiguration.java:39](C:/Users/79924/Desktop/DokiTV/agent/src/main/java/com/tianmu/agent/harness/trace/TraceConfiguration.java:39)、[OutboxSpanExporter.java:85](C:/Users/79924/Desktop/DokiTV/agent/src/main/java/com/tianmu/agent/harness/trace/OutboxSpanExporter.java:85)。

触发：设置`harness.trace.delivery.kafka-request-millis=20000`、`kafka-delivery-millis=30000`，其余默认。record范围和关系校验允许该配置，工厂也正确传给Producer，但sink仍执行`producer.send(record).get(8,SECONDS)`。如果有效ACK在第9–20秒到达，sink已报失败，outbox保留记录并在下一次尝试重新send；前一次Future随后成功也不会acknowledge原spool文件。持续延迟时造成重复应用级发送和积压，不能达到配置所表达的等待行为。Kafka幂等Producer不能合并多次独立send；下游按event_id去重虽可减轻重复，不修复出口积压与失败统计。

依据是源码控制流推演，未启动Kafka或人为等待20秒实测。默认delivery=5秒未触发此问题，不声称默认路径回归或记录必然永久丢失。

建议：把宿主ACK等待归同一策略，覆盖Producer交付期限并明确max.block是否另计；或者限制可接受配置到宿主支持范围。保留超时未知结果的at-least-once语义与event_id去重说明。

验收：使用可控Producer/Future核对实际get期限，验证长于8秒但在配置窗口内的ACK被视为成功、spool删除、没有第二次send；超出窗口仍保留重试；默认配置保持兼容。补范围/关系绑定测试，不只检查Producer参数Map。

### F2 [P2] CI提供隔离服务但未开启记忆集成门禁，治理修改缺少持续持久化验证

位置：[ci.yml:77](C:/Users/79924/Desktop/DokiTV/.github/workflows/ci.yml:77)，关联[CompactionOnlyIntegrationTest.java:13](C:/Users/79924/Desktop/DokiTV/agent/src/test/java/com/tianmu/agent/memory/CompactionOnlyIntegrationTest.java:13)、[MemoryRuntimeIntegrationTest.java:25](C:/Users/79924/Desktop/DokiTV/agent/src/test/java/com/tianmu/agent/memory/MemoryRuntimeIntegrationTest.java:25)。

当前java任务配置MySQL/Redis、`AGENT_STORAGE_IT=true`及_it数据库连接，却没有`AGENT_MEMORY_IT=true`。CompactionIntegrationTest、CompactionOnlyIntegrationTest、MemoryRuntimeIntegrationTest、MysqlPersonalMemoryIntegrationTest和NativeMemoryIntegrationTest均要求后者，因此现有工作流即使服务健康仍跳过这些持久测试。此次恰好迁移MemoryLedger、后台租约/重试、压缩和个人记忆SQL时限，仅有默认本地全绿不能防止以后在这些消费点回归。

这不是要求取消本地门禁，也不把未执行认定为SQL已损坏。问题是持续集成已经有隔离条件但没有相应执行入口；工作流的定义不能被表述成这些场景已经被CI覆盖。

建议：在专用Agent任务开启记忆集成门禁，或新增独立明确的记忆集成job，保留_it库保护与隔离；外部付费模型门禁继续关闭。

验收：实际工作流日志证明上述五类执行且不因环境门禁跳过，记录失败/执行数；无Key、无费用调用；测试库清理不影响业务/生产库。不能仅以改一行环境变量作为已验收证据。

## 逐领域复核

| 领域 | 本次检查与结论 | 局限 |
|---|---|---|
| 完整设计、状态与回滚 | 主规范15.1–15.4区分WAITING恢复、RUNNING丢失窗口、前台共享/后台独立预算、成功commit未知结果与2B新旧reader/writer；目标与当前差异在16节明确 | 没有执行真实SQL故障注入；设计条目不是实现完成 |
| 外部研究 | 固定提交、官方SDK/教学复刻/行为观察/451未读仓库分类清楚；未把复刻当官方核心源码 | 此次复核本地研究材料和引用组织，没有重新下载全部第三方仓库或证明其生产可靠性 |
| 配置与装配 | 阅读六组record与生产工厂、Execution池、Context/ToolParallel绑定；Feedback/Storage/Trace手工new显式传策略；抽查Memory和Run SQL参数与lease/清理消费 | F1；六组字段引用检查不是非默认行为全覆盖，剩余硬编码明确未迁移 |
| 完成裁决 | 模型ANSWER、工具terminal、整批结果、恢复和收尾进入complete；并行以transform后已接纳exchange评估；串行整批settle；唯一taskType Owner及fallback；Delivery继续证据/阅读覆盖检查 | ReportOwner按回执选择并非通用多目标规划；源覆盖仍由Delivery实现，不将其误判为Owner已完整落实 |
| 授权/终态 | 变换后再检查工具集合/批次准入；Delivery沿用reservation、实时memory屏障和正式提交；不新增Role授权 | 未模拟所有撤权/commit竞争；现有成功意图非可靠重试限制保留 |
| 上下文与Trace | ContextLifecycle首次begin→load/inherit/compaction→HARNESS recall；恢复direct verify/普通restore后apply，均保留assembler验证；真实merge包住await和裁决，分支共用输入 | stage入口抛错时dispose位于lambda内，取消回调仍负责取消；建议后续外移finally增强结构化清理，未证明实际泄漏，不列缺陷 |
| 目录及CI | 检索构建/部署/脚本中的旧目录斜线引用未发现残留；旧内部包/桶/镜像身份保留，不与目录改名混淆；默认及rule-only冒烟均接CI | 没有部署；F2；不审核用户上传改动的业务正确性 |
| 文档体系 | 当前索引、历史替代标记、源码配置生成、主入口坏链接与负例、状态表/开发运维规范一致 | 仅验证本地链接存在，不验证全部anchor和远程有效性；维护节奏没有伪称创建自动任务 |

## 验证证据

本Agent实际运行：`check_agent_governance.py` PASS；`test_agent_governance.py` 1项PASS；JDK17定向Maven 20项、0失败/错误/跳过、BUILD SUCCESS（GovernanceArchitectureTest、GovernanceConfigurationBaselineTest、CompletionRoutingTest、NativeToolBatchTest、ParallelPreparationEntryTest），日志`var/full-independent-review-tests.log`，17:51:14结束。未修改运行代码，未调用付费模型，未使用生产库。

另外直接读取已有日志：`governance-final-verify-r3.log`为754项、0失败/错误、77跳过且完成打包；最终parallel/rule-only Demo均PASS、paidModelCalls=0。它们由主Agent此前执行，不冒充本Agent重新执行。未在本次重复全量、前端构建或真实数据库验收。两项发现来自额外的源码/配置组合检查，因此上述全绿不能关闭它们。

## 原路线未实施项

2B持久策略快照及兼容写入、Preparation页面/Focus等剩余职责分离、RunState写者收敛、操作kind注册、通用组合目标协议、Hook超时隔离及完整错误治理、剩余配置消费迁移，仍未完成。它们是后续工程，不因“存在路线图”计入完成，也不把未实现本身伪装成新增运行缺陷。77项门控测试、真实模型、线上有效配置及生产发布没有本轮独立通过证据。

本次完整审查已覆盖约定领域，但不等同数学穷尽或生产批准；应先修复F1并补F2持续验证，再对修订部分复审。

## F1/F2修复复核（后续授权发布阶段，静态结论）

本节覆盖前文发现的后续代码修订，不覆盖生产发布或真实模型验收。本Agent只读复核，没有构建、连接服务器或修改运行代码。

F1代码修正关闭：TraceDeliveryProperties新增kafkaAckMillis，默认8000毫秒，校验不得小于kafkaDeliveryMillis且上限600000；由于delivery自身大于0，亦不会接受非正ACK时限。TraceConfiguration把绑定值传入KafkaTraceSink四参数构造，实际Future.get使用该值和MILLISECONDS。旧二/三参数构造仍委托8000毫秒，默认行为兼容。新增TraceDeliveryTest直接核对实际Future.get(35000,MILLISECONDS)，配置测试覆盖request20s/delivery30s/ack35s合法及ACK不足拒绝。源码配置目录已包含新键。此修改消除了原固定8秒截断可配置窗口的问题；此处仅确认单次确认等待契约，并未声称启动Kafka实测或取消重复投递语义。

F2工作流定义修正关闭、实际执行证据待补：ci.yml现显式开启AGENT_MEMORY_IT=true，并配置AGENT_MEMORY_JDBC_URL/AGENT_MEMORY_DB_USER/AGENT_MEMORY_DB_PASSWORD，名称与上述五类测试读取完全一致，URL仍使用隔离tianmu_ci_it库。保持付费模型门禁关闭。本Agent未看到此次修改后的真实GitHub Actions日志，不能将静态接线正确写成CI已实际通过。

主Agent报告14项专项通过，并正在使用服务器新建随机专用_it库及临时Redis运行隔离集成测试；本节没有独立读取该运行结果，因此该项仍是进行中信息，不作为通过依据。完成后应保存各测试类执行/跳过数量和清理证据，再补录持久化验证与真实CI状态。

此次静态复核没有新增P0/P1发现，前文原路线未实施项仍有效；用户后续授权部署不使这些功能自动成为已完成能力。

## Trace前端fan-in布局修复复核

只读复核front/src/trace/trace-presentation.mjs的layoutPresentation及新增重叠fan-in测试；没有执行构建或修改前端代码。graphTime只在图谱排序、兄弟重叠分组和摆放阶段把merge的展示转换时点设为其结束时间，原始offsetMs/durationMs与详情数据未被改写；普通节点的时间规则保持不变。end不完整时回退原start，未制造一个虚构时长。相同终点的零耗时边界仍通过原compare稳定排序。

新增fixture将merge开始置于三个识别分支执行期间，验证三条branch→merge及merge→model连边、merge的横坐标确实在三分支之后，并保持原始时间11/80。这覆盖此次后端真实等待Span带来的前端回归，不是将后端耗时重新缩短迎合图形。

直接读取var/governance-release/graph-audit-fixed.log：五个真实Trace图谱检查均PASS，含CANCELLED、NEEDS_INPUT、通用/指标/报告SUCCEEDED；取消样本usageComplete=false仍保留，未用布局修复补造完整Token。日志由主Agent执行，不冒充本Agent重新测试，亦不代表已对更新部署后的浏览器产物完成验收。

未发现此补丁新增P0/P1/P2阻塞。补充边界建议：以后增加分支超时后忽略中断、其Span实际结束晚于merge的fixture；图谱应继续保留真实重叠/未完成状态，不能为了强制所有箭头都归并而篡改late branch的时间。当前图谱本就以时间顺序而非全部因果依赖为契约，不据该未新增用例认定已有实现错误。

## 2B首个稳定点静态审查

本节检查新增ExecutionPolicySnapshot、ExecutionPolicyFactory/PolicySnapshotProperties、RunPolicies及RunState、Snapshot/PreparedTask/Continuation、Mysql/Memory仓库、TaskAssembler、LoopControl/Harness/Preparation/DeepSeek消费接线。只读，没有构建或修改运行代码。

已确认：writer默认关闭；只有仓库new create捕获，恢复只读；Snapshot常规/成功澄清/失败取消及Mysql supersede终态均透传policy；Continuation引用hash且比对预算limit/reservation；TaskAssembler版本化路径通过bindPreparedTask一次保存任务/策略/deadline并在异常回滚内存三者，legacy保留旧行为；materialize fresh也恢复初始快照；Mysql acceptedAt截断微秒匹配DATETIME(6)。Loop、Preparation、Context按Run固定参数读取，CallControl各with方法保留frozenContext；DeepSeek消息组装与wire大小检查使用固定Context，独立后台调用未被强行并入前台预算。资源manifest及准备模型revision有显式校验，Trace区分legacy与v1。没有发现明确P0/P1。

直接读取policy-snapshot-contract-tests-r2.log：15项0失败/错误/跳过，BUILD SUCCESS，包含Spring装配；由主Agent执行，本Agent未重跑。该证据不能替代真实MySQL JSON/DATETIME/hash往返、完整WAITING恢复及旧版本rollback读取。writer启用前仍需reader先发布、真实_it库全量、schema在途检查和回滚读取证明。

P2加固建议：ExecutionPolicySnapshot.verify当前只检查部分嵌套标量，videoToolMillis、recentMessages/recentBytes、reportWindowMillis等没有与对应强类型策略同等范围检查。合法当前writer不会产出这些非法值，未据此判默认路径回归；但一个已重新签名而包含非法标量的兼容记录，会在后续消费中才出现普通IllegalArgumentException或非预期时限，而非入口统一POLICY_SNAPSHOT_INVALID。建议在verify统一验证完整标量及windowReason/阶段一致性，并补签名正确但非法数值的反例；哈希验证只能发现内容未同步更新，不替代语义验证。

原“2B未实现”的历史结论在此已进入首批实现审查阶段，不能继续用它概括当前源码；同样不能因首个可编译稳定点就改写为全量持久兼容验收完成。其余RunState写者收敛、通用operation kind、组合目标、Hook隔离仍是独立后续工作。

## 2B、上下文、完成与操作治理修订静态收口

本节只读审阅当前未提交修订，没有运行Maven、没有改运行代码。最终真实SQL/Redis完整回归正在由主Agent执行，本节不提前记录为通过。

已关闭的静态问题：

- 任务策略绑定丢ACK的P1：Mysql finishLocked对失败/取消采用持久行的task/policy，避免以本地回滚的initial策略覆盖已提交final策略；RunState.restore回投有效deadline；materialize另外核deadline_at、max_model_calls、prepared_task_json与快照投影。新增真实SQL用例先backend.save提交再抛错，覆盖原before-save失败用例没有涉及的窗口；实际执行结果待主Agent提供。
- ContextResolver搜索时限P2：改用Supplier，在页面元数据处理后、真正搜索开始时取得control；不再让页面IO提前消耗搜索单次时限。页面权限、唯一发现、Focus投影及lookup条件保持。
- 非法初始窗口P2：verify现要求initialDeadline处于acceptedAt+1ms到+10min之间，签名正确的超长及0.5ms快照均有拒绝反例；反馈effective窗口仍按独立reportWindow校验，没有错误限制到10min。
- 先前嵌套标量校验建议：已补调用/轮数/失败数、Token预留、各timeout、Context近期窗口、报告窗口与阶段关系范围；恢复还检查task的policyHash、deadline、maxCalls和resourceVersions一致，以及无policy但有hash的非法组合。

本轮其他新增的静态结论：

TaskExecutionPolicy.Input使用复制的证据Map/产物Set和PreparedTask，已不向策略暴露RunState写入口。CompletionRequirement在Owner返回Deliver后核证据归属、业务覆盖和阅读记录；Delivery保留变换后复验及实时权限屏障。空证据及回答边界仍保留INVALID_ANSWER语义。补齐宿主登记和真实资产的报告就绪夹具是合理基线修订，不等于仅凭READY字符串放行。

HookDispatcher的Gate/Observer为独立固定线程池、有界队列，等待超时后取消Future并移除排队项；实际忽略中断的worker退出前仍占池容量，不提前释放许可。Gate超时/满载失败关闭，Observer只记录失败，不成为授权准入。晚到Hook仅接收HookEvent，没有通过返回值提交正式Run终态的通道；必需生命周期动作仍不能被挪成可丢Observer。

OperationRegistry以实际OperationHandler唯一注册，OperationDispatcher按当前waiting分组派发；ToolDispatcher校验新pending kind，TransformInvariants禁止变换修改pending。既有反馈处理器保留自己的范围/权限/持久账本和worker隔离；孤儿reconcile单独调度仍在。未知kind明确失败，不假称可自动恢复未来不兼容语义。构造链中ToolDispatcher使用ObjectProvider延迟取Registry，未发现直接构造循环，仍须由真实Spring全量证据证明装配。

TraceAttributes白名单已允许policy.source/hash；HarnessSession结束重新记录最终hash；RuntimeHarness阶段上下文重绑定TraceModelCall identity，DeepSeek model.request取同阶段标识。准备阶段可记录initial hash、后续阶段记录final hash，原始阶段事实与最终根状态因此能区分；不能把最终root hash强制覆盖全部历史请求。仍需实际Trace导出验证字段没有在下游被过滤。

截至此静态稳定点，没有未关闭的已确认P0/P1/P2代码发现。此结论不替代当前SQL/Redis全量、writer开关部署顺序、真实模型与Trace验收；也不宣布原整条路线已经完成。RunState整体写者收敛、完整配置遗漏清理、长期版本回滚实证及其余明确路线项应继续按主规范逐项核对。

## Transform隔离、预算恢复与旧manifest兼容的末次修订

此次只读核对代码和新增测试，没有运行Maven；实际Spring/SQL/Hook定向与全量证据仍由主Agent执行后补录。

首次materialize预算问题的修正合理：RunState.restore仅在executionPolicy原先为空、首次接受有效policy时建立冻结limit/reservation，已有policy的restore不重建账本；WAITING的restoreContinuation随后以持久预算恢复charged/unknown等余额。不会以普通刷新抹去已有用量。主Agent报告预算修复真实SQL35项通过；本节没有独立重跑，保留证据来源区别。

TransformDispatcher新增独立固定worker池、有界队列、超时/容量失败关闭，返回结果后先checkpoint，再执行原TransformInvariants与大小约束才接纳。线程只收到不可变受支持载荷与TransformContext，当前上下文/Trace通过捕获Context传递；超时Future取消不会让忽略中断的实际worker释放容量，晚到返回值没有再次进入后续Hook或正式状态的入口。TransformIsolationTest检查晚到后following仍为0及worker没有被重复执行。HookPolicy增加兼容构造后现已给canonical构造标注ConstructorBinding；实际Spring绑定结果仍待新运行证据。

Prompt内容哈希刚加入时，本审查发现新的P1：全局versions增加prompt-content键，而旧任务仍被全量Map相等比较，上一已发布版本的WAITING即使资源未变也会必然失败。当前修订已关闭该静态缺口：

- TaskAssembler先保存上一版完整legacyVersions，再建立带manifest:format=2及完整prompt-content的新versions。
- compatibleResources对无格式/内容键的旧manifest严格比较legacyVersions；存在格式键或任一内容键即要求新Map完整相等，因此缺键、错误格式或混合指纹不会被宽松子集比较接受。
- writer关闭时无policy的legacy新组装继续保存legacyVersions；新v1任务使用format2。旧记录不补造历史内容hash，保持当时只有声明版本的保证边界；新格式仍拒绝相同声明版本但正文变化。
- 新增compat/agent-59a1746-resource-manifest.json夹具包含上一发布版本50个资源键及23个工具，仅保存兼容元数据。本Agent检查文件内容与其消费测试，未独立连接服务器证明抽取来源。单元用例覆盖旧manifest可读和新格式缺键拒绝；真实SQL用例持久旧manifest WAITING、重建Repository后复验并断言policy为空且没有回填内容hash。该SQL用例是否执行通过仍等待主Agent日志。

当前上述静态发现均已修正，未发现新的已确认P0/P1/P2；此结论限当前代码审阅，不预先覆盖正在运行的测试，也不扩大成原整条治理路线、生产发布或完整回滚矩阵均已完成。

## 最终发布与执行证据复核（5be4caf）

本节为当前所列续项的最终验收证据复核，覆盖前面仍标“等待执行结果”的项目。审查方式为直接阅读主Agent保存的日志、JSON结果与验收断言源码，没有亲自重新运行测试、连接服务器、部署或调用模型；只修改本审查报告。源码HEAD为5be4caffa629ea70d3b2a1256856356e23597f5e。

### 工程执行证据

- policy-ci-state-auth.json对应运行42371352871，整体completed/success，frontend、java(agent)、java(business)、publication四作业均success。policy-ci-agent-success.log的checkout为5be4caf，Agent790项、0失败/错误、4跳过、BUILD SUCCESS，实际执行786项。4项是PaidContextCostEvaluationTest与CompactionRealModelTest各2项，不是存储测试门禁被跳过。
- full-isolated-verify.log为跨平台资源兼容补丁前的Windows全量789项、0失败/错误、4跳过；不能将它说成最终补丁的Windows全量。最终源码policy-isolated-contracts.log为99项、0失败/错误/跳过，含MysqlStorageIntegrationTest36项真实SQL；portable-manifest-tests-r2.log为38项、0失败/错误/跳过。这两组定向结果补充最终Linux全量，不互相冒充覆盖范围。
- 最终打包legacy并行、snapshot并行、snapshot规则回退三条policy-smoke日志均PASS，报告Trace分别49/49/47个span，paidModelCalls=0。此前记录的真实SQL丢ACK、版本化WAITING预算、旧manifest恢复，以及隔离Hook/Transform等新增用例由最终全量/定向执行结果支持，不能再笼统称“尚未执行数据库测试”。

### 线上受控场景与Trace

直接核对policy-evidence/summary.json的七项：reader-legacy成功4 dispatch、snapshot-general成功4、recent30-report成功11、two-date-question为NEEDS_INPUT且3、two-date-resume成功6、snapshot-cancellation为CANCELLED且4、rollback-legacy成功4；加总36个实际dispatch。逐项input/output求和分别101537/8933，与新验收文档一致。取消用量不完整，不能将已报告Token当完整计费总量。

summary各项traceChecks为PASS且authorizationIsolation=true；验收脚本将后者写为bool(other)，另一账号请求断言因此没有在本次结果中被静默跳过。最近30天范围长度、COMMENT/DANMAKU来源、两日期续接边界及policyHash一致有明确断言；HTML私有访问、匿名401和另一账号403/404亦在相应断言范围内。

policy-graph-audit.json实际位于var/governance-release根目录，七图谱均PASS；取消样本usageComplete=false保留。图谱审计使用当前parser/layout，没有把真实耗时或未知usage补成正常值。正式终态、唯一DONE、取消answer为空及stopReason保持由验收和postcheck脚本检查。这里确认实际保存结果及断言范围，不把读取汇总等同对模型回答质量做独立人工全量评价。

### Reader/writer顺序和制品身份

policy-preflight.json记录发布前activeRuns=0、284条legacy保留记录。部署后postcheck记录release为20261107-5be4caf-policy-reader，revision为5be4caf完整SHA，jarSha256为1415435368506fbbe12c2e012a2539a4477296d3644d34d06d1cc8910b55d29c，Agent healthy、writerEnabled=true。

证据链覆盖writer关闭的兼容reader基线→开启writer的五项v1→关闭writer重启且保留读取六条旧Run→新legacy对照→重新开启writer重启并读取全部七条。policy-rollback-acceptance-r2.log记录保留六条PASS，最终postcheck列出七条原终态和legacy/v1来源。这是同一兼容reader上的writer开关回退，**不是退回不识别v1的旧二进制**；不应将其扩展成完整跨版本二进制回滚矩阵。

preflight与postcheck中frontend及business的容器ID逐字一致，revision分别仍为2aaf8b9和bb09d4f；Agent容器更新到5be4caf。policy-preserved-user-check.json六项均true，支持所抽查独立工作区文件未变的声明，不把六项抽查说成审计了所有无关文件。

### 最新结论与保留限制

**当前所列策略快照、上下文职责、完成校验、操作注册、Hook/Transform隔离及资源兼容续项，静态发现已关闭，最终Linux工程回归、所列真实SQL和七项线上受控Trace证据复核通过。当前没有未关闭的已确认P0/P1/P2。** 此结论只覆盖上述明确范围与5be4caf。

新文档《策略快照续项发布与验收》的主要数字和边界与已读证据一致：区分补丁前后、本地/CI、模拟/真实、已报告/完整计费用量、兼容reader关闭writer/旧二进制回滚，并保留FAQ首次命中诊断，不用该命中冒充三路真实模型对照。没有发现需要据此阻止本次所列续项验收的事实矛盾。

原路线仍未因此全部完成：RunState其余写者全面收敛、剩余模块运行旋钮清理、通用自然语言组合目标与长期跨版本回滚矩阵继续保留。旧Prompt没有历史正文指纹，不补证同版本正文；format2提供新保证。RUNNING交接崩溃仍RUNNER_LOST，未宣称自动重放副作用。四项付费专项评测仍未运行，七场景不能替代它们。

## 2026-11-07追加：思考与前端正文修复的独立审查

复用独立审查agent进行两轮只读检查，范围为thinking协议、reasoning私有载荷、记忆来源复验、原生工具回放、WAITING、SSE正文及汇合图。首次发现SSE在同turn重新start或游标失效重放时重复追加正文的P2；已改为turn事件高水位，并补充同turn重启与游标重置测试。复核确认该项关闭，未确认新增P1/P2。

审查确认新增SQL测试有效经过真实ledger消费与私有载荷接口，在authority缺失或来源删除后拒绝读取；native下一次HTTP与MySQL WAITING恢复保留reasoning均有直接断言。审查未代替执行测试。发布必须关闭入口并排空；仅在切换前查询两次active=0存在新增Run竞态，不能声称并发零窗口。最终工程及生产执行结果见[发布验收记录](策略快照续项发布与验收.md)新增记录。

追加超时与详情审查：120秒调用仍受job deadline、原调用/Token预算和fence约束，租约为调用时限加既有margin，未确认新增P1/P2。详情组件首次发现仅凭memoryProtected/runId隐藏任意业务JSON的P2；已收紧到服务端精确占位结构，带额外业务字段仍显示并可复制。实际Vue组件服务端渲染验证标准保护卡、额外业务字段、用户bd231九个上下文模块及协议折叠均通过；这不代替窄侧栏浏览器目视验收。用户已确认刷新后bd231图形正常。

追加输出预算审查：真实8192 Token全被思考耗尽的证据后，复核主/后台effort、后台预算消费、预留/结算、两种反馈policyHash及原生catalog v2身份；未发现新增确定P1/P2。完整usage先结算再抛MODEL_OUTPUT_LIMIT，该码不进入后台重试白名单。HTTP参数/length拒收、配置边界、cache协议失效、模型revision恢复拒绝及失败只执行一次均有本地行为验证；最终仍以低强度思考和16384预算的真实报告结果验收。

追加登录时序与历史详情终审：确认旧authorizationRef复验VIDEO来源会错误拒绝新登录者的P2，已改用同身份fresh caller，真实SQL55项通过。登录竞态修复首次审查发现token更新会abort终态报告但不再触发加载的P2，已改为仅重接监控、保留同用户报告GET，并用实际SFC函数的晚HTML响应测试关闭。最终只读审查未发现新确定遗漏；当前401仍清会话、旧401不清新会话、跨principal同步清私有数据且更换实例，没有自动重放提交POST。前端98项通过；实际GUI自动化被URL安全检查中止，代码级复现不冒充浏览器目视验收。
