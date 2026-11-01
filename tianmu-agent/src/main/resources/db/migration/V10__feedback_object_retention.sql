CREATE TABLE agent_feedback_object (
    object_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    KEY ix_feedback_object_job(job_id),
    KEY ix_feedback_object_expiry(expires_at)
);
