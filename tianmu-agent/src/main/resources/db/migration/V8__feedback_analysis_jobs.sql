CREATE TABLE agent_feedback_guard (
    guard_id INT PRIMARY KEY
);
INSERT INTO agent_feedback_guard (guard_id) VALUES (1);

CREATE TABLE agent_feedback_job (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    job_json JSON NOT NULL,
    asset_json JSON NULL,
    error_code VARCHAR(80) NULL,
    token_limit BIGINT NOT NULL,
    call_limit INT NOT NULL,
    charged_tokens BIGINT NOT NULL DEFAULT 0,
    model_calls INT NOT NULL DEFAULT 0,
    completed_work INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    deadline DATETIME(6) NOT NULL,
    UNIQUE KEY uq_feedback_run (run_id),
    KEY ix_feedback_scan (phase, created_at),
    KEY ix_feedback_owner (tenant_id, user_id, phase)
);

CREATE TABLE agent_feedback_work (
    work_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) NOT NULL,
    level_no INT NOT NULL,
    ordinal_no INT NOT NULL,
    input_json JSON NOT NULL,
    result_json JSON NULL,
    state VARCHAR(16) NOT NULL,
    fence BIGINT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    available_at DATETIME(6) NOT NULL,
    error_code VARCHAR(80) NULL,
    UNIQUE KEY uq_feedback_work (job_id, level_no, ordinal_no),
    KEY ix_feedback_claim (job_id, state, available_at),
    CONSTRAINT fk_feedback_work_job FOREIGN KEY (job_id) REFERENCES agent_feedback_job(job_id) ON DELETE CASCADE
);

CREATE TABLE agent_feedback_attempt (
    work_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fence BIGINT NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    prompt_tokens BIGINT NULL,
    completion_tokens BIGINT NULL,
    state VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (work_id, fence),
    CONSTRAINT fk_feedback_attempt_job FOREIGN KEY (job_id) REFERENCES agent_feedback_job(job_id) ON DELETE CASCADE
);
