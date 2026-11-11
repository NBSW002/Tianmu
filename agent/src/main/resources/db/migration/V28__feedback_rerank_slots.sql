CREATE TABLE agent_feedback_rerank_slot (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    slot VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fence BIGINT NOT NULL,
    result_json JSON NULL,
    PRIMARY KEY (job_id, slot),
    CONSTRAINT fk_feedback_rerank_slot_job FOREIGN KEY (job_id)
        REFERENCES agent_feedback_job(job_id) ON DELETE CASCADE
);
