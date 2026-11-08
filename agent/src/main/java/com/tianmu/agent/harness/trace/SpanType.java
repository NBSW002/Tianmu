package com.tianmu.agent.harness.trace;

/** Stable business taxonomy, independent of legacy span display names. */
public enum SpanType {
    RECEIVE_QUERY, PRE_ROUTE, FAQ_LOOKUP, FAQ_DIRECT, INTENT_RECOGNITION, EMOTION_RECOGNITION, QUERY_UNDERSTANDING, CLARIFICATION_CHECK, TASK_READINESS, PREPARATION_MERGE,
    MEMORY_LOAD, MEMORY_RECALL, CONTEXT_COMPACT, CONTEXT_BUILD, AGENT_START, LLM_INFERENCE, TOOL_CALL, READ_SKILL,
    ANSWER_READY, TERMINAL_DELIVERY, AGENT_END, FINISH, ERROR;

    public String value() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static SpanType forStage(String name) {
        return switch (name) {
            case "agent.preparation" -> PRE_ROUTE;
            case "agent.prepare.validate" -> CLARIFICATION_CHECK;
            case "agent.prepare.clarification" -> CLARIFICATION_CHECK;
            case "agent.prepare.readiness" -> TASK_READINESS;
            case "agent.prepare.merge" -> PREPARATION_MERGE;
            case "agent.prepare.faq" -> FAQ_LOOKUP;
            case "agent.deliver.faq" -> FAQ_DIRECT;
            case "agent.prepare.emotion" -> EMOTION_RECOGNITION;
            case "agent.prepare.intent" -> INTENT_RECOGNITION;
            case "agent.prepare.understanding" -> QUERY_UNDERSTANDING;
            case "agent.memory.load" -> MEMORY_LOAD;
            case "agent.memory.snapshot" -> MEMORY_LOAD;
            case "agent.memory.recall", "agent.memory.overview" -> MEMORY_RECALL;
            case "agent.context.compact" -> CONTEXT_COMPACT;
            case "agent.context.compaction_load" -> CONTEXT_BUILD;
            case "agent.context", "agent.assemble" -> CONTEXT_BUILD;
            case "agent.model", "agent.model.finalize", "video.model.vision", "video.model.transcription" -> LLM_INFERENCE;
            case "agent.tool" -> TOOL_CALL;
            case "agent.skill" -> READ_SKILL;
            case "agent.deliver" -> ANSWER_READY;
            default -> null;
        };
    }
}
