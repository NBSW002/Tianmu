CREATE TABLE IF NOT EXISTS user_video_history (
  user_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  position_seconds DOUBLE NOT NULL DEFAULT 0,
  watched_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(user_id,video_id),
  INDEX ix_history_recent(user_id,watched_at,video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
