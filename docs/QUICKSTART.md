# EchoFrame 运行与接入

[返回项目首页](../README.md) · [架构说明](ARCHITECTURE.md)

所有构建与启动命令均从克隆后的仓库根目录开始，除非命令中另有 `cd`。

## 选择运行方式

| 方式 | 必要依赖 | 可选接入 |
| --- | --- | --- |
| Agent Demo | JDK 17、Maven 3.6.3+ | 无需模型 Key 或中间件 |
| Trace Studio | Node.js 22、npm | 导入真实 Trace 文件；无需后端 |
| 完整视频社区 | JDK 17、Maven、Node.js、MySQL、Redis、MinIO、Elasticsearch（IK）、RocketMQ、Canal | 创作者 Agent 与模型服务 |
| 真实创作者 Agent | 业务后端与登录、文本模型凭据；持久化模式另需 Agent 专用 MySQL、Redis 与私有对象存储 | 画面理解需 FFmpeg / ffprobe 与视觉模型；转写单独配置；Kafka / ClickHouse 用于可选 Trace 存储 |

```bash
git clone --depth 1 https://github.com/blackirlsama/EchoFrame.git
cd EchoFrame
```

项目与仓库名称为 **EchoFrame**；模块目录、构建产物与配置键仍沿用 `tianmu`，以下命令保留实际名称。仓库包含[视频素材](../materials/README.md)，首次克隆会一并下载。

## 方式一：体验 Agent Demo

**无需模型 Key、数据库或中间件。** 在仓库根目录执行：

```bash
mvn -B -f agent/pom.xml package
java -jar agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

服务默认运行在 `http://127.0.0.1:8201`。另开终端创建任务：

```bash
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'
```

将返回的 `runId` 替换到下方命令中，查看执行结果：

```bash
curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

Demo 使用模拟身份、脚本模型和内存存储，适合验证任务流程，不代表真实视频分析效果。以上请求使用 Bash；PowerShell 可使用：

```powershell
$headers = @{ Authorization = "Bearer demo-alice" }
$body = @{ message = "查询视频1001昨天的弹幕数" } | ConvertTo-Json
$run = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8201/api/agent/runs" -Headers $headers -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod -Uri "http://127.0.0.1:8201/api/agent/runs/$($run.runId)" -Headers $headers
```

任务异步执行；若仍在运行，稍后再次查询。

## 方式二：体验 Trace Studio

```bash
cd front
npm ci
npm run serve
```

打开终端显示的开发服务器地址，访问 `/trace`，通常为 `http://localhost:8080/trace`。页面内置演示日志，也支持导入真实的 JSON / JSONL 日志；文件在浏览器本地解析。

当前版本提供离线可视化，尚未连接在线 Trace 查询。更多说明见[前端文档](../front/README.md)。

## 方式三：运行完整网站

先按[部署参考](../docs/DEPLOYMENT.md)准备业务库和中间件，再参考 [`.env.example`](../.env.example) 设置 `TIANMU_*`、登录密钥与邮件配置。直接运行 Java 不会自动读取 `.env` 文件。

[网站容器部署说明](../deploy/site/README.md)面向已有单机验收环境，依赖既有共享网络与服务，不是空环境一键启动脚本。以下命令用于依赖就绪后的本地启动。

在仓库根目录构建后端与 Agent：

```bash
mvn -B package
mvn -B -f agent/pom.xml package
```

分别在两个 Bash 终端启动服务。真实 Agent 需先配置 `DEEPSEEK_API_KEY`、Agent 专用 `AGENT_JDBC_URL` / `AGENT_DB_*`、`AGENT_REDIS_*` 与 MinIO 凭据。业务桥接须使用与网站登录一致的 JWT 密钥和 Redis 会话配置：

```bash
# 视频业务后端
AGENT_BRIDGE_ENABLED=true java -jar target/tianmu-0.0.1-SNAPSHOT.jar
```

```bash
# 接入真实业务、模型和持久化存储的 Agent
BUSINESS_BASE_URL=http://127.0.0.1:8101/api \
SPRING_PROFILES_ACTIVE=business,deepseek,persistent \
  java -jar agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

需要画面理解时，再启用视觉模型并为业务后端安装 FFmpeg / ffprobe，配置见[网站部署的视频内容理解说明](../deploy/site/README.md#视频内容理解)。`deepseek` 配置默认开启视频工具；视觉服务不支持图片输入时，应设置 `VIDEO_CONTENT_ENABLED=false`，或配置可用的 `VIDEO_MODEL_*`。

然后启动前端（默认代理指向本地 8101 / 8201）：

```bash
cd front
npm ci
npm run serve
```

| 服务 | 默认地址 |
| --- | --- |
| 业务 API | `http://127.0.0.1:8101` |
| Agent API | `http://127.0.0.1:8201` |
| 弹幕 WebSocket | 端口 `9101` |
| 前端 | 以开发服务器输出为准 |

真实模式需配置数据库、登录、中间件及模型凭据，模型调用可能产生费用。前端代理和模型参数的配置分别见[前端说明](../front/README.md)、[Agent 说明](../agent/README.md)。容器部署可选择[独立 Agent 栈](../deploy/README.md)或[完整网站栈](../deploy/site/README.md)。

