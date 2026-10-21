/* 文件职责：隔离MySQL验证完整普通讨论、版本复用、内容对照、取消恢复和报告复核。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.content.ContentMaterialStore;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.project.*;
import io.echoinsight.report.DiscussionReportService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 所有模型响应来自内存LOCAL_STUB；后台、付费和平台关闭，不删除用户或测试历史。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=discussion-digest-it",
      "echoinsight.worker.execution-scope=discussion-digest-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class DiscussionDigestWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired ImportMapper inputs;
  @Autowired AnalysisRequestStore requests;
  @Autowired AnalysisPreflight preflight;
  @Autowired AnalysisContextStore contexts;
  @Autowired AnalysisScopeStore scopes;
  @Autowired ContentMaterialStore materials;
  @Autowired DiscussionReportService reports;
  @Autowired SemanticReviewStore reviews;
  @Autowired JobStore jobs;
  @Autowired ModelLedger ledger;
  @Autowired TaskControlService control;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @MockitoBean ConfiguredModelPlan plans;

  /** 每例显式使用固定模拟协议，不读取正式模型设置。 */
  @BeforeEach
  void defaults() {
    when(plans.discussionDigest()).thenReturn(plan(1000000000));
    var free =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.discussion()).thenReturn(free.discussion());
    when(plans.discussionWithContext()).thenReturn(free.discussionWithContext());
    when(plans.discussionWithContent()).thenReturn(free.discussionWithContent());
  }

  /** 只读预检完整85评论，费用含三片和一次汇总；没确认时原子回滚。 */
  @Test
  void fullPreflightAndAdmissionAreAtomic() {
    var f = fixture(85);
    var preview = preview(f, null, null);
    assertThat(preview.selectedComments()).isEqualTo(85);
    assertThat(preview.fullScope().plannedBatches()).isEqualTo(4);
    assertThat(preview.fullScope().maximumUnits()).isEqualTo(56384);
    assertThat(requests.list(f.p(), 1, 10).total()).isZero();
    assertThatThrownBy(
            () ->
                requests.createWithScope(
                    f.p(),
                    key(),
                    f.snapshot(),
                    "总结所有评论",
                    false,
                    List.of(),
                    false,
                    null,
                    "FULL",
                    false))
        .hasMessageContaining("确认");
    assertThat(requests.list(f.p(), 1, 10).total()).isZero();
    var r = create(f, null, null);
    assertThat(count("model_call", r.jobId())).isZero();
    assertThat(requests.scope(f.p(), r.id()).reuse().entries()).hasSize(3);
  }

  /** 400评论触发真正多层归并，末片原文进入最终引用，全部节点在同一账本。 */
  @Test
  void hierarchicalSynthesisIncludesTailAndOriginalEvidence() throws Exception {
    var f = fixture(400);
    var r = create(f, null, null);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      run(f.p(), r, gateway, () -> {});
    }
    var artifact = requests.artifact(f.p(), r.id());
    assertThat(provider.calls).hasValue(13);
    assertThat(count("model_batch", r.jobId())).isEqualTo(13);
    assertThat(artifact.path("processing").path("nodes")).hasSize(13);
    assertThat(artifact.path("sourceMode").asText()).isEqualTo("LOCAL_STUB");
    String evidence = artifact.path("issues").get(0).path("evidenceIds").get(0).asText();
    assertThat(findEvidence(artifact, evidence).path("sourceCommentId").asText())
        .isEqualTo("source00399");
    assertThat(artifact.path("scope").path("selectedComments").asInt()).isEqualTo(400);
    assertThat(ledger.view(r.jobId(), 1, 30).budget().settledUnits()).isEqualTo(65);
  }

  /** 同作品80评论追加5条，前两片复用，只新调用一片和FINAL；原费用留原任务。 */
  @Test
  void incrementalMapsKeepBillingAndCurrentSourceIdsCorrect() throws Exception {
    var base = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(base, null, null);
      run(base.p(), a, gateway, () -> {});
      var next = new Fixture(base.p(), base.work(), snapshot(base.p(), base.work(), 85, false));
      var preview = preview(next, a.id(), null);
      assertThat(preview.reuse().reusedChunks()).isEqualTo(2);
      assertThat(preview.fullScope().plannedBatches()).isEqualTo(2);
      var b = create(next, a.id(), null);
      run(next.p(), b, gateway, () -> {});
      assertThat(provider.calls).hasValue(5);
      assertThat(count("model_batch", b.jobId())).isEqualTo(2);
      assertThat(
              db.queryForList(
                  "SELECT batch_index FROM model_batch WHERE job_id=? ORDER BY batch_index",
                  Integer.class,
                  b.jobId()))
          .containsExactly(2, 3);
      assertThat(ledger.view(a.jobId(), 1, 30).budget().settledUnits()).isEqualTo(15);
      assertThat(ledger.view(b.jobId(), 1, 30).budget().settledUnits()).isEqualTo(10);
      var progress = requests.progress(next.p(), b.id());
      assertThat(progress.processing().reusedMaps()).isEqualTo(2);
      assertThat(progress.completedComments()).isEqualTo(85);
      assertThat(progress.completedBatches()).isEqualTo(2);
      var artifact = requests.artifact(next.p(), b.id());
      assertThat(
              artifact
                  .path("processing")
                  .path("reuse")
                  .path("origins")
                  .get(0)
                  .path("sourceJobId")
                  .asText())
          .isEqualTo(a.jobId());
    }
  }

  /** 输入原ID和顺序变化时，局部输入仍可命中，引用映射成当前快照而非旧别名。 */
  @Test
  void remapsReusedEvidenceWhenCurrentInputOrderChanges() throws Exception {
    var f = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var next = new Fixture(f.p(), f.work(), snapshot(f.p(), f.work(), 80, true));
      var b = create(next, a.id(), null);
      run(f.p(), b, gateway, () -> {});
      assertThat(count("model_call", b.jobId())).isEqualTo(1);
      var artifact = requests.artifact(f.p(), b.id());
      String id = artifact.path("issues").get(0).path("evidenceIds").get(0).asText();
      assertThat(findEvidence(artifact, id).path("sourceCommentId").asText())
          .isEqualTo("source00079");
    }
  }

  /** 基准沿多版本传播时，保留最初实际收费出处，不把上一版缓存记成新账。 */
  @Test
  void chainedReuseRetainsOriginalPhysicalOrigin() throws Exception {
    var f = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var next = new Fixture(f.p(), f.work(), snapshot(f.p(), f.work(), 85, false));
      var b = create(next, a.id(), null);
      run(f.p(), b, gateway, () -> {});
      var c = create(next, b.id(), null);
      run(f.p(), c, gateway, () -> {});
      var origins =
          requests.artifact(f.p(), c.id()).path("processing").path("reuse").path("origins");
      assertThat(origins.get(0).path("sourceJobId").asText()).isEqualTo(a.jobId());
      assertThat(origins.get(2).path("sourceJobId").asText()).isEqualTo(b.jobId());
      assertThat(count("model_call", c.jobId())).isEqualTo(1);
    }
  }

  /** 预算不足拒绝完整任务，不用少算归并费用绕开上界。 */
  @Test
  void rejectsUnderfundedPlanWithoutLeavingRequest() {
    var f = fixture(85);
    when(plans.discussionDigest()).thenReturn(plan(30000));
    assertThat(preview(f, null, null).fullScope().available()).isFalse();
    assertThatThrownBy(() -> create(f, null, null)).hasMessageContaining("预算不足");
    assertThat(requests.list(f.p(), 1, 10).total()).isZero();
  }

  /** 不同作品/问题/计划都不能复用；不选择基准仍可全新执行。 */
  @Test
  void validatesWorkQuestionAndPlanBeforeReuse() throws Exception {
    var f = fixture(80);
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var another = new Fixture(f.p(), key(), snapshot(f.p(), key(), 85, false));
      assertThatThrownBy(() -> preview(another, a.id(), null)).hasMessageContaining("同一作品");
      assertThatThrownBy(
              () ->
                  preflight.previewWithContent(
                      f.p(),
                      f.snapshot(),
                      false,
                      List.of(),
                      "总结另一问题的讨论",
                      null,
                      "FULL",
                      null,
                      null,
                      null,
                      null,
                      a.id()))
          .hasMessageContaining("问题或评议要求不同");
      when(plans.discussionDigest()).thenReturn(plan(1000000001));
      assertThatThrownBy(() -> preview(f, a.id(), null)).hasMessageContaining("模型协议或费用计划已变化");
      assertThat(preview(f, null, null).reuse().reusedChunks()).isZero();
    }
  }

  /** 未成功、免费演示、跨项目和错误模式均在出站前拒绝。 */
  @Test
  void rejectsUnqualifiedBaselines() throws Exception {
    var f = fixture(80);
    var pending = create(f, null, null);
    assertThatThrownBy(() -> preview(f, pending.id(), null)).hasMessageContaining("已完成");
    var free =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.discussionDigest()).thenReturn(free.discussionDigest());
    var demo = create(f, null, null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(f.p(), demo, gateway, () -> {});
    }
    when(plans.discussionDigest()).thenReturn(plan(1000000000));
    assertThatThrownBy(() -> preview(f, demo.id(), null)).hasMessageContaining("免费演示");
    var other = fixture(1);
    assertThatThrownBy(() -> preview(other, pending.id(), null)).hasMessageContaining("不存在");
    assertThatThrownBy(
            () ->
                requests.createWithMaterials(
                    f.p(),
                    key(),
                    f.snapshot(),
                    "总结",
                    false,
                    List.of(),
                    false,
                    null,
                    "PREVIEW",
                    false,
                    null,
                    null,
                    null,
                    null,
                    pending.id()))
        .hasMessageContaining("仅支持普通完整讨论");
  }

  /** 同键重发保留原输入/基准，即使新配置变更也不重新派工；换基准409。 */
  @Test
  void idempotencyIncludesBaselineAndReplaysOriginalPlan() throws Exception {
    var f = fixture(80);
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      String key = key();
      var b =
          requests.createWithMaterials(
              f.p(),
              key,
              f.snapshot(),
              "总结所有评论",
              false,
              List.of(),
              false,
              null,
              "FULL",
              true,
              null,
              null,
              null,
              null,
              a.id());
      when(plans.discussionDigest()).thenReturn(plan(1000000001));
      assertThat(
              requests
                  .createWithMaterials(
                      f.p(),
                      key,
                      f.snapshot(),
                      "总结所有评论",
                      false,
                      List.of(),
                      false,
                      null,
                      "FULL",
                      false,
                      null,
                      null,
                      null,
                      null,
                      a.id())
                  .created())
          .isFalse();
      assertThatThrownBy(
              () ->
                  requests.createWithMaterials(
                      f.p(),
                      key,
                      f.snapshot(),
                      "总结所有评论",
                      false,
                      List.of(),
                      false,
                      null,
                      "FULL",
                      true,
                      null,
                      null,
                      null,
                      null,
                      null))
          .hasMessageContaining("其他讨论复用基准");
      assertThat(count("model_call", b.request().jobId())).isZero();
    }
  }

  /** 内容原文在接受时冻结，后续修订不漂移；模型输出可同时引评论和内容，角色与定位保留。 */
  @Test
  void bindsContentVersionAndPreservesOriginalLocations() throws Exception {
    var f = fixture(41);
    var m = material(f.p(), null, "明确说明这是角色台词，不代表作者观点。");
    var r = create(f, null, m.summary().id());
    material(f.p(), m.summary().id(), "更新说明不回写旧任务");
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      run(f.p(), r, gateway, () -> {});
    }
    var binding = requests.context(f.p(), r.id()).selection().content();
    assertThat(binding.materialId()).isEqualTo(m.summary().id());
    assertThat(binding.selectedSegments()).isEqualTo(1);
    var artifact = requests.artifact(f.p(), r.id());
    var evidence = findEvidence(artifact, "context-content-1");
    assertThat(evidence.path("sourceRole").asText()).isEqualTo("CONTENT_TEXT");
    assertThat(evidence.path("startChar").asInt()).isZero();
    assertThat(evidence.path("text").asText()).contains("角色台词");
    assertThat(provider.seenContent.get()).isPositive();
  }

  /** 内容选择不同使旧基准失效，不能假装已看同一背景；跨项目材料404。 */
  @Test
  void changedContentCannotReuseOldContext() throws Exception {
    var f = fixture(80);
    var m = material(f.p(), null, "原文背景");
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f, null, m.summary().id());
      run(f.p(), a, gateway, () -> {});
      var revised = material(f.p(), m.summary().id(), "修订后的背景");
      assertThatThrownBy(() -> preview(f, a.id(), revised.summary().id()))
          .hasMessageContaining("背景不同");
      var other = fixture(1);
      assertThatThrownBy(() -> preview(other, null, m.summary().id())).hasMessageContaining("不存在");
    }
  }

  /** 大材料未命中仍保留选择事实，0段不伪装为已理解全篇。 */
  @Test
  void unmatchedContentReportsExplicitGap() {
    var f = fixture(1);
    var m = material(f.p(), null, "资料".repeat(10000));
    var preview = preview(f, null, m.summary().id());
    assertThat(preview.content().selectedSegments()).isZero();
    assertThat(preview.content().notes()).anyMatch(n -> n.contains("没有可纳入"));
  }

  /** 片间取消尚无新调用，继续时仍使用原快照/冻结复用清单。 */
  @Test
  void cancelBetweenReusedMapsAndResumeOriginalRequest() throws Exception {
    var f = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var next = new Fixture(f.p(), f.work(), snapshot(f.p(), f.work(), 85, false));
      var b = create(next, a.id(), null);
      var checks = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  run(
                      f.p(),
                      b,
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 5) {
                          control.cancel(f.p(), "ANALYSIS", b.jobId());
                          throw new JobStore.LostLease();
                        }
                      }))
          .isInstanceOf(JobStore.LostLease.class);
      assertThat(count("model_call", b.jobId())).isZero();
      control.resume(f.p(), "ANALYSIS", b.jobId());
      run(f.p(), b, gateway, () -> {});
      assertThat(provider.calls).hasValue(5);
      assertThat(requests.get(f.p(), b.id()).snapshotId()).isEqualTo(next.snapshot());
    }
  }

  /** 新片成功后暂停，安全重试不重复发送该片，归并才新调用。 */
  @Test
  void retryKeepsSuccessfulFreshMapCheckpoint() throws Exception {
    var f = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var next = new Fixture(f.p(), f.work(), snapshot(f.p(), f.work(), 85, false));
      var b = create(next, a.id(), null);
      var checks = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  run(
                      f.p(),
                      b,
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 7)
                          throw new IllegalStateException("fixture pause");
                      }))
          .hasMessageContaining("fixture pause");
      assertThat(provider.calls).hasValue(4);
      jobs.fail(b.jobId(), jobs.get(b.jobId()).ownerToken(), "PIPELINE_ERROR");
      control.retry(f.p(), "ANALYSIS", b.jobId());
      run(f.p(), b, gateway, () -> {});
      assertThat(provider.calls).hasValue(5);
    }
  }

  /** FINAL发送后取消，迟到结果不能发布，未知费用仍阻止继续。 */
  @Test
  void inFlightFinalCancellationPreservesUnknownCost() throws Exception {
    var f = fixture(80);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider);
        var pool = Executors.newSingleThreadExecutor()) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var b = create(f, a.id(), null);
      provider.entered = new CountDownLatch(1);
      provider.release = new CountDownLatch(1);
      var future =
          pool.submit(
              () -> {
                run(f.p(), b, gateway, () -> {});
                return null;
              });
      try {
        assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue();
        control.cancel(f.p(), "ANALYSIS", b.jobId());
        provider.release.countDown();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
        assertThat(requests.get(f.p(), b.id()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> control.resume(f.p(), "ANALYSIS", b.jobId()))
            .hasMessageContaining("未决");
      } finally {
        provider.release.countDown();
      }
    }
  }

  /** 坏最终引用不发布，保留发送后未知费用，禁止直接重试。 */
  @Test
  void invalidFinalReferenceCannotPublish() throws Exception {
    var f = fixture(41);
    var r = create(f, null, null);
    var provider = new FixtureProvider();
    provider.invalidFinal = true;
    try (var gateway = gateway(provider)) {
      assertThatThrownBy(() -> run(f.p(), r, gateway, () -> {}))
          .hasMessageContaining("MODEL_OUTCOME_UNKNOWN");
    }
    jobs.fail(r.jobId(), jobs.get(r.jobId()).ownerToken(), "MODEL_OUTCOME_UNKNOWN");
    assertThatThrownBy(() -> requests.artifact(f.p(), r.id())).hasMessageContaining("尚未发布");
    assertThatThrownBy(() -> control.retry(f.p(), "ANALYSIS", r.jobId()))
        .hasMessageContaining("未决");
  }

  /** 首屏移除完整节点/原文大数组，节点分页保留所需原文和原分母，导出仍完整。 */
  @Test
  void pagedNodesAndReviewPacketAreReadOnly() throws Exception {
    var f = fixture(85);
    var r = create(f, null, null);
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      run(f.p(), r, gateway, () -> {});
    }
    var overview = reports.overview(f.p(), r.id());
    assertThat(overview.artifact().path("processing").path("nodes")).isEmpty();
    assertThat(overview.artifact().path("evidence").size()).isLessThan(85);
    var page = reports.nodes(f.p(), r.id(), 1, 2);
    assertThat(page.total()).isEqualTo(4);
    assertThat(page.items()).hasSize(2);
    assertThat(page.evidence()).isNotEmpty();
    var packet = json.readTree(reports.export(f.p(), r.id(), "review-json"));
    assertThat(packet.path("status").asText()).isEqualTo("DRAFT");
    assertThat(packet.path("independentQualityProven").asBoolean()).isFalse();
    assertThat(packet.path("annotations")).isEmpty();
    assertThat(packet.path("artifact").path("processing").path("nodes")).hasSize(4);
    assertThat(count("model_call", r.jobId())).isEqualTo(4);
  }

  /** 人工意见只追加；同署名更新取最新，其他署名冲突独立显示，不算准确率。 */
  @Test
  void reviewSummaryShowsCoverageAndDisagreementWithoutAccuracy() throws Exception {
    var f = fixture(1);
    var r = create(f, null, null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(f.p(), r, gateway, () -> {});
    }
    var empty = reviews.summary(f.p(), r.id());
    assertThat(empty.reviewedTargets()).isZero();
    reviews.create(
        f.p(),
        r.id(),
        key(),
        new SemanticReviewStore.Input(
            "ISSUE", "i", "SUPPORTED", "甲", "有原文依据", List.of("comment-1")));
    reviews.create(
        f.p(),
        r.id(),
        key(),
        new SemanticReviewStore.Input(
            "ISSUE", "i", "UNSUPPORTED", "甲", "修订核验意见", List.of("comment-1")));
    assertThat(reviews.summary(f.p(), r.id()).unsupportedTargets()).isEqualTo(1);
    reviews.create(
        f.p(),
        r.id(),
        key(),
        new SemanticReviewStore.Input(
            "ISSUE", "i", "SUPPORTED", "乙", "仍需对照", List.of("comment-1")));
    var summary = reviews.summary(f.p(), r.id());
    assertThat(summary.conflictingTargets()).isEqualTo(1);
    assertThat(summary.historyRecords()).isEqualTo(3);
    assertThat(summary.independentQualityProven()).isFalse();
    assertThat(summary.complete()).isTrue();
  }

  /** HTTP沿原入口支持完整讨论和基准分页，新节点/复核摘要接口仍核对项目。 */
  @Test
  void httpExposesFullDigestAndScopedReadEndpoints() throws Exception {
    var f = fixture(41);
    var r = create(f, null, null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(f.p(), r, gateway, () -> {});
    }
    String base = "/api/v1/projects/" + f.p() + "/analysis-requests";
    var candidates =
        http.getForEntity(base + "?baselineForSnapshotId=" + f.snapshot(), JsonNode.class);
    assertThat(candidates.getBody().path("total").asInt()).isEqualTo(1);
    assertThat(
            http.getForEntity(base + "/" + r.id() + "/synthesis-nodes?size=1", JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            http.getForEntity(base + "/" + r.id() + "/semantic-reviews/summary", JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    var other = fixture(1);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/"
                        + other.p()
                        + "/analysis-requests/"
                        + r.id()
                        + "/synthesis-nodes",
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    var body =
        Map.of(
            "snapshotId",
            f.snapshot(),
            "instruction",
            "总结所有评论",
            "analysisScope",
            "FULL",
            "baselineRequestId",
            r.id());
    var preview = http.postForEntity(base + "/preview", body, JsonNode.class);
    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(preview.getBody().path("reuse").path("reusedChunks").asInt()).isEqualTo(2);
  }

  /** 免费完整讨论仅规划，语义完成数/费用0，不作为复用基准。 */
  @Test
  void freeFullWorkflowDoesNotPretendSemanticCompletion() throws Exception {
    var configured =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.discussionDigest()).thenReturn(configured.discussionDigest());
    var f = fixture(41);
    var r = create(f, null, null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(f.p(), r, gateway, () -> {});
    }
    var artifact = requests.artifact(f.p(), r.id());
    assertThat(artifact.path("sourceMode").asText()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(artifact.path("limitations").toString())
        .contains("已执行语义分片为0")
        .doesNotContain("归并全部成功摘要");
    assertThat(artifact.path("processing").path("completedMaps").asInt()).isZero();
    assertThat(requests.progress(f.p(), r.id()).processing().phase()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(count("model_call", r.jobId())).isZero();
    assertThat(count("model_budget", r.jobId())).isZero();
  }

  /** 旧成功检查点损坏时关闭复用，不把失败悄悄变成新的收费调用。 */
  @Test
  void damagedSourceCheckpointIsNotReusable() throws Exception {
    var f = fixture(80);
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      db.update(
          "UPDATE model_batch SET input_hash=? WHERE job_id=? AND batch_index=0",
          "0".repeat(64),
          a.jobId());
      assertThatThrownBy(() -> preview(f, a.id(), null)).hasMessageContaining("检查点不可用");
      assertThat(requests.list(f.p(), 1, 20).total()).isEqualTo(1);
    }
  }

  /** 冻结响应被改写后，连只读进度也拒绝错误凭证，不等待执行到收费边界再发现。 */
  @Test
  void corruptedFrozenReuseCannotBeReadOrExecuted() throws Exception {
    var f = fixture(80);
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f, null, null);
      run(f.p(), a, gateway, () -> {});
      var b = create(f, a.id(), null);
      var node =
          json.readTree(
              db.queryForObject(
                  "SELECT manifest_json FROM analysis_request_scope WHERE request_id=?",
                  String.class,
                  b.id()));
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              node.path("reuse").path("entries").get(0).path("cached"))
          .put("responseHash", "broken");
      db.update(
          "UPDATE analysis_request_scope SET manifest_json=? WHERE request_id=?",
          node.toString(),
          b.id());
      assertThatThrownBy(() -> requests.progress(f.p(), b.id()))
          .hasMessageContaining("response hash mismatch");
      assertThat(count("model_call", b.jobId())).isZero();
    }
  }

  /** 完整报告的追问保留已选内容及旧原文，不会因为新增修订而自动换背景。 */
  @Test
  void continuationKeepsFrozenIndependentContent() throws Exception {
    var f = fixture(41);
    var m = material(f.p(), null, "明确区分角色台词与作者说法");
    var a = create(f, null, m.summary().id());
    try (var gateway = gateway(new FixtureProvider())) {
      run(f.p(), a, gateway, () -> {});
    }
    material(f.p(), m.summary().id(), "后续修订不属于原追问");
    String anchor = "raw-0";
    var child = requests.createFollowup(f.p(), key(), a.id(), anchor, "总结这条评论的理由", false).request();
    var frozen = requests.context(f.p(), child.id()).selection();
    assertThat(frozen.content().materialId()).isEqualTo(m.summary().id());
    assertThat(frozen.history().parentRequestId()).isEqualTo(a.id());
    assertThat(frozen.sources())
        .anySatisfy(
            source -> {
              assertThat(source.role()).isEqualTo(AnalysisContextStore.Role.CONTENT_TEXT);
              assertThat(source.text()).contains("角色台词");
            });
    assertThat(count("model_call", child.jobId())).isZero();
  }

  /** 合成项目、作品与快照身份。 */
  private record Fixture(String p, String work, String snapshot) {}

  /** 每例保留独立项目，不清理其他或历史数据。 */
  private Fixture fixture(int count) {
    String p = projects.create(key(), "完整讨论（合成）", "工程协议验收").project().id(), work = key();
    return new Fixture(p, work, snapshot(p, work, count, false));
  }

  /** 同一作品版本保留稳定sourceCommentId，原ID/列表顺序可变化。 */
  private String snapshot(String p, String work, int count, boolean reverse) {
    var time = OffsetDateTime.parse("2026-10-20T00:00:00Z");
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int n = 0; n < count; n++)
      comments.add(
          new ImportDocument.Comment(
              (reverse ? "new-" : "raw-") + n,
              String.format("source%05d", n),
              null,
              "合成论点与理由" + n,
              time));
    if (reverse) Collections.reverse(comments);
    return imports
        .ingest(
            p,
            new ImportDocument(
                "1.0",
                new ImportDocument.Content(
                    "item-" + work, "SYNTHETIC", work, "creator", "合成讨论", time),
                comments,
                List.of()))
        .snapshotId();
  }

  /** 新请求显式确认完整范围；服务器自己决定模型和价格。 */
  private AnalysisRequestStore.RequestRow create(Fixture f, String baseline, String material) {
    return requests
        .createWithMaterials(
            f.p(),
            key(),
            f.snapshot(),
            "总结所有评论",
            false,
            List.of(),
            false,
            null,
            "FULL",
            true,
            null,
            material,
            null,
            null,
            baseline)
        .request();
  }

  /** 只读预检。 */
  private AnalysisPreflight.View preview(Fixture f, String baseline, String material) {
    return preflight.previewWithContent(
        f.p(),
        f.snapshot(),
        false,
        List.of(),
        "总结所有评论",
        null,
        "FULL",
        null,
        material,
        null,
        null,
        baseline);
  }

  /** 按原DISCUSSION→ARTIFACT步骤执行，生产中的状态/租约/发布方法不替换。 */
  private void run(
      String p, AnalysisRequestStore.RequestRow r, ModelGateway gateway, Runnable check)
      throws Exception {
    String token = jobs.claim(r.jobId());
    var plan = jobs.plan(r.jobId());
    var input =
        json.readValue(inputs.snapshot(r.snapshotId()).documentJson(), ImportDocument.class);
    jobs.startStep(r.jobId(), token, "DISCUSSION");
    var artifact =
        new DiscussionAnalysisService(
                new StructuralDiscussionAnalyzer(), gateway, new ThreadContextSelector())
            .analyze(
                jobs.get(r.jobId()),
                token,
                plan,
                r,
                input,
                key(),
                check,
                requests.context(p, r.id()),
                null,
                requests.scope(p, r.id()));
    check.run();
    jobs.saveDiscussion(r.jobId(), token, json.writeValueAsString(artifact));
    jobs.startStep(r.jobId(), token, "ARTIFACT");
    jobs.publishArtifact(
        r.jobId(),
        token,
        artifact.artifactId(),
        r.id(),
        r.snapshotId(),
        artifact.schemaVersion(),
        json.writeValueAsString(artifact));
  }

  /** 原网关统一限制线程和未知费用。 */
  private ModelGateway gateway(FixtureProvider p) {
    return new ModelGateway(ledger, json, List.of(), List.of(p));
  }

  /** 合成独立内容，不附加评论或假装同作品认证。 */
  private ContentMaterialStore.Material material(String p, String previous, String text) {
    return materials.create(
        p, key(), "内容对照（合成）", "测试资料", previous, "TXT", text.getBytes(StandardCharsets.UTF_8));
  }

  /** 模拟单位固定，每个调用2输入＋3输出。 */
  private ModelContract.Plan plan(long budget) {
    return new ModelContract.Plan(
        "DIGEST_FIXTURE",
        "fixture",
        DiscussionDigestPlan.PROMPT,
        DiscussionDigestPlan.TAXONOMY,
        40,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        budget,
        10000,
        "fixture",
        "LOCAL_STUB");
  }

  /** 只查询当前合成任务。 */
  private long count(String table, String job) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE job_id=?", Long.class, job);
  }

  /** 从不可变原文集合解析引用。 */
  private JsonNode findEvidence(JsonNode artifact, String id) {
    for (var e : artifact.path("evidence")) if (e.path("id").asText().equals(id)) return e;
    throw new AssertionError("Missing fixture evidence");
  }

  /** 新增身份。 */
  private String key() {
    return UUID.randomUUID().toString();
  }

  /** 模拟有证据的有界图，不代表真实语义质量。 */
  private static final class FixtureProvider implements DiscussionModelContract.Provider {
    final AtomicInteger calls = new AtomicInteger(), seenContent = new AtomicInteger();
    boolean invalidFinal;
    CountDownLatch entered, release;

    /** 固定供应商身份。 */
    public String id() {
      return "DIGEST_FIXTURE";
    }

    /** 固定模型身份。 */
    public String modelVersion() {
      return "fixture";
    }

    /** 不接受旧协议误路由。 */
    public void validatePlan(ModelContract.Plan p) {
      assertThat(DiscussionDigestPlan.enabled(p)).isTrue();
    }

    /** 本用例不应走旧有界讨论。 */
    public DiscussionModelContract.Response analyze(DiscussionModelContract.Request r, Duration d) {
      throw new AssertionError("Unexpected legacy call");
    }

    /** MAP引末目标，归并引末子摘要；可阻塞FINAL或制造坏引用。 */
    public DiscussionModelContract.Response digest(DiscussionDigestContract.Request r, Duration d) {
      calls.incrementAndGet();
      var ids = new ArrayList<String>();
      if ("MAP".equals(r.phase())) ids.add(r.targetCommentIds().getLast());
      else ids.add(r.summaries().getLast().issues().getFirst().evidenceIds().getFirst());
      if (!r.context().isEmpty()) {
        ids.add(r.context().getFirst().evidenceId());
        seenContent.incrementAndGet();
      }
      if ("FINAL".equals(r.phase())) {
        if (invalidFinal) ids.set(0, "invented-comment");
        if (entered != null) entered.countDown();
        if (release != null)
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new IllegalStateException("fixture timeout");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
      }
      return new DiscussionModelContract.Response(
          List.of(new DiscussionModelContract.Issue("i", "争议是什么", "工程摘要，尚未独立核验", List.copyOf(ids))),
          List.of(
              new DiscussionModelContract.Claim(
                  "c",
                  "i",
                  "保留条件和反例",
                  DiscussionModelContract.ReasoningStatus.REASONED,
                  List.copyOf(ids))),
          List.of(),
          r.includeAssessment()
              ? List.of(
                  new DiscussionModelContract.Assessment(
                      "i",
                      DiscussionModelContract.Verdict.INSUFFICIENT,
                      "不足以下结论",
                      "只是合成测试",
                      List.copyOf(ids)))
              : List.of(),
          2,
          3,
          "fixture");
    }
  }
}
