-- 文件职责：为任务追加执行计划、批次结果与模拟预算账本，不清理或重写旧报告。
ALTER TABLE analysis_job ADD COLUMN execution_plan_json LONGTEXT NULL;
CREATE TABLE model_budget (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 limit_units BIGINT NOT NULL,
 reserved_units BIGINT NOT NULL DEFAULT 0,
 settled_units BIGINT NOT NULL DEFAULT 0,
 unit VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_budget_job FOREIGN KEY (job_id) REFERENCES analysis_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE model_batch (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_index INT NOT NULL,
 input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(24) NOT NULL,
 current_call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 result_json LONGTEXT NULL,
 PRIMARY KEY (job_id,batch_index),
 CONSTRAINT fk_batch_job FOREIGN KEY (job_id) REFERENCES analysis_job(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE model_call (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 batch_index INT NOT NULL,
 owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reserved_units BIGINT NOT NULL,
 settled_units BIGINT NULL,
 input_tokens INT NULL,
 output_tokens INT NULL,
 request_id VARCHAR(128) NULL,
 error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 KEY idx_call_job (job_id,batch_index,created_at),
 CONSTRAINT fk_call_batch FOREIGN KEY (job_id,batch_index) REFERENCES model_batch(job_id,batch_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
