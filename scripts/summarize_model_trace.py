"""Read-only cost/latency accounting from exported spans; never loads payloads or credentials."""
import argparse
import collections
import json
from decimal import Decimal
from pathlib import Path


def token(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None


def estimate(attrs, prices):
    rates = prices.get("models", {}).get(attrs.get("model.name"))
    prompt, output = token(attrs.get("model.input_tokens")), token(attrs.get("model.output_tokens"))
    if not rates or prompt is None or output is None:
        return None
    hit, miss = token(attrs.get("model.cache_hit_tokens")), token(attrs.get("model.cache_miss_tokens"))
    if hit is None and miss is not None:
        hit = prompt - miss
    if miss is None and hit is not None:
        miss = prompt - hit
    if hit is not None and (hit < 0 or miss < 0 or hit + miss != prompt):
        return None
    low, high = rates["minimum"], rates["maximum"]
    def cost(rate, cached, uncached):
        return (Decimal(str(rate["cache_hit"])) * cached + Decimal(str(rate["cache_miss"])) * uncached
                + Decimal(str(rate["output"])) * output) / 1_000_000
    return [cost(low, prompt if hit is None else hit, 0 if miss is None else miss),
            cost(high, 0 if hit is None else hit, prompt if miss is None else miss)]


def summarize(spans, prices):
    unique = {}
    for span in spans:
        key = (span["traceId"], span["spanId"])
        if key in unique:
            # Delivery envelope/event IDs may differ across retries; a span's accounting must not.
            fields = ("name", "startTimeUnixNano", "endTimeUnixNano", "attributes")
            if any(unique[key].get(f) != span.get(f) for f in fields):
                raise ValueError("Conflicting duplicate span; refusing ambiguous accounting")
        unique[key] = span
    def ancestors(span):
        seen = set()
        while span and (span["traceId"], span["spanId"]) not in seen:
            seen.add((span["traceId"], span["spanId"]))
            yield span
            span = unique.get((span["traceId"], span.get("parentSpanId")))
    calls, groups = [], collections.defaultdict(list)
    for span in unique.values():
        attrs = span.get("attributes", {})
        if span["name"] != "model.request" or attrs.get("model.dispatched") is not True:
            continue
        lineage = list(ancestors(span))
        run = next((s.get("attributes", {}).get("run.id") for s in lineage if s.get("attributes", {}).get("run.id")), None)
        worker = next((s for s in lineage if s["name"] == "feedback.work"), None)
        stage = "feedback" if worker else "preparation" if any(s["name"].startswith("agent.prepare.") for s in lineage) else "loop_or_tool"
        cost = estimate(attrs, prices)
        projection = next((s["attributes"]["context.projection_version"] for s in lineage
                           if "context.projection_version" in s.get("attributes", {})), None)
        first_token_state = attrs.get("model.first_token_state", "NOT_REPORTED")
        row = {"run_id": run, "trace_id": span["traceId"], "span_id": span["spanId"], "stage": stage,
               "model": attrs.get("model.name"), "http_status": attrs.get("model.http_status"),
               "usage_state": attrs.get("model.usage_state", "NOT_REPORTED"),
               "input_tokens": token(attrs.get("model.input_tokens")), "output_tokens": token(attrs.get("model.output_tokens")),
               "cache_hit_tokens": token(attrs.get("model.cache_hit_tokens")), "cache_miss_tokens": token(attrs.get("model.cache_miss_tokens")),
               "response_ms": attrs.get("model.response_ms"), "cost_range": [str(x) for x in cost] if cost else None,
               "first_token_state": first_token_state,
               "ttft_ms": token(attrs.get("model.first_token_ms")) if first_token_state == "RECEIVED" else None,
               "context_projection": projection, "message_roles_in_order": attrs.get("message.ordered_roles"),
               "context": {k: v for k, v in attrs.items() if k.startswith("context.") and k.endswith("_bytes")}}
        if worker:
            row["batch"] = {k: v for k, v in worker.get("attributes", {}).items()
                            if k in {"analysis.job_id", "analysis.work_id", "analysis.batch_ordinal", "execution.attempt"}}
        calls.append(row)
        groups[run].append(row)
    runs = []
    for run, rows in groups.items():
        related = [s for s in unique.values() if s.get("attributes", {}).get("run.id") == run]
        roots = [s for s in related if s["name"] == "agent.run"]
        reported = [r for r in rows if r["input_tokens"] is not None and r["output_tokens"] is not None]
        priced = [r for r in rows if r["cost_range"] is not None]
        sums = {field: sum(r[field] for r in rows if r[field] is not None)
                for field in ("input_tokens", "output_tokens", "cache_hit_tokens", "cache_miss_tokens")}
        timing_keys = ["execution.queue_ms", "analysis.retry_wait_ms", "io.feedback_object_ms", "io.feedback_database_ms"]
        timings = {k: sum(s["attributes"][k] for s in related if k in s.get("attributes", {}))
                   if any(k in s.get("attributes", {}) for s in related) else None for k in timing_keys}
        runs.append({"run_id": run, "calls": len(rows), "reported_calls": len(reported), "unreported_calls": len(rows)-len(reported),
                     "usage_complete": len(reported) == len(rows), "by_model": dict(collections.Counter(r["model"] for r in rows)),
                     "tokens_reported": sums, "cache_split_reported_calls": sum(r["cache_hit_tokens"] is not None and r["cache_miss_tokens"] is not None for r in rows),
                     "estimated_cost_known_calls_range": [str(sum((Decimal(r["cost_range"][i]) for r in priced), Decimal(0))) for i in (0, 1)] if priced else None,
                     "unpriced_calls": len(rows)-len(priced), "timing_sums_ms": timings,
                     "model_response_sum_ms": sum(r["response_ms"] for r in rows if r["response_ms"] is not None),
                     "recorded_run_span_ms": (max(s["endTimeUnixNano"] for s in roots)-min(s["startTimeUnixNano"] for s in roots))/1e6 if roots else None,
                     "terminal_root_observed": any(s.get("attributes", {}).get("run.status") in {"SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_INPUT"} for s in roots)})
    return {"schema_version": 1, "coverage": "EXPORTED_REQUEST_SPANS_ONLY", "currency": prices.get("currency"), "price_source": prices.get("source"),
            "price_checked_at": prices.get("checked_at"), "billing_status": "ESTIMATE_NOT_PROVIDER_INVOICE",
            "notes": ["Token counts are provider-reported; missing usage is unknown, never free.",
                      "Cost ranges cover configured tariff bounds and unknown cache splits; unpriced calls are excluded, not zero-cost.",
                      "Timing sums can overlap and are not additive wall time. Network response time includes provider waiting; server inference time is unavailable.",
                      "TTFT measures the first visible text chunk for streaming calls. Tool-only, non-streaming and missing samples are unknown.",
                      "Database metrics cover instrumented feedback ledger operations, not all business SQL. Missing timing is unmeasured.",
                      "Aggregated root usage is never summed with request spans. Only exported model.request spans marked dispatched are counted.",
                      "Usage completeness applies to observed calls only; compare against run/job call counters to detect missing exports."],
            "pre_dispatch_rejections": sum(s["name"] == "model.request" and not s.get("attributes", {}).get("model.dispatched", False) for s in unique.values()),
            "legacy_model_spans_without_request_accounting": sum(s["name"] in {"agent.model", "feedback.work"} and "model.input_tokens" in s.get("attributes", {}) for s in unique.values()),
            "runs": runs, "calls": calls}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("traces", type=Path, nargs="+")
    parser.add_argument("--prices", type=Path, required=True, help="Explicit dated per-million token price ranges; no implicit live price assumptions")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spans = [json.loads(line) for path in args.traces for line in path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    result = summarize(spans, json.loads(args.prices.read_text(encoding="utf-8-sig")))
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"runs": len(result["runs"]), "calls": len(result["calls"]), "billing_status": result["billing_status"]}))


if __name__ == "__main__":
    main()
