-- 文件职责：追加人工核对的不可变输入与意见版本；不复制分析任务、预算或执行状态。
CREATE TABLE quality_review_input (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json LONGTEXT NOT NULL,
 input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_quality_input_key (project_id,request_key),
 KEY ix_quality_input_project (project_id,created_at,id),
 CONSTRAINT fk_quality_input_project FOREIGN KEY (project_id) REFERENCES business_project(id),
 CONSTRAINT fk_quality_input_request FOREIGN KEY (request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 草稿和提交都是追加快照；提交后继续编辑会生成新记录，不改写曾用于对照的判断。
CREATE TABLE quality_review_record (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 input_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reviewer VARCHAR(80) NOT NULL,
 record_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 record_json LONGTEXT NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_quality_record_key (input_id,request_key),
 KEY ix_quality_record_reader (input_id,reviewer,created_at,id),
 CONSTRAINT fk_quality_record_input FOREIGN KEY (input_id) REFERENCES quality_review_input(id),
 CONSTRAINT fk_quality_record_previous FOREIGN KEY (previous_id) REFERENCES quality_review_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
