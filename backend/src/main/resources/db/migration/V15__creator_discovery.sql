-- 文件职责：保存UP主的有界候选发现、租约和不可变结果；不复用评论快照表示候选元数据。
CREATE TABLE creator_discovery (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 owner_mid VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 keyword VARCHAR(80) NOT NULL,
 candidate_limit INT NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(6) NULL,
 attempts INT NOT NULL DEFAULT 0,
 result_json LONGTEXT NULL,
 raw_json MEDIUMTEXT NULL,
 raw_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_creator_discovery_key(project_id,request_key),
 KEY ix_discovery_pending(status,lease_until,created_at),
 CONSTRAINT fk_creator_discovery_project FOREIGN KEY(project_id) REFERENCES business_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
