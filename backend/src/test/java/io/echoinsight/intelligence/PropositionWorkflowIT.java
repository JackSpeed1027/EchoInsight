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
      "echoinsight.projects.workspace=proposition-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class PropositionWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisRequestStore requests;
  @Autowired JobStore jobs;
  @Autowired ModelLedger ledger;
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @MockitoBean ConfiguredModelPlan plans;

  @BeforeEach
  void configureOnlySyntheticPlan() {
    when(plans.discussion()).thenReturn(plan());
    when(plans.discussionWithProposition()).thenReturn(plan());
    when(plans.current()).thenReturn(ModelContract.Plan.mock());
  }

  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "stability-v1",
        StanceProtocol.VERSION,
        StanceProtocol.TAXONOMY,
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

  /** 只读预检要求明确参照，13条只选12条；拒绝创建时无半份请求/任务/规格。 */
  @Test
  void clarificationAndPartialScopeDoNotCreateHalfRequest() {
    String p = project(), s = snapshot(p, 13);
    var body = new LinkedHashMap<String, Object>();
    body.put("snapshotId", s);
    body.put("instruction", "哪些评论支持这个观点");
    int calls = db.queryForObject("SELECT COUNT(*) FROM model_call", Integer.class);
    var preview = post(p, "/analysis-requests/preview", body, "preview").getBody();
    assertThat(preview.path("goal").path("needsProposition").asBoolean()).isTrue();
    assertThat(preview.path("selectedComments").asInt()).isEqualTo(12);
    assertThat(post(p, "/analysis-requests", body, "no-target").getStatusCode().value())
        .isEqualTo(422);
    body.put("proposition", "剧情因果自洽");
    assertThat(post(p, "/analysis-requests", body, "no-consent").getStatusCode().value())
        .isEqualTo(422);
    assertThat(rows("analysis_request", p)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call", Integer.class))
        .isEqualTo(calls);
  }

  /** 同键同目标重发复用；换目标409，原规格不变；越项目404。 */
  @Test
  void frozenTargetAndIdempotencyHaveSameTransactionBoundary() {
    String p = project(),
        other = project(),
        s = snapshot(p, 13),
        key = UUID.randomUUID().toString();
    var body = new LinkedHashMap<String, Object>();
    body.put("snapshotId", s);
    body.put("instruction", "分析支持和反对");
    body.put("proposition", "剧情因果自洽");
    body.put("allowPartialInput", true);
    var first = post(p, "/analysis-requests", body, key);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    String id = first.getBody().path("id").asText();
    assertThat(post(p, "/analysis-requests", body, key).getBody().path("id").asText())
        .isEqualTo(id);
    body.put("proposition", "另一命题");
    assertThat(post(p, "/analysis-requests", body, key).getStatusCode().value()).isEqualTo(409);
    assertThat(requests.specification(p, id).proposition()).isEqualTo("剧情因果自洽");
    assertThat(rows("analysis_request", p)).isEqualTo(1);
    assertThat(jobs.plan(first.getBody().path("jobId").asText()).promptVersion())
        .isEqualTo(StanceProtocol.VERSION);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + other + "/analysis-requests/" + id + "/specification",
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  private DiscussionModelContract.Provider provider(
      java.util.concurrent.atomic.AtomicInteger calls, boolean valid) {
    return new DiscussionModelContract.Provider() {
      public String id() {
        return "SPRING_AI";
      }

      public String modelVersion() {
        return "stability-v1";
      }

      public void validatePlan(ModelContract.Plan plan) {
        assertThat(plan.promptVersion()).isEqualTo(StanceProtocol.VERSION);
      }

      public DiscussionModelContract.Response analyze(
          DiscussionModelContract.Request input, Duration timeout) {
        calls.incrementAndGet();
        var rows =
            valid
                ? input.comments().stream()
                    .map(
                        c ->
                            new StanceProtocol.Decision(
                                c.commentId(),
                                StanceProtocol.Coverage.UNKNOWN,
                                "合成：无法判断",
                                List.of()))
                    .toList()
                : List.<StanceProtocol.Decision>of();
        return new DiscussionModelContract.Response(
            List.of(), List.of(), List.of(), List.of(), 10, 2, "local-v5", rows);
      }
    };
  }

  private DiscussionModelContract.Request modelRequest(
      String snapshot, AnalysisSpecification spec) {
    return new DiscussionModelContract.Request(
        snapshot,
        "分析立场",
        "合成",
        "SYNTHETIC",
        false,
        1,
        List.of(new DiscussionModelContract.Text("c0", null, "合成主张")),
        128000,
        4096,
        List.of(),
        StanceProtocol.VERSION,
        spec);
  }

  /** 合法命题调用复用原检查点，物理调用一次；输入指纹包含命题。 */
  @Test
  void validStanceReusesCheckpointAndCannotChangeTarget() {
    String p = project(), s = snapshot(p, 1);
    var created =
        requests
            .createWithSpecification(
                p, UUID.randomUUID().toString(), s, "分析立场", false, List.of(), false, "剧情因果自洽")
            .request();
    String token = jobs.claim(created.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, true)))) {
      var input = modelRequest(s, requests.specification(p, created.id()));
      var missingMarker =
          new DiscussionModelContract.Request(
              s, "分析立场", "合成", "SYNTHETIC", false, 1, input.comments(), 128000, 4096);
      assertThatThrownBy(() -> gateway.discuss(created.jobId(), token, plan(), missingMarker))
          .hasMessage("MODEL_INVALID_REQUEST_BOUNDARY");
      assertThat(calls).hasValue(0);
      assertThat(ledger.view(created.jobId(), 1, 10).total()).isZero();
      gateway.discuss(created.jobId(), token, plan(), input);
      gateway.discuss(created.jobId(), token, plan(), input);
      assertThat(calls).hasValue(1);
      assertThat(ledger.view(created.jobId(), 1, 10).budget().settledUnits()).isEqualTo(12);
      assertThatThrownBy(
              () ->
                  gateway.discuss(
                      created.jobId(),
                      token,
                      plan(),
                      modelRequest(
                          s,
                          new AnalysisSpecification(
                              AnalysisSpecification.VERSION, "不同命题", "USER_CLARIFICATION"))))
          .isInstanceOf(ModelContract.Failure.class);
      assertThat(calls).hasValue(1);
    }
    jobs.fail(created.jobId(), token, "SYNTHETIC_COMPLETE");
  }

  /** 已发出的遗漏覆盖不发布，仍保留未知费用；不得自动付费重试。 */
  @Test
  void missingCoverageRetainsUnknownFeeAndCause() {
    String p = project(), s = snapshot(p, 1);
    var created =
        requests
            .createWithSpecification(
                p, UUID.randomUUID().toString(), s, "分析立场", false, List.of(), false, "剧情因果自洽")
            .request();
    String token = jobs.claim(created.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, false)))) {
      assertThatThrownBy(
              () ->
                  gateway.discuss(
                      created.jobId(),
                      token,
                      plan(),
                      modelRequest(s, requests.specification(p, created.id()))))
          .hasMessage("MODEL_OUTCOME_UNKNOWN");
    }
    jobs.fail(created.jobId(), token, "MODEL_OUTCOME_UNKNOWN");
    var failure =
        http.getForEntity(
                "/api/v1/projects/" + p + "/analysis-requests/" + created.id() + "/failure",
                JsonNode.class)
            .getBody();
    assertThat(failure.path("causeCode").asText()).isEqualTo("MODEL_STANCE_INVALID");
    assertThat(failure.path("feeUncertain").asBoolean()).isTrue();
    assertThat(failure.path("canRetry").asBoolean()).isFalse();
    assertThat(ledger.view(created.jobId(), 1, 10).budget().reservedUnits()).isPositive();
  }

  /** v5产物含全部选中证据和父关系；只读统计不把无法判断转成中立或编造支持率。 */
  @Test
  void analysisPublishesRoleEvidenceAndJavaStatistics() throws Exception {
    String p = project(), s = snapshot(p, 2);
    var request =
        requests
            .createWithSpecification(
                p, UUID.randomUUID().toString(), s, "分析立场", false, List.of(), false, "剧情因果自洽")
            .request();
    String token = jobs.claim(request.jobId());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var time = OffsetDateTime.parse("2026-10-18T00:00:00Z");
    var input =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("x", "SYNTHETIC", "x", "x", "合成", time),
            List.of(
                new ImportDocument.Comment("parent", "p", null, "父评论", time),
                new ImportDocument.Comment("reply", "r", "parent", "回复", time)),
            List.of());
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider(calls, true)))) {
      var service =
          new DiscussionAnalysisService(
              new StructuralDiscussionAnalyzer(), gateway, new ThreadContextSelector());
      var artifact =
          service.analyze(
              jobs.get(request.jobId()),
              token,
              plan(),
              request,
              input,
              UUID.randomUUID().toString(),
              () -> {},
              AnalysisContextStore.View.none(),
              requests.specification(p, request.id()));
      assertThat(artifact.schemaVersion()).isEqualTo("discussion-artifact-v5-stance");
      assertThat(artifact.stance().statistics().coveredComments()).isEqualTo(2);
      assertThat(artifact.stance().statistics().totalClaims()).isZero();
      assertThat(artifact.evidence()).hasSize(2);
      assertThat(artifact.evidence().get(1).parentCommentId()).isEqualTo("comment-1");
      assertThat(
              json.readValue(json.writeValueAsString(artifact), DiscussionArtifact.class)
                  .stance()
                  .analyzed())
          .isTrue();
      jobs.startStep(request.jobId(), token, "DISCUSSION");
      jobs.saveDiscussion(request.jobId(), token, json.writeValueAsString(artifact));
      jobs.startStep(request.jobId(), token, "ARTIFACT");
      jobs.publishArtifact(
          request.jobId(),
          token,
          artifact.artifactId(),
          request.id(),
          s,
          artifact.schemaVersion(),
          json.writeValueAsString(artifact));
      assertThat(requests.artifact(p, request.id()).path("stance").path("analyzed").asBoolean())
          .isTrue();
      assertThat(
              db.queryForObject(
                  "SELECT schema_version FROM analysis_artifact WHERE request_id=?",
                  String.class,
                  request.id()))
          .isEqualTo("discussion-artifact-v5-stance");
    }
  }
}
