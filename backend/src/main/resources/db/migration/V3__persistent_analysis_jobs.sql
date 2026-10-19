-- 文件职责：新增任务、步骤、执行历史和报告；不修改既有导入与快照数据。
CREATE TABLE analysis_job (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(24) NOT NULL,
 model_version VARCHAR(64) NOT NULL,
 prompt_version VARCHAR(64) NOT NULL,
 taxonomy_version VARCHAR(64) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 recoveries INT NOT NULL DEFAULT 0,
 owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 last_error_code VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_job_request (request_key),
 KEY idx_job_dispatch (status,lease_until),
 CONSTRAINT fk_job_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE job_step (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 step_key VARCHAR(32) NOT NULL,
 ordinal_no INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 result_json LONGTEXT NULL,
 PRIMARY KEY (job_id,step_key),
 CONSTRAINT fk_step_job FOREIGN KEY (job_id) REFERENCES analysis_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE job_attempt (
 token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 attempt_no INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 error_code VARCHAR(64) NULL,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at DATETIME(6) NULL,
 UNIQUE KEY uk_job_attempt (job_id,attempt_no),
 CONSTRAINT fk_attempt_job FOREIGN KEY (job_id) REFERENCES analysis_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE analysis_report (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version INT NOT NULL,
 report_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_report_job (job_id),
 CONSTRAINT fk_report_job FOREIGN KEY (job_id) REFERENCES analysis_job(id),
 CONSTRAINT fk_report_snapshot FOREIGN KEY (snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
