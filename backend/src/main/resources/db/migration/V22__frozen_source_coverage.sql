-- 文件职责：只给新请求冻结来源诊断；旧请求不倒填，继续使用原请求/任务/报告状态。
CREATE TABLE analysis_request_source_coverage (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 coverage_json TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_source_coverage_request FOREIGN KEY(request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
