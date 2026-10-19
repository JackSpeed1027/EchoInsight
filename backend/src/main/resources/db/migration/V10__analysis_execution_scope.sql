-- 文件职责：给分析调度增加服务器执行范围；兼容存量任务，不删除、重建或改写其计划/结果。
ALTER TABLE analysis_job
  ADD COLUMN execution_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'local',
  ADD KEY ix_job_execution_scope (execution_scope,status,lease_until,created_at);
