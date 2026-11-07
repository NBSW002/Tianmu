# DSH 与 Claude 架构治理调研

日期：2026-11-07。状态：调研结论，已纳入统一治理设计并完成设计复审；本报告自身不表示已实施架构。研究阶段仅阅读公开源码与官方文档；后续代码和文档治理落地见主技术设计第16节，没有部署。

本文件保存外部研究证据；本次统一治理设计入口为 [Agent 架构与工程治理技术设计](Agent架构与工程治理技术设计.md)。不在本文件另行维护迁移方案。

## 研究边界与版本

- DeepSeek Harness：通过 GitHub API 核对当前 master 为 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`，与此前缓存一致。此次补读 agent、调度、会话、上下文、配置和压缩实现，不把之前工具并行调研代替本次架构调查。
- Claude：考察 Claude Code / Agent SDK 的公开契约，不能据此推断 claude.ai 网页产品的私有实现。Python SDK 当前 main 固定为 `2209f2c134fee914c0f75733b631a5fc238ca846`，读取 types、subprocess transport 和 control query；SDK 调用 CLI 并桥接控制协议，不是公开了整个 Agent 循环源码。
- 阅读源码不等于运行验收。未安装或执行第三方 Harness，没有运行外部付费模型。

## 1. 决策不是合成一个无所不管的总裁判

DSH 的 `ReactLoopAgent.preStep` 获取当前模型选择、解析可用工具、组装提示词，再经过 `agent/pre-step` 得到进入或拒绝结果。随后模型生成行动，工具执行器处理调用。所检查的默认循环没有硬编码“意图／情绪／澄清三次分类”。这不能证明插件不会做分类，也不意味着本站必须删除既有三路识别。

Claude 官方循环契约同样由模型产生文本或工具请求；工具调用还要经过宿主权限流程。权限流程有明确优先级，Hook 的允许不能绕过拒绝规则。语义规划与执行授权是不同决策，不应合并成一个可以互相覆盖的布尔值。

对本站的启示：建立决策职责表，而不是简单再加一个总 Router。前置识别提供结构化观察；业务条件策略确认默认值和必要条件；模型负责后续行动选择；工具宿主负责授权、参数和资源准入。每一类裁决有唯一负责人和理由，允许安全检查在多个边界重复执行。是否保留、合并或条件触发三个识别调用，需要另外评测并与用户对齐。

依据：[DSH 默认循环](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/agent-loop/src/agent.ts)、[Claude 循环](https://code.claude.com/docs/en/agent-sdk/agent-loop)、[Claude 权限](https://code.claude.com/docs/en/agent-sdk/permissions)。

## 2. 中间件先定义语义，再定义统一接口

DSH `AgentEventDispatch` 明确分成三种：

| 机制 | 实现契约 | 本站可对应的职责 |
|---|---|---|
| emit | 通知所有观察者，隔离同步异常和 Promise 拒绝，不允许观察者阻止生命周期 | 日志、指标、通知 |
| serial | 按序等待，允许按事件契约提前裁决 | 必须按先后执行的准入检查 |
| waterfall | 带 next 的包裹式组合 | 受约束的请求投影、模型调用装饰 |

Claude 同一事件匹配的 Hook 可以并行运行，不能把它当成有隐含数据依赖的顺序流水线。其事件分别定义可返回的控制字段，多 Hook 的工具权限冲突也有显式规则。

对本站的启示：先区分业务阶段、强制策略、纯变换和观察者。有数据依赖的“恢复历史 → 决策 → 召回 → 装配”应由应用编排明确调用；扩展点不接管主流程。需要规定输入、输出、写入权限、执行模式、超时、取消、失败默认行为、重复执行语义。统一契约不等于把所有逻辑塞进一个万能中间件总线。

依据：[DSH dispatch](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/agent/src/dispatch.ts)、[Claude Hooks](https://code.claude.com/docs/en/hooks)。

## 3. 配置由模块拥有，配置平台负责解析和校验

DSH 插件导出 Config 类型和 Schema，Schema 声明默认值与约束。开发文档要求部署间可调参数进入配置。agent-loop 的并行数属于 loop；bash 超时属于终端工具；压缩预算属于压缩模块。配置目录可以从声明生成，设置视图分开呈现默认／基础／用户层，并使用 revision 拒绝陈旧写入，秘密只返回已设置状态。

Claude 提供设置来源和优先级，也有 SDK 请求级选项。Python SDK 将 max_turns、max_budget_usd 等转成 CLI 参数；HookMatcher 的 timeout 是另一种配置。并非所有超时都由一个全局 timeout 控制，环境变量与设置项的优先级也需要按具体配置明确。

对本站的启示：按模块维护强类型配置和合法范围，汇总成可查目录；默认值只有一个声明来源。循环轮数、调用数、请求时限、单次工具时限、排队时限、Token 与字节预算分别命名。有效值必须能解释来源、覆盖规则和适用范围。先复用 Spring 配置绑定与校验，不引入 Cordis 或自造配置框架。

依据：[DSH 配置说明](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/docs/user/develop/basic/config.zh.md)、[DSH 设置契约](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/settings/settings/src/types.ts)、[Claude 设置](https://code.claude.com/docs/en/settings)、[Python SDK 参数映射](https://github.com/anthropics/claude-agent-sdk-python/blob/2209f2c134fee914c0f75733b631a5fc238ca846/src/claude_agent_sdk/_internal/transport/subprocess_cli.py)。

## 4. 固定的是明确的执行边界，不是一切永久冻结

DSH 在步骤准备时捕获模型选择；request middleware 不能在同一步偷偷换 provider/model。`agent.model.select()` 影响后续步骤。实际请求配置与工具声明记入 request/header；构建请求时冻结本次 envelope 和消息视图。并行执行数等另有 volatile 配置契约。

对本站的启示：修正“所有配置一律任务冻结”的笼统表述，逐项划分：

- 用户授权的任务目标、来源和已确认操作范围：保留稳定依据，变更要走显式续接／确认规则。
- 每次模型／工具调用的有效参数：调用开始后稳定，重试和恢复如何取值需要约定。
- 允许变化的运行配置：明确从下一任务或下一步骤生效，记录版本。
- 权限撤销、账号停用、资源下架：实时重新校验，不能被旧快照绕过。

这里是本站待定的设计建议，不能声称 DSH 具有本站同样的任务冻结模型。

依据：[DSH runtime types](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/agent/src/runtime-types.ts)、[request header](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/session/src/request-header.ts)。

## 5. 持久事实、上下文视图与 Trace 分开

DSH 用 Session 事件记录输入、调用、结果、模型选择和边界；请求配置可从 header 事件重建。上下文是投影，并非把整个状态对象直接交给模型。压缩记录开始、摘要、来源范围和结束事件，再用明确的替换消息更新模型视图。

Claude session 持久化对话及工具调用，resume/fork 复用会话；官方明确会话恢复不等于文件系统恢复。两者都不能直接证明本站后台业务操作可以安全重放。

对本站的启示：继续使用 MySQL、现有执行记录和来源账本，先明确各类数据的权威所有者；不要为模仿 DSH 立即改造成完整事件溯源系统。Trace 是事实的可观测投影，不负责制定业务决策或成为状态权威库。

依据：[DSH Session 类型](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/session/src/types.ts)、[Claude sessions](https://code.claude.com/docs/en/agent-sdk/sessions)。

## 6. 上下文组装有所有者，贡献方不自行拼整份请求

DSH 的 system-prompt 模块区分有序提示词段、变量、工具提供方和动态运行时上下文。工具 schema 由提供方管理；组装后再渲染与投影。作用域能限制贡献只影响某个 Agent。动态事实在该实现里可采用带来源的 user 消息，并不能据此认定所有产品都应该使用相同 wire role。

对本站的启示：保留已经确认的固定槽位协议；由一个装配入口掌握最终顺序、预算和信任边界，模型适配器只做 wire-format 投影。记忆、历史摘要、工具返回不能借装配升级为可信系统指令。Skill 和工具目录与实际开放集合应同源。

依据：[DSH system-prompt 实现说明](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/core/system-prompt/README.zh.md)。

## 7. 压缩配置需要覆盖完整预算关系

DSH compaction-basic 分别处理触发比例、输出与压缩余量、近期保留量、摘要模型和输出上限、压缩尝试次数与溢出恢复次数，并允许按 provider/model 覆盖。工具结果确定性裁剪是独立策略，不混成长期记忆写入。正常压力压缩与提供方确认的 context overflow 有不同恢复路径。

Claude 官方公开自动压缩和 compact_boundary 事件，SDK 公开部分控制项；无法从文档推定内部所有阈值。Claude Code 的 auto memory 也不能等同于本站多用户、可撤销来源的个人记忆体系。

对本站的启示：把“压缩窗口阈值”拆成触发、保留、目标、预留和重试；核对 Token 估算与当前字节预算，不能仅换变量名。先建立可靠的整请求预算，再决定是否采用按模型覆盖。已有召回、提取和压缩服务继续分职复用。

依据：[DSH 压缩配置解析](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/compaction/compaction-basic/src/config.ts)、[压缩触发实现](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/compaction/compaction-basic/src/index.ts)、[工具结果裁剪配置](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/packages/compaction/compaction-tool-result-pruner/src/config.ts)、[Claude 自动压缩](https://code.claude.com/docs/en/agent-sdk/agent-loop#automatic-compaction)、[Claude memory](https://code.claude.com/docs/en/memory)。

## 8. 文档目录与配置目录应该可核对

DSH 的 config-catalog 标记生成区块和源码出处，模块说明区分用法、实现和限制，历史架构笔记独立归档。这些是当前源码中可以确认的组织方式，不保证其所有文档都始终无误。

对本站的启示：保留一个当前架构入口、一份能力与验收状态表、一份模块配置目录；历史设计按时间归档。状态表必须区分设计、接通、验收和线上生效。未来 RAG 仍标为未形成完整链路，不用索引或证据类存在来代替功能验收。

依据：[DSH 配置目录](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/docs/config-catalog.zh.md)。

## 9. 补充研究：Claude Code 复刻与还原项目

根据用户补充，研究范围扩展到 GitHub 上实际实现 Claude Code 类 Harness 的项目。上文“Claude 研究限定官方契约”仅描述第一轮研究范围，不再代表全部研究范围。官方 SDK、独立复刻、源码还原和交互观测必须分别标注，不能混称官方完整源码。

### 仓库筛选与固定版本

- `shareAI-lab/learn-claude-code`：固定 `7226f555d48900076eba126acf37c9ed5f002b5d`。本轮直接读取 `agents/s02_tool_use.py`、`agents/s06_context_compact.py`、`agents/s08_background_tasks.py`。这是渐进教学复刻，适合检查机制的最小边界，不能作为生产可靠性证明。
- `Yuyz0112/claude-code-reverse`：固定 `c0d99ea1ab7168c12ba74838cfea355ce10f6c56`。仓库自述为 LLM 交互可视化工具；目录含日志及压缩提示词。此次仅完成仓库定位，未完成逐文件审计，不视为完整核心循环实现。
- `dnakov/claude-code`：直接访问及 GitHub API 均返回 451，本轮未读取源码，不为其内部实现下结论。
- DSH 与 Python SDK 分支头重新通过 GitHub API 核对，仍分别为本文开头的固定提交；DSH `agent.ts`、SDK `query.py` 与远程对应提交逐字比较（仅归一换行）一致。本地缓存不是独立 Git checkout，不能使用目录中的 `git rev-parse HEAD` 证明第三方版本；该命令会向上找到本站仓库。

### 源码观察与迁移判断

| 观察对象 | 源码实现 | 对本站的判断 |
|---|---|---|
| s02 工具扩展 | 循环按协议处理 tool_use，工具名到实现的映射在 TOOL_HANDLERS，结果关联原调用 ID | 通用循环依赖调用协议与执行器；业务工具和交付策略在业务模块注册。仅把 switch 搬到另一个类仍不够，还需约束依赖方向 |
| s06 结果裁剪 | micro_compact 处理较旧工具结果；独立 auto_compact 归档并生成摘要；模型可请求 compact | 值得借鉴“确定性裁剪／摘要压缩／触发入口”分职。该例仍在循环识别 compact，不能直接作为我们消除业务特例的最终接口 |
| s06 预算 | 阈值 50000、近期保留 3、摘要 2000 Token、模型输出 8000 Token、摘要输入最后 80000 字符；估算为字符串长度除以 4 | 教学复刻仍有大量硬编码，不能因为源码简短就认为配置治理完善。本站需要整请求预算、单位区分与跨参数校验，不能照搬数值和中文 Token 估算 |
| s06 状态 | micro_compact 原地修改消息；auto_compact 用摘要替换当前消息列表，另存 transcript | 本站须分离原始事实和模型视图，并验证来源与恢复；不能直接让压缩覆盖原始消息权威记录 |
| s08 后台执行 | BackgroundManager 启动 daemon 线程，内存保存任务和通知，循环在下一次模型调用前排空通知 | 学习“任务执行与结果注入分开”。该示例没有展示持久租约、崩溃恢复、持久通知确认和终态仲裁；排空队列后失败的恢复需要本站另行设计 |
| s08 成功语义 | 子进程正常返回就标 completed，没有按 returncode 区分业务成功 | completed 不能直接当作业务成功，本站需分别描述执行结束、执行结果和业务交付完成 |

源码来源：[工具调度](https://github.com/shareAI-lab/learn-claude-code/blob/7226f555d48900076eba126acf37c9ed5f002b5d/agents/s02_tool_use.py)、[压缩](https://github.com/shareAI-lab/learn-claude-code/blob/7226f555d48900076eba126acf37c9ed5f002b5d/agents/s06_context_compact.py)、[后台执行](https://github.com/shareAI-lab/learn-claude-code/blob/7226f555d48900076eba126acf37c9ed5f002b5d/agents/s08_background_tasks.py)。以上是源码阅读，没有运行验收。

### 生产契约的交叉核对

本轮补读 DSH 默认循环确认：每步解析工具集合后通过 finally 释放；错误路径为未完成工具调用补结果；模型请求失败的重试由 request-error 契约返回；取消原因与普通故障分开记录；流式尝试与已提交 assistant 消息分开收敛。因此本站的“解耦”必须保留资源释放、配对、取消和终态不变量，不能只展示顺利执行图。

Python SDK `query.py` 的控制协议用 request_id 配对请求与应答，控制请求取消后不再发送过期应答；但权限及 Hook 回调传入的 signal 仍为 None 并带 TODO。不能据此声称整个 SDK 已提供端到端取消。`subprocess_cli.py` 将 max_turns、max_budget_usd、resume 等映射到 CLI 参数，进一步证明 SDK 桥接层与核心循环需要分开研究。

Claude 官方权限契约还表明：canUseTool 并非每次调用的必经点，部分提前批准路径会跳过它。对应本站的强制租户与资源权限必须在不可旁路的执行边界复核，不能只依靠某个可选扩展回调。依据：[权限流程](https://code.claude.com/docs/en/agent-sdk/permissions)。

随后补读同提交 `s05_skill_loading.py` 和 `s07_task_system.py`：前者将 Skill 描述放入目录、正文按工具调用加载；后者将任务和依赖保存在 `.tasks` JSON 文件，独立于对话压缩。适合借鉴职责分离，但后者的跨文件更新不构成生产事务和租约恢复保证。研究仍可扩展更完整复刻实现的主循环、持久恢复和配置测试；当前五份教学源码不能代表所有 Claude 复刻。统一治理方案应逐条给出“来源事实 → 本站问题 → 采用或拒绝理由 → 验收方式”，不以项目声望代替判断。

## 下一步需要对齐的治理产物

1. 决策职责与冲突处理表：区分语义建议、业务条件和授权裁决，列出现有重复点。
2. 主流程及恢复流程图：明确业务阶段和扩展点，不把阶段全部中间件化。
3. 状态归属与读写矩阵：列出原始输入、派生 Query、准备结果、任务、Focus、执行状态和上下文视图。
4. 配置目录：所属模块、单位、默认值、覆盖规则、约束、生效边界、快照／实时行为、凭据分类。
5. 中间件契约：观察、裁决、变换、包裹分别规定顺序、并行性、失败、取消和恢复。
6. 文档迁移与架构验收：明确唯一现状入口；验证配置覆盖、非法配置、事件冲突、旁路授权、恢复幂等、原生调用配对和 Trace 原因。

不照搬的内容：Cordis 插件运行时、终端文件式权限、多用户记忆替换为 MEMORY.md、整套 JSONL 存储迁移、全部参数热更新、无业务必要的多 Agent。本站继续使用现有 Java/Spring 与持久化基础设施。
