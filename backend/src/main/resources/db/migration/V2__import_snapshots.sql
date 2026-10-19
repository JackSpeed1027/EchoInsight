CREATE TABLE content_item (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  source_type VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  source_content_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_content_source (source_type, source_content_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE comment_revision (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  content_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_comment_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  revision_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json LONGTEXT NOT NULL,
  UNIQUE KEY uk_comment_revision (content_id, source_comment_id, revision_hash),
  CONSTRAINT fk_revision_content FOREIGN KEY (content_id) REFERENCES content_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE data_snapshot (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  content_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  document_json LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_snapshot_input (content_id, input_hash),
  CONSTRAINT fk_snapshot_content FOREIGN KEY (content_id) REFERENCES content_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ingestion_import (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  content_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(24) NOT NULL,
  imported_comments INT NOT NULL,
  eligible_comments INT NOT NULL,
  duplicate_comments INT NOT NULL,
  new_revisions INT NOT NULL,
  snapshot_reused BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_import_content FOREIGN KEY (content_id) REFERENCES content_item(id),
  CONSTRAINT fk_import_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
