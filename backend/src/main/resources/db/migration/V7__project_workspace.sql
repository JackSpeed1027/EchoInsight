-- 文件职责：新增项目与明确快照挂载；不修改旧表、迁移或用户历史。
CREATE TABLE business_project (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  workspace_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(120) NOT NULL,
  analysis_goal VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_project_request (workspace_scope, request_key),
  KEY ix_project_list (workspace_scope, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE project_snapshot (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  added_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_project_snapshot (project_id, snapshot_id),
  KEY ix_project_snapshot_list (project_id, id),
  CONSTRAINT fk_project_snapshot_project FOREIGN KEY (project_id) REFERENCES business_project(id),
  CONSTRAINT fk_project_snapshot_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
