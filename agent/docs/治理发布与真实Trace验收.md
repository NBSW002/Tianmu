# 治理发布与真实Trace验收

日期：2026-11-07。用户明确授权部署、中文提交推送和真实测试。本页记录实际执行状态，设计范围及剩余迁移见[主规范](Agent架构与工程治理技术设计.md)。

## 范围与发布前检查

发布本轮Agent治理实现、配置抽取、目录改名和文档体系；工作区独立上传/对象存储改动不提交、不发布。保留网站业务后端现有容器；Agent替换为治理版本，前端从干净提交构建，补齐线上d02db57后已提交的Trace汇合信息展示，不带上传功能草稿。保留旧版本、旧静态产物和数据库备份以便回滚。后续策略快照等未实施工程不随此次发布变成已完成。

发布前核实线上Agent为d02db57，健康，无在途任务；真实DeepSeek模型列表/余额、普通及JSON请求均HTTP200，实际响应模型deepseek-flash。未记录Key、密码或登录Token。

## 独立审查整改

- F1：新增`harness.trace.delivery.kafka-ack-millis`，默认8000ms，校验覆盖Producer的delivery时限；配置传入KafkaTraceSink实际Future.get，旧构造保持默认兼容。长窗口绑定和实际等待参数用例通过。
- F2：CI启用AGENT_MEMORY_IT，补齐记忆测试专用JDBC/账号变量，保留_it数据库保护和付费模型门禁。首轮SSH隧道SQL延迟影响时限，随后改用本机现成专用MySQL实例的新建随机_it库，配合服务器临时Redis执行持久化回归，不复用生产库；实际远端CI结果需另行记录。

针对性14项测试通过，两项修复经原独立审查Agent只读复核。完整隔离回归、部署和真实Trace结果将在执行后追加，不提前标为通过。


## 最终部署状态

Agent代码版本`59a1746c75d76166b9e4bd2badaaf11275be0e8f`，前端Trace修复版本`2aaf8b91fecd6fd464a188cc83eba14dc37df837`。最终发布目录`/opt/tianmu-site/releases/20261107-2aaf8b9-trace`；Agent健康，业务后端保持原bb09d4f容器。数据库结构仍为V24，无本轮格式迁移。Agent jar的SHA256为`b1cd002179def3ded9c21e199660741dfbce1c6e13bbeb66d01096f451ff5501`，与线上文件逐字校验一致。

首轮治理发布创建Agent数据库压缩备份，保留d02db57旧镜像/发布目录和旧静态文件；后续Trace修复仅切换前端，Agent容器ID保持不变。前端从干净提交构建，不含工作区上传改动；新旧哈希静态资源并存，避免旧页面引用失效。已核对HTTPS入口、Trace路由、入口资源哈希及nginx -t。

## 测试与持续集成

