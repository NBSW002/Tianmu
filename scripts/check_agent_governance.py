"""Check the repository documentation inventory and module-owned numeric policies.

Run with --write to regenerate the catalog after an intentional policy change.
This checks declared defaults/consumer references, not production effective values.
"""
from pathlib import Path
import argparse
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "agent/docs"
JAVA = ROOT / "agent/src/main/java/com/tianmu/agent"
POLICIES = (
    "feedback/FeedbackBucketProperties.java",
    "feedback/FeedbackCompressionProperties.java",
    "memory/CompactionProperties.java", "memory/MemoryRuntimeProperties.java",
    "feedback/FeedbackRuntimeProperties.java", "video/VideoRuntimeProperties.java",
    "adapters/storage/StorageRuntimeProperties.java", "harness/trace/TraceDeliveryProperties.java",
    "harness/hook/HookPolicy.java",
    "runtime/OperationPolicy.java",
)
CATALOG = DOCS / "配置目录.md"


def catalog():
    lines = ["# 配置目录", "", "状态：当前已实现配置声明；2026-11-07建立。",
             "", "本目录由 `python scripts/check_agent_governance.py --write` 生成。来源为模块的强类型声明，CI检查漂移；运行环境有效值仍需按运维手册单独核验。",
             "", "下列模块数值参数启动绑定，修改后重启生效；没有热更新。前台策略快照由独立开关控制，只冻结新Run的前台执行参数，不冻结本表所有后台/基础设施容量。键中的 millis/seconds/days/hours/bytes/tokens 表示单位，其余为数量。",
             "", "Spring标准优先级适用：命令行参数覆盖系统属性、环境变量和配置文件；profile覆盖基础文件。环境变量按Spring规则去掉属性名中的连字符，再把点换成下划线并大写。例如 `agent.video.runtime.index-workers` 对应 `AGENT_VIDEO_RUNTIME_INDEXWORKERS`。既有YAML显式别名另以YAML为准，不凭目录臆造别名。",
             "", "配置失败应阻止启动；预算、租约/心跳、连接池和重试关系由各record构造器校验。不得用调大容量代替故障定位。安全协议限制不在本表自动变成可调参数。",
             "", "前台Loop/Context/Preparation、现有FeedbackPolicy/VideoCachePolicy等既有配置见[统一技术设计](Agent架构与工程治理技术设计.md)第9、15.5、16节及application*.yml；本表列出新增模块数值声明。",
             "", "## agent.policy-snapshot", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
             "| `agent.policy-snapshot.enabled` | false | PolicySnapshotProperties → ExecutionPolicyFactory；YAML显式环境别名 `AGENT_POLICY_SNAPSHOT_ENABLED`。仅新Run创建时捕获，旧记录永不补造；关闭写者仍兼容读取已保存v1。先部署兼容reader再启用writer。 |", ""]
    lines += ["## agent.feedback.compression协议", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.compression.enabled` | false | FeedbackCompressionProperties → TaskAssembler在受理时冻结feedback:compression资源标记，ToolDispatcher按任务标记选择三Tool；只影响新任务，旧任务不自动升级。显式别名FEEDBACK_COMPRESSION_ENABLED。启动生效，启用须有Embedding/Milvus；缺失不得EXACT_ONLY回退。 |",
              "| `agent.feedback.compression.min-similarity` | 0.90 | FeedbackCompression任务内星形聚类的余弦下限，finite且(0,1]；FEEDBACK_CLUSTER_MIN_SIMILARITY。进入材料/后台policyHash，重启生效，已封存assignment不重算。 |",
              "| `agent.feedback.compression.min-centrality` | 0.75 | FeedbackCompression代表准入的质心余弦阈值，finite且[-1,1]；FEEDBACK_MIN_CENTRALITY。缺来源代表明确失败，进入材料policyHash。 |",
              "| `agent.feedback.compression.near-duplicate-similarity` | 0.96 | FeedbackCompression可选代表的近重复门槛，finite且[min-similarity,1]；FEEDBACK_NEAR_DUPLICATE_SIMILARITY。来源mandatory优先，进入材料policyHash。 |", ""]
    lines += ["## agent.feedback.buckets相似度", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.buckets.min-similarity` | 0.65 | 最终成员到桶中心的余弦下限；finite且0..1，进入冻结revision，启动生效；需在真实语料校准。YAML别名 `FEEDBACK_BUCKETS_MIN_SIMILARITY`；其他字段别名依次为 `FEEDBACK_BUCKETS_MAX`、`FEEDBACK_BUCKETS_MIN_MEMBERS`、`FEEDBACK_BUCKETS_MAX_ITERATIONS`、`FEEDBACK_BUCKETS_CANDIDATE_SAMPLES`。 |", ""]
    lines += ["## agent.feedback.buckets任务中心化", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.buckets.centering-enabled` | false | 可选任务内均值中心化；YAML别名 FEEDBACK_BUCKETS_CENTERING_ENABLED，记录实际模式和回退原因，进入协议revision。 |",
              "| `agent.feedback.buckets.center-min-rms` | 0.01 | 向量残差均方根低于此值时全任务回退原始余弦；别名 FEEDBACK_BUCKETS_CENTER_MIN_RMS。 |",
              "| `agent.feedback.buckets.center-min-residual` | 0.001 | 中心化后的单条残差范数下限；低于该值归未归类；别名 FEEDBACK_BUCKETS_CENTER_MIN_RESIDUAL。 |",
              "| `agent.feedback.buckets.center-max-excluded-fraction` | 0.20 | 近均值表达组或其原始成员的比例超过该值时整体回退原始余弦；别名 FEEDBACK_BUCKETS_CENTER_MAX_EXCLUDED_FRACTION。 |",
              "| `agent.feedback.buckets.centered-similarity` | 0.30 | 任务内中心化余弦门槛；别名 FEEDBACK_BUCKETS_CENTERED_SIMILARITY，与原始余弦门槛不能互换。 |", ""]
    lines += ["## agent.models", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.models.thinking-enabled` | true | DeepSeekProperties → DeepSeekClient；YAML环境别名 `AGENT_MODEL_THINKING_ENABLED`。启动绑定，主循环原生工具及后台反馈分析启用；短输出前置识别、记忆模型仍关闭。仅DeepSeek端点或deepseek模型名发送thinking协议。模式和协议版本进入模型revision，变更需排空在途Run；不是执行策略快照字段。旧Trace不补造思考。 |", ""]
    lines += ["## agent.models思考预算", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.models.thinking-effort` | high | DeepSeekClient主循环思考强度，low/high/max；显式别名 `AGENT_MODEL_THINKING_EFFORT`，启动生效，进入native-thinking-v2模型revision。 |",
              "| `agent.models.analysis-thinking-effort` | low | 后台分析思考强度，low/high/max；显式别名 `AGENT_ANALYSIS_THINKING_EFFORT`。思考保持开启，强度和模式进入两种反馈policyHash，变化前排空job。 |",
              "| `agent.models.analysis-max-tokens` | 16384 | 后台分析单次总输出预算，含思考及最终JSON；8192–32768 Token。显式别名 `AGENT_ANALYSIS_MAX_OUTPUT_TOKENS`。消费DeepSeekFeedbackModels及DeepSeekAudienceModel，预算随实际值预留；job总预算不变，变化进入反馈policyHash。 |", ""]
    lines += ["## agent.feedback思考调用时限", "", "| 属性键 | 默认值 | 消费及生效语义 |", "|---|---|---|",
              "| `agent.feedback.model-timeout` | 基础30s；deepseek profile 120s | FeedbackPolicy → FeedbackAnalysisService；显式环境别名 `FEEDBACK_MODEL_TIMEOUT`，启动生效，精确范围1–180秒。单次模型调用取此值与job剩余deadline较小者；工作租约为超时加model-lease-margin-seconds，取消、fence、调用与Token预算不变。修改前排空既有任务，不将新策略冒充旧job版本。 |", ""]
    sources = {p: p.read_text(encoding="utf-8") for p in JAVA.rglob("*.java")}
    errors = []
    for relative in POLICIES:
        path = JAVA / relative
        source = sources[path]
        prefix = re.search(r'@ConfigurationProperties\("([^"]+)"\)', source).group(1)
        fields = re.findall(r'@DefaultValue\("([^"\n]+)"\)\s+int\s+(\w+)', source)
        lines += [f"## {prefix}", "", f"声明与完整校验：[源码](../src/main/java/com/tianmu/agent/{relative})。", "",
                  "| 属性键 | 默认值 | 消费类 |", "|---|---:|---|"]
        for default, name in fields:
            key = prefix + "." + re.sub(r"[A-Z]", lambda m: "-" + m[0].lower(), name)
            consumers = sorted(p.stem for p, text in sources.items() if p != path and
                               ((path.stem in text and re.search(r"\." + name + r"\(\)", text)) or "${" + key + ":" in text))
            if not consumers:
                errors.append(f"No consumer: {key}")
            lines.append(f"| `{key}` | {default} | {'、'.join(consumers) or '缺失'} |")
        lines.append("")
    return "\n".join(lines), errors


def check(write=False):
    content, errors = catalog()
    if write:
        CATALOG.write_text(content, encoding="utf-8", newline="\n")
    elif not CATALOG.exists() or CATALOG.read_text(encoding="utf-8") != content:
        errors.append("Configuration catalog drift; run python scripts/check_agent_governance.py --write")
    index = DOCS / "README.md"
    if not index.exists():
        errors.append("Missing documentation index")
    else:
        indexed = index.read_text(encoding="utf-8")
        for path in DOCS.glob("*.md"):
            if path != index and f"]({path.name})" not in indexed:
                errors.append(f"Unclassified document: {path.name}")
    current_pages = [DOCS / name for name in ("README.md", "配置目录.md", "开发与维护规范.md", "运维与验收手册.md",
                                               "Agent架构与工程治理技术设计.md", "项目进度.md")]
    current_pages.append(DOCS.parent / "README.md")
    for path in current_pages:
        name = path.name
        if not path.exists():
            errors.append(f"Missing current document: {name}")
            continue
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
            if "://" in target or target.startswith("#"):
                continue
            target = target.split("#")[0]
            if not (path.parent / target).exists():
                errors.append(f"Broken local link in {name}: {target}")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    errors = check(parser.parse_args().write)
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Agent governance: {'FAIL' if errors else 'PASS'}")
    sys.exit(bool(errors))
