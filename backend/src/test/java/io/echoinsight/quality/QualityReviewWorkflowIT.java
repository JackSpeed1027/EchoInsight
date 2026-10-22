/* 文件职责：在隔离MySQL验证无预测输入、追加草稿/提交、并发冲突、身份映射与只读对照。 */
package io.echoinsight.quality;

import static io.echoinsight.quality.QualityReviewProtocol.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.*;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 无后台、真实模型、付费或平台调用；只追加本测试合成项目，不清理用户资料或历史。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=quality-review-it",
      "echoinsight.worker.execution-scope=quality-review-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class QualityReviewWorkflowIT {
  @Autowired QualityReviewStore reviews;
  @Autowired QualityReviewComparison comparisons;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisRequestStore requests;
  @Autowired ImportMapper inputs;
  @Autowired JobStore jobs;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;

  /** 抽样前报告尚未产生，输入响应只有原文，创建不派工或记费用。 */
  @Test
  void inputDoesNotReadPredictionOrCreateCalls() {
    var f = fixture(false);
    var v = create(f);
    String encoded = reviews.encode(v);
    assertThat(v.input().targetIds()).hasSize(3);
    assertThat(v.input().comments()).hasSize(3);
    assertThat(encoded).doesNotContain("\"issues\"", "\"claims\"", "\"decisions\"", "\"reuse\"");
    assertThat(jobs.get(f.r().jobId()).status()).isEqualTo("PENDING");
    assertThat(calls(f)).isZero();
  }

  /** 原请求键不因后来的配置或新增分析而改变，换输入同键冲突。 */
  @Test
  void inputIdempotencyAndConflict() {
    var f = fixture(false);
    String key = key();
    var body = new Create(f.r().id(), 3, "fixed");
    var a = reviews.create(f.p(), key, body);
    assertThat(a.created()).isTrue();
    assertThat(reviews.create(f.p(), key, body).value()).isEqualTo(a.value());
    assertThatThrownBy(() -> reviews.create(f.p(), key, new Create(f.r().id(), 2, "fixed")))
        .hasMessageContaining("请求键");
    assertThat(reviews.list(f.p(), 1, 10).total()).isEqualTo(1);
  }

  /** 未完成草稿可恢复，但不能打开预测或静默提交。 */
  @Test
  void draftIsVersionedAndCannotUnlockComparison() {
    var f = fixture(false);
    var v = create(f);
    var a =
        reviews
            .save(f.p(), v.id(), key(), new RecordBody("甲", "UNKNOWN", "DRAFT", null, List.of()))
            .value();
    assertThat(reviews.record(f.p(), v.id(), a.id()).body().labels()).isEmpty();
    assertThatThrownBy(() -> comparisons.compare(f.p(), v.id(), a.id()))
        .hasMessageContaining("先提交");
    assertThatThrownBy(
            () ->
                reviews.save(
                    f.p(),
                    v.id(),
                    key(),
                    new RecordBody("甲", "UNKNOWN", "SUBMISSION", a.id(), List.of())))
        .hasMessageContaining("全部抽样目标");
    assertThat(reviews.records(f.p(), v.id(), "甲", 1, 10).total()).isEqualTo(1);
  }

  /** 提交后修改保存为新草稿，旧提交和哈希保持不变。 */
  @Test
  void submittedRecordIsNeverOverwrittenByLaterDraft() {
    var f = fixture(false);
    var v = create(f);
    var submitted = submit(f, v, "甲", null);
    reviews.save(
        f.p(), v.id(), key(), new RecordBody("甲", "VIEWED", "DRAFT", submitted.id(), List.of()));
    assertThat(reviews.record(f.p(), v.id(), submitted.id())).isEqualTo(submitted);
    assertThat(comparisons.compare(f.p(), v.id(), submitted.id()).state())
        .isEqualTo("REPORT_NOT_READY");
    assertThat(reviews.records(f.p(), v.id(), "甲", 1, 10).total()).isEqualTo(2);
  }

  /** 相同网络重发先识别幂等，不会被它自己新增的previousId挡住。 */
  @Test
  void recordReplayPrecedesOptimisticConflict() {
    var f = fixture(false);
    var v = create(f);
    var body = new RecordBody("甲", "NOT_VIEWED", "DRAFT", null, List.of());
    String key = key();
    var a = reviews.save(f.p(), v.id(), key, body);
    assertThat(a.created()).isTrue();
    assertThat(reviews.save(f.p(), v.id(), key, body).created()).isFalse();
    assertThatThrownBy(() -> reviews.save(f.p(), v.id(), key(), body)).hasMessageContaining("已有更新");
    assertThatThrownBy(
            () ->
                reviews.save(
                    f.p(), v.id(), key, new RecordBody("甲", "VIEWED", "DRAFT", null, List.of())))
        .hasMessageContaining("请求键");
  }

  /** 两标签页并发保存同一个上一版，只有一版接受，另一页明确冲突而非静默覆盖。 */
  @Test
  void concurrentDraftsDoNotLoseHistory() throws Exception {
    var f = fixture(false);
    var v = create(f);
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    reviews.save(
                        f.p(),
                        v.id(),
                        key(),
                        new RecordBody("甲", "UNKNOWN", "DRAFT", null, List.of()));
                    return true;
                  } catch (ResponseStatusException e) {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    return false;
                  }
                }));
      gate.countDown();
      int accepted = 0;
      for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) accepted++;
      assertThat(accepted).isEqualTo(1);
    }
    assertThat(reviews.records(f.p(), v.id(), "甲", 1, 10).total()).isEqualTo(1);
  }

  /** 不自动展示其他署名的判断；同项目也要明确读取对应人工版本。 */
  @Test
  void reviewerHistoryIsExplicitAndMetadataOnly() {
    var f = fixture(false);
    var v = create(f);
    submit(f, v, "甲", null);
    assertThat(reviews.records(f.p(), v.id(), "乙", 1, 10).items()).isEmpty();
    assertThat(reviews.encode(reviews.records(f.p(), v.id(), "甲", 1, 10)))
        .doesNotContain("rationale", "statement");
    assertThatThrownBy(() -> reviews.records(f.p(), v.id(), "", 1, 10)).hasMessageContaining("署名");
  }

  /** MySQL默认排序可能忽略大小写；本机署名精确匹配，不能误载另一个名字的意见。 */
  @Test
  void reviewerNamesAreMatchedExactly() {
    var f = fixture(false);
    var v = create(f);
    submit(f, v, "Reader", null);
    assertThat(reviews.records(f.p(), v.id(), "reader", 1, 10).total()).isZero();
    submit(f, v, "reader", null);
    assertThat(reviews.records(f.p(), v.id(), "Reader", 1, 10).total()).isEqualTo(1);
    assertThat(reviews.records(f.p(), v.id(), "reader", 1, 10).total()).isEqualTo(1);
  }

  /** 项目和执行范围分别校验，不能从其他路径导出原文或意见。 */
  @Test
  void crossProjectAndExecutionScopeAreRejected() {
    var f = fixture(false);
    var v = create(f);
    String other = projects.create(key(), "另一个合成项目", "隔离").project().id();
    assertThatThrownBy(() -> reviews.get(other, v.id()))
        .isInstanceOf(ResponseStatusException.class);
    db.update(
        "UPDATE analysis_job SET execution_scope='other-quality-scope' WHERE id=?", f.r().jobId());
    assertThatThrownBy(() -> reviews.get(f.p(), v.id()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(reviews.list(f.p(), 1, 10).total()).isZero();
  }

  /** 冻结输入损坏则只读失败，不重建或覆盖原资料。 */
  @Test
  void corruptedInputFailsClosed() {
    var f = fixture(false);
    var v = create(f);
    db.update(
        "UPDATE quality_review_input SET input_json=CONCAT(input_json,' ') WHERE id=?", v.id());
    assertThatThrownBy(() -> reviews.get(f.p(), v.id())).hasMessageContaining("hash mismatch");
    assertThat(calls(f)).isZero();
  }

  /** 意见正文损坏同样不能以新的哈希或空记录掩盖。 */
  @Test
  void corruptedRecordFailsClosed() {
    var f = fixture(false);
    var v = create(f);
    var r = submit(f, v, "甲", null);
    db.update(
        "UPDATE quality_review_record SET record_json=CONCAT(record_json,' ') WHERE id=?", r.id());
    assertThatThrownBy(() -> reviews.record(f.p(), v.id(), r.id()))
        .hasMessageContaining("hash mismatch");
  }

  /** 结构演示没有可比较的语义预测，不能输出0%或100%准确率。 */
  @Test
  void structuralDemoIsNotSemanticComparison() throws Exception {
    var f = fixture(false);
    publish(f, "STRUCTURAL_DEMO", false);
    var v = create(f);
    var r = submit(f, v, "甲", null);
    var result = comparisons.compare(f.p(), v.id(), r.id());
    assertThat(result.state()).isEqualTo("NO_SEMANTIC_PREDICTION");
    assertThat(result.items()).isEmpty();
    assertThat(result.independentQualityProven()).isFalse();
  }

  /** 测试替身按来源ID映射当前原文，单条人工与模型分歧只作为复查线索。 */
  @Test
  void stanceComparisonUsesOriginalSourceIdentity() throws Exception {
    var f = fixture(true);
    publish(f, "LOCAL_STUB", true);
    var v = create(f);
    assertThat(v.input().proposition()).isEqualTo("因果关系自洽");
    var r = submit(f, v, "甲", null);
    var result = comparisons.compare(f.p(), v.id(), r.id());
    assertThat(result.state()).isEqualTo("ENGINEERING_ONLY");
    assertThat(result.coverageDisagreements()).isEqualTo(3);
    assertThat(result.items())
        .allSatisfy(
            item -> {
              assertThat(item.modelDecision().path("commentId").asText())
                  .isEqualTo(item.commentId());
              assertThat(item.coverageComparison()).isEqualTo("DIFFERENT");
            });
    assertThat(result.independentQualityProven()).isFalse();
    assertThat(calls(f)).isZero();
  }

  /** 普通讨论只关联显式引用，不把没引用目标判为模型漏读或语义错误。 */
  @Test
  void discussionComparisonDoesNotInventAutomaticSemanticMatch() throws Exception {
    var f = fixture(false);
    publish(f, "LOCAL_STUB", false);
    var v = create(f);
    var r = submit(f, v, "甲", null);
    var result = comparisons.compare(f.p(), v.id(), r.id());
    assertThat(result.items())
        .allSatisfy(i -> assertThat(i.positionComparison()).isEqualTo("NOT_APPLICABLE"));
    assertThat(result.items().stream().mapToInt(i -> i.citedClaims().size()).sum()).isEqualTo(1);
  }

  /** 同一冻结输入不追随新配置，若原持久计划本身遭改写则拒绝对照。 */
  @Test
  void originalPlanDriftIsNotSilentlyAccepted() throws Exception {
    var f = fixture(false);
    publish(f, "LOCAL_STUB", false);
    var v = create(f);
    var r = submit(f, v, "甲", null);
    db.update(
        "UPDATE analysis_job SET execution_plan_json=CONCAT(execution_plan_json,' ') WHERE id=?",
        f.r().jobId());
    // 仅空白改变不会改变解码后的规范计划；真正字段改变才是漂移。
    var plan =
        (ObjectNode)
            json.readTree(
                db.queryForObject(
                    "SELECT execution_plan_json FROM analysis_job WHERE id=?",
                    String.class,
                    f.r().jobId()));
    plan.put("timeoutMs", 9999);
    db.update(
        "UPDATE analysis_job SET execution_plan_json=? WHERE id=?", plan.toString(), f.r().jobId());
    assertThatThrownBy(() -> comparisons.compare(f.p(), v.id(), r.id()))
        .hasMessageContaining("source drift");
  }

  /** HTTP输入导出没有预测，提交前对照400、跨项目404、保存重发200。 */
  @Test
  void httpContractsPreserveBlindingAndIdempotency() {
    var f = fixture(false);
    String base = "/api/v1/projects/" + f.p() + "/quality-review-inputs";
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    var body = new Create(f.r().id(), 2, "fixed");
    var created =
        http.exchange(base, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    String id = created.getBody().path("id").asText();
    assertThat(
            http.exchange(base, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    String exported = http.getForObject(base + "/" + id + "/export", String.class);
    assertThat(exported).doesNotContain("\"claims\"", "\"decisions\"");
    var record =
        reviews
            .save(f.p(), id, key(), new RecordBody("HTTP署名", "UNKNOWN", "DRAFT", null, List.of()))
            .value();
    assertThat(
            http.getForEntity(
                    base + "/" + id + "/records/" + record.id() + "/comparison", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            http.getForEntity(base + "/" + id + "/export?format=bad", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }

  /** 合成请求身份；每例独立项目，保持所有测试历史。 */
  private record Fixture(String p, AnalysisRequestStore.RequestRow r) {}

  private Fixture fixture(boolean stance) {
    String p = projects.create(key(), "人工核对（合成）", "验证质量验收链").project().id();
    var time = OffsetDateTime.parse("2026-10-21T00:00:00Z");
    var doc =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("c-" + key(), "SYNTHETIC", key(), "creator", "合成原文", time),
            List.of(
                new ImportDocument.Comment("raw-a", "source-a", null, "这是原文主张，不是模型预测。", time),
                new ImportDocument.Comment("raw-b", "source-b", "raw-a", "这个条件是否成立？", time),
                new ImportDocument.Comment("raw-c", "source-c", null, "第三条原文供独立判断。", time)),
            List.of());
    String snapshot = imports.ingest(p, doc).snapshotId();
    String question = stance ? "围绕命题「因果关系自洽」，分析哪些主张支持或反对" : "总结讨论的主要观点";
    var r =
        requests
            .createWithScope(
                p, key(), snapshot, question, false, List.of(), true, null, "PREVIEW", false)
            .request();
    return new Fixture(p, r);
  }

  private QualityReviewStore.View create(Fixture f) {
    return reviews.create(f.p(), key(), new Create(f.r().id(), 3, "fixed")).value();
  }

  private QualityReviewStore.Saved submit(
      Fixture f, QualityReviewStore.View v, String reviewer, String previous) {
    var labels =
        v.input().targetIds().stream()
            .map(id -> new Label(id, "UNKNOWN", List.of(), "", "", "人工测试意见，语境仍需核对。", List.of(id)))
            .toList();
    return reviews
        .save(
            f.p(),
            v.id(),
            key(),
            new RecordBody(reviewer, "NOT_VIEWED", "SUBMISSION", previous, labels))
        .value();
  }

  /** 通过原租约/步骤/发布流程写入明确的工程替身，不调用外部模型或宣称真实质量。 */
  private void publish(Fixture f, String mode, boolean stance) throws Exception {
    var r = f.r();
    String token = jobs.claim(r.jobId()), artifactId = key();
    var doc = json.readValue(inputs.snapshot(r.snapshotId()).documentJson(), ImportDocument.class);
    var object =
        (ObjectNode)
            json.valueToTree(
                new StructuralDiscussionAnalyzer()
                    .analyze(jobs.get(r.jobId()), r, doc, artifactId));
    object.put("sourceMode", mode);
    var evidence = object.putArray("evidence");
    for (int i = 0; i < doc.comments().size(); i++) {
      var c = doc.comments().get(i);
      var e = evidence.addObject();
      e.put("id", "alias-" + i);
      e.put("sourceCommentId", c.sourceCommentId());
      e.put("parentCommentId", c.parentId());
      e.put("text", c.text());
      e.put("sourceRole", "COMMENT");
    }

    if (mode.equals("LOCAL_STUB")) {
      // 使用完整合法的模拟计划，不能把免费计划仅改来源标签后当语义协议。
      var fixedPlan =
          new io.echoinsight.model.ModelContract.Plan(
              "REVIEW_FIXTURE",
              "fixture",
              io.echoinsight.model.springai.DiscussionPrompt.VERSION,
              io.echoinsight.model.springai.DiscussionPrompt.TAXONOMY,
              1,
              5000,
              4096,
              "SIMULATED_UNITS",
              "fixture",
              1,
              1,
              1000000,
              10000,
              "fixture",
              "LOCAL_STUB");
      db.update(
          "UPDATE analysis_job SET execution_plan_json=? WHERE id=?",
          io.echoinsight.model.ModelContract.encode(fixedPlan),
          r.jobId());
    }
    var issue = object.putArray("issues").addObject();
    issue.put("id", "issue-1");
    issue.put("question", "工程议题");
    issue.put("summary", "工程替身摘要");
    issue.putArray("evidenceIds").add("alias-0");
    var claims = object.putArray("claims");
    var claim = claims.addObject();
    claim.put("id", "claim-1");
    claim.put("issueId", "issue-1");
    claim.put("statement", "工程替身主张");
    claim.put("reasoningStatus", "UNKNOWN");
    claim.putArray("evidenceIds").add("alias-0");
    if (stance) {
      var st = object.putObject("stance");
      st.put("analyzed", true);
      var decisions = st.putArray("decisions");
      for (int i = 0; i < doc.comments().size(); i++) {
        var d = decisions.addObject();
        d.put("commentId", "alias-" + i);
        d.put("coverage", "QUESTION");
        d.putArray("claims");
      }
    }
    jobs.startStep(r.jobId(), token, "DISCUSSION");
    jobs.saveDiscussion(r.jobId(), token, object.toString());
    jobs.startStep(r.jobId(), token, "ARTIFACT");
    jobs.publishArtifact(r.jobId(), token, artifactId, r.id(), r.snapshotId(), object.toString());
  }

  private long calls(Fixture f) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM model_call WHERE job_id=?", Long.class, f.r().jobId());
  }

  private String key() {
    return UUID.randomUUID().toString();
  }
}
