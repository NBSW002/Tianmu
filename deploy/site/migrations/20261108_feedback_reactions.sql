-- Current effective feedback reactions; video likes remain in their existing independent table.
CREATE TABLE IF NOT EXISTS feedback_reaction (
  source VARCHAR(16) NOT NULL,
  item_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (source,item_id,actor_id),
  INDEX idx_feedback_reaction_actor(actor_id,source,item_id),
  CONSTRAINT chk_feedback_reaction_source CHECK (source IN ('COMMENT','DANMAKU'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
