-- 文件职责：追加不可变的多作品输入清单；每个作品只绑定一个明确快照，不追随最新版本。
CREATE TABLE input_manifest (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(120) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_manifest_request (project_id,request_key),
  KEY ix_manifest_list (project_id,created_at,id),
  CONSTRAINT fk_manifest_project FOREIGN KEY (project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE input_manifest_item (
  manifest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ordinal_no INT NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_content_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(200) NOT NULL,
  comment_count INT NOT NULL,
  source_completeness VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  source_reported_comments INT NULL,
  document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (manifest_id,ordinal_no),
  UNIQUE KEY uk_manifest_work (manifest_id,source_type,source_content_id),
  CONSTRAINT fk_manifest_item_manifest FOREIGN KEY (manifest_id) REFERENCES input_manifest(id),
  CONSTRAINT fk_manifest_item_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id),
  CONSTRAINT fk_manifest_item_collection FOREIGN KEY (collection_id) REFERENCES source_collection_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
