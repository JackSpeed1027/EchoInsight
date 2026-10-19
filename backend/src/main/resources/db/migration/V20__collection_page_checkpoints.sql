-- 文件职责：旧任务保留限量语义；新可见范围采集追加成功页事实，复用批次状态、租约和显式重试。
ALTER TABLE source_collection_batch
  ADD COLUMN collection_mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'LIMITED';

CREATE TABLE source_collection_page (
  batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ordinal_no INT NOT NULL,
  page_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  page_json MEDIUMTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(batch_id, page_key),
  UNIQUE KEY uk_collection_page_order(batch_id, ordinal_no),
  CONSTRAINT fk_collection_page_batch FOREIGN KEY(batch_id) REFERENCES source_collection_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
