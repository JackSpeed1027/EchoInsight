-- 文件职责：独立内容版本和任务输入绑定；沿用原任务/账本/产物，不伪造评论或删除历史。
CREATE TABLE content_material (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 previous_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 version_depth INT NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(120) NOT NULL,
 source_description VARCHAR(200) NOT NULL,
 format_name VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 raw_text LONGTEXT NOT NULL,
 raw_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 segments_json LONGTEXT NOT NULL,
 segments_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 segment_count INT NOT NULL,
 text_chars INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_content_material_key (project_id,request_key),
 KEY ix_content_material_list (project_id,created_at,id),
 CONSTRAINT fk_content_material_project FOREIGN KEY (project_id) REFERENCES business_project(id),
 CONSTRAINT fk_content_material_previous FOREIGN KEY (previous_id) REFERENCES content_material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 独立内容没有评论快照；只放宽关联可空性，既有快照/请求外键仍保留，不回填历史。
ALTER TABLE analysis_job MODIFY snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE analysis_artifact
 MODIFY request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 MODIFY snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;

CREATE TABLE content_analysis_input (
 job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 material_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 instruction VARCHAR(1000) NOT NULL,
 selection_json LONGTEXT NOT NULL,
 selection_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_content_analysis_request (project_id,request_key),
 KEY ix_content_analysis_project (project_id,created_at,job_id),
 CONSTRAINT fk_content_input_job FOREIGN KEY (job_id) REFERENCES analysis_job(id),
 CONSTRAINT fk_content_input_project FOREIGN KEY (project_id) REFERENCES business_project(id),
 CONSTRAINT fk_content_input_material FOREIGN KEY (material_id) REFERENCES content_material(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
