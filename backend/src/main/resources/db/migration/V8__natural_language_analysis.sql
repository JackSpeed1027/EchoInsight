-- 文件职责：为自然语言分析追加请求、任务类型和类型化产物，不改写既有任务、报告或项目资料。
ALTER TABLE analysis_job
  ADD COLUMN job_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'FEEDBACK',
  ADD COLUMN analysis_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uk_job_analysis_request (analysis_request_id);

CREATE TABLE analysis_request (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  instruction VARCHAR(1000) NOT NULL,
  intent VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  include_assessment TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_analysis_request_key (project_id, request_key),
  UNIQUE KEY uk_analysis_request_job (job_id),
  KEY ix_analysis_request_list (project_id, created_at, id),
  CONSTRAINT fk_analysis_request_project FOREIGN KEY (project_id) REFERENCES business_project(id),
  CONSTRAINT fk_analysis_request_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE analysis_job
  ADD CONSTRAINT fk_job_analysis_request
  FOREIGN KEY (analysis_request_id) REFERENCES analysis_request(id);

ALTER TABLE analysis_request
  ADD CONSTRAINT fk_analysis_request_job
  FOREIGN KEY (job_id) REFERENCES analysis_job(id);

CREATE TABLE analysis_artifact (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_json LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_analysis_artifact_request (request_id),
  UNIQUE KEY uk_analysis_artifact_job (job_id),
  CONSTRAINT fk_analysis_artifact_request FOREIGN KEY (request_id) REFERENCES analysis_request(id),
  CONSTRAINT fk_analysis_artifact_job FOREIGN KEY (job_id) REFERENCES analysis_job(id),
  CONSTRAINT fk_analysis_artifact_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
