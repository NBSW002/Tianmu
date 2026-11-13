-- Verify index is absent before applying to an existing database.
CREATE INDEX idx_bullet_video_playback ON bullet(video_id,playback_time,bullet_id);
