-- 文件职责：新增追加式核验事件，不改写既有报告、快照和历史数据。
CREATE TABLE report_review (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 finding_id CHAR(66) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reviewer_id VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision INT NOT NULL,
 verdict VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 corrected_evidence_json JSON NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_review_request (request_key),
 UNIQUE KEY uk_review_revision (report_id,finding_id,reviewer_id,revision),
 CONSTRAINT fk_review_report FOREIGN KEY (report_id) REFERENCES analysis_report(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
