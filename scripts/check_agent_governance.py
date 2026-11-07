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
