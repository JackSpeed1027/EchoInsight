-- 文件职责：只追加原分析请求之间的追问导航与冻结身份；不修改任务状态、产物或旧请求。
CREATE TABLE analysis_continuation (
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 parent_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 root_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 anchor_comment_id VARCHAR(128) NOT NULL,
 depth INT NOT NULL,
 parent_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 history_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_continuation_root(root_request_id,created_at,request_id),
 CONSTRAINT fk_continuation_child FOREIGN KEY(request_id) REFERENCES analysis_request(id),
 CONSTRAINT fk_continuation_parent FOREIGN KEY(parent_request_id) REFERENCES analysis_request(id),
 CONSTRAINT fk_continuation_root FOREIGN KEY(root_request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
