-- 文件职责：只追加完整命题请求的不可变分片清单，不回填旧请求、任务或产物。
CREATE TABLE analysis_request_scope (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 manifest_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_request_scope_request FOREIGN KEY(request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
