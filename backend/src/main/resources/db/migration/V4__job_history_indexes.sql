-- 文件职责：给时间倒序、状态筛选的历史查询增加索引，不修改任何任务和报告记录。
CREATE INDEX idx_job_history_created ON analysis_job(created_at,id);
CREATE INDEX idx_job_history_status ON analysis_job(status,created_at,id);
