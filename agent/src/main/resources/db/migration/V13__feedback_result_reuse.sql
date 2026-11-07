CREATE TABLE agent_feedback_result_cache (
 cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 user_id VARCHAR(64) NOT NULL,
 proposals_json JSON NOT NULL,
 source_job CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 cached_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 KEY ix_feedback_cache_expiry (expires_at)
);
CREATE TABLE agent_feedback_cache_guard (guard_id INT PRIMARY KEY);
INSERT INTO agent_feedback_cache_guard VALUES (1);
CREATE TABLE agent_feedback_cache_flight (
 cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 user_id VARCHAR(64) NOT NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 lease_until DATETIME(6) NOT NULL,
 KEY ix_feedback_flight_expiry (lease_until)
);
