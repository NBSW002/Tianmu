CREATE TABLE agent_video_index (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  video_id BIGINT NOT NULL,
  document LONGTEXT NOT NULL,
  document_hash CHAR(64) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, owner_user_id, video_id),
  KEY ix_video_index_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
