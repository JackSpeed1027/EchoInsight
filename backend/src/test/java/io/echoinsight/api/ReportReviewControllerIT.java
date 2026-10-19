/* 文件职责：使用独立合成来源验证核验幂等、追加修订、引用归属及并发冲突。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.ingestion.*;
import io.echoinsight.orchestration.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 所有写入仅使用本测试生成的来源和任务，保留用户与此前测试数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportReviewControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired JobStore jobs;
  @Autowired AnalysisWorker worker;
  @Autowired JdbcTemplate db;

  /** 生成包含一条可分类评论和一条可替代原文的全新测试范围。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content(
            "local", "SYNTHETIC", "review-it-" + UUID.randomUUID(), "test", "人工核验合成测试", time),
        List.of(
            new ImportDocument.Comment("positive", "p", null, "线程池与拒绝策略的例子很清楚，帮助我理解了。", time),
            new ImportDocument.Comment("alternative", "a", null, "用于核验的未知原文", time)),
        List.of());
  }

  /** 复用当前任务执行器发布旧格式报告，不预先写入 findingId。 */
  private String report() {
    var job = jobs.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    worker.runOne(job.id());
    return jobs.reportForJob(job.id()).id();
  }

  /** 读取核验目录，生成由服务器定义的稳定结论 ID。 */
  private JsonNode view(String report) {
    return http.getForObject("/api/v1/reports/" + report + "/reviews", JsonNode.class);
  }

  /** 创建提交内容；不同版本必须显式携带新的 expectedRevision。 */
  private Map<String, Object> body(int revision, String reason, List<String> ids) {
    return Map.of(
        "verdict",
        "SUPPORTED",
        "reason",
        reason,
        "correctedEvidenceIds",
        ids,
        "expectedRevision",
        revision);
  }

  /** 经真实 HTTP 提交，验证校验与状态码而非绕过控制器。 */
  private ResponseEntity<JsonNode> save(
      String report, String finding, String key, Map<String, Object> body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.postForEntity(
        "/api/v1/reports/" + report + "/findings/" + finding + "/reviews",
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  /** 1、提交与重放；2、追加修订；3、重放旧请求；4、确认模型报告字节不变。 */
  @Test
  void reviewsAppendAndOldRequestsReplayWithoutChangingReport() {
    String report = report(), original = jobs.report(report).json();
    String finding = view(report).path("findings").get(0).path("findingId").asText();
    String key = UUID.randomUUID().toString();
    var first = save(report, finding, key, body(0, "原文支持演示分类", List.of("alternative")));
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(
            save(report, finding, key, body(0, "原文支持演示分类", List.of("alternative")))
                .getBody()
                .path("review")
                .path("id"))
        .isEqualTo(first.getBody().path("review").path("id"));
    assertThat(
            save(report, finding, UUID.randomUUID().toString(), body(1, "补充核验意见", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(201);
    assertThat(
            save(report, finding, key, body(0, "原文支持演示分类", List.of("alternative")))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            save(report, finding, UUID.randomUUID().toString(), body(0, "旧页面覆盖", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    var findingView = view(report).path("findings").get(0);
    assertThat(findingView.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(findingView.path("history").get(1).path("reason").asText()).isEqualTo("原文支持演示分类");
    assertThat(jobs.report(report).json()).isEqualTo(original);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM report_review WHERE report_id=?", Integer.class, report))
        .isEqualTo(2);
  }

  /** 不存在资源、跨快照证据、重复引用与非法表单不能写入。 */
  @Test
  void invalidResourcesAndEvidenceLeaveNoReviewRows() {
    String report = report(),
        finding = view(report).path("findings").get(0).path("findingId").asText();
    assertThat(
            save(report, "f_missing", UUID.randomUUID().toString(), body(0, "理由", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            save(
                    UUID.randomUUID().toString(),
                    finding,
                    UUID.randomUUID().toString(),
                    body(0, "理由", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            save(report, finding, UUID.randomUUID().toString(), body(0, "理由", List.of("outside")))
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            save(
                    report,
                    finding,
                    UUID.randomUUID().toString(),
                    body(0, "理由", List.of("alternative", "alternative")))
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            save(report, finding, UUID.randomUUID().toString(), body(0, " ", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            save(report, finding, "invalid key", body(0, "理由", List.of())).getStatusCode().value())
        .isEqualTo(400);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM report_review WHERE report_id=?", Integer.class, report))
        .isZero();
  }

  /** 同一请求键不能在不同内容、结论或报告中被重新使用。 */
  @Test
  void reusedKeyWithChangedPayloadOrReportConflicts() {
    String report = report(),
        finding = view(report).path("findings").get(0).path("findingId").asText(),
        key = UUID.randomUUID().toString();
    assertThat(save(report, finding, key, body(0, "第一份意见", List.of())).getStatusCode().value())
        .isEqualTo(201);
    assertThat(save(report, finding, key, body(0, "另一份意见", List.of())).getStatusCode().value())
        .isEqualTo(409);
    String other = report(),
        otherFinding = view(other).path("findings").get(0).path("findingId").asText();
    assertThat(save(other, otherFinding, key, body(0, "第一份意见", List.of())).getStatusCode().value())
        .isEqualTo(409);
  }

  /** 两个旧页面同时提交时仅一方追加成功，另一方得到可解释的版本冲突。 */
  @Test
  void simultaneousEditsCannotOverwriteEachOther() throws Exception {
    String report = report(),
        finding = view(report).path("findings").get(0).path("findingId").asText();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      Callable<Integer> task =
          () -> {
            ready.countDown();
            if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
            return save(report, finding, UUID.randomUUID().toString(), body(0, "并发意见", List.of()))
                .getStatusCode()
                .value();
          };
      var first = pool.submit(task);
      var second = pool.submit(task);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(view(report).path("findings").get(0).path("reviewCount").asInt()).isEqualTo(1);
  }

  /** 历史量受限，默认读取没有写入副作用，非法 limit 被拒绝。 */
  @Test
  void boundedHistoryAndReadOnlyDirectoryAreExplicit() {
    String report = report(),
        finding = view(report).path("findings").get(0).path("findingId").asText();
    save(report, finding, UUID.randomUUID().toString(), body(0, "第一次", List.of()));
    save(report, finding, UUID.randomUUID().toString(), body(1, "第二次", List.of("alternative")));
    var result =
        http.getForObject("/api/v1/reports/" + report + "/reviews?limit=1", JsonNode.class)
            .path("findings")
            .get(0);
    assertThat(result.path("history").size()).isEqualTo(1);
    assertThat(result.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(result.path("latestCorrectedEvidence").get(0).path("text").asText())
        .isEqualTo("用于核验的未知原文");
    assertThat(
            http.getForEntity("/api/v1/reports/" + report + "/reviews?limit=101", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM report_review WHERE report_id=?", Integer.class, report))
        .isEqualTo(2);
  }

  /** 去重前行数与快照数采用明确别名，旧字段含义保持兼容。 */
  @Test
  void countAliasesDistinguishRawRowsFromSnapshotComments() {
    var source = input();
    var receipt =
        imports.ingest(
            new ImportDocument(
                source.schemaVersion(),
                source.content(),
                List.of(
                    source.comments().getFirst(),
                    source.comments().getFirst(),
                    source.comments().getLast()),
                List.of()));
    var uploaded = http.getForObject("/api/v1/imports/" + receipt.id(), JsonNode.class);
    assertThat(uploaded.path("rawRecordCount").asInt()).isEqualTo(3);
    assertThat(uploaded.path("snapshotCommentCount").asInt()).isEqualTo(2);
    var job = jobs.create(receipt.snapshotId(), UUID.randomUUID().toString());
    worker.runOne(job.id());
    var report =
        http.getForObject("/api/v1/reports/" + jobs.reportForJob(job.id()).id(), JsonNode.class);
    assertThat(report.path("coverage").path("snapshotCommentCount").asInt()).isEqualTo(2);
    assertThat(report.path("coverage").path("importedComments").asInt()).isEqualTo(2);
  }
}
