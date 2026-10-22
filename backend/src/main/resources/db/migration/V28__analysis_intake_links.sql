-- 文件职责：仅保存入口原话及原采集/分析的一次绑定；没有第二份任务状态、租约或预算。
CREATE TABLE analysis_intake (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 execution_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_json LONGTEXT NOT NULL,
 input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 collection_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 analysis_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 analysis_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_intake_request(project_id,execution_scope,request_key),
 UNIQUE KEY uk_intake_collection(collection_id),
 UNIQUE KEY uk_intake_analysis(analysis_request_id),
 KEY ix_intake_project(project_id,execution_scope,created_at,id),
 CONSTRAINT fk_intake_project FOREIGN KEY(project_id) REFERENCES business_project(id),
 CONSTRAINT fk_intake_snapshot FOREIGN KEY(snapshot_id) REFERENCES data_snapshot(id),
 CONSTRAINT fk_intake_collection FOREIGN KEY(collection_id) REFERENCES source_collection_batch(id),
 CONSTRAINT fk_intake_analysis FOREIGN KEY(analysis_request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
