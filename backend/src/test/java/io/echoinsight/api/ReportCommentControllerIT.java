/* 文件职责：验证评论分页筛选、旧快照归属、直接上下文、存储一致性与查询不触发重分析。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.analysis.FixtureClassifier;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportService;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.report.ReportBuilder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅追加唯一合成材料和测试报告，不需要真实供应商，不清理用户数据。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"echoinsight.worker.enabled=false", "echoinsight.model.enabled=false"})
class ReportCommentControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired JobStore jobs;
  @Autowired FixtureClassifier classifier;
  @Autowired ReportBuilder reports;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  /** 混合已知标签、未知文本和父评论；来源 ID 固定排序以符合导入规范化规则，不当作独立金标。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-16T12:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content(
            "local", "SYNTHETIC", "comment-it-" + UUID.randomUUID(), "test", "逐条明细合成验收", time),
        List.of(
            new ImportDocument.Comment("p", "source-01", null, "线程池与拒绝策略的例子很清楚，帮助我理解了。", time),
            new ImportDocument.Comment("u1", "source-02", null, "AbC 字面搜索 %_ ' OR 1=1 --", time),
            new ImportDocument.Comment("n", "source-03", null, "线程池与拒绝策略的边界条件缺少完整案例，希望补充。", time),
            new ImportDocument.Comment(
                "child", "source-04", "p", "它的另一个边界呢？<script>测试文本</script>", time),
            new ImportDocument.Comment("neutral", "source-05", null, "请问线程池与拒绝策略的示例代码在哪里下载？", time),
            new ImportDocument.Comment(
                "u2", "source-06", null, "abc 未知样本 PRIVATE_STORED_BODY_SENTINEL", time)),
        List.of());
  }

  /** 反转分类返回顺序再发布，模拟供应商乱序响应；展示仍必须保持原快照顺序。 corrupt 只对本测试新建产物设置故障，原报告始终按完整正确列表构建，不修改旧数据。 */
  private JobStore.ReportRow publish(ImportDocument input, String corrupt) throws Exception {
    var job = jobs.create(imports.ingest(input).snapshotId(), UUID.randomUUID().toString());
    var complete = new ArrayList<>(classifier.classify(input, () -> {}));
    Collections.reverse(complete);
    String saved = json.writeValueAsString(complete);
    if ("missing".equals(corrupt))
      saved = json.writeValueAsString(complete.subList(1, complete.size()));
    if ("duplicate".equals(corrupt)) {
      var duplicate = new ArrayList<>(complete);
      duplicate.set(0, duplicate.get(1));
      saved = json.writeValueAsString(duplicate);
    }
    if ("bad-label".equals(corrupt)) saved = saved.replace("UNKNOWN", "UNRECOGNIZED");
    if ("numeric-id".equals(corrupt))
      saved = saved.replace("\"commentId\":\"u2\"", "\"commentId\":42");
    String token = jobs.claim(job.id());
    jobs.startStep(job.id(), token, "CLASSIFY");
    jobs.saveClassification(job.id(), token, saved);
    jobs.startStep(job.id(), token, "REPORT");
    var report = reports.build(jobs.get(job.id()), input, complete, jobs.plan(job.id()));
    String reportJson = json.writeValueAsString(report);
    if ("counts".equals(corrupt))
      reportJson = reportJson.replace("\"positive\":1", "\"positive\":2");
    jobs.publish(job.id(), token, report.reportId(), job.snapshotId(), reportJson);
    return jobs.report(report.reportId());
  }

  /** 在真实 HTTP 入口组合筛选，模板参数由客户端编码，SQL 样式文本不会变成查询代码。 */
  private JsonNode get(String report, int page, int size, String label, String query) {
    return http.getForObject(
        "/api/v1/reports/" + report + "/comments?page={page}&size={size}&label={label}&q={q}",
        JsonNode.class,
        Map.of("page", page, "size", size, "label", label, "q", query));
  }

  /** 顺序、引用身份与直接父评论都来自原报告快照；读查询不增加任务、调用或意见。 */
  @Test
  void pagesInSnapshotOrderAndExpandsOnlyDirectContextWithoutWrites() throws Exception {
    var report = publish(input(), null);
    long calls = db.queryForObject("SELECT COUNT(*) FROM model_call", Long.class);
    long jobCount = db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class);
    long reviewCount = db.queryForObject("SELECT COUNT(*) FROM report_review", Long.class);
    var first = get(report.id(), 1, 2, "ALL", "");
    assertThat(first.path("snapshotCommentCount").asInt()).isEqualTo(6);
    assertThat(first.path("total").asInt()).isEqualTo(6);
    assertThat(first.path("totalPages").asInt()).isEqualTo(3);
    assertThat(first.path("sourceMode").asText()).isEqualTo("MOCK");
    assertThat(first.path("items").get(0).path("commentId").asText()).isEqualTo("p");
    assertThat(first.path("items").get(1).path("commentId").asText()).isEqualTo("u1");
    assertThat(first.path("items").get(0).path("label").asText()).isEqualTo("POSITIVE");
    assertThat(first.path("items").get(0).path("parent").isNull()).isTrue();
    assertThat(first.path("items").get(0).path("findingIds").get(0).asText()).startsWith("f_");
    assertThat(first.path("items").get(1).path("findingIds").isEmpty()).isTrue();
    var second = get(report.id(), 2, 2, "ALL", "");
    assertThat(second.path("items").get(0).path("commentId").asText()).isEqualTo("n");
    assertThat(second.path("items").get(1).path("parent").path("commentId").asText())
        .isEqualTo("p");
    assertThat(second.path("items").get(1).path("parent").path("text").asText()).contains("例子很清楚");
    assertThat(get(report.id(), 10000, 2, "ALL", "").path("items").isEmpty()).isTrue();
    assertThat(jobs.report(report.id()).json()).isEqualTo(report.json());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call", Long.class)).isEqualTo(calls);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class))
        .isEqualTo(jobCount);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM report_review", Long.class))
        .isEqualTo(reviewCount);
    var headers =
        http.getForEntity("/api/v1/reports/" + report.id() + "/comments", JsonNode.class)
            .getHeaders();
    assertThat(headers.getCacheControl()).isEqualTo("no-store");
    assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  /** 标签与字面正文查询组合生效；全快照计数不随筛选改变，不连带匹配父评论。 */
  @Test
  void filtersOriginalLabelsAndLiteralCaseInsensitiveTextWithStableCounts() throws Exception {
    var report = publish(input(), null);
    var body = get(report.id(), 1, 20, "UNKNOWN", "  ABC  ");
    assertThat(body.path("query").asText()).isEqualTo("ABC");
    assertThat(body.path("total").asInt()).isEqualTo(2);
    assertThat(body.path("labelCounts").path("positive").asInt()).isEqualTo(1);
    assertThat(body.path("labelCounts").path("unknown").asInt()).isEqualTo(3);
    assertThat(get(report.id(), 1, 20, "ALL", "%_").path("total").asInt()).isEqualTo(1);
    assertThat(get(report.id(), 1, 20, "ALL", "' OR 1=1 --").path("total").asInt()).isEqualTo(1);
    var parentOnly = get(report.id(), 1, 20, "ALL", "例子很清楚");
    assertThat(parentOnly.path("total").asInt()).isEqualTo(1);
    assertThat(parentOnly.path("items").get(0).path("commentId").asText()).isEqualTo("p");
    var empty = get(report.id(), 1, 20, "NEGATIVE", "ABC");
    assertThat(empty.path("total").asInt()).isZero();
    assertThat(empty.path("totalPages").asInt()).isZero();
    assertThat(empty.path("items").isEmpty()).isTrue();
  }

  /** 同作品新导入不能替换旧报告明细及父评论，搜索也只在旧范围内进行。 */
  @Test
  void newImportCannotReplaceHistoricalTextOrLabels() throws Exception {
    var original = input();
    var report = publish(original, null);
    var comments = new ArrayList<>(original.comments());
    var first = comments.getFirst();
    comments.set(
        0,
        new ImportDocument.Comment(
            first.id(), first.sourceCommentId(), null, "NEW_PARENT_REVISION", first.occurredAt()));
    var next = imports.ingest(new ImportDocument("1.0", original.content(), comments, List.of()));
    assertThat(next.snapshotId()).isNotEqualTo(report.snapshotId());
    var page = get(report.id(), 1, 20, "ALL", "");
    assertThat(page.path("snapshotId").asText()).isEqualTo(report.snapshotId());
    assertThat(page.path("items").get(0).path("text").asText()).isEqualTo(first.text());
    assertThat(page.path("items").get(3).path("parent").path("text").asText())
        .isEqualTo(first.text());
    assertThat(get(report.id(), 1, 20, "ALL", "NEW_PARENT_REVISION").path("total").asInt())
        .isZero();
    assertThat(jobs.report(report.id()).json()).isEqualTo(report.json());
  }

  /** 没有可用分类分母时，仍可检查全部未知样本，不把 UNKNOWN 误当成未执行。 */
  @Test
  void allUnknownCommentsRemainVisibleWithoutFindingReferences() throws Exception {
    var original = input();
    var onlyUnknown =
        new ImportDocument(
            "1.0",
            original.content(),
            List.of(original.comments().get(1), original.comments().get(5)),
            List.of());
    var report = publish(onlyUnknown, null);
    var body = get(report.id(), 1, 20, "UNKNOWN", "");
    assertThat(body.path("total").asInt()).isEqualTo(2);
    assertThat(body.path("labelCounts").path("unknown").asInt()).isEqualTo(2);
    assertThat(body.path("labelCounts").path("positive").asInt()).isZero();
    for (var comment : body.path("items"))
      assertThat(comment.path("findingIds").isEmpty()).isTrue();
    assertThat(
            json.readTree(report.json())
                .path("metrics")
                .path("positiveShareAmongClassified")
                .isNull())
        .isTrue();
  }

  /** 参数范围、标签大小写和 Unicode 上限明确；报告 ID 与任务 ID 不可混用。 */
  @Test
  void rejectsInvalidParametersAndMissingReportWithoutExecutingAnalysis() throws Exception {
    var report = publish(input(), null);
    String base = "/api/v1/reports/" + report.id() + "/comments";
    for (String query :
        List.of(
            "page=0",
            "page=10001",
            "size=0",
            "size=101",
            "label=positive",
            "label=FAILED",
            "page=abc")) {
      assertThat(http.getForEntity(base + "?" + query, JsonNode.class).getStatusCode().value())
          .isEqualTo(400);
    }
    assertThat(
            http.getForEntity(base + "?q={q}", JsonNode.class, "😀".repeat(101))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(get(report.id(), 1, 20, "ALL", "😀".repeat(100)).path("total").asInt()).isZero();
    assertThat(
            http.getForEntity("/api/v1/reports/" + UUID.randomUUID() + "/comments", JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    var pending = jobs.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    assertThat(
            http.getForEntity("/api/v1/reports/" + pending.id() + "/comments", JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(jobs.get(pending.id()).status()).isEqualTo("PENDING");
  }

  /** 缺失、重复、非法标签/身份和计数矛盾一律拒绝，不能用 UNKNOWN 补填或悄悄重分析。 */
  @Test
  void inconsistentStoredArtifactsFailClosed() throws Exception {
    for (String defect : List.of("missing", "duplicate", "bad-label", "numeric-id", "counts")) {
      var report = publish(input(), defect);
      var response =
          http.getForEntity("/api/v1/reports/" + report.id() + "/comments", String.class);
      assertThat(response.getStatusCode().value()).isEqualTo(500);
      assertThat(response.getBody()).doesNotContain("PRIVATE_STORED_BODY_SENTINEL");
      assertThat(jobs.report(report.id()).json()).isEqualTo(report.json());
    }
  }
}
