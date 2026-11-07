ALTER TABLE agent_video_content_cache MODIFY COLUMN expires_at DATETIME(6) NULL;
ALTER TABLE agent_video_content_cache ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
CREATE INDEX ix_video_cache_updated ON agent_video_content_cache(updated_at);
UPDATE agent_video_content_cache SET expires_at=NULL;
