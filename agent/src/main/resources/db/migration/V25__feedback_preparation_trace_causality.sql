ALTER TABLE agent_feedback_preparation
    ADD COLUMN trigger_trace_id CHAR(32) NULL,
    ADD COLUMN trigger_span_id CHAR(16) NULL,
    ADD COLUMN accepted_trace_id CHAR(32) NULL,
    ADD COLUMN accepted_span_id CHAR(16) NULL;
