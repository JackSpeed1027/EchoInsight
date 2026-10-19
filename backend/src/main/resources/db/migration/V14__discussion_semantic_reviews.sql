-- 文件职责：追加保存讨论语义核验，不修改模型产物，也不把人工意见自动变成金标准。
CREATE TABLE discussion_semantic_review (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 artifact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 review_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_semantic_review_key(project_id,request_key),
 KEY ix_semantic_review_history(request_id,created_at,id),
 CONSTRAINT fk_semantic_review_project FOREIGN KEY(project_id) REFERENCES business_project(id),
 CONSTRAINT fk_semantic_review_request FOREIGN KEY(request_id) REFERENCES analysis_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
