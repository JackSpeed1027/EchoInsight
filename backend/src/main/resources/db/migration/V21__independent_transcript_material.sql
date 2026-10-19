-- 文件职责：字幕作为独立不可变材料附着作品，评论快照和旧上下文JSON不回填。
CREATE TABLE project_transcript_material (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_id VARCHAR(128) NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL,
 source_description VARCHAR(200) NOT NULL,
 format_name VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 raw_text MEDIUMTEXT NOT NULL,
 raw_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 segments_json MEDIUMTEXT NOT NULL,
 segments_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 segment_count INT NOT NULL,
 duration_ms BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_transcript_request(project_id,request_key),
 KEY ix_transcript_content(project_id,created_at,id),
 CONSTRAINT fk_transcript_project FOREIGN KEY(project_id) REFERENCES business_project(id),
 CONSTRAINT fk_transcript_snapshot FOREIGN KEY(snapshot_id) REFERENCES data_snapshot(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
