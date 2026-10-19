/* 文件职责：验证任务历史的分页、筛选、报告关联以及只读边界。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.ingestion.*;
import io.echoinsight.orchestration.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 所有测试均保留原有数据，只增加独立合成来源并核对结果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobHistoryControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired JobStore store;
  @Autowired AnalysisWorker worker;
  @Autowired JdbcTemplate db;

  /** 构造独立作品快照和待执行任务，避免依赖已有数据数量。 */
  private JobStore.Job create() {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    var input =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content(
                "local", "SYNTHETIC", "history-it-" + UUID.randomUUID(), "test", "历史查询合成测试", time),
            List.of(new ImportDocument.Comment("a", "1", null, "历史查询专用合成评论", time)),
            List.of());
    return store.create(imports.ingest(input).snapshotId(), UUID.randomUUID().toString());
  }

  /** 同一创建时间也要按 ID 稳定分页，不能在相邻页重复或遗漏测试记录。 */
  @Test
  void paginationIsStableWhenCreationTimesMatch() {
    var jobs = List.of(create(), create(), create());
    // 只调整本测试新建的三条记录，以验证相同时间下的第二排序键。
    db.update(
        "UPDATE analysis_job SET created_at=CURRENT_TIMESTAMP(6) WHERE id IN (?,?,?)",
        jobs.get(0).id(),
        jobs.get(1).id(),
        jobs.get(2).id());
    var ids = jobs.stream().map(JobStore.Job::id).sorted(Comparator.reverseOrder()).toList();
    for (int i = 0; i < 3; i++) {
      var page =
          http.getForObject(
              "/api/v1/analysis-jobs?page=" + (i + 1) + "&size=1&status=PENDING", JsonNode.class);
      assertThat(page.path("items").size()).isEqualTo(1);
      assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(ids.get(i));
      assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(3);
    }
  }

  /** 状态筛选只返回匹配任务，同时验证标题与报告关联正确。 */
  @Test
  void completedHistoryIncludesOriginalReportAndTitle() {
    var job = create();
    worker.runOne(job.id());
    var page = http.getForObject("/api/v1/analysis-jobs?status=SUCCESS&size=50", JsonNode.class);
    JsonNode target = null;
    for (var item : page.path("items")) {
      assertThat(item.path("status").asText()).isEqualTo("SUCCESS");
      if (item.path("id").asText().equals(job.id())) target = item;
    }
    assertThat(target).isNotNull();
    assertThat(target.path("title").asText()).isEqualTo("历史查询合成测试");
    assertThat(target.path("snapshotId").asText()).isEqualTo(job.snapshotId());
    assertThat(target.path("reportId").asText()).isEqualTo(store.reportForJob(job.id()).id());
  }

  /** 查看列表和越界空页不应执行任务、创建报告或修改尝试次数。 */
  @Test
  void readingHistoryHasNoExecutionSideEffects() {
    var job = create();
    var page = http.getForObject("/api/v1/analysis-jobs?page=10000&size=50", JsonNode.class);
    assertThat(page.path("items")).isEmpty();
    assertThat(page.path("total").asLong()).isGreaterThan(0);
    http.getForObject("/api/v1/analysis-jobs", JsonNode.class);
    assertThat(store.get(job.id()).status()).isEqualTo("PENDING");
    assertThat(store.get(job.id()).attempts()).isZero();
    assertThat(store.reportForJob(job.id())).isNull();
  }

  /** 页大小与状态非法时返回 400，而不是全量读取或忽略条件。 */
  @Test
  void invalidPaginationAndStatusReturn400() {
    for (var query :
        List.of(
            "page=0", "page=-1", "page=10001", "size=0", "size=51", "status=BOGUS", "page=abc")) {
      assertThat(
              http.getForEntity("/api/v1/analysis-jobs?" + query, String.class)
                  .getStatusCode()
                  .value())
          .isEqualTo(400);
    }
  }
}
