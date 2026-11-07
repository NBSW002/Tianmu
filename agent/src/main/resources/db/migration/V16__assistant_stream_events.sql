-- Streaming fragments and the immutable model choice use the same authorized replay log.
ALTER TABLE agent_run_event
    DROP CHECK ck_event_type,
    ADD CONSTRAINT ck_event_type CHECK (
        event_type IN ('PROGRESS', 'ARTIFACT_READY', 'MODEL_SELECTED', 'TEXT_START', 'TEXT_DELTA', 'TEXT_END',
                       'ANSWER', 'CLARIFICATION', 'ERROR', 'ABORT', 'DONE')
    );
