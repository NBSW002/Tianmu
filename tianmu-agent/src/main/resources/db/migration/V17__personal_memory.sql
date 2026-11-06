-- Explicit MANUAL memories only. Conversation-derived sources and model injection are not enabled.
CREATE TABLE agent_memory_account (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  memory_seq BIGINT NOT NULL DEFAULT 0,
  consent_epoch BIGINT NOT NULL DEFAULT 0,
  erase_epoch BIGINT NOT NULL DEFAULT 0,
  settings_version BIGINT NOT NULL DEFAULT 0,
  mode VARCHAR(16) NOT NULL DEFAULT 'ON',
  PRIMARY KEY (tenant_id, owner_user_id),
  CONSTRAINT ck_memory_mode CHECK (mode IN ('ON','READ_ONLY','OFF'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_item (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  current_version BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id, owner_user_id, memory_id),
  KEY ix_memory_item_status (tenant_id,owner_user_id,status),
  CONSTRAINT ck_memory_status CHECK (status IN ('ACTIVE','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_source (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_kind VARCHAR(32) NOT NULL,
  declaration TEXT NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  created_at DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (tenant_id,owner_user_id,source_id),
  CONSTRAINT ck_memory_source_kind CHECK (source_kind='MANUAL'),
  CONSTRAINT ck_memory_source_status CHECK (status IN ('ACTIVE','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_revision (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  valid_from_seq BIGINT NOT NULL,
  valid_to_seq BIGINT NULL,
  scope_type VARCHAR(16) NOT NULL,
  scope_key VARCHAR(64) NOT NULL,
  kind VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  expires_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,memory_id,version),
  KEY ix_memory_revision_scope (tenant_id,owner_user_id,scope_type,scope_key,valid_from_seq),
  KEY ix_memory_revision_sequence (tenant_id,owner_user_id,valid_from_seq),
  CONSTRAINT fk_memory_revision_item FOREIGN KEY (tenant_id,owner_user_id,memory_id)
    REFERENCES agent_memory_item (tenant_id,owner_user_id,memory_id),
  CONSTRAINT ck_memory_scope CHECK ((scope_type='USER' AND scope_key='self') OR scope_type='VIDEO'),
  CONSTRAINT ck_memory_kind CHECK (kind IN ('PREFERENCE','GOAL')),
  CONSTRAINT ck_memory_revision_range CHECK (version>0 AND valid_from_seq>0 AND (valid_to_seq IS NULL OR valid_to_seq>valid_from_seq))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_dependency (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,memory_id,version,source_id),
  KEY ix_memory_dependency_source (tenant_id,owner_user_id,source_id),
  CONSTRAINT fk_memory_dependency_revision FOREIGN KEY (tenant_id,owner_user_id,memory_id,version)
    REFERENCES agent_memory_revision (tenant_id,owner_user_id,memory_id,version),
  CONSTRAINT fk_memory_dependency_source FOREIGN KEY (tenant_id,owner_user_id,source_id)
    REFERENCES agent_memory_source (tenant_id,owner_user_id,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_keyword (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL,
  term VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,term,memory_id,version),
  KEY ix_memory_keyword_item (tenant_id,owner_user_id,memory_id),
  CONSTRAINT fk_memory_keyword_revision FOREIGN KEY (tenant_id,owner_user_id,memory_id,version)
    REFERENCES agent_memory_revision (tenant_id,owner_user_id,memory_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_mutation (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  receipt_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_memory_tombstone (
  tenant_id VARCHAR(64) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL,
  target_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  erase_epoch BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,owner_user_id,target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
