ALTER TABLE agent_memory_projection DROP INDEX uk_projection_range,
  ADD UNIQUE KEY uk_projection_range (tenant_id,owner_user_id,session_id,from_seq,through_seq,source_fingerprint,model_version,consent_epoch,erase_epoch);
