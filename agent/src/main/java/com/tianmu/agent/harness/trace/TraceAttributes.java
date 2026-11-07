package com.tianmu.agent.harness.trace;

import io.opentelemetry.api.common.*;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** Export allowlist is the final boundary, including for attributes added by third-party hooks. */
public final class TraceAttributes {
    private TraceAttributes() {}
    private static final Set<String> ALLOWED = Set.of("analysis.cache_wait_ms", "analysis.cache_hit", "analysis.cache_age_ms", "analysis.cache_source_job", "analysis.force_reanalysis", "storage.failure_kind", "storage.http_status", "video.shared_wait", "video.shared_wait_ms", "budget.token_limit", "budget.charged_tokens", "budget.reported_calls", "budget.unsettled_calls", "video.frame_count", "video.audio_available", "video.id", "video.source_version", "video.start_ms", "video.end_ms", "video.cache_hit", "run.id", "run.status", "run.steps", "run.stop_reason", "run.model_calls", "run.finalizing", "loop.step",
            "policy.source", "policy.hash", "tool.name", "tool.call_id", "skill.name", "skill.version", "hook.name", "hook.point", "hook.mode",
            "hook.failed", "operation.executed", "operation.outcome", "error.code", "exception.type",
            "input.char_count", "context.observation_count", "answer.evidence_count", "http.method", "http.route", "http.status_code",
            "request.route", "faq.id", "faq.version", "preparation.state", "preparation.method", "preparation.reason", "clarification.required", "preparation.skipped_stages", "preparation.degraded_stages", "preparation.emotion", "preparation.intent", "preparation.degraded_count", "preparation.skipped_count",
            "query.rewrite_state", "query.rewrite_reason", "preparation.rule_task", "preparation.effective_task", "preparation.merge_reason", "preparation.defaults", "clarification.reason",
            "run.preparation_calls", "run.total_model_calls", "preparation.intent_candidates", "preparation.intent_selection", "preparation.intent_primary", "preparation.intent_related",
            "model.name", "model.input_tokens", "model.output_tokens", "span.type", "session.id", "run.turn_seq",
            "tenant.id", "actor.id", "run.trace_id", "route.reason", "model.decision", "model.selected_tool", "model.selected_tools",
            "query.rewritten", "faq.hit", "agent.max_turns", "agent.available_tools", "agent.available_tool_count", "agent.total_turns",
            "agent.reached_turn_limit", "agent.outcome", "tool.success", "tool.has_evidence", "tool.terminal_delivery",
            "output.char_count", "answer.char_count", "finish.type", "run.e2e_ms", "run.answer_ready_ms",
            "usage.input_tokens", "usage.output_tokens", "usage.reported_calls", "usage.scope", "usage.complete",
            "delivery.type", "delivery.replay", "delivery.success", "delivery.first_content_ms", "delivery.e2e_ms",
            "node.type", "node.status", "execution.attempt", "execution.retry_count", "execution.retryable", "execution.timeout_ms", "execution.queue_ms", "execution.active_ms", "execution.wait_ms",
            "model.provider", "model.usage_state", "model.total_tokens", "model.cache_hit_tokens", "model.cache_miss_tokens", "model.reasoning_tokens", "model.finish_reason", "model.temperature", "model.max_tokens", "model.streaming", "model.first_token_ms", "model.first_token_state", "model.response_ms", "model.message_count", "model.reasoning_state", "model.http_status",
            "analysis.job_id", "analysis.phase", "analysis.work_id", "analysis.work_kind", "analysis.batch_ordinal", "budget.scope", "run.background_model_calls", "usage.external_model_calls", "usage.total_tokens", "usage.cache_hit_tokens", "usage.reasoning_tokens", "usage.state",
            "context.history_removed", "context.schemas_removed", "context.byte_limit", "context.input_bytes", "context.output_bytes", "context.changed",
            "context.format", "context.history_messages", "context.classified_batches_released", "context.report_inputs_released", "context.projection_bytes_saved",
            "context.schema_version", "context.projection_version", "context.present_slots", "context.slot_bytes",
            "memory.candidate_count", "memory.selected_count", "memory.context_bytes", "memory.snapshot_seq",
            "memory.reused", "memory.restore_reason", "memory.added_count", "memory.updated_count", "memory.duplicate_count", "memory.conflict_count", "memory.source_messages", "memory.policy_version",
            "tool.replay", "tool.error_code", "tool.batch_id", "tool.batch_count", "tool.batch_index", "tool.execution_mode",
            "memory.job_id", "compaction.covered_from", "compaction.covered_through", "compaction.source_messages", "compaction.input_bytes", "compaction.output_bytes", "compaction.saved_bytes", "compaction.trigger_reason", "compaction.prompt_version", "compaction.selected_segments", "compaction.context_bytes",
            "model.response_repaired", "model.validation_error",
            "model.tool_protocol", "model.tool_choice", "video.fact_strategy", "tool.handler_executed", "tool.validation_issue_count", "tool.argument_correction",
            "detail.input_ref", "detail.input_state", "detail.input_bytes", "detail.input_redacted",
            "detail.output_ref", "detail.output_state", "detail.output_bytes", "detail.output_redacted",
            "detail.provider_output_ref", "detail.provider_output_state", "detail.provider_output_bytes", "detail.provider_output_redacted",
            "detail.error_ref", "detail.error_state", "detail.error_bytes", "detail.error_redacted",
            "context.request_bytes", "context.tools_bytes", "context.message_bytes", "context.system_bytes", "context.user_bytes", "context.assistant_bytes", "context.tool_result_bytes", "context.duplicate_result_bytes", "context.history_bytes", "context.results_compacted", "context.compaction_segments",
            "usage.attempted_calls", "usage.unreported_calls", "usage.cache_miss_tokens", "usage.cache_reported_calls", "model.dispatched",
            "analysis.retry_wait_ms", "analysis.validation_issue", "analysis.result_accepted",
            "io.feedback_object_ms", "io.feedback_object_calls", "io.feedback_database_ms", "io.feedback_database_calls");
    public static Map<String, Object> sanitize(Map<String, ?> values) {
        Map<String, Object> clean = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!ALLOWED.contains(key) || value == null) return;
            if (value instanceof Number || value instanceof Boolean) clean.put(key, value);
            else if (value instanceof List<?> items && items.size() <= 32 && items.stream().allMatch(item ->
                    item instanceof String text && text.length() <= 160 && text.matches("[A-Za-z0-9_./:@ -]*")))
                clean.put(key, List.copyOf(items));
            else if (value instanceof String text && text.length() <= 160 && text.matches("[A-Za-z0-9_./:@ -]*"))
                clean.put(key, text);
        });
        return Map.copyOf(clean);
    }
    public static void apply(Span span, Map<String, ?> attributes) {
        sanitize(attributes).forEach((key, value) -> {
            if (value instanceof Double || value instanceof Float) span.setAttribute(key, ((Number)value).doubleValue());
            else if (value instanceof Number number) span.setAttribute(key, number.longValue());
            else if (value instanceof Boolean bool) span.setAttribute(key, bool);
            else if (value instanceof List<?> items) span.setAttribute(AttributeKey.stringArrayKey(key), items.stream().map(Object::toString).toList());
            else span.setAttribute(key, value.toString());
        });
    }
    public static Map<String, Object> from(Attributes attributes) {
        Map<String, Object> values = new HashMap<>();
        attributes.forEach((key, value) -> values.put(key.getKey(), value));
        return sanitize(values);
    }
}
