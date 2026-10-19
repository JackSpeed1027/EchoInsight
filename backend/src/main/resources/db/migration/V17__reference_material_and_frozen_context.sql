-- 文件职责：追加项目参考文本与单次请求的冻结背景；不修改旧快照、旧请求或模型产物。
CREATE TABLE project_reference_material (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL,
 source_description VARCHAR(200) NOT NULL,
 body_text MEDIUMTEXT NOT NULL,
 body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_reference_material_key(project_id,request_key),
 KEY ix_reference_material_history(project_id,created_at,id),
 CONSTRAINT fk_reference_material_project FOREIGN KEY(project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE analysis_request_context (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 context_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 context_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_analysis_context_request FOREIGN KEY(request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
