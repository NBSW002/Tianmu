ALTER TABLE agent_memory_account ADD auto_extract TINYINT NOT NULL DEFAULT 0;
ALTER TABLE agent_memory_source DROP CHECK ck_memory_source_kind,
  ADD CONSTRAINT ck_memory_source_kind CHECK (source_kind IN ('MANUAL','EXPLICIT_DECLARATION','AUTO_EXTRACTED','TASK_DERIVED')),
  ADD origin_session_id CHAR(36) CHARACTER SET ascii NULL,
  ADD origin_run_id CHAR(36) CHARACTER SET ascii NULL,
  ADD source_expires_at DATETIME(6) NULL,
  ADD KEY ix_memory_origin (tenant_id,owner_user_id,origin_session_id,origin_run_id);
ALTER TABLE agent_memory_revision DROP CHECK ck_memory_scope, DROP CHECK ck_memory_kind,
  ADD CONSTRAINT ck_memory_scope CHECK ((scope_type='USER' AND scope_key='self') OR scope_type IN ('VIDEO','TOPIC','SESSION')),
  ADD CONSTRAINT ck_memory_kind CHECK (kind IN ('PREFERENCE','GOAL','PROFILE','EPISODE','EXPERIENCE','SESSION_SUMMARY'));
CREATE TABLE agent_memory_run (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  memory_seq BIGINT NOT NULL,
  consent_epoch BIGINT NOT NULL,
  erase_epoch BIGINT NOT NULL,
  mode VARCHAR(16) NOT NULL,
  consumed TINYINT NOT NULL DEFAULT 0,
  selected_json JSON NOT NULL,
  pending_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,run_id),
  KEY ix_memory_run_session (tenant_id,owner_user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_topic (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  topic_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(80) NOT NULL,
  name_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,topic_id),
  UNIQUE KEY uk_memory_topic_name (tenant_id,owner_user_id,name_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_job (
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  consent_epoch BIGINT NOT NULL,
  erase_epoch BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(6) NOT NULL,
  lease_token CHAR(36) CHARACTER SET ascii NULL,
  lease_until DATETIME(6) NULL,
  model_version VARCHAR(128) NOT NULL,
  trace_parent VARCHAR(55) CHARACTER SET ascii NULL,
  trace_id CHAR(32) CHARACTER SET ascii NULL,
  model_calls INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY(job_id),
  UNIQUE KEY uk_memory_extract_source(tenant_id,owner_user_id,run_id,model_version),
  KEY ix_memory_job_work(state,available_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
