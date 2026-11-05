CREATE TABLE agent_feedback_preparation (
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  step VARCHAR(16) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  fence BIGINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  lease_until DATETIME(6) NULL,
  result_json JSON NULL,
  embedding_calls INT NOT NULL DEFAULT 0,
  PRIMARY KEY (job_id, step),
  CONSTRAINT fk_feedback_preparation_job FOREIGN KEY (job_id)
    REFERENCES agent_feedback_job(job_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
