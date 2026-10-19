/* 文件职责：独立MySQL范围验收无评论内容版本、冻结任务、共享网关/账本/取消/恢复及HTTP关系。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 真实数据库、内存供应商、合成材料；不会连接平台/DeepSeek，不清理用户历史或业务资料。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=content-workflow-it",
      "echoinsight.worker.execution-scope=content-workflow-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ContentWorkflowIT {
  @Autowired ContentMaterialStore materials;
  @Autowired ContentAnalysisStore analyses;
  @Autowired ProjectStore projects;
  @Autowired AnalysisWorker worker;
  @Autowired JobStore jobs;
  @Autowired ProjectTaskQuery tasks;
  @Autowired TaskControlService control;
  @Autowired ModelLedger ledger;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @MockitoBean ConfiguredModelPlan plans;

  /** 默认免费；需要网关的单个测试才指定内存供应商计划，不改真实运行配置。 */
  @BeforeEach
  void freePlan() {
    when(plans.content())
        .thenReturn(
            new ConfiguredModelPlan(
                    new ModelSettings(false, false, null, null, null, null, null, null))
                .content());
  }

  /** 无评论也能保存材料；没有插入data_snapshot、project_snapshot或虚构评论。 */
  @Test
  void materialDoesNotRequireOrInventComments() {
    String p = project();
    var m = material(p, "TXT", "一份独立讲稿");
    assertThat(m.summary().textChars()).isEqualTo(6);
    assertThat(projects.snapshots(p, 1, 20).total()).isZero();
    assertThat(projects.get(p).workCount()).isZero();
    assertThat(projects.get(p).contentMaterialCount()).isEqualTo(1);
  }

  /** 同键同上传复用；改变原文/标题/父版本不覆盖旧版本。 */
  @Test
  void materialUploadIsIdempotentAndConflictsPreserveOldText() {
    String p = project(), key = key();
    var a = materials.create(p, key, "讲稿", "合成来源", null, "TXT", bytes("原文"));
    assertThat(materials.create(p, key, "讲稿", "合成来源", null, "TXT", bytes("原文")).summary().id())
        .isEqualTo(a.summary().id());
    assertThatThrownBy(() -> materials.create(p, key, "讲稿", "合成来源", null, "TXT", bytes("换原文")))
        .hasMessageContaining("请求键");
    assertThat(materials.get(p, a.summary().id()).segments().getFirst().text()).isEqualTo("原文");
    assertThat(materials.list(p, 1, 20).total()).isEqualTo(1);
  }

  /** 修订记录明确父版本，旧版不被隐藏；其他项目不能引用这个父版本。 */
  @Test
  void appendsVersionsAndRejectsForeignParent() {
    String p = project();
    var first = material(p, "TXT", "旧稿");
    var revised =
        materials.create(p, key(), "新版", "用户声明", first.summary().id(), "MD", bytes("# 新稿"));
    assertThat(revised.summary().previousId()).isEqualTo(first.summary().id());
    assertThat(revised.summary().versionDepth()).isEqualTo(2);
    assertThat(materials.list(p, 1, 20).total()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                materials.create(
                    project(), key(), "错项目", "", first.summary().id(), "TXT", bytes("稿")))
        .hasMessageContaining("不存在");
  }

  /** 预检只读，文字较长也不静默建任务或预算，明确呈现遗漏。 */
  @Test
  void previewIsReadOnlyAndReportsPartialRange() {
    String p = project();
    var m = material(p, "TXT", "甲".repeat(14000));
    var preview = analyses.preview(p, m.summary().id(), "总结全文", null, null);
    assertThat(preview.requiresPartialConsent()).isTrue();
    assertThat(preview.input().selection().selectedChars()).isEqualTo(12000);
    assertThat(preview.input().selection().omittedSegments()).isEqualTo(1);
    assertThat(analyses.list(p, 1, 20).total()).isZero();
    assertThat(tasks.list(p, "ALL", "ALL", 1, 20).total()).isZero();
  }

  /** 没同意裁剪、时间范围空或类型不符，都在派工前拒绝。 */
  @Test
  void rejectsUnconfirmedPartialAndEmptyOrInvalidWindow() {
    String p = project();
    var m = material(p, "TXT", "甲".repeat(14000));
    assertThatThrownBy(() -> analyses.create(p, key(), m.summary().id(), "总结", null, null, false))
        .hasMessageContaining("明确同意");
    assertThatThrownBy(() -> analyses.create(p, key(), m.summary().id(), "总结", 0L, 1000L, false))
        .hasMessageContaining("只有字幕");
    var subtitle = material(p, "SRT", subtitle());
    assertThatThrownBy(
            () -> analyses.create(p, key(), subtitle.summary().id(), "总结", 5000L, 6000L, false))
        .hasMessageContaining("没有正文");
    assertThat(analyses.list(p, 1, 20).total()).isZero();
  }

  /** 相同接受键固定原输入；修改范围或问题409，不重复创建第二个任务。 */
  @Test
  void acceptsOnceAndRejectsChangedQuestionOrScope() {
    String p = project();
    var m = material(p, "TXT", "独立内容");
    String key = key();
    var first = analyses.create(p, key, m.summary().id(), "总结", null, null, false);
    assertThat(first.created()).isTrue();
    assertThat(analyses.create(p, key, m.summary().id(), "总结", null, null, false).created())
        .isFalse();
    assertThatThrownBy(() -> analyses.create(p, key, m.summary().id(), "换个问题", null, null, false))
        .hasMessageContaining("该请求键");
    assertThat(analyses.list(p, 1, 20).total()).isEqualTo(1);
    assertThat(jobs.get(first.analysis().jobId()).snapshotId()).isNull();
  }

  /** 模型关闭时原Worker能成功发布明确结构演示，没有语义标签或费用账本。 */
  @Test
  void originalWorkerPublishesFreeStructuralContentArtifact() {
    String p = project();
    var accepted = create(p, material(p, "TXT", "合成原文，待分析。"));
    worker.runOne(accepted.jobId());
    var done = analyses.get(p, accepted.jobId());
    assertThat(done.status()).isEqualTo("SUCCESS");
    assertThat(done.artifact().path("schemaVersion").asText()).isEqualTo("content-artifact-v1");
    assertThat(done.artifact().path("sourceMode").asText()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(done.artifact().path("findings")).isEmpty();
    assertThat(done.artifact().path("evidence").get(0).path("text").asText())
        .isEqualTo("合成原文，待分析。");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, accepted.jobId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_request WHERE job_id=?",
                Integer.class,
                accepted.jobId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_artifact WHERE job_id=? AND request_id IS NULL AND"
                    + " snapshot_id IS NULL",
                Integer.class,
                accepted.jobId()))
        .isEqualTo(1);
  }

  /** 新材料版本不能漂移原请求的标题/时间/原文，报告只引用原冻结段。 */
  @Test
  void oldInputAndTimeEvidenceRemainFrozenAfterNewVersion() {
    String p = project();
    var old = material(p, "SRT", subtitle());
    var accepted =
        analyses.create(p, key(), old.summary().id(), "第一段说了什么", 500L, 1500L, false).analysis();
    String frozen = jsonText(analyses.byJob(accepted.jobId()).frozen());
    materials.create(
        p,
        key(),
        "新标题",
        "来源更改",
        old.summary().id(),
        "SRT",
        bytes(subtitle().replace("旧字幕", "完全不同的新字幕")));
    worker.runOne(accepted.jobId());
    var report = analyses.get(p, accepted.jobId()).artifact();
    assertThat(jsonText(analyses.byJob(accepted.jobId()).frozen())).isEqualTo(frozen);
    assertThat(report.path("material").path("id").asText()).isEqualTo(old.summary().id());
    assertThat(report.path("evidence").get(0).path("startMs").asLong()).isZero();
    assertThat(report.toString()).contains("旧字幕").doesNotContain("完全不同");
  }

  /** 项目/执行范围必须一致；共享控制接口也不能越项目取消内容任务。 */
  @Test
  void contentAnalysisAndControlRespectProjectAndExecutionScope() {
    String p = project();
    var m = material(p, "TXT", "原文");
    var accepted = create(p, m);
    String other = project();
    assertThatThrownBy(() -> materials.get(other, m.summary().id())).hasMessageContaining("不存在");
    assertThatThrownBy(() -> analyses.get(other, accepted.jobId())).hasMessageContaining("不存在");
    assertThatThrownBy(() -> control.cancel(other, "ANALYSIS", accepted.jobId()))
        .hasMessageContaining("不存在");
    db.update(
        "UPDATE analysis_job SET execution_scope='content-foreign-it' WHERE id=?",
        accepted.jobId());
    assertThat(analyses.list(p, 1, 20).total()).isZero();
    assertThatThrownBy(() -> analyses.get(p, accepted.jobId())).hasMessageContaining("不存在");
  }

  /** 统一任务列表展示内容身份与中文标题，项目数量准确且不伪造作品快照。 */
  @Test
  void taskCenterAndProjectCountsIncludeIndependentContent() {
    String p = project();
    var m = material(p, "TXT", "内容");
    var accepted = create(p, m);
    var item = tasks.list(p, "ANALYSIS", "ALL", 1, 20).items().getFirst();
    assertThat(item.inputKind()).isEqualTo("CONTENT");
    assertThat(item.materialId()).isEqualTo(m.summary().id());
    assertThat(item.snapshotId()).isNull();
    assertThat(item.title()).isEqualTo("合成内容");
    assertThat(projects.get(p).jobCount()).isEqualTo(1);
    assertThat(projects.get(p).snapshotCount()).isZero();
    assertThat(tasks.detail(p, "ANALYSIS", accepted.jobId()).model().budget().reservedUnits())
        .isZero();
  }

  /** 内容任务沿用原取消/继续，原输入不变，继续不会另建请求或预算。 */
  @Test
  void cancellationAndResumeUseOriginalTaskAndFrozenMaterial() {
    String p = project();
    var accepted = create(p, material(p, "TXT", "原文"));
    String id = accepted.jobId();
    String frozen = jsonText(accepted.input());
    control.cancel(p, "ANALYSIS", id);
    assertThat(analyses.get(p, id).status()).isEqualTo("CANCELLED");
    assertThat(jobs.claim(id)).isNull();
    control.resume(p, "ANALYSIS", id);
    worker.runOne(id);
    assertThat(analyses.get(p, id).status()).isEqualTo("SUCCESS");
    assertThat(jsonText(analyses.get(p, id).input())).isEqualTo(frozen);
    assertThat(tasks.detail(p, "ANALYSIS", id).eventCount()).isEqualTo(2);
    assertThat(analyses.list(p, 1, 20).total()).isEqualTo(1);
  }

  /** 真实网关由内存供应商驱动，成功批次按实际合成用量结算，报告保留明确LOCAL_STUB。 */
  @Test
  void contentUsesSharedGatewayAndUsageLedger() throws Exception {
    when(plans.content()).thenReturn(stubPlan());
    String p = project();
    var accepted = create(p, material(p, "TXT", "合成论述"));
    AtomicInteger calls = new AtomicInteger();
    try (var gateway = gateway(provider(calls, false, null, null))) {
      new ContentAnalysisService(analyses, jobs, gateway, json)
          .run(
              jobs.get(accepted.jobId()),
              jobs.claim(accepted.jobId()),
              jobs.plan(accepted.jobId()),
              () -> {});
      var report = analyses.get(p, accepted.jobId()).artifact();
      assertThat(report.path("sourceMode").asText()).isEqualTo("LOCAL_STUB");
      assertThat(report.path("findings")).hasSize(1);
      assertThat(ledger.view(accepted.jobId(), 1, 20).budget().settledUnits()).isEqualTo(5);
      assertThat(ledger.view(accepted.jobId(), 1, 20).budget().reservedUnits()).isZero();
      assertThat(calls).hasValue(1);
    }
  }

  /** CONTENT草稿成功后发布前中断：安全恢复复用同一草稿/批次，不再次出站。 */
  @Test
  void resumesSavedContentStepWithoutAnotherModelCall() throws Exception {
    when(plans.content()).thenReturn(stubPlan());
    String p = project();
    var accepted = create(p, material(p, "TXT", "合成论述"));
    AtomicInteger calls = new AtomicInteger(), checks = new AtomicInteger();
    String id = accepted.jobId(), token = jobs.claim(id);
    try (var gateway = gateway(provider(calls, false, null, null))) {
      var service = new ContentAnalysisService(analyses, jobs, gateway, json);
      assertThatThrownBy(
              () ->
                  service.run(
                      jobs.get(id),
                      token,
                      jobs.plan(id),
                      () -> {
                        if (checks.incrementAndGet() == 2)
                          throw new IllegalStateException("fixture pause");
                      }))
          .hasMessageContaining("fixture pause");
      assertThat(jobs.steps(id).getFirst().status()).isEqualTo("SUCCEEDED");
      String original = jobs.steps(id).getFirst().resultJson();
      jobs.fail(id, token, "PIPELINE_ERROR");
      control.retry(p, "ANALYSIS", id);
      service.run(jobs.get(id), jobs.claim(id), jobs.plan(id), () -> {});
      assertThat(analyses.get(p, id).status()).isEqualTo("SUCCESS");
      assertThat(calls).hasValue(1);
      assertThat(jobs.steps(id).getFirst().resultJson()).isEqualTo(original);
    }
  }

  /** 非法模型引用仍可能收费，发送后记UNKNOWN保留预留，不能“修复”成免费重试。 */
  @Test
  void invalidModelEvidenceRetainsUnknownCostAndPreventsRetry() throws Exception {
    when(plans.content()).thenReturn(stubPlan());
    String p = project();
    var accepted = create(p, material(p, "TXT", "原文"));
    String id = accepted.jobId(), token = jobs.claim(id);
    try (var gateway = gateway(provider(new AtomicInteger(), true, null, null))) {
      assertThatThrownBy(
              () ->
                  new ContentAnalysisService(analyses, jobs, gateway, json)
                      .run(jobs.get(id), token, jobs.plan(id), () -> {}))
          .hasMessageContaining("MODEL_OUTCOME_UNKNOWN");
      jobs.fail(id, token, "MODEL_OUTCOME_UNKNOWN");
      assertThat(tasks.detail(p, "ANALYSIS", id).task().feeUncertain()).isTrue();
      assertThat(ledger.view(id, 1, 20).budget().reservedUnits()).isPositive();
      assertThatThrownBy(() -> control.retry(p, "ANALYSIS", id)).hasMessageContaining("未决");
      assertThat(analyses.get(p, id).artifact()).isNull();
    }
  }

  /** 供应商请求正在进行时取消：迟到响应不能结算/发布，未决预留保留，继续被拒绝。 */
  @Test
  void cancellationWhileModelIsInFlightCannotPublishOrResume() throws Exception {
    when(plans.content()).thenReturn(stubPlan());
    String p = project();
    var accepted = create(p, material(p, "TXT", "原文"));
    String id = accepted.jobId(), token = jobs.claim(id);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var gateway = gateway(provider(new AtomicInteger(), false, entered, release));
        var pool = Executors.newSingleThreadExecutor()) {
      var future =
          pool.submit(
              () -> {
                new ContentAnalysisService(analyses, jobs, gateway, json)
                    .run(jobs.get(id), token, jobs.plan(id), () -> {});
                return null;
              });
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      control.cancel(p, "ANALYSIS", id);
      release.countDown();
      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
      assertThat(analyses.get(p, id).status()).isEqualTo("CANCELLED");
      assertThat(analyses.get(p, id).artifact()).isNull();
      assertThatThrownBy(() -> control.resume(p, "ANALYSIS", id)).hasMessageContaining("未决");
    } finally {
      release.countDown();
    }
  }

  /** 输入或材料哈希损坏应拒绝而非换新版；只修改本测试刚创建的合成记录。 */
  @Test
  void detectsCorruptedFrozenInputAndMaterial() {
    String p = project();
    var m = material(p, "TXT", "原文");
    var accepted = create(p, m);
    db.update(
        "UPDATE content_analysis_input SET selection_json=CONCAT(selection_json,' ') WHERE"
            + " job_id=?",
        accepted.jobId());
    assertThatThrownBy(() -> analyses.get(p, accepted.jobId()))
        .hasMessageContaining("hash mismatch");
    db.update(
        "UPDATE content_material SET raw_text='fixture-corrupt' WHERE id=?", m.summary().id());
    assertThatThrownBy(() -> materials.get(p, m.summary().id()))
        .hasMessageContaining("hash mismatch");
  }

  /** HTTP首发201/重发200、预检只读、跨项目404与参数400；旧评论外键历史保持。 */
  @Test
  void httpContractAndLegacySnapshotInvariantRemainValid() {
    String p = project();
    var m = material(p, "TXT", "原文");
    String path = "/api/v1/projects/" + p + "/content-analyses", key = key();
    var body = Map.of("materialId", m.summary().id(), "instruction", "总结内容");
    assertThat(http.postForEntity(path + "/preview", body, JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    var first = http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(
            http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    String id = first.getBody().path("jobId").asText();
    assertThat(
            http.getForEntity(path.replace(p, project()) + "/" + id, JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + p + "/content-materials?size=51", JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_job WHERE job_kind IN ('FEEDBACK','DISCUSSION') AND"
                    + " snapshot_id IS NULL",
                Integer.class))
        .isZero();
  }

  /** 网关仍只有原两线程与原账本，内存供应商不能访问外网。 */
  private ModelGateway gateway(ContentModelContract.Provider provider) {
    return new ModelGateway(ledger, json, List.of(), List.of(), List.of(provider));
  }

  /** 合成供应商可暂停/返回坏引用，测试共享状态边界，不评价语义准确率。 */
  private ContentModelContract.Provider provider(
      AtomicInteger calls, boolean invalid, CountDownLatch entered, CountDownLatch release) {
    return new ContentModelContract.Provider() {
      public String id() {
        return "CONTENT_FIXTURE";
      }

      public String modelVersion() {
        return "content-fixture-v1";
      }

      public void validatePlan(ModelContract.Plan plan) {
        assertThat(plan.promptVersion()).isEqualTo(ContentModelContract.PROMPT);
      }

      public ContentModelContract.Response analyze(
          ContentModelContract.Request request, Duration timeout) {
        calls.incrementAndGet();
        if (entered != null) entered.countDown();
        if (release != null)
          try {
            if (!release.await(4, TimeUnit.SECONDS))
              throw new IllegalStateException("fixture timeout");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        return new ContentModelContract.Response(
            List.of(
                new ContentModelContract.Finding(
                    "合成归纳",
                    ContentModelContract.Kind.OBSERVATION,
                    "仅为结构夹具。",
                    List.of(invalid ? "foreign" : request.selection().segments().getFirst().id()))),
            List.of("未验证语义质量"),
            2,
            3,
            "fixture");
      }
    };
  }

  /** 原任务计划模拟单位，不是真实报价或DeepSeek调用。 */
  private ModelContract.Plan stubPlan() {
    return new ModelContract.Plan(
        "CONTENT_FIXTURE",
        "content-fixture-v1",
        ContentModelContract.PROMPT,
        ContentModelContract.TAXONOMY,
        1,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        100000,
        10000,
        "content-fixture-v1",
        "LOCAL_STUB");
  }

  /** 简化接受免费小材料，测试需要的复杂范围另外显式传入。 */
  private ContentAnalysisStore.View create(String p, ContentMaterialStore.Material m) {
    return analyses.create(p, key(), m.summary().id(), "总结材料", null, null, false).analysis();
  }

  /** 全新材料身份和工程来源声明，不覆盖旧版本。 */
  private ContentMaterialStore.Material material(String p, String format, String text) {
    return materials.create(p, key(), "合成内容", "合成测试，不是来源认证", null, format, bytes(text));
  }

  /** 两条字幕供时间范围/冻结断言。 */
  private String subtitle() {
    return "1\n00:00:00,000 --> 00:00:02,000\n旧字幕一\n\n2\n00:00:02,000 --> 00:00:04,000\n旧字幕二";
  }

  /** 显式UTF8避免Windows默认编码影响证据身份。 */
  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 统一JSON只用于工程哈希比较，不能当独立评测标签。 */
  private String jsonText(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 服务器隔离工作空间的新合成项目。 */
  private String project() {
    return projects.create(key(), "独立内容验收（合成）", "工程边界，不是语义质量").project().id();
  }

  /** 唯一追加键，不清理过去测试记录。 */
  private String key() {
    return UUID.randomUUID().toString();
  }
}
