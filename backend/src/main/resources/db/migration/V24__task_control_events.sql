-- 文件职责：只追加任务控制审计；任务、租约、页和费用仍使用原表，历史数据不回填。
CREATE TABLE task_control_event (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 next_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_task_control_history (project_id,task_kind,task_id,created_at,id),
 CONSTRAINT fk_task_control_project FOREIGN KEY (project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 列表只读取任务摘要；不建立另一份任务或复制模型/采集状态。
CREATE INDEX ix_job_scope_created ON analysis_job(execution_scope,created_at,id);
