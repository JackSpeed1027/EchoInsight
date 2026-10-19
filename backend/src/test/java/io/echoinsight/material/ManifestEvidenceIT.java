/* 文件职责：验证跨作品对照与人工核验的证据归属、稳定依赖和只读边界。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.intelligence.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.project.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 免费结构计划只验证系统契约，不声称已经完成人工语义评测或模型准确率测量。 */
@SpringBootTest(
    properties = {
      "echoinsight.projects.workspace=evidence-report-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ManifestEvidenceIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired InputManifestStore manifests;
  @Autowired ManifestAnalysisStore runs;
  @Autowired ManifestEvidenceReport reports;
  @Autowired AnalysisWorker worker;
  @Autowired AnalysisRequestStore requests;
  @Autowired SemanticReviewStore reviews;
  @Autowired JobStore jobs;
  @Autowired JdbcTemplate db;

  /** 完成后反复GET保持同指纹且不新增任务；每条原文只能来自自己所属产物。 */
  @Test
  void reportIsReadOnlyStableAndEvidenceScoped() {
    String project = project();
    var run = run(project);
    assertThatThrownBy(() -> reports.get(project, run.id()))
        .isInstanceOf(ResponseStatusException.class);
    run.items().forEach(i -> worker.runOne(i.jobId()));
    long before = db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class);
    var first = reports.get(project, run.id());
    var repeat = reports.get(project, run.id());
    assertThat(first).isEqualTo(repeat);
    assertThat(first.dependencySha256()).hasSize(64);
    assertThat(first.succeeded()).isEqualTo(2);
    assertThat(first.topics()).isNotEmpty();
    for (var topic : first.topics())
      for (var slice : topic.sources()) {
        var artifact = requests.artifact(project, slice.requestId());
        Set<String> ids = new HashSet<>();
        artifact.path("evidence").forEach(e -> ids.add(e.path("id").asText()));
        assertThat(slice.evidence()).allSatisfy(e -> assertThat(ids).contains(e.id()));
        assertThat(slice.sourceMode()).isEqualTo("STRUCTURAL_DEMO");
      }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class))
        .isEqualTo(before);
    assertThatThrownBy(() -> reports.get(project(), run.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 部分失败的作品留在报告分母中，不只导出成功样本；重试期间报告暂不可用。 */
  @Test
  void partialFailureRemainsVisibleAndRetryInvalidatesReadiness() {
    String project = project();
    var run = run(project);
    worker.runOne(run.items().getFirst().jobId());
    var failed = run.items().get(1);
    String token = jobs.claim(failed.jobId());
    jobs.fail(failed.jobId(), token, "SYNTHETIC_FAILURE");
    var report = reports.get(project, run.id());
    assertThat(report.status()).isEqualTo("PARTIAL_FAILED");
    assertThat(report.failed()).isEqualTo(1);
    assertThat(report.works()).hasSize(2);
    jobs.retry(failed.jobId());
    assertThatThrownBy(() -> reports.get(project, run.id()))
        .isInstanceOf(ResponseStatusException.class);
    String retryToken = jobs.claim(failed.jobId());
    jobs.fail(failed.jobId(), retryToken, "SYNTHETIC_OTHER_FAILURE");
    assertThat(reports.get(project, run.id()).dependencySha256())
        .isNotEqualTo(report.dependencySha256());
  }

  /** 核验意见追加且幂等，必须引用当前产物；不改写原产物或对照依赖。 */
  @Test
  void reviewsAreAppendOnlyAndCannotBorrowForeignEvidence() {
    String project = project();
    var run = run(project);
    run.items().forEach(i -> worker.runOne(i.jobId()));
    String request = run.items().getFirst().requestId();
    var artifact = requests.artifact(project, request);
    String issue = artifact.path("issues").get(0).path("id").asText(),
        evidence = artifact.path("evidence").get(0).path("id").asText();
    String before = artifact.toString(),
        fingerprint = reports.get(project, run.id()).dependencySha256(),
        key = key();
    var input =
        new SemanticReviewStore.Input(
            "ISSUE", issue, "UNCERTAIN", "合成验收署名", "只检查核验流程，不作为独立标注", List.of(evidence));
    var review = reviews.create(project, request, key, input);
    assertThat(reviews.create(project, request, key, input).id()).isEqualTo(review.id());
    var second =
        new SemanticReviewStore.Input(
            "ISSUE", issue, "UNSUPPORTED", "合成验收署名", "补充另一条意见，保留先前记录", List.of(evidence));
    reviews.create(project, request, key(), second);
    assertThat(reviews.list(project, request, 1, 10).total()).isEqualTo(2);
    assertThatThrownBy(() -> reviews.create(project, request, key, second))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                reviews.create(
                    project,
                    request,
                    key(),
                    new SemanticReviewStore.Input(
                        "ISSUE", issue, "SUPPORTED", "测试", "越界引用", List.of("foreign-evidence"))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(requests.artifact(project, request).toString()).isEqualTo(before);
    assertThat(reports.get(project, run.id()).dependencySha256()).isEqualTo(fingerprint);
    assertThatThrownBy(() -> reviews.list(project(), request, 1, 10))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 核验目标不属于当前产物时拒绝，未发布请求同样不能提前核验。 */
  @Test
  void nonexistentTargetsAndUnpublishedRequestsAreRejected() {
    String project = project();
    var run = run(project);
    String request = run.items().getFirst().requestId();
    var input =
        new SemanticReviewStore.Input("CLAIM", "missing", "SUPPORTED", "测试", "不存在的目标", List.of());
    assertThatThrownBy(() -> reviews.create(project, request, key(), input))
        .isInstanceOf(ResponseStatusException.class);
    worker.runOne(run.items().getFirst().jobId());
    assertThatThrownBy(() -> reviews.create(project, request, key(), input))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(reviews.list(project, request, 1, 10).total()).isZero();
  }

  /** 两个独立来源且不同原文，便于发现报告把证据串到其他作品的错误。 */
  private ManifestAnalysisStore.Run run(String project) {
    String a = imports.ingest(project, InputManifestIT.document(key(), "作品甲的独有问题")).snapshotId(),
        b = imports.ingest(project, InputManifestIT.document(key(), "作品乙的独有问题")).snapshotId();
    return runs.create(
        project,
        key(),
        manifests.create(project, key(), "对照样本", List.of(a, b)).summary().id(),
        "总结讨论观点并比较理由");
  }

  private String project() {
    return projects.create(key(), "证据对照合成验收", "核对归属与只读行为").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }
}
