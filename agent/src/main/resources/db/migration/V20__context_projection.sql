ALTER TABLE agent_memory_job ADD job_type VARCHAR(24) NOT NULL DEFAULT 'EXTRACT', ADD plan_json JSON NULL;
CREATE TABLE agent_memory_projection (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  projection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  projection_kind VARCHAR(24) NOT NULL DEFAULT 'SESSION',
  revision BIGINT NOT NULL DEFAULT 1,
  from_seq BIGINT NOT NULL,
  through_seq BIGINT NOT NULL,
  source_history_version BIGINT NOT NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL,
  consent_epoch BIGINT NOT NULL,
  erase_epoch BIGINT NOT NULL,
  content_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'READY',
  model_version VARCHAR(128) NOT NULL,
  prompt_version VARCHAR(64) NOT NULL,
  input_bytes INT NOT NULL,
  output_bytes INT NOT NULL,
  message_count INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,projection_id),
  UNIQUE KEY uk_projection_range (tenant_id,owner_user_id,session_id,from_seq,through_seq,source_fingerprint,model_version),
  KEY ix_projection_session (tenant_id,owner_user_id,session_id,status,through_seq),
  CHECK (from_seq>0 AND through_seq>=from_seq AND input_bytes>0 AND output_bytes>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_projection_member (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  projection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,projection_id,message_id),
  KEY ix_projection_source (tenant_id,owner_user_id,run_id),
  FOREIGN KEY (tenant_id,owner_user_id,projection_id) REFERENCES agent_memory_projection(tenant_id,owner_user_id,projection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