- 完整隔离回归：759项，0失败/错误，4项付费模型专门评测门控跳过，完成打包。MySQL使用本机33317专用测试实例新建随机_it库，Redis使用服务器临时容器；库/账号/容器已清理，未使用生产数据执行破坏性测试。
- 五类记忆/压缩集成实际执行44项（5+7+12+10+10），MySQL存储31项、Redis2项均通过，不再只靠跳过得到全绿。
- 干净发布前端原79项通过；真实Trace布局修复后80项通过并重新构建。独立Trace展示专项20项通过，包含真实等待重叠及分支迟到不能伪画为已汇合的情况。
- GitHub治理代码工作流[42365060275](https://github.com/blackirlsama/EchoFrame/actions/runs/42365060275)四个任务全部成功。Trace修复的代码工作流见[42366044732](https://github.com/blackirlsama/EchoFrame/actions/runs/42366044732)，实际状态随下面记录。

首轮跨公网SSH隧道的SQL回归因延迟触发时限，保留原日志后改用本机专用MySQL；没有放宽业务时限。隔离回归曾揭示FAQ权限测试读取中间状态的竞态：取消信号在正式终态提交前发出；测试改为等待onTerminal事件并保留FORBIDDEN断言，运行语义未变，随后完整回归通过。

## 真实模型与Trace验收

通过HTTPS真实登录账号与真实DeepSeek Flash测试，未用Demo模型代替。下表为最终五个主场景；模型次数来自实际dispatched的model.request，Token来自实际usage，未估算取消请求的未知用量。

| 场景 | 正式终态 | Trace ID | Span | 准备/主循环/实际请求 |
|---|---|---|---:|---|
| 普通知识问答 | SUCCEEDED | `10bf02f65ee64a7f12b274203f182932` | 32 | 3 / 1 / 4 |
| 未选择视频的报告澄清 | NEEDS_INPUT | `333cd11e1124d49940d385a8427f8a36` | 25 | 3 / 0 / 3 |
| 作者真实视频的昨日弹幕指标 | SUCCEEDED | `ff2062586a462e27fce4cbd0de449b68` | 45 | 3 / 3 / 6 |
| 评论与弹幕组合报告 | SUCCEEDED | `4d17f43edab94b1f8d80cd6c52b5b699` | 76 | 3 / 3 / 11（含后台5次） |
| 模型生成中取消 | CANCELLED | `751b2df4d192cb375170833a7a092b0b` | 31 | 3 / 1 / 4 |

已核对：三分支是真实MODEL/EXECUTED且同父，结合源码及回归确认读取同一不可变输入；merge覆盖实际等待、结束后才装配任务；Span ID不重复、Trace ID不串任务、时长非负；正式快照、根Trace、finish及SSE终态一致，每个流只有一个DONE；真实provider dispatch与调用计数一致；原始测试问题标记未出现在导出Span，根payload保持memoryProtected；匿名和另一账号不能读取Trace及私有报告。

指标证据与只读业务SQL对账：2026-11-06该真实视频的弹幕数为0，两边一致，明确不是Demo的128。报告完成analyze_comments、analyze_danmaku、后台准备/分类/合并和create_feedback_report，私有HTML可被所有者访问且拒绝匿名/跨账号。取消请求的agent.loop、agent.model、model.request均记录CANCELLED；稍后再次读取仍CANCELLED、无答案、单一DONE。

五个主场景合计28次实际模型请求，已报告输入82008、输出9121 Token；取消的在途请求usage未报告，图谱usageComplete=false，以上仅已知用量，不宣称完整费用账单。预检、诊断及额外澄清请求不计入此五场景合计，实际本轮总调用多于28。

## 真实测试发现并修复的Trace展示问题

后端merge Span记录实际fan-in等待后，它与三个识别Span重叠；旧前端按开始时间排序，把“汇合”绘为第四条并行分支。此问题由线上真实Trace重放定位（原merge.x与三分支同为176）。修复只对图谱转移时点采用merge结束时间，保留原始offset、duration和详情，不伪造后端耗时。

五条真实Trace均用前端实际parse/build/layout函数重放：检查三分支分别连入merge、merge位于后续列、后续执行在汇合后、节点坐标有限、边端点存在；前端Token汇总与实际请求usage逐项相等。修复后再次发布前端并核对线上静态产物。此处是实际数据重放及HTTP产物验证，没有将其表述为独立浏览器逐像素验收。

## 发现的既有输入限制与验收边界

- 旧验收配置仍写样例视频1001，真实指标创建被403拒绝；之后按账号实际归属只读查询选择真实视频。没有扩大权限或临时修改视频归属。
- “最近30天”不属于现有确定性时间语法的受支持形式，会安全转入澄清；当前返回的来源提示不够精确。包含两个日期的澄清续接会在创建前返回400 INVALID_REQUEST。最终报告使用明确起止时间的新任务完成；这些交互限制没有因主链路通过而被隐去，也没有扩展本次治理为自然语言语法改造。
- 4项付费模型专门评测没有开启；线上五个真实场景不等同于所有质量/费用评测通过。2B策略快照等原路线未实施项仍然未完成。

## 证据位置

本地忽略目录`var/governance-release/`保存隔离回归、两次部署、干净前端构建、五条Trace、图谱重放和发布后复查日志。服务器详细证据在`/opt/tianmu-site/validation/20261107-59a1746-governance/governance-trace`，访问权限0600，不将账号、Key、Token或业务原文发布进Git。公开文档只列版本、Trace定位及脱敏结果。

Trace修复代码CI核验：`completed / success`，四个任务全部通过。
