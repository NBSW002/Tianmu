-- Only new accounts inherit automatic learning. Existing opt-outs are unchanged.
ALTER TABLE agent_run DROP CHECK ck_run_route,
 ADD CONSTRAINT ck_run_route CHECK (route IS NULL OR route IN ('FAQ','CLARIFY','HARNESS','DIRECT'));
ALTER TABLE agent_memory_account ALTER COLUMN auto_extract SET DEFAULT 1;
ALTER TABLE agent_memory_observation ADD observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
ALTER TABLE agent_memory_window ADD available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ADD explicit_request TINYINT NOT NULL DEFAULT 0;
CREATE TABLE agent_memory_dream (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 through_seq BIGINT NOT NULL DEFAULT 0,last_completed_at DATETIME(6) NULL,
 scan_before BIGINT NOT NULL DEFAULT 9223372036854775807,
 PRIMARY KEY(tenant_id,owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_memory_index (
 tenant_id VARCHAR(64) NOT NULL,owner_user_id VARCHAR(64) NOT NULL,
 memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,version BIGINT NOT NULL,
 is_core TINYINT NOT NULL DEFAULT 0,importance INT NOT NULL DEFAULT 0,retired TINYINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,owner_user_id,memory_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Drain old binaries before this migration; single-message extraction is retired.
UPDATE agent_memory_job SET state='CANCELLED',error_code='MEMORY_JOB_OBSOLETE',lease_token=NULL,lease_until=NULL WHERE job_type='EXTRACT' AND state IN ('PENDING','RUNNING');
UPDATE agent_outbox SET status='SENT',sent_at=UTC_TIMESTAMP(6) WHERE destination='MEMORY_EXTRACT' AND status IN ('PENDING','INFLIGHT');

UPDATE agent_memory_job SET state='CANCELLED',error_code='MEMORY_POLICY_REPLACED',lease_token=NULL,lease_until=NULL WHERE job_type='CONSOLIDATE' AND state IN ('PENDING','RUNNING');
UPDATE agent_outbox SET status='SENT',sent_at=UTC_TIMESTAMP(6) WHERE destination='MEMORY_CONSOLIDATE' AND status IN ('PENDING','INFLIGHT');
