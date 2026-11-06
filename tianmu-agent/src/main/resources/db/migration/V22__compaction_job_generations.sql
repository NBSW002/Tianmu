ALTER TABLE agent_memory_job ADD generation_key CHAR(64) CHARACTER SET ascii NOT NULL DEFAULT '',
  DROP INDEX uk_memory_extract_source,
  ADD UNIQUE KEY uk_memory_job_generation(tenant_id,owner_user_id,run_id,model_version,job_type,generation_key);
