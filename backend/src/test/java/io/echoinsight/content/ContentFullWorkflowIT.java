/* 文件职责：在隔离MySQL范围核对全量内容的接受、预算、原批次恢复、取消、汇总引用和兼容。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

/** 内存供应商的工程用例，不访问平台/DeepSeek，不删除测试或用户历史。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=content-full-it",
      "echoinsight.worker.execution-scope=content-full-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ContentFullWorkflowIT {
  @Autowired ContentMaterialStore materials;
  @Autowired ContentAnalysisStore analyses;
  @Autowired ProjectStore projects;
  @Autowired JobStore jobs;
  @Autowired AnalysisWorker worker;
  @Autowired ModelLedger ledger;
  @Autowired TaskControlService control;
  @Autowired ProjectTaskQuery tasks;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @MockitoBean ConfiguredModelPlan plans;

  /** 每次明确免费计划，不读取正式凭据或修改价格。 */
  @BeforeEach
  void defaults() {
    var configured =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.content()).thenReturn(configured.content());
    when(plans.contentFull()).thenReturn(configured.contentFull());
  }

  /** 全量预检纳入尾部且只读，无任务/账本；200000字计划17片和1次汇总。 */
  @Test
  void previewFreezesAllTextAndDoesNotEnqueue() {
    String p = project();
    var m = material(p, "甲".repeat(200000));
    var preview = analyses.preview(p, m.summary().id(), "总结全文", null, null, "FULL");
    assertThat(preview.input().selection().selectedChars()).isEqualTo(200000);
    assertThat(preview.input().selection().omittedSegments()).isZero();
    assertThat(preview.input().fullPlan().chunks()).hasSize(17);
    assertThat(preview.plannedCalls()).isEqualTo(18);
    assertThat(preview.reservationUnits()).isZero();
    assertThat(analyses.list(p, 1, 20).total()).isZero();
    assertThat(tasks.list(p, "ALL", "ALL", 1, 20).total()).isZero();
  }

  /** 新旧模式不同身份，重发复用原任务；显式PREFIX与旧省略字段保持兼容。 */
  @Test
  void keepsModeSpecificIdempotencyAndLegacyReplay() {
    String p = project(), key = key();
    var m = material(p, "甲".repeat(14000));
    var full = analyses.create(p, key, m.summary().id(), "总结", null, null, false, "FULL");
    assertThat(analyses.create(p, key, m.summary().id(), "总结", null, null, false, "FULL").created())
        .isFalse();
    assertThatThrownBy(
            () -> analyses.create(p, key, m.summary().id(), "总结", null, null, false, "PREFIX"))
        .hasMessageContaining("请求键");
    String oldKey = key();
    var old = analyses.create(p, oldKey, m.summary().id(), "总结", null, null, true);
    assertThat(
            analyses
                .create(p, oldKey, m.summary().id(), "总结", null, null, true, "PREFIX")
                .analysis()
                .jobId())
        .isEqualTo(old.analysis().jobId());
    assertThat(analyses.list(p, 1, 20).items())
        .anySatisfy(
            item -> {
              assertThat(item.jobId()).isEqualTo(full.analysis().jobId());
              assertThat(item.coverageMode()).isEqualTo("FULL");
            });
    assertThat(analyses.list(p, 1, 20).items())
        .anySatisfy(
            item -> {
              assertThat(item.jobId()).isEqualTo(old.analysis().jobId());
              assertThat(item.coverageMode()).isEqualTo("PREFIX");
            });
    assertThat(jobs.plan(full.analysis().jobId()).promptVersion()).isEqualTo(ContentReuse.PROMPT);
    assertThat(jobs.plan(old.analysis().jobId()).promptVersion())
        .isEqualTo(ContentModelContract.PROMPT);
  }

  /** 预算必须覆盖所有计划调用上界，不能只验证第一片够付费。 */
  @Test
  void refusesUnderfundedFullPlanBeforeCreatingJob() {
    when(plans.contentFull()).thenReturn(plan(20000));
    String p = project();
    var m = material(p, "甲".repeat(14000));
    var preview = analyses.preview(p, m.summary().id(), "总结", null, null, "FULL");
    assertThat(preview.perCallReservationUnits()).isEqualTo(14096);
    assertThat(preview.reservationUnits()).isEqualTo(42288);
    assertThat(preview.budgetSufficient()).isFalse();
    assertThatThrownBy(() -> full(p, m)).hasMessageContaining("预算不足");
    assertThat(analyses.list(p, 1, 20).total()).isZero();
  }

  /** 非法方式、空时间窗拒绝，不静默回退有限输入。 */
  @Test
  void refusesInvalidModeAndEmptyInput() {
    String p = project();
    var m = material(p, "原文");
    assertThatThrownBy(() -> analyses.preview(p, m.summary().id(), "总结", null, null, "AUTO"))
        .hasMessageContaining("FULL或PREFIX");
    var sub =
        materials.create(
            p, key(), "字幕", "合成", null, "SRT", bytes("1\n00:00:00,000 --> 00:00:01,000\n原文"));
    assertThatThrownBy(() -> analyses.preview(p, sub.summary().id(), "总结", 2000L, 3000L, "FULL"))
        .hasMessageContaining("没有正文");
  }

  /** 免费成功仅表示结构计划可用，没有虚构分片语义完成数或收费记录。 */
  @Test
  void freeWorkerPublishesPlanOnlyWithNoModelCalls() {
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    worker.runOne(accepted.jobId());
    var view = analyses.get(p, accepted.jobId());
    assertThat(view.status()).isEqualTo("SUCCESS");
    assertThat(view.artifact().path("schemaVersion").asText()).isEqualTo("content-artifact-v2");
    assertThat(view.artifact().path("findings")).isEmpty();
    assertThat(view.artifact().path("processing").path("completedChunks").asInt()).isZero();
    assertThat(view.progress().completedChunks()).isZero();
    assertThat(view.progress().phase()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(count("model_call", accepted.jobId())).isZero();
    assertThat(count("model_budget", accepted.jobId())).isZero();
  }

  /** 每片和汇总都经原账本，汇总能引用尾片，原文和覆盖分母完整。 */
  @Test
  void fullModelCallsAllChunksAndSynthesisWithOriginalEvidence() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var m = material(p, "前".repeat(12000) + "尾".repeat(2000));
    var accepted = full(p, m);
    var calls = new AtomicInteger();
    try (var gateway = gateway(provider(calls, null, null, false))) {
      run(accepted.jobId(), gateway, () -> {});
      var view = analyses.get(p, accepted.jobId());
      assertThat(view.status()).isEqualTo("SUCCESS");
      assertThat(calls).hasValue(3);
      assertThat(view.progress().completedChunks()).isEqualTo(2);
      assertThat(view.progress().successfulCalls()).isEqualTo(3);
      assertThat(view.artifact().path("findings").get(0).path("evidenceIds").get(0).asText())
          .isEqualTo("text-7");
      assertThat(view.artifact().path("evidence").get(6).path("text").asText())
          .isEqualTo("尾".repeat(2000));
      assertThat(view.artifact().path("processing").path("chunks")).hasSize(2);
      assertThat(ledger.view(accepted.jobId(), 1, 30).budget().settledUnits()).isEqualTo(15);
    }
  }

  /** 已结算首片后安全中断，继续复用缓存，不重发成功片且不换新材料版本。 */
  @Test
  void safelyRetriesBetweenChunksWithoutPayingAgain() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var m = material(p, "旧".repeat(14000));
    var accepted = full(p, m);
    var calls = new AtomicInteger();
    var checks = new AtomicInteger();
    try (var gateway = gateway(provider(calls, null, null, false))) {
      assertThatThrownBy(
              () ->
                  run(
                      accepted.jobId(),
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 3)
                          throw new IllegalStateException("fixture pause");
                      }))
          .hasMessageContaining("fixture pause");
      assertThat(calls).hasValue(1);
      String original =
          db.queryForObject(
              "SELECT result_json FROM model_batch WHERE job_id=? AND batch_index=0",
              String.class,
              accepted.jobId());
      jobs.fail(accepted.jobId(), jobs.get(accepted.jobId()).ownerToken(), "PIPELINE_ERROR");
      materials.create(p, key(), "修订", "合成", m.summary().id(), "TXT", bytes("新".repeat(14000)));
      control.retry(p, "ANALYSIS", accepted.jobId());
      run(accepted.jobId(), gateway, () -> {});
      assertThat(calls).hasValue(3);
      assertThat(analyses.get(p, accepted.jobId()).artifact().path("material").path("id").asText())
          .isEqualTo(m.summary().id());
      assertThat(
              db.queryForObject(
                  "SELECT result_json FROM model_batch WHERE job_id=? AND batch_index=0",
                  String.class,
                  accepted.jobId()))
          .isEqualTo(original);
    }
  }

  /** 片间取消没有在途收费，继续仍为同一任务且复用首片。 */
  @Test
  void cancelsBetweenChunksAndResumesOriginalTask() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    var calls = new AtomicInteger();
    var checks = new AtomicInteger();
    try (var gateway = gateway(provider(calls, null, null, false))) {
      assertThatThrownBy(
              () ->
                  run(
                      accepted.jobId(),
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 3) {
                          control.cancel(p, "ANALYSIS", accepted.jobId());
                          throw new JobStore.LostLease();
                        }
                      }))
          .isInstanceOf(JobStore.LostLease.class);
      assertThat(analyses.get(p, accepted.jobId()).progress().completedChunks()).isEqualTo(1);
      control.resume(p, "ANALYSIS", accepted.jobId());
      run(accepted.jobId(), gateway, () -> {});
      assertThat(calls).hasValue(3);
      assertThat(analyses.get(p, accepted.jobId()).status()).isEqualTo("SUCCESS");
    }
  }

  /** 汇总坏引用发生在已发送后：保留未知费用，不发布，也不安全重试。 */
  @Test
  void invalidSynthesisEvidenceRetainsUnknownCost() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    try (var gateway = gateway(provider(new AtomicInteger(), null, null, true))) {
      assertThatThrownBy(() -> run(accepted.jobId(), gateway, () -> {}))
          .hasMessageContaining("MODEL_OUTCOME_UNKNOWN");
      jobs.fail(accepted.jobId(), jobs.get(accepted.jobId()).ownerToken(), "MODEL_OUTCOME_UNKNOWN");
      var view = analyses.get(p, accepted.jobId());
      assertThat(view.progress().completedChunks()).isEqualTo(2);
      assertThat(view.progress().synthesisStatus()).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(view.artifact()).isNull();
      assertThat(ledger.view(accepted.jobId(), 1, 30).budget().reservedUnits()).isPositive();
      assertThatThrownBy(() -> control.retry(p, "ANALYSIS", accepted.jobId()))
          .hasMessageContaining("未决");
    }
  }

  /** 汇总请求在途取消，迟到结果不得写入产物或改变取消状态。 */
  @Test
  void inFlightSynthesisCannotPublishAfterCancellation() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var gateway = gateway(provider(new AtomicInteger(), entered, release, false));
        var pool = Executors.newSingleThreadExecutor()) {
      var future =
          pool.submit(
              () -> {
                run(accepted.jobId(), gateway, () -> {});
                return null;
              });
      assertThat(entered.await(4, TimeUnit.SECONDS)).isTrue();
      control.cancel(p, "ANALYSIS", accepted.jobId());
      release.countDown();
      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
      assertThat(analyses.get(p, accepted.jobId()).status()).isEqualTo("CANCELLED");
      assertThat(analyses.get(p, accepted.jobId()).artifact()).isNull();
      assertThatThrownBy(() -> control.resume(p, "ANALYSIS", accepted.jobId()))
          .hasMessageContaining("未决");
    } finally {
      release.countDown();
    }
  }

  /** 成功CONTENT草稿在发布前中断，恢复连汇总也不重新发送。 */
  @Test
  void resumesFinalDraftWithoutAnyAdditionalCall() throws Exception {
    when(plans.contentFull()).thenReturn(plan(1000000));
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    var calls = new AtomicInteger();
    try (var gateway = gateway(provider(calls, null, null, false))) {
      assertThatThrownBy(
              () ->
                  run(
                      accepted.jobId(),
                      gateway,
                      () -> {
                        if ("SUCCEEDED".equals(jobs.steps(accepted.jobId()).getFirst().status()))
                          throw new IllegalStateException("fixture before publication");
                      }))
          .hasMessageContaining("fixture before publication");
      String draft = jobs.steps(accepted.jobId()).getFirst().resultJson();
      jobs.fail(accepted.jobId(), jobs.get(accepted.jobId()).ownerToken(), "PIPELINE_ERROR");
      control.retry(p, "ANALYSIS", accepted.jobId());
      run(accepted.jobId(), gateway, () -> {});
      assertThat(calls).hasValue(3);
      assertThat(jobs.steps(accepted.jobId()).getFirst().resultJson()).isEqualTo(draft);
    }
  }

  /** 旧冻结JSON没有fullPlan仍可免费执行，旧v1产物协议不回填为v2。 */
  @Test
  void legacyFrozenJsonRemainsReadableAndRunsV1() {
    String p = project();
    var m = material(p, "旧内容");
    var old = analyses.create(p, key(), m.summary().id(), "总结", null, null, false).analysis();
    String frozen =
        db.queryForObject(
            "SELECT selection_json FROM content_analysis_input WHERE job_id=?",
            String.class,
            old.jobId());
    var node = json.valueToTree(old.input());
    ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("fullPlan");
    String legacy = node.toString();
    db.update(
        "UPDATE content_analysis_input SET selection_json=?,selection_sha256=? WHERE job_id=?",
        legacy,
        io.echoinsight.material.ReferenceMaterialStore.hash(legacy),
        old.jobId());
    worker.runOne(old.jobId());
    assertThat(analyses.get(p, old.jobId()).artifact().path("schemaVersion").asText())
        .isEqualTo("content-artifact-v1");
  }

  /** HTTP沿用原接口；FULL与预检进度可见，越项目仍404。 */
  @Test
  void httpSupportsFullModeAndOriginalProjectBoundary() {
    String p = project();
    var m = material(p, "甲".repeat(14000));
    String path = "/api/v1/projects/" + p + "/content-analyses";
    var body =
        Map.of("materialId", m.summary().id(), "instruction", "总结全文", "coverageMode", "FULL");
    var preview =
        http.postForEntity(path + "/preview", body, com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(preview.getBody().path("plannedCalls").asInt()).isEqualTo(3);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    var created =
        http.postForEntity(
            path, new HttpEntity<>(body, headers), com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = created.getBody().path("jobId").asText();
    assertThat(created.getBody().path("progress").path("plannedChunks").asInt()).isEqualTo(2);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + project() + "/content-analyses/" + id, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** 超过24片的真实HTTP拒绝必须说明上限和下一动作，不能被通用422消息遮蔽。 */
  @Test
  void oversizedSubtitleReturnsReadableCapacityReasonWithoutTask() {
    String p = project();
    var source = new StringBuilder();
    for (int i = 0; i < 2401; i++) {
      int a = i * 2, b = a + 2;
      source
          .append(i + 1)
          .append("\n")
          .append(
              String.format(
                  "%02d:%02d:%02d,000 --> %02d:%02d:%02d,000\n原文\n\n",
                  a / 3600, a / 60 % 60, a % 60, b / 3600, b / 60 % 60, b % 60));
    }
    var m = materials.create(p, key(), "密集字幕", "合成", null, "SRT", bytes(source.toString()));
    var result =
        http.postForEntity(
            "/api/v1/projects/" + p + "/content-analyses/preview",
            Map.of("materialId", m.summary().id(), "instruction", "总结", "coverageMode", "FULL"),
            com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(result.getBody().path("message").asText()).contains("24片", "缩小字幕时间范围");
    assertThat(analyses.list(p, 1, 20).total()).isZero();
  }

  /** 新任务材料和输入只追加，项目仍不产生假作品或评论快照。 */
  @Test
  void fullTaskKeepsOriginalProjectTaskIdentity() {
    String p = project();
    var accepted = full(p, material(p, "甲".repeat(14000)));
    var item = tasks.list(p, "ANALYSIS", "ALL", 1, 20).items().getFirst();
    assertThat(item.id()).isEqualTo(accepted.jobId());
    assertThat(item.snapshotId()).isNull();
    assertThat(projects.get(p).snapshotCount()).isZero();
    assertThat(projects.get(p).jobCount()).isEqualTo(1);
  }

  /** 领取原任务后直接执行内容服务，注入回调制造明确检查点。 */
  private void run(String id, ModelGateway gateway, Runnable check) throws Exception {
    new ContentAnalysisService(analyses, jobs, gateway, json)
        .run(jobs.get(id), jobs.claim(id), jobs.plan(id), check);
  }

  /** 注册唯一内存内容供应商，原网关/账本负责所有调用。 */
  private ModelGateway gateway(ContentModelContract.Provider provider) {
    return new ModelGateway(ledger, json, List.of(), List.of(), List.of(provider));
  }

  /** 模拟MAP/REDUCE明确分流；可阻塞汇总或返回坏引用，不包含真实供应商调用。 */
  private ContentModelContract.Provider provider(
      AtomicInteger calls, CountDownLatch entered, CountDownLatch release, boolean invalid) {
    return new ContentModelContract.Provider() {
      /** 固定供应商身份。 */
      public String id() {
        return "CONTENT_FULL_FIXTURE";
      }

      /** 固定模型身份。 */
      public String modelVersion() {
        return "content-full-fixture";
      }

      /** 不允许退回旧协议。 */
      public void validatePlan(ModelContract.Plan plan) {
        assertThat(plan.promptVersion()).isEqualTo(ContentFullContract.PROMPT);
      }

      /** 旧入口不能意外承接完整协议。 */
      public ContentModelContract.Response analyze(
          ContentModelContract.Request request, Duration timeout) {
        throw new AssertionError("Unexpected legacy call");
      }

      /** 每片引用自己的末段，汇总引用尾片，便于核对后文未丢失。 */
      public ContentModelContract.Response analyzeFull(
          ContentFullContract.Request request, Duration timeout) {
        calls.incrementAndGet();
        String evidence;
        if ("MAP".equals(request.phase())) evidence = request.selection().segments().getLast().id();
        else {
          if (entered != null) entered.countDown();
          if (release != null)
            try {
              if (!release.await(4, TimeUnit.SECONDS))
                throw new IllegalStateException("fixture timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
          evidence =
              invalid
                  ? "invented-source"
                  : request.summaries().getLast().findings().getFirst().evidenceIds().getFirst();
        }
        return new ContentModelContract.Response(
            List.of(
                new ContentModelContract.Finding(
                    "工程归纳",
                    ContentModelContract.Kind.OBSERVATION,
                    "仅验证协议、恢复与原文引用，不作为语义准确率样本。",
                    List.of(evidence))),
            List.of("尚未独立验证语义质量"),
            2,
            3,
            "fixture");
      }
    };
  }

  /** 统一模拟价格/预算，支持验证全部预留上界。 */
  private ModelContract.Plan plan(long budget) {
    return new ModelContract.Plan(
        "CONTENT_FULL_FIXTURE",
        "content-full-fixture",
        ContentFullContract.PROMPT,
        ContentFullContract.TAXONOMY,
        40,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        budget,
        10000,
        "content-full-fixture",
        "LOCAL_STUB");
  }

  /** 新材料身份，不覆盖已有文件或数据库行。 */
  private ContentMaterialStore.Material material(String p, String text) {
    return materials.create(p, key(), "完整内容合成材料", "本地工程验收", null, "TXT", bytes(text));
  }

  /** 用户明确选择FULL，不同意裁剪也不需要截取前缀。 */
  private ContentAnalysisStore.View full(String p, ContentMaterialStore.Material m) {
    return analyses
        .create(p, key(), m.summary().id(), "总结完整所选范围", null, null, false, "FULL")
        .analysis();
  }

  /** 只查询本测试原任务记录，不清理账本。 */
  private long count(String table, String id) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE job_id=?", Long.class, id);
  }

  /** 合成项目位于隔离工作空间。 */
  private String project() {
    return projects.create(key(), "完整内容（合成）", "验证工作流与证据").project().id();
  }

  /** 显式UTF8。 */
  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 唯一追加键，保留全部历史。 */
  private String key() {
    return UUID.randomUUID().toString();
  }
}
