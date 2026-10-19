/* 文件职责：用隔离项目/MySQL及内存供应商验证只读预检、接受原子性、错误字段与未知费用展示。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.*;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** worker/真实模型/平台均关闭；即使冻结MODEL计划，也只用测试内存供应商，不可能连接付费端点。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=full-stance-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class FullStanceWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisRequestStore requests;
  @Autowired JobStore jobs;
  @Autowired ModelLedger ledger;
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @MockitoBean ConfiguredModelPlan plans;
  @Autowired io.echoinsight.infrastructure.persistence.ImportMapper frozenInputs;

  @BeforeEach
  void configureOnlySyntheticPlan() {
    when(plans.discussion()).thenReturn(plan());
    when(plans.discussionWithProposition()).thenReturn(plan());
    when(plans.discussionFull()).thenReturn(plan());
    when(plans.current()).thenReturn(ModelContract.Plan.mock());
  }

  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "stability-v1",
        StanceChunkPlanner.PROMPT,
        StanceChunkPlanner.TAXONOMY,
        1,
        3000,
        4096,
        "USD_NANOS",
        "test-only",
        1,
        1,
        1000000,
        128000,
        "stability-v1",
        "MODEL");
  }

  private String project() {
    return projects.create(UUID.randomUUID().toString(), "稳定性合成资料", "总结观点").project().id();
  }

  private String snapshot(String project, int count) {
    var time = OffsetDateTime.parse("2026-10-18T00:00:00Z");
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int n = 0; n < count; n++)
      comments.add(new ImportDocument.Comment("c" + n, "s" + n, null, "合成主张" + n, time));
    var d =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content(
                "test", "SYNTHETIC", "stability-" + UUID.randomUUID(), "creator", "合成验收", time),
            comments,
            List.of());
    return imports.ingest(project, d).snapshotId();
  }

  private ResponseEntity<JsonNode> post(String project, String suffix, Object body, String key) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.exchange(
        "/api/v1/projects/" + project + suffix,
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  private int rows(String table, String project) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE project_id=?", Integer.class, project);
  }

  /** 合成完整目标：范围确认由服务器再次核对，不信任浏览器旧预检。 */
  private Map<String, Object> body(String snapshot, boolean confirm) {
    return Map.of(
        "snapshotId",
        snapshot,
        "instruction",
        "分析全部评论对命题「剧情因果自洽」的立场",
        "analysisScope",
        "FULL",
        "confirmFullScope",
        confirm);
  }

  /** 恢复原不可变正文，不能用测试临时资料替换已经接受的快照。 */
  private ImportDocument input(String snapshot) throws Exception {
    return json.readValue(frozenInputs.snapshot(snapshot).documentJson(), ImportDocument.class);
  }

  /** 创建已确认的完整请求，模型仅为内存替身，后台自动执行关闭。 */
  private AnalysisRequestStore.RequestRow create(String p, String s) {
    return requests
        .createWithScope(
            p,
            UUID.randomUUID().toString(),
            s,
            "分析全部评论对命题「剧情因果自洽」的立场",
            false,
            List.of(),
            false,
            null,
            "FULL",
            true)
        .request();
  }

  /** 预检不写预算；缺成本确认422，显式变范围同键409，跨项目进度404。 */
  @Test
  void fullAdmissionIsAtomicAndScopeIsFrozen() {
    String p = project(),
        other = project(),
        s = snapshot(p, 25),
        key = UUID.randomUUID().toString();
    var preview = post(p, "/analysis-requests/preview", body(s, false), "preview").getBody();
    assertThat(preview.path("selectedComments").asInt()).isEqualTo(25);
    assertThat(preview.path("fullAnalysisAvailable").asBoolean()).isTrue();
    assertThat(preview.path("fullScope").path("plannedBatches").asInt()).isEqualTo(3);
    assertThat(rows("analysis_request", p)).isZero();
    assertThat(post(p, "/analysis-requests", body(s, false), key).getStatusCode().value())
        .isEqualTo(422);
    assertThat(rows("analysis_request", p)).isZero();
    var first = post(p, "/analysis-requests", body(s, true), key);
    String id = first.getBody().path("id").asText();
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(post(p, "/analysis-requests", body(s, false), key).getBody().path("id").asText())
        .isEqualTo(id);
    var changed = new HashMap<String, Object>(body(s, true));
    changed.put("analysisScope", "PREVIEW");
    changed.put("allowPartialInput", true);
    assertThat(post(p, "/analysis-requests", changed, key).getStatusCode().value()).isEqualTo(409);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + other + "/analysis-requests/" + id + "/progress",
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    var progress = requests.progress(p, id);
    assertThat(progress.targetComments()).isEqualTo(25);
    assertThat(progress.completedComments()).isZero();
  }

  /** 服务器预算不足拒绝整个请求；不为全量增加预算或静默退为部分。 */
  @Test
  void insufficientWorstCaseBudgetCreatesNoTask() {
    String p = project(), s = snapshot(p, 121);
    when(plans.discussionFull())
        .thenReturn(
            new ModelContract.Plan(
                "SPRING_AI",
                "stability-v1",
                StanceChunkPlanner.PROMPT,
                StanceChunkPlanner.TAXONOMY,
                12,
                3000,
                4096,
                "USD_NANOS",
                "test-only",
                1,
                1,
                132096,
                128000,
                "stability-v1",
                "MODEL"));
    var preview = post(p, "/analysis-requests/preview", body(s, true), "preview").getBody();
    assertThat(preview.path("fullScope").path("reasonCode").asText())
        .isEqualTo("FULL_BUDGET_INSUFFICIENT");
    assertThat(post(p, "/analysis-requests", body(s, true), "budget").getStatusCode().value())
        .isEqualTo(422);
    assertThat(rows("analysis_request", p)).isZero();
  }

  /** 本地替身按归属ID构造固定响应，不把该人为规则当成语义模型。 */
  private DiscussionModelContract.Provider provider(
      java.util.concurrent.atomic.AtomicInteger calls, int badIndex) {
    return new DiscussionModelContract.Provider() {
      public String id() {
        return "SPRING_AI";
      }

      public String modelVersion() {
        return "stability-v1";
      }

      public void validatePlan(ModelContract.Plan plan) {
        assertThat(plan.promptVersion()).isEqualTo(StanceChunkPlanner.PROMPT);
      }

      public DiscussionModelContract.Response analyze(
          DiscussionModelContract.Request request, Duration timeout) {
        calls.incrementAndGet();
        return new DiscussionModelContract.Response(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            10,
            2,
            "local-full",
            request.batchIndex() == badIndex ? List.of() : decisions(request.targetCommentIds()));
      }
    };
  }

  /** 用可解释的合成规则生成支持/反对/限定/提问/未知，便于核对两个分母。 */
  private List<StanceProtocol.Decision> decisions(List<String> ids) {
    return ids.stream()
        .map(
            id -> {
              int n = Integer.parseInt(id.substring("comment-".length())) % 5;
              if (n == 0 || n == 4)
                return new StanceProtocol.Decision(
                    id,
                    n == 0 ? StanceProtocol.Coverage.QUESTION : StanceProtocol.Coverage.UNKNOWN,
                    "合成记录",
                    List.of());
              var position =
                  n == 1
                      ? StanceProtocol.Position.SUPPORT
                      : n == 2 ? StanceProtocol.Position.OPPOSE : StanceProtocol.Position.QUALIFIED;
              return new StanceProtocol.Decision(
                  id,
                  StanceProtocol.Coverage.CLAIMS,
                  "合成规则",
                  List.of(
                      new StanceProtocol.Claim(
                          "合成主张", position, n == 3 ? "前提成立" : "", "合成理由", List.of(id))));
            })
        .toList();
  }

  /** 复用原步骤和原子发布，测试数据库版本与JSON版本一致。 */
  private void publish(
      AnalysisRequestStore.RequestRow request, String token, DiscussionArtifact artifact)
      throws Exception {
    jobs.startStep(request.jobId(), token, "DISCUSSION");
    jobs.saveDiscussion(request.jobId(), token, json.writeValueAsString(artifact));
    jobs.startStep(request.jobId(), token, "ARTIFACT");
    jobs.publishArtifact(
        request.jobId(),
        token,
        artifact.artifactId(),
        request.id(),
        request.snapshotId(),
        artifact.schemaVersion(),
        json.writeValueAsString(artifact));
  }

  /** 中断在第二片发送前，恢复只调用剩余片；成功片原检查点不再计费。 */
  @Test
  void interruptedFullScopeReusesCompletedBatchesAndPublishesOnlyWhenComplete() throws Exception {
    String p = project(), s = snapshot(p, 25);
    var request = create(p, s);
    String token = jobs.claim(request.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, -1)))) {
      var analyzer = new FullStanceAnalyzer(gateway);
      assertThatThrownBy(
              () ->
                  analyzer.analyze(
                      jobs.get(request.jobId()),
                      token,
                      plan(),
                      request,
                      input(s),
                      UUID.randomUUID().toString(),
                      () -> {
                        if (checks.incrementAndGet() == 3)
                          throw new ModelContract.Failure("SYNTHETIC_PAUSE");
                      },
                      AnalysisContextStore.View.none(),
                      requests.specification(p, request.id()),
                      requests.scope(p, request.id())))
          .hasMessage("SYNTHETIC_PAUSE");
      assertThat(calls).hasValue(1);
      assertThat(requests.progress(p, request.id()).completedComments()).isEqualTo(12);
      assertThatThrownBy(() -> requests.artifact(p, request.id()))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
      jobs.fail(request.jobId(), token, "SYNTHETIC_PAUSE");
      jobs.retry(request.jobId());
      String resumed = jobs.claim(request.jobId());
      var result =
          analyzer.analyze(
              jobs.get(request.jobId()),
              resumed,
              plan(),
              request,
              input(s),
              UUID.randomUUID().toString(),
              () -> {},
              AnalysisContextStore.View.none(),
              requests.specification(p, request.id()),
              requests.scope(p, request.id()));
      assertThat(calls).hasValue(3);
      assertThat(result.stance().statistics())
          .isEqualTo(
              StanceProtocol.summarize(
                  decisions(
                      java.util.stream.IntStream.rangeClosed(1, 25)
                          .mapToObj(n -> "comment-" + n)
                          .toList())));
      assertThat(result.stance().decisions()).hasSize(25);
      assertThat(result.stance().completion().coverageVerified()).isTrue();
      assertThat(ledger.view(request.jobId(), 1, 10).budget().settledUnits()).isEqualTo(36);
      publish(request, resumed, result);
      assertThat(requests.progress(p, request.id()).published()).isTrue();
      assertThat(requests.progress(p, request.id()).completedComments()).isEqualTo(25);
      assertThat(requests.artifact(p, request.id()).path("schemaVersion").asText())
          .isEqualTo("discussion-artifact-v6-stance");
    }
  }

  /** 第二片发出后漏覆盖仍UNKNOWN：第一片保留，但不发布“部分完成”的完整报告。 */
  @Test
  void sentInvalidChunkBlocksPublicationAndRetry() throws Exception {
    String p = project(), s = snapshot(p, 25);
    var request = create(p, s);
    String token = jobs.claim(request.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, 1)))) {
      assertThatThrownBy(
              () ->
                  new FullStanceAnalyzer(gateway)
                      .analyze(
                          jobs.get(request.jobId()),
                          token,
                          plan(),
                          request,
                          input(s),
                          UUID.randomUUID().toString(),
                          () -> {},
                          AnalysisContextStore.View.none(),
                          requests.specification(p, request.id()),
                          requests.scope(p, request.id())))
          .hasMessage("MODEL_OUTCOME_UNKNOWN");
    }
    jobs.fail(request.jobId(), token, "MODEL_OUTCOME_UNKNOWN");
    assertThat(calls).hasValue(2);
    assertThat(requests.progress(p, request.id()).completedComments()).isEqualTo(12);
    assertThat(requests.progress(p, request.id()).published()).isFalse();
    assertThat(ledger.view(request.jobId(), 1, 10).budget().reservedUnits()).isPositive();
    assertThatThrownBy(() -> jobs.retry(request.jobId()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 冻结清单被错误替换时，在任何费用预留之前拒绝。 */
  @Test
  void changedManifestFailsBeforeModelReservation() throws Exception {
    String p = project(), s = snapshot(p, 13);
    var request = create(p, s);
    String token = jobs.claim(request.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var original = requests.scope(p, request.id());
    var bad =
        new StanceChunkPlanner.Manifest(
            original.version(),
            s,
            "wrong",
            original.targetComments(),
            original.contextOccurrences(),
            original.chunks());
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, -1)))) {
      assertThatThrownBy(
              () ->
                  new FullStanceAnalyzer(gateway)
                      .analyze(
                          jobs.get(request.jobId()),
                          token,
                          plan(),
                          request,
                          input(s),
                          UUID.randomUUID().toString(),
                          () -> {},
                          AnalysisContextStore.View.none(),
                          requests.specification(p, request.id()),
                          bad))
          .hasMessage("FULL_SCOPE_CHANGED");
      assertThat(calls).hasValue(0);
      assertThat(ledger.view(request.jobId(), 1, 10).total()).isZero();
    }
    jobs.fail(request.jobId(), token, "FULL_SCOPE_CHANGED");
  }

  /** 同一根评论跨三片反复提供语境，最终25条决策只出现一次且完整对账成功。 */
  @Test
  void repeatedAncestorsDoNotInflatePublishedDenominators() throws Exception {
    String p = project(), base = snapshot(p, 25);
    var original = input(base);
    var comments =
        original.comments().stream()
            .map(
                c ->
                    new ImportDocument.Comment(
                        c.id(),
                        c.sourceCommentId(),
                        "c0".equals(c.id()) ? null : "c0",
                        c.text(),
                        c.occurredAt()))
            .toList();
    String s =
        imports
            .ingest(
                p,
                new ImportDocument(
                    original.schemaVersion(),
                    original.content(),
                    comments,
                    original.transcriptSegments()))
            .snapshotId();
    var request = create(p, s);
    String token = jobs.claim(request.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, -1)))) {
      var result =
          new FullStanceAnalyzer(gateway)
              .analyze(
                  jobs.get(request.jobId()),
                  token,
                  plan(),
                  request,
                  input(s),
                  UUID.randomUUID().toString(),
                  () -> {},
                  AnalysisContextStore.View.none(),
                  requests.specification(p, request.id()),
                  requests.scope(p, request.id()));
      assertThat(calls).hasValue(3);
      assertThat(result.stance().completion().contextOccurrences()).isEqualTo(2);
      assertThat(result.stance().decisions())
          .extracting(StanceProtocol.Decision::commentId)
          .hasSize(25)
          .doesNotHaveDuplicates();
      assertThat(result.stance().statistics().coveredComments()).isEqualTo(25);
      assertThat(result.evidence()).hasSize(25);
      publish(request, token, result);
      assertThat(requests.progress(p, request.id()).completedComments()).isEqualTo(25);
    }
  }
}
