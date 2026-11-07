# Agent工程约束

- 当前治理入口是`docs/Agent架构与工程治理技术设计.md`，当前实现看第16节；文档导航看`docs/README.md`。目标设计不得描述成已实现功能。
- 修改通用循环前先检查`runtime.spi`与业务注册能力；AgentRuntime不得引入业务工具名或具体业务适配器。保持GovernanceArchitectureTest精确例外不增长。
- 新配置归模块所有，含默认、单位、范围、消费、生效/恢复语义；同步生成配置目录并运行`scripts/check_agent_governance.py`。
- 所有终态提案经完成策略及既有Delivery校验；不绕过实时权限、reservation、事务和原生工具配对。
- 不因Future取消提前释放仍在执行的worker容量；Trace/Context/缓存不作权威事实。WAITING恢复与RUNNING崩溃重放必须区分。
- 文档遵循`docs/开发与维护规范.md`，验证遵循`docs/运维与验收手册.md`；明确未执行门控测试及真实模型验证。
- 保留用户无关工作区修改。没有明确部署指令时保持本地变更，不将设计评审当发布批准。
