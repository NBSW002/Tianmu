-- Apply before the new backend/frontend. Existing published media is unchanged.
CREATE TABLE IF NOT EXISTS upload_session (
    upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    file_hash CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    file_type VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    file_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    cleaned_at DATETIME(6) NULL,
    KEY ix_upload_owner(owner_id, file_hash, created_at),
    KEY ix_upload_cleanup(expires_at, cleaned_at),
    CONSTRAINT ck_upload_status CHECK (status IN ('UPLOADING','MERGING','COMPLETE')),
    CONSTRAINT ck_upload_size CHECK (size_bytes BETWEEN 1 AND 3221225472)
);
