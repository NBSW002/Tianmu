-- Apply before enabling feedback.vector-work.enabled. Existing vectors are preserved.
CREATE TABLE IF NOT EXISTS feedback_vector_work (
 video_id BIGINT PRIMARY KEY,
 generation BIGINT NOT NULL DEFAULT 1,
 completed_generation BIGINT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36) NULL,
 lease_until DATETIME(6) NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(64) NULL,
 INDEX idx_vector_work_due(next_at,lease_until)
) ENGINE=InnoDB;
