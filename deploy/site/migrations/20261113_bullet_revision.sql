-- Apply before starting the new business version.
ALTER TABLE video_stats ADD COLUMN bullet_revision BIGINT NOT NULL DEFAULT 0;
