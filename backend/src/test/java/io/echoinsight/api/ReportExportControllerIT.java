/* 文件职责：通过本机 MySQL 和 HTTP 验证导出协议、历史原文归属、核验附录及只读语义。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportService;
import io.echoinsight.orchestration.AnalysisWorker;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.review.ReviewService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只追加本测试独立合成来源；不开付费供应商，不删除历史记录。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"echoinsight.worker.enabled=false", "echoinsight.model.enabled=false"})
class ReportExportControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired JobStore jobs;
  @Autowired AnalysisWorker worker;
  @Autowired ReviewService reviews;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  /** 包含原引用、可选替代原文及不应导出的无关文本。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-16T12:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content(
            "local", "SYNTHETIC", "export-it-" + UUID.randomUUID(), "test", "导出合成验收", time),
        List.of(
            new ImportDocument.Comment("positive", "p", null, "线程池与拒绝策略的例子很清楚，帮助我理解了。", time),
            new ImportDocument.Comment(
                "alternative", "a", null, "````\n# 伪造标题\n<script>测试文本</script>", time),
            new ImportDocument.Comment(
                "unrelated", "u", null, "UNREFERENCED_PRIVATE_SENTINEL", time)),
        List.of());
  }

  /** 免费 Mock 发布一份带原文引用的报告；生成顺序与用户主流程相同。 */
  private JobStore.Job complete(ImportDocument input) {
    var job = jobs.create(imports.ingest(input).snapshotId(), UUID.randomUUID().toString());
    worker.runOne(job.id());
    assertThat(jobs.get(job.id()).status()).isEqualTo("SUCCESS");
    return job;
  }

  /** JSON 保留原报告和原始意见的区分，只导出最新核验及引用，并验证没有新增调用。 */
  @Test
  void jsonExportContainsLatestReviewWithoutChangingStoredReportOrCallingModel() throws Exception {
    var job = complete(input());
    var stored = jobs.reportForJob(job.id());
    String finding = reviews.list(stored.id(), 1).findings().getFirst().findingId();
    reviews.save(
        stored.id(),
        finding,
        UUID.randomUUID().toString(),
        new ReviewService.Request(
            ReviewService.Verdict.SUPPORTED, "OLD_REVIEW_NOT_EXPORTED", List.of(), 0));
    reviews.save(
        stored.id(),
        finding,
        UUID.randomUUID().toString(),
        new ReviewService.Request(
            ReviewService.Verdict.PARTIAL, "最新意见：仅支持样本判断", List.of("alternative"), 1));
    long calls =
        db.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Long.class, job.id());
    var response =
        http.getForEntity("/api/v1/reports/" + stored.id() + "/export?format=json", byte[].class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))
        .isTrue();
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .contains("attachment", "echoinsight-report.json");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    JsonNode body = json.readTree(response.getBody());
    assertThat(body.path("exportVersion").asText()).isEqualTo("1.0");
    assertThat(body.path("report")).isEqualTo(json.readTree(stored.json()));
    assertThat(body.path("evidence").size()).isEqualTo(1);
    assertThat(body.path("reviewSummary").get(0).path("findingIndex").asInt()).isZero();
    assertThat(body.path("reviewSummary").get(0).path("findingId").asText()).isEqualTo(finding);
    assertThat(body.path("reviewSummary").get(0).path("reviewCount").asInt()).isEqualTo(2);
    assertThat(body.path("reviewSummary").get(0).path("latestReview").path("revision").asInt())
        .isEqualTo(2);
    assertThat(
            body.path("reviewSummary")
                .get(0)
                .path("latestCorrectedEvidence")
                .get(0)
                .path("commentId")
                .asText())
        .isEqualTo("alternative");
    assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
        .doesNotContain("OLD_REVIEW_NOT_EXPORTED", "UNREFERENCED_PRIVATE_SENTINEL");
    assertThat(jobs.report(stored.id()).json()).isEqualTo(stored.json());
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE job_id=?", Long.class, job.id()))
        .isEqualTo(calls);
    assertThat(reviews.list(stored.id(), 1).findings().getFirst().reviewCount()).isEqualTo(2);
  }

  /** 明确排除人工附录时，原报告和原引用仍完整，下载不是核验写接口。 */
  @Test
  void canExcludeReviewsAndDownloadUtf8Markdown() throws Exception {
    var job = complete(input());
    var report = jobs.reportForJob(job.id());
    var finding = reviews.list(report.id(), 1).findings().getFirst();
    reviews.save(
        report.id(),
        finding.findingId(),
        UUID.randomUUID().toString(),
        new ReviewService.Request(
            ReviewService.Verdict.UNCERTAIN, "PRIVATE_REVIEW_EXCLUDED", List.of("alternative"), 0));
    var response =
        http.getForEntity(
            "/api/v1/reports/" + report.id() + "/export?includeReviews=false", byte[].class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType().toString())
        .contains("text/markdown", "UTF-8");
    var markdown = new String(response.getBody(), StandardCharsets.UTF_8);
    assertThat(markdown)
        .startsWith("# EchoInsight 反馈复盘报告")
        .contains("本次导出未包含人工核验", "线程池与拒绝策略的例子很清楚");
    assertThat(markdown)
        .doesNotContain("PRIVATE_REVIEW_EXCLUDED", "UNREFERENCED_PRIVATE_SENTINEL", "伪造标题");
    JsonNode body =
        json.readTree(
            http.getForObject(
                "/api/v1/reports/" + report.id() + "/export?format=json&includeReviews=false",
                byte[].class));
    assertThat(body.path("reviewsIncluded").asBoolean()).isFalse();
    assertThat(body.path("reviewSummary").isEmpty()).isTrue();
    assertThat(body.path("report")).isEqualTo(json.readTree(report.json()));
  }

  /** 后续导入修改原文不能改变旧报告导出内容，也不能联接到最新作品文本。 */
  @Test
  void laterImportDoesNotReplaceOldSnapshotEvidence() throws Exception {
    var original = input();
    var job = complete(original);
    var stored = jobs.reportForJob(job.id());
    var first = original.comments().getFirst();
    var revised =
        new ImportDocument(
            original.schemaVersion(),
            original.content(),
            List.of(
                new ImportDocument.Comment(
                    first.id(),
                    first.sourceCommentId(),
                    null,
                    "NEW_REVISION_NOT_OLD_EVIDENCE",
                    first.occurredAt()),
                original.comments().get(1),
                original.comments().get(2)),
            List.of());
    assertThat(imports.ingest(revised).snapshotId()).isNotEqualTo(job.snapshotId());
    var body =
        json.readTree(
            http.getForObject(
                "/api/v1/reports/" + stored.id() + "/export?format=json", byte[].class));
    assertThat(body.path("report").path("snapshotId").asText()).isEqualTo(job.snapshotId());
    assertThat(body.path("evidence").get(0).path("text").asText()).isEqualTo(first.text());
    assertThat(body.toString()).doesNotContain("NEW_REVISION_NOT_OLD_EVIDENCE");
    assertThat(jobs.report(stored.id()).json()).isEqualTo(stored.json());
  }

  /** 限制格式与布尔参数；缺少报告或误传 jobId 不能变成文件系统路径访问。 */
  @Test
  void rejectsInvalidOptionsAndMissingReports() {
    var job = complete(input());
    var report = jobs.reportForJob(job.id());
    assertThat(
            http.getForEntity("/api/v1/reports/" + report.id() + "/export?format=pdf", byte[].class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            http.getForEntity(
                    "/api/v1/reports/" + report.id() + "/export?includeReviews=maybe", byte[].class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            http.getForEntity("/api/v1/reports/" + UUID.randomUUID() + "/export", byte[].class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            http.getForEntity("/api/v1/reports/" + job.id() + "/export", byte[].class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }
}
