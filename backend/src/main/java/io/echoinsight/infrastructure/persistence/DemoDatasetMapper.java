package io.echoinsight.infrastructure.persistence;

import io.echoinsight.ingestion.DemoDataset;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DemoDatasetMapper {
  @Select(
      "SELECT id, title, comment_count, transcript_segment_count, synthetic FROM demo_dataset ORDER"
          + " BY id")
  List<DemoDataset> findAll();
}
