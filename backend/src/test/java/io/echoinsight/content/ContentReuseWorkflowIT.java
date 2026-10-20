/* 文件职责：通过隔离MySQL验证跨版本复用、费用出处、幂等、版本资格与原任务取消恢复。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.*;
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

/** 只追加合成数据；所有语义响应来自内存供应商，绝不访问平台或DeepSeek。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=content-reuse-it",
      "echoinsight.worker.execution-scope=content-reuse-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ContentReuseWorkflowIT {
  @Autowired ContentMaterialStore materials;
  @Autowired ContentAnalysisStore analyses;
  @Autowired ProjectStore projects;
  @Autowired JobStore jobs;
  @Autowired ModelLedger ledger;
  @Autowired TaskControlService control;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @MockitoBean ConfiguredModelPlan plans;

  /** 每例独立的固定v3替身计划，正式配置和密钥不参与测试。 */
  @BeforeEach
  void defaults() {
    when(plans.contentFull()).thenReturn(plan(1000000));
  }

  /** 24000字追加2000字：前两片复用，只发送新增片和新汇总；历史账本不改。 */
  @Test
  void reusesUnchangedMapsAndBillsOnlyNewCalls() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var baseline = create(f.p(), f.first(), null);
      run(baseline.jobId(), gateway, () -> {});
      var preview = preview(f, baseline.jobId());
      assertThat(preview.input().reusePlan().reused()).isEqualTo(2);
      assertThat(preview.plannedCalls()).isEqualTo(2);
      assertThat(preview.reservationUnits()).isEqualTo(28192);
      assertThat(count("model_call", baseline.jobId())).isEqualTo(3);
      var next = create(f.p(), f.next(), baseline.jobId());
      run(next.jobId(), gateway, () -> {});
      var view = analyses.get(f.p(), next.jobId());
      assertThat(provider.calls).hasValue(5);
      assertThat(view.status()).isEqualTo("SUCCESS");
      assertThat(view.progress().reusedChunks()).isEqualTo(2);
      assertThat(view.progress().completedChunks()).isEqualTo(3);
      assertThat(view.progress().successfulCalls()).isEqualTo(2);
      assertThat(count("model_batch", next.jobId())).isEqualTo(2);
      assertThat(
              db.queryForList(
                  "SELECT batch_index FROM model_batch WHERE job_id=? ORDER BY batch_index",
                  Integer.class,
                  next.jobId()))
          .containsExactly(2, 3);
      assertThat(ledger.view(next.jobId(), 1, 30).budget().settledUnits()).isEqualTo(10);
      assertThat(ledger.view(baseline.jobId(), 1, 30).budget().settledUnits()).isEqualTo(15);
      assertThat(view.artifact().path("material").path("id").asText())
          .isEqualTo(f.next().summary().id());
      assertThat(view.artifact().path("findings").get(0).path("evidenceIds").get(0).asText())
          .isEqualTo("text-13");
      assertThat(
              view.artifact()
                  .path("processing")
                  .path("reuse")
                  .path("origins")
                  .get(0)
                  .path("sourceJobId")
                  .asText())
          .isEqualTo(baseline.jobId());
      assertThat(view.artifact().path("processing").path("chunks").toString())
          .doesNotContain("\"e1\"");
    }
  }

  /** 同版本可明确选择基准，全部MAP复用但REDUCE仍是新收费调用。 */
  @Test
  void alwaysRegeneratesSynthesisEvenWhenEveryMapMatches() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.first(), a.jobId());
      run(b.jobId(), gateway, () -> {});
      assertThat(provider.calls).hasValue(4);
      assertThat(count("model_call", b.jobId())).isEqualTo(1);
      assertThat(analyses.get(f.p(), b.jobId()).progress().reusedChunks()).isEqualTo(2);
      assertThat(ledger.view(b.jobId(), 1, 30).budget().settledUnits()).isEqualTo(5);
    }
  }

  /** 复用可沿直接父版本传播，但凭证始终指向实际发生收费的原任务。 */
  @Test
  void preservesPhysicalOriginAcrossVersionChain() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.next(), a.jobId());
      run(b.jobId(), gateway, () -> {});
      var third = material(f.p(), f.next().summary().id(), "甲".repeat(24000) + "乙".repeat(2000));
      var c = create(f.p(), third, b.jobId());
      run(c.jobId(), gateway, () -> {});
      var receipt = analyses.get(f.p(), c.jobId()).artifact().path("processing").path("reuse");
      assertThat(receipt.path("reusedChunks").asInt()).isEqualTo(3);
      assertThat(receipt.path("origins").get(0).path("sourceJobId").asText()).isEqualTo(a.jobId());
      assertThat(receipt.path("origins").get(2).path("sourceJobId").asText()).isEqualTo(b.jobId());
      assertThat(count("model_call", c.jobId())).isEqualTo(1);
    }
  }

  /** 改动一片只使该片失效；修改标题或来源会使全部局部输入变化。 */
  @Test
  void recomputesOnlyChangedPayloadAndInvalidatesMetadata() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var changed =
          material(f.p(), f.first().summary().id(), "甲".repeat(12000) + "改".repeat(12000));
      assertThat(
              analyses
                  .preview(f.p(), changed.summary().id(), "总结", null, null, "FULL", a.jobId())
                  .input()
                  .reusePlan()
                  .reused())
          .isEqualTo(1);
      var renamed =
          materials.create(
              f.p(), key(), "改标题", "合成", f.first().summary().id(), "TXT", bytes("甲".repeat(24000)));
      assertThat(
              analyses
                  .preview(f.p(), renamed.summary().id(), "总结", null, null, "FULL", a.jobId())
                  .input()
                  .reusePlan()
                  .reused())
          .isZero();
      var newSource =
          materials.create(
              f.p(),
              key(),
              "复用合成材料",
              "改来源",
              f.first().summary().id(),
              "TXT",
              bytes("甲".repeat(24000)));
      assertThat(
              analyses
                  .preview(f.p(), newSource.summary().id(), "总结", null, null, "FULL", a.jobId())
                  .input()
                  .reusePlan()
                  .reused())
          .isZero();
    }
  }

  /** 用户问题不同就拒绝原基准；取消基准后允许全新问题。 */
  @Test
  void refusesChangedInstructionAndPrefixReuse() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      assertThatThrownBy(
              () ->
                  analyses.preview(
                      f.p(), f.next().summary().id(), "比较论点", null, null, "FULL", a.jobId()))
          .hasMessageContaining("问题不同");
      assertThatThrownBy(
              () ->
                  analyses.preview(
                      f.p(), f.next().summary().id(), "总结", null, null, "PREFIX", a.jobId()))
          .hasMessageContaining("仅完整");
      assertThat(
              analyses
                  .preview(f.p(), f.next().summary().id(), "比较论点", null, null, "FULL", null)
                  .input()
                  .reusePlan()
                  .reused())
          .isZero();
    }
  }

  /** 同项目也不能任意复用兄弟材料或跨越两代原文。 */
  @Test
  void requiresSameOrDirectParentMaterial() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var unrelated = material(f.p(), null, "甲".repeat(24000));
      assertThatThrownBy(
              () ->
                  analyses.preview(
                      f.p(), unrelated.summary().id(), "总结", null, null, "FULL", a.jobId()))
          .hasMessageContaining("直接前一版本");
      var third = material(f.p(), f.next().summary().id(), "甲".repeat(26000));
      assertThatThrownBy(
              () ->
                  analyses.preview(
                      f.p(), third.summary().id(), "总结", null, null, "FULL", a.jobId()))
          .hasMessageContaining("直接前一版本");
      String other = project();
      var m = material(other, null, "甲");
      assertThatThrownBy(
              () -> analyses.preview(other, m.summary().id(), "总结", null, null, "FULL", a.jobId()))
          .hasMessageContaining("不存在");
    }
  }

  /** 未成功任务和不同价格/预算计划不能被当作合格成功缓存。 */
  @Test
  void rejectsPendingAndChangedPlan() throws Exception {
    var f = fixture();
    var a = create(f.p(), f.first(), null);
    assertThatThrownBy(() -> preview(f, a.jobId())).hasMessageContaining("已完成");
    try (var gateway = gateway(new FixtureProvider())) {
      run(a.jobId(), gateway, () -> {});
    }
    when(plans.contentFull()).thenReturn(plan(1000001));
    assertThatThrownBy(() -> preview(f, a.jobId())).hasMessageContaining("模型协议或费用计划已变化");
  }

  /** 旧v2可继续执行和发布，但不能伪装为局部依赖已经隔离的v3缓存。 */
  @Test
  void preservesV2ExecutionAndRefusesItAsBaseline() throws Exception {
    var f = fixture();
    var p = plan(1000000);
    when(plans.contentFull())
        .thenReturn(
            new ModelContract.Plan(
                p.providerId(),
                p.modelVersion(),
                ContentFullContract.PROMPT,
                ContentFullContract.TAXONOMY,
                p.batchSize(),
                p.timeoutMs(),
                p.maxOutputTokens(),
                p.unit(),
                p.priceVersion(),
                p.inputUnitRate(),
                p.outputUnitRate(),
                p.budgetLimit(),
                p.inputTokenCeiling(),
                p.responseModel(),
                p.executionMode()));
    var a = create(f.p(), f.first(), null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(a.jobId(), gateway, () -> {});
    }
    when(plans.contentFull()).thenReturn(p);
    assertThat(analyses.get(f.p(), a.jobId()).status()).isEqualTo("SUCCESS");
    assertThat(analyses.get(f.p(), a.jobId()).input().reusePlan()).isNull();
    assertThatThrownBy(() -> preview(f, a.jobId())).hasMessageContaining("旧协议");
    assertThat(analyses.list(f.p(), 1, 10, f.next().summary().id()).total()).isZero();
  }

  /** 只读预检不初始化账本或创建任务，新幂等身份包含显式基准。 */
  @Test
  void previewIsReadOnlyAndIdempotencyIncludesBaseline() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      preview(f, a.jobId());
      assertThat(analyses.list(f.p(), 1, 10).total()).isEqualTo(1);
      String key = key();
      var created =
          analyses.create(
              f.p(), key, f.next().summary().id(), "总结", null, null, false, "FULL", a.jobId());
      assertThat(count("model_budget", created.analysis().jobId())).isZero();
      when(plans.contentFull()).thenReturn(plan(1000001));
      assertThat(
              analyses
                  .create(
                      f.p(),
                      key,
                      f.next().summary().id(),
                      "总结",
                      null,
                      null,
                      false,
                      "FULL",
                      a.jobId())
                  .created())
          .isFalse();
      assertThatThrownBy(
              () ->
                  analyses.create(
                      f.p(), key, f.next().summary().id(), "总结", null, null, false, "FULL", null))
          .hasMessageContaining("请求键");
    }
  }

  /** 冻结之后追加后续版本不改本任务；片间取消零新调用，恢复继续原问题和原输入。 */
  @Test
  void cancellationBetweenReusedMapsPreservesFrozenInput() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.next(), a.jobId());
      var checks = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  run(
                      b.jobId(),
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 3) {
                          control.cancel(f.p(), "ANALYSIS", b.jobId());
                          throw new JobStore.LostLease();
                        }
                      }))
          .isInstanceOf(JobStore.LostLease.class);
      assertThat(count("model_call", b.jobId())).isZero();
      assertThat(analyses.get(f.p(), b.jobId()).progress().reusedChunks()).isEqualTo(2);
      material(f.p(), f.next().summary().id(), "更新后的资料");
      control.resume(f.p(), "ANALYSIS", b.jobId());
      run(b.jobId(), gateway, () -> {});
      assertThat(provider.calls).hasValue(5);
      assertThat(analyses.get(f.p(), b.jobId()).input().material().id())
          .isEqualTo(f.next().summary().id());
    }
  }

  /** 新片成功后中断，恢复既复用跨版冻结片，也复用本任务已结算检查点。 */
  @Test
  void retryDoesNotRepaySuccessfulFreshMap() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider)) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.next(), a.jobId());
      var checks = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  run(
                      b.jobId(),
                      gateway,
                      () -> {
                        if (checks.incrementAndGet() == 5)
                          throw new IllegalStateException("fixture pause");
                      }))
          .hasMessageContaining("fixture pause");
      assertThat(provider.calls).hasValue(4);
      jobs.fail(b.jobId(), jobs.get(b.jobId()).ownerToken(), "PIPELINE_ERROR");
      control.retry(f.p(), "ANALYSIS", b.jobId());
      run(b.jobId(), gateway, () -> {});
      assertThat(provider.calls).hasValue(5);
      assertThat(count("model_call", b.jobId())).isEqualTo(2);
    }
  }

  /** 冻结响应损坏时拒绝读取/执行，不尝试付费补偿或静默丢弃复用。 */
  @Test
  void rejectsCorruptedFrozenResponse() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.next(), a.jobId());
      var frozen = json.valueToTree(b.input());
      ((com.fasterxml.jackson.databind.node.ObjectNode)
              frozen.path("reusePlan").path("entries").get(0).path("cached"))
          .put("responseHash", "broken");
      String body = frozen.toString();
      db.update(
          "UPDATE content_analysis_input SET selection_json=?,selection_sha256=? WHERE job_id=?",
          body,
          io.echoinsight.material.ReferenceMaterialStore.hash(body),
          b.jobId());
      assertThatThrownBy(() -> analyses.get(f.p(), b.jobId()))
          .hasMessageContaining("response hash mismatch");
      assertThat(count("model_call", b.jobId())).isZero();
    }
  }

  /** 原批次输入哈希损坏不能作为新基准；只检查当前合成任务，不修改用户数据。 */
  @Test
  void rejectsDamagedSourceCheckpoint() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      db.update(
          "UPDATE model_batch SET input_hash=? WHERE job_id=? AND batch_index=0",
          "0".repeat(64),
          a.jobId());
      assertThatThrownBy(() -> preview(f, a.jobId())).hasMessageContaining("检查点不可用");
    }
  }

  /** 新汇总在途取消：原费用未知保护和发布围栏仍有效，不能借复用绕开。 */
  @Test
  void cancellationDuringNewSynthesisRetainsUnknownCost() throws Exception {
    var f = fixture();
    var provider = new FixtureProvider();
    try (var gateway = gateway(provider);
        var pool = Executors.newSingleThreadExecutor()) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var b = create(f.p(), f.first(), a.jobId());
      provider.entered = new CountDownLatch(1);
      provider.release = new CountDownLatch(1);
      var future =
          pool.submit(
              () -> {
                run(b.jobId(), gateway, () -> {});
                return null;
              });
      try {
        assertThat(provider.entered.await(5, TimeUnit.SECONDS)).isTrue();
        control.cancel(f.p(), "ANALYSIS", b.jobId());
        provider.release.countDown();
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
        assertThat(analyses.get(f.p(), b.jobId()).artifact()).isNull();
        assertThat(analyses.get(f.p(), b.jobId()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> control.resume(f.p(), "ANALYSIS", b.jobId()))
            .hasMessageContaining("未决");
      } finally {
        provider.release.countDown();
      }
    }
  }

  /** HTTP通过原路由传基准，候选服务器分页；跨项目材料参数仍404。 */
  @Test
  void httpFiltersCandidatesAndAcceptsExplicitBaseline() throws Exception {
    var f = fixture();
    try (var gateway = gateway(new FixtureProvider())) {
      var a = create(f.p(), f.first(), null);
      run(a.jobId(), gateway, () -> {});
      var pending = create(f.p(), f.first(), null);
      String path = "/api/v1/projects/" + f.p() + "/content-analyses";
      var list =
          http.getForEntity(
              path + "?baselineForMaterialId=" + f.next().summary().id() + "&page=1&size=1",
              JsonNode.class);
      assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(list.getBody().path("total").asInt()).isEqualTo(1);
      assertThat(list.getBody().path("items").get(0).path("jobId").asText()).isEqualTo(a.jobId());
      assertThat(list.getBody().path("items").get(0).path("reuseCapable").asBoolean()).isTrue();
      var body =
          Map.of(
              "materialId",
              f.next().summary().id(),
              "instruction",
              "总结",
              "coverageMode",
              "FULL",
              "baselineJobId",
              a.jobId());
      var pre = http.postForEntity(path + "/preview", body, JsonNode.class);
      assertThat(pre.getBody().path("plannedCalls").asInt()).isEqualTo(2);
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      headers.set("Idempotency-Key", key());
      var created = http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
      assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(created.getBody().path("progress").path("reusedChunks").asInt()).isEqualTo(2);
      String other = project();
      assertThat(
              http.getForEntity(
                      "/api/v1/projects/"
                          + other
                          + "/content-analyses?baselineForMaterialId="
                          + f.next().summary().id(),
                      String.class)
                  .getStatusCode())
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  /** 无成功语义响应的免费演示不能作为基准，并从候选中排除。 */
  @Test
  void excludesFreeStructuralDemoFromReuse() throws Exception {
    var configured =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.contentFull()).thenReturn(configured.contentFull());
    var f = fixture();
    var a = create(f.p(), f.first(), null);
    try (var gateway = gateway(new FixtureProvider())) {
      run(a.jobId(), gateway, () -> {});
    }
    assertThat(analyses.get(f.p(), a.jobId()).progress().reusedChunks()).isZero();
    assertThat(count("model_call", a.jobId())).isZero();
    assertThat(analyses.list(f.p(), 1, 10, f.next().summary().id()).total()).isZero();
    when(plans.contentFull()).thenReturn(plan(1000000));
    assertThatThrownBy(() -> preview(f, a.jobId())).hasMessageContaining("免费演示");
  }

  /** 工程夹具包含两片完全相同但字符位置不同的文本，验证位置也参与匹配。 */
  private record Fixture(
      String p, ContentMaterialStore.Material first, ContentMaterialStore.Material next) {}

  private Fixture fixture() {
    String p = project();
    var a = material(p, null, "甲".repeat(24000));
    return new Fixture(p, a, material(p, a.summary().id(), "甲".repeat(24000) + "乙".repeat(2000)));
  }

  /** 预检明确选择直接父版本。 */
  private ContentAnalysisStore.Preview preview(Fixture f, String baseline) {
    return analyses.preview(f.p(), f.next().summary().id(), "总结", null, null, "FULL", baseline);
  }

  /** 新任务只追加，基准可以为空。 */
  private ContentAnalysisStore.View create(
      String p, ContentMaterialStore.Material m, String baseline) {
    return analyses
        .create(p, key(), m.summary().id(), "总结", null, null, false, "FULL", baseline)
        .analysis();
  }

  /** 原任务领取/步骤/发布实现不替换。 */
  private void run(String id, ModelGateway gateway, Runnable check) throws Exception {
    new ContentAnalysisService(analyses, jobs, gateway, json)
        .run(jobs.get(id), jobs.claim(id), jobs.plan(id), check);
  }

  /** 所有新调用仍走原账本。 */
  private ModelGateway gateway(FixtureProvider provider) {
    return new ModelGateway(ledger, json, List.of(), List.of(), List.of(provider));
  }

  /** 内存供应商固定引用本片末段；可阻塞汇总复现真实在途取消。 */
  private static final class FixtureProvider implements ContentModelContract.Provider {
    final AtomicInteger calls = new AtomicInteger();
    CountDownLatch entered, release;

    /** 固定供应商身份。 */
    public String id() {
      return "REUSE_FIXTURE";
    }

    /** 固定响应模型身份。 */
    public String modelVersion() {
      return "reuse-fixture";
    }

    /** v2仅用于兼容用例，其余均要求完整内容协议。 */
    public void validatePlan(ModelContract.Plan p) {
      assertThat(ContentFullContract.supports(p)).isTrue();
    }

    /** 不允许误走前缀入口。 */
    public ContentModelContract.Response analyze(ContentModelContract.Request r, Duration d) {
      throw new AssertionError("Unexpected legacy call");
    }

    /** MAP引用局部别名，REDUCE只能引用全部成功摘要中的当前原段。 */
    public ContentModelContract.Response analyzeFull(ContentFullContract.Request r, Duration d) {
      calls.incrementAndGet();
      String evidence;
      if ("MAP".equals(r.phase())) evidence = r.selection().segments().getLast().id();
      else {
        if (entered != null) entered.countDown();
        if (release != null)
          try {
            if (!release.await(5, TimeUnit.SECONDS))
              throw new IllegalStateException("fixture timeout");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        evidence = r.summaries().getLast().findings().getFirst().evidenceIds().getFirst();
      }
      return new ContentModelContract.Response(
          List.of(
              new ContentModelContract.Finding(
                  "归纳", ContentModelContract.Kind.OBSERVATION, "仅用于工程验收", List.of(evidence))),
          List.of("语义未经独立验证"),
          2,
          3,
          "fixture");
    }
  }

  /** 模拟价格每次2输入＋3输出，便于核对新旧账本归属。 */
  private ModelContract.Plan plan(long budget) {
    return new ModelContract.Plan(
        "REUSE_FIXTURE",
        "reuse-fixture",
        ContentReuse.PROMPT,
        ContentReuse.TAXONOMY,
        100,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        budget,
        10000,
        "reuse-fixture",
        "LOCAL_STUB");
  }

  /** 保持标题/来源相同，只有参数指定的原文或父版本发生变化。 */
  private ContentMaterialStore.Material material(String p, String previous, String text) {
    return materials.create(p, key(), "复用合成材料", "合成", previous, "TXT", bytes(text));
  }

  /** 只读本测试任务，无清理或删除。 */
  private long count(String table, String id) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE job_id=?", Long.class, id);
  }

  /** 测试工作空间隔离。 */
  private String project() {
    return projects.create(key(), "增量合成项目", "工程协议验收").project().id();
  }

  /** 明确UTF8。 */
  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** 唯一追加身份。 */
  private String key() {
    return UUID.randomUUID().toString();
  }
}
