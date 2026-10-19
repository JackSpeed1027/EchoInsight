CREATE TABLE demo_dataset (
  id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  comment_count INT NOT NULL,
  transcript_segment_count INT NOT NULL,
  synthetic BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT chk_demo_counts CHECK (comment_count >= 0 AND transcript_segment_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO demo_dataset (id, title, comment_count, transcript_segment_count, synthetic) VALUES
('threadpool', '线程池与拒绝策略', 100, 5, TRUE),
('sql-index', 'SQL 索引与执行计划', 100, 5, TRUE);
