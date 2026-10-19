-- 文件职责：记录用户从冻结候选中明确选择的采集范围；子批次沿用原采集状态机和租约。
CREATE TABLE collection_group (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 project_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 discovery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 selection_json TEXT NOT NULL,
 root_limit INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_collection_group_key(project_id,request_key),
 CONSTRAINT fk_collection_group_project FOREIGN KEY(project_id) REFERENCES business_project(id),
 CONSTRAINT fk_collection_group_discovery FOREIGN KEY(discovery_id) REFERENCES creator_discovery(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE collection_group_item (
 group_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal_no INT NOT NULL,
 bvid VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 collection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(group_id,ordinal_no),
 UNIQUE KEY uk_collection_group_child(collection_id),
 CONSTRAINT fk_collection_group_item_parent FOREIGN KEY(group_id) REFERENCES collection_group(id),
 CONSTRAINT fk_collection_group_item_batch FOREIGN KEY(collection_id) REFERENCES source_collection_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
