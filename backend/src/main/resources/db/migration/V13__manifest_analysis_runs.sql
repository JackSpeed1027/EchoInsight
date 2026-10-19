-- 文件职责：保存冻结集合的一次分析要求和已有单资料请求关联，不增加第二套任务调度器。
CREATE TABLE manifest_analysis_run (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  manifest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  instruction VARCHAR(1000) NOT NULL,
  execution_plan_json LONGTEXT NOT NULL,
  budget_unit VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  aggregate_budget_limit BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_manifest_run_request (project_id,request_key),
  KEY ix_manifest_run_history (project_id,manifest_id,created_at,id),
  CONSTRAINT fk_manifest_run_project FOREIGN KEY (project_id) REFERENCES business_project(id),
  CONSTRAINT fk_manifest_run_manifest FOREIGN KEY (manifest_id) REFERENCES input_manifest(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE manifest_analysis_item (
  run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ordinal_no INT NOT NULL,
  snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  analysis_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (run_id,ordinal_no),
  UNIQUE KEY uk_manifest_run_child (analysis_request_id),
  CONSTRAINT fk_manifest_analysis_run FOREIGN KEY (run_id) REFERENCES manifest_analysis_run(id),
  CONSTRAINT fk_manifest_analysis_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id),
  CONSTRAINT fk_manifest_analysis_request FOREIGN KEY (analysis_request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
