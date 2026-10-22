-- 文件职责：只追加资料恢复回执；不修改旧项目/资料/分析/费用或人工意见。
CREATE TABLE project_material_restore (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 workspace_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 archive_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 receipt_json LONGTEXT NOT NULL,
 receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_material_restore_request(workspace_scope,request_key),
 UNIQUE KEY uk_material_restore_project(project_id),
 CONSTRAINT fk_material_restore_project FOREIGN KEY(project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
