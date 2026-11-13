<div align="center">

# EchoFrame

**视频社区与创作者个人助手**

观看与发布视频，理解观众反馈，查看助手的每一步执行。

[![Build and verify](https://github.com/blackirlsama/EchoFrame/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/blackirlsama/EchoFrame/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](pom.xml)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883.svg)](front/package.json)

[界面预览](#界面预览) · [系统概览](#系统概览) · [快速开始](#快速开始) · [文档导航](#文档导航)

</div>

## 界面预览

**视频社区** · 发现视频、分类浏览、播放与互动。

![EchoFrame 视频社区首页](images/echoframe-home-current.png)

**视频与个人助手** · 在观看页面讨论内容，创作者可以整理弹幕和评论、生成反馈报告及互动草稿。

![视频页中的 EchoFrame 个人助手和反馈报告](images/echoframe-assistant-current.png)

**Trace Studio** · 执行图、时间线与节点详情联动，查看模型和工具调用、上下文、Token 用量及响应耗时。

![EchoFrame 运行追踪：执行图、时间线和节点详情](images/echoframe-trace-current.png)

截图来自实际部署。项目持续开发中；本地 Demo 使用模拟数据，真实模型与完整网站需要额外配置。

## 可以做什么

| 能力 | 用法 |
| --- | --- |
| 视频社区 | 上传、播放、搜索与分类浏览，发送弹幕、评论和回复 |
| 视频助手 | 讨论当前视频或指定片段，生成评论与弹幕草稿，由用户确认后发送 |
| 观众反馈报告 | 创作者分析自己的评论与弹幕，结合采样画面生成带原话引用的报告，支持预览和 HTML 下载 |
| 个人记忆 | 查看、修改、忘记或暂停记忆，让后续对话使用相关信息 |
| 运行追踪 | 按 Trace ID 查询有权限的运行，也可以离线导入 JSON / JSONL |

可以向助手提问：

```text
分析这个视频的弹幕和评论，结合画面生成观众反馈报告。
概括这个视频最后一个片段的画面内容。
生成几条引导观众讨论的作者评论，先给我草稿。
```

## 系统概览

### 业务后端：服务与依赖

业务后端是一个 Spring Boot 服务，负责用户、视频、评论、上传和搜索；Netty 处理弹幕 WebSocket。业务记录写入 MySQL，其他组件各有明确职责：Redis 保存会话与热点数据，MinIO 保存媒体，Elasticsearch 提供检索，RocketMQ 承接弹幕消息，Canal 订阅数据库变更。

```mermaid
flowchart TB
    Gateway["Nginx：HTTP / WebSocket 入口"]
    Gateway --> Business["Spring Boot 业务接口<br/>用户 · 视频 · 评论 · 上传 · 搜索"]
    Gateway --> Netty["Netty 弹幕服务<br/>连接、鉴权与房间广播"]
    Business --> Mysql[("MySQL<br/>权威业务记录")]
    Business --> Redis[("Redis<br/>会话与热点数据")]
    Business --> Minio[("MinIO<br/>视频与封面")]
    Business --> Es[("Elasticsearch + IK<br/>用户与视频检索")]
    Netty --> Rocket["RocketMQ<br/>弹幕消息"]
    Rocket --> Consumer["弹幕消费者<br/>幂等入库"]
    Consumer --> Mysql
    Mysql -.-> Canal["Canal<br/>订阅 Binlog"]
    Canal --> Redis
    Canal --> Es
```

弹幕在这套组件中按下面的顺序流转。发送成功后，Netty 将弹幕加入房间广播；消费者负责持久化，数据库提交后 Canal 再更新播放缓存。广播与持久化属于两个阶段，不能把房间广播当作已经入库。

```mermaid
flowchart TB
    Send["播放器发送弹幕"] --> WS["Netty 校验登录、视频与内容"]
    WS --> MQ["RocketMQ 接收事件"]
    MQ --> Broadcast["Netty 向房间广播"]
    MQ --> Consumer["消费者按弹幕 ID 幂等处理"]
    Consumer --> DB[("MySQL 弹幕记录")]
    DB -.-> Canal["Canal 读取提交后的变更"]
    Canal --> Cache[("Redis 播放弹幕缓存")]
    Cache --> Read["后续播放读取"]
```

更完整的后端模块与部署说明见[架构说明](docs/ARCHITECTURE.md)和[网站部署](deploy/site/README.md)。独立 Agent 的请求和报告流程见下方三节。

### 一次请求如何执行

```mermaid
flowchart TB
    A["用户消息与当前页面"] --> B["身份与视频范围校验"]
    B --> C{"FAQ 或记忆总览<br/>可直接回答？"}
    C -->|是| D["宿主直接交付"]
    C -->|否| E["同一请求快照"]
    E --> F["意图识别"]
    E --> G["情绪识别"]
    E --> H["澄清识别"]
    F --> I["识别结果汇合"]
    G --> I
    H --> I
    I --> J["服务端召回记忆<br/>组装任务与上下文"]
    J --> K{"条件齐全？"}
    K -->|否| L["澄清并等待补充"]
    K -->|是| M["模型决策 → 授权工具 → 结果回填"]
    M -->|需要下一轮| M
    M --> N["完成裁决与交付"]
    D --> O["SSE 正文与进度"]
    L --> O
    N --> O
    B -.-> T["Trace：节点、父子关系和时间线"]
    I -.-> T
    M -.-> T
    N -.-> T
```

FAQ 命中或纯记忆总览可由宿主直接回答；其他请求的三路识别共享同一输入，汇合后才固定任务范围。模型与工具循环受轮次、时限、权限和预算控制。Trace 记录真实执行，节点并非额外模型步骤。

### 观众反馈如何变成报告

```mermaid
flowchart TB
    R["创作者请求反馈报告"] --> P["冻结授权范围内的反馈快照"]
    R --> V["视频采样分析<br/>画面概览、时间点观察与可选转写"]
    P --> C["清洗与去重<br/>保留原文、来源和回复关系"]
    C --> S{"主评论与弹幕材料<br/>可直接放入预算？"}
    S -->|是| D["直接阅读材料"]
    S -->|否| B["主评论与弹幕向量聚类<br/>最多五个候选桶"]
    B --> X["粗排：每桶最多二十个候选"]
    X --> F["精排：每桶最多五个素材"]
    D --> A["按真实 ID 附加子评论<br/>每个主评论最多五条不同回复"]
    F --> A
    A --> T["确认主题与证据引用"]
    V -->|简短概览| T
    T --> W["独立撰写报告"]
    V -->|完整采样分析结果| W
    W --> Q["核对主题、引用和计数"]
    Q --> H["网页预览与 HTML 下载<br/>每主题最多展示三个素材"]
```

主评论和弹幕参与向量粗排、精排，选中后再附加真实子评论。候选桶用于筛选，确认模型可以跨桶整理主题。小材料按预算直接阅读，候选不足时按实际数量处理。

一段主评论讨论占一个素材位置，主评论和实际采用的子评论仍按原始 ID 分别计数。视频理解依据采样画面和可选转写，主题结论依据代表反馈，不等于逐条分析全部互动。

详细选材与回复关联规则见[反馈报告工程说明](agent/docs/Agent架构与工程治理技术设计.md)。

### 记忆如何写入与召回

写入和读取是两条独立链路。对话提交后，只有开启自动整理且内容合格，才会入后台整理队列；用户明确说“请记住”也可进入保存流程。后台提取候选事实后，再核对来源、权限和重复项，写入带版本的 MySQL 记忆。索引工作器扫描已提交版本生成向量；向量是可重建的检索索引。

```mermaid
flowchart TB
    Message["已提交的对话"] --> Eligible{"开启自动整理<br/>且值得长期保留？"}
    Explicit["明确请求保存"] --> Job["持久化后台整理任务"]
    Eligible -->|是| Job
    Eligible -->|否| End["本轮不自动写入"]
    Job --> Extract["模型提取候选事实"]
    Extract --> Validate["来源、授权与重复检查"]
    Validate --> Revision[("MySQL<br/>记忆版本与来源")]
    Revision --> Index["独立索引工作器"]
    Index --> Vector[("MySQL<br/>模型版本对应向量")]
```

下一轮请求由 Agent 宿主先读取授权快照。问“你了解我吗”一类个人总览时，宿主直接分页列出可读原文；普通问题则合并核心事实、关键词与语义候选，按相关性和上下文预算选择，再交给模型回答。两条路径都重新检查来源和当前权限。

```mermaid
flowchart TB
    Ask["下一轮请求"] --> Guard{"记忆模式与授权快照"}
    Guard -->|暂停| None["不读取记忆"]
    Guard -->|个人总览| Overview["读取可用原文<br/>服务端分页清单"]
    Overview --> Direct["宿主直接回答"]
    Guard -->|相关问题| Candidates["核心事实 + 关键词候选"]
    Vector[("当前版本的可用向量")] --> Semantic["语义候选"]
    Guard -->|相关问题| Semantic
    Candidates --> Fuse["融合排序、去重与预算限制"]
    Semantic --> Fuse
    Fuse --> Recheck["再次核对来源与权限"]
    Recheck --> Context["选中的事实进入模型上下文"]
    Manage["管理页：修改 / 忘记 / 暂停"] --> Revision["更新版本或撤销授权"]
    Revision -.->|旧版本失效| Vector
    Revision -.-> Guard
```

“只使用”模式允许读取已有记忆，但不自动新增。管理页修改、忘记或暂停后，旧版本与对应向量不能继续作为可读事实。模型不持有搜索、保存或删除记忆的 Tool。

更细的边界、失败与恢复规则见[架构说明](docs/ARCHITECTURE.md)、[报告实现](agent/docs/代表材料三工具实施与验收.md)和[记忆方案](agent/docs/记忆召回重构技术方案.md)。

## 快速开始

### 选择运行方式

| 我想体验 | 必要条件 | 入口 |
| --- | --- | --- |
| Agent 执行流程 | JDK 17、Maven 3.6.3+ | [无密钥 Demo](#体验-agent-demo) |
| Trace 日志可视化 | Node.js 22、npm | [Trace Studio](#体验-trace-studio) |
| 完整视频社区 | Java、Node.js、业务库与中间件 | [完整网站接入](docs/QUICKSTART.md#方式三运行完整网站) |
| 真实创作者 Agent | 业务登录与数据、模型凭据、Agent 持久化服务 | [运行模式与依赖](docs/QUICKSTART.md#选择运行方式) |

```bash
git clone --depth 1 https://github.com/blackirlsama/EchoFrame.git
cd EchoFrame
```

模块目录、构建产物与配置键仍沿用 `tianmu`。仓库包含[视频素材](materials/README.md)，首次克隆会一并下载。

### 体验 Agent Demo

无需模型 Key、数据库或中间件。在仓库根目录执行：

```bash
mvn -B -f agent/pom.xml package
java -jar agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

另开 Bash 终端创建任务：

```bash
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'
```

将返回的 `runId` 替换到 `RUN_ID`，查询任务结果；若仍在运行，稍后再次查询：

```bash
curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

Demo 使用模拟身份、脚本模型和内存存储，用于体验流程，不代表真实模型分析效果。[PowerShell 示例与完整启动说明 →](docs/QUICKSTART.md#方式一体验-agent-demo)

### 体验 Trace Studio

从仓库根目录执行：

```bash
cd front
npm ci
npm run serve
```

打开开发服务器的 `/trace` 页面，通常为 `http://localhost:8080/trace`。内置演示日志，也支持导入 JSON / JSONL 文件，在浏览器本地解析，无需后端。

本地可直接使用演示日志或导入文件；接入网站并登录后，还可按 Trace ID 在线查询本人有权查看的运行。[前端开发说明 →](front/README.md)

### 接入真实业务与模型

完整网站需要 MySQL、Redis、MinIO、Elasticsearch（IK）、RocketMQ 与 Canal；真实 Agent 还需要模型凭据，持久化模式使用 Agent 专用 MySQL、Redis 和私有对象存储。画面理解另需 FFmpeg / ffprobe 与视觉模型，转写单独配置。

按[运行与接入指南](docs/QUICKSTART.md#方式三运行完整网站)准备依赖、配置登录与模型，再启动业务后端、Agent 和前端。模型调用可能产生费用。现有[网站容器部署](deploy/site/README.md)依赖既有共享网络与服务，不是空环境的一键启动脚本。

## 开发与测试

在仓库根目录验证业务后端与 Agent：

```bash
mvn -B verify
mvn -B -f agent/pom.xml verify
```

验证前端：

```bash
cd front
npm ci
npm run test:agent
npm run build
```

[GitHub Actions](https://github.com/blackirlsama/EchoFrame/actions/workflows/ci.yml) 覆盖前端、业务后端、Agent、发布规范与隔离数据库集成测试，不调用付费模型。配置与验证要求见[贡献指南](CONTRIBUTING.md)。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [运行与接入](docs/QUICKSTART.md) | 运行方式、Bash / PowerShell 示例、真实模型与完整网站接入 |
| [架构说明](docs/ARCHITECTURE.md) | 业务链路、任务准备、Harness 与反馈处理边界 |
| [前端开发](front/README.md) | 页面、Trace Studio、代理与构建 |
| [Agent 开发](agent/README.md) | 模型、工具、存储与配置 |
| [Agent 治理与文档索引](agent/docs/README.md) | 当前架构、配置目录、开发维护、验收与独立审查 |
| [网站部署](deploy/site/README.md) / [独立 Agent 部署](deploy/README.md) | 容器与环境配置 |
| [部署参考](docs/DEPLOYMENT.md) | 环境与集成测试 |
| [项目路线图](docs/ROADMAP.md) | 开发方向与待完善能力 |

源码入口：[业务后端](src/) · [前端](front/) · [Agent](agent/) · [脚本](scripts/)。

## 参与贡献

欢迎通过 [Issue](https://github.com/blackirlsama/EchoFrame/issues) 反馈问题和使用场景，或提交 [Pull Request](https://github.com/blackirlsama/EchoFrame/pulls) 改进体验、测试与文档。

从最新 `master` 创建分支，按 [CONTRIBUTING.md](CONTRIBUTING.md) 完成修改与验证。安全问题请按 [SECURITY.md](SECURITY.md) 反馈。感谢所有[贡献者](https://github.com/blackirlsama/EchoFrame/graphs/contributors)。

## 许可证与素材

项目代码采用 [MIT License](LICENSE)。第三方组件、视频素材、首页插画与图标保留各自权利，不随项目代码重新授权；详见 [NOTICE](NOTICE)、[第三方说明](docs/THIRD_PARTY.md) 与[素材来源](front/src/assets/img/home/README.md)。
