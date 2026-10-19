-- 文件职责：追加不可变命题规格；保留所有旧请求、快照、调用账本与产物。
CREATE TABLE analysis_request_specification (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 specification_json TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_specification_request FOREIGN KEY(request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
