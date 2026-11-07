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
