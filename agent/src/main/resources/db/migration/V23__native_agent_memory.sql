ALTER TABLE agent_memory_account ADD observation_seq BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_memory_source ADD origin_message_id CHAR(36) CHARACTER SET ascii NULL,
 ADD origin_message_hash CHAR(64) CHARACTER SET ascii NULL;
ALTER TABLE agent_memory_revision ADD support_policy VARCHAR(24) NOT NULL DEFAULT 'LEGACY_V1';
CREATE TABLE agent_tool_call (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_id VARCHAR(64) CHARACTER SET ascii NOT NULL,call_index INT NOT NULL,
 call_id VARCHAR(128) CHARACTER SET ascii NOT NULL,tool_name VARCHAR(96) NOT NULL,
 tool_version VARCHAR(32) NOT NULL,arguments_json JSON NOT NULL,arguments_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 execution_mode VARCHAR(16) NOT NULL,state VARCHAR(20) NOT NULL DEFAULT 'PREPARED',
 result_json JSON NULL,evidence_id VARCHAR(128) NULL,completed_at DATETIME(6) NULL,
 PRIMARY KEY(tenant_id,owner_user_id,run_id,batch_id,call_index),
 CONSTRAINT fk_tool_call_run FOREIGN KEY(tenant_id,run_id) REFERENCES agent_run(tenant_id,run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_observation (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 observation_seq BIGINT NOT NULL,session_id CHAR(36) CHARACTER SET ascii NOT NULL,
 run_id CHAR(36) CHARACTER SET ascii NOT NULL,message_id CHAR(36) CHARACTER SET ascii NOT NULL,
 message_seq BIGINT NOT NULL,content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 consent_epoch BIGINT NOT NULL,erase_epoch BIGINT NOT NULL,eligible TINYINT NOT NULL,
 PRIMARY KEY(tenant_id,owner_user_id,observation_seq),
 UNIQUE KEY uk_memory_observation_run(tenant_id,owner_user_id,run_id),
 KEY ix_memory_observation_session(tenant_id,owner_user_id,session_id,message_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_cursor (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 session_id CHAR(36) CHARACTER SET ascii NOT NULL,
 scheduled_through BIGINT NOT NULL DEFAULT 0,committed_through BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,owner_user_id,session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_window (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 session_id CHAR(36) CHARACTER SET ascii NOT NULL,run_id CHAR(36) CHARACTER SET ascii NOT NULL,
 message_seq BIGINT NOT NULL,observation_seq BIGINT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',job_id CHAR(36) CHARACTER SET ascii NULL,
 decision_counts JSON NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,owner_user_id,run_id),
 KEY ix_memory_window_progress(tenant_id,owner_user_id,session_id,message_seq,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_fact (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 fact_key CHAR(64) CHARACTER SET ascii NOT NULL,identity_json JSON NOT NULL,
 memory_id CHAR(36) CHARACTER SET ascii NOT NULL,memory_version BIGINT NOT NULL,
 observation_seq BIGINT NOT NULL,value_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 PRIMARY KEY(tenant_id,owner_user_id,fact_key),
 KEY ix_memory_fact_item(tenant_id,owner_user_id,memory_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_candidate (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 job_id CHAR(36) CHARACTER SET ascii NOT NULL,candidate_index INT NOT NULL,
 fact_key CHAR(64) CHARACTER SET ascii NOT NULL,decision VARCHAR(32) NOT NULL,
 memory_id CHAR(36) CHARACTER SET ascii NULL,details_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,owner_user_id,job_id,candidate_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
