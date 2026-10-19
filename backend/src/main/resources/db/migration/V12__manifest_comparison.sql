-- 文件职责：追加保存明确集合之间的差异结果；缺失只标未知，不删除旧评论或快照。
CREATE TABLE manifest_comparison (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  base_manifest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_manifest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  algorithm_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_json LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_comparison_request (project_id,request_key),
  KEY ix_comparison_history (project_id,created_at,id),
  CONSTRAINT fk_comparison_project FOREIGN KEY (project_id) REFERENCES business_project(id),
  CONSTRAINT fk_comparison_base FOREIGN KEY (base_manifest_id) REFERENCES input_manifest(id),
  CONSTRAINT fk_comparison_target FOREIGN KEY (target_manifest_id) REFERENCES input_manifest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
