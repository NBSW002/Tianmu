CREATE TABLE agent_memory_recall_manifest (
 tenant_id VARCHAR(64) NOT NULL, owner_user_id VARCHAR(64) NOT NULL,
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_seq BIGINT NOT NULL, consent_epoch BIGINT NOT NULL, erase_epoch BIGINT NOT NULL,
 mode VARCHAR(16) NOT NULL, manifest_json JSON NOT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,owner_user_id,run_id),
 KEY ix_memory_recall_session(tenant_id,owner_user_id,session_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_embedding (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,version BIGINT NOT NULL,
 model_version VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 dimension INT NOT NULL,vector_json JSON NULL,state VARCHAR(16) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,next_attempt_at DATETIME(6) NOT NULL,
 generated_at DATETIME(6) NULL,error_code VARCHAR(64) NULL,
 PRIMARY KEY(tenant_id,owner_user_id,memory_id,version,model_version),
 KEY ix_memory_embedding_retry(state,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
