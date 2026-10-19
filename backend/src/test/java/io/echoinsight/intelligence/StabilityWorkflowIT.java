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
      "echoinsight.projects.workspace=stability-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class StabilityWorkflowIT {
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
    when(plans.current()).thenReturn(ModelContract.Plan.mock());
  }

  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "stability-v1",
        "discussion-json-v3",
        "argument-map-v1",
        1,
        3000,
        512,
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

  /** 预检不生成请求或预算；超120条必须确认有限范围；拒绝接受不留下半份任务。 */
  @Test
  void previewIsReadOnlyAndPartialConsentIsAtomic() {
    String project = project(),
        snapshot = snapshot(project, 121),
        key = UUID.randomUUID().toString();
    var body = Map.of("snapshotId", snapshot, "instruction", "总结所有评论的观点");
    int calls = db.queryForObject("SELECT COUNT(*) FROM model_call", Integer.class);
    var preview = post(project, "/analysis-requests/preview", body, key);
    assertThat(preview.getStatusCode().value()).isEqualTo(200);
    assertThat(preview.getBody().path("selectedComments").asInt()).isEqualTo(120);
    assertThat(preview.getBody().path("omittedComments").asInt()).isEqualTo(1);
    assertThat(preview.getBody().path("requiresPartialConsent").asBoolean()).isTrue();
    assertThat(rows("analysis_request", project)).isZero();
    var rejected = post(project, "/analysis-requests", body, key);
    assertThat(rejected.getStatusCode().value()).isEqualTo(422);
    assertThat(rejected.getBody().path("message").asText()).contains("有限输入");
    assertThat(rows("analysis_request", project)).isZero();
    var accepted = new HashMap<String, Object>(body);
    accepted.put("allowPartialInput", true);
    var created = post(project, "/analysis-requests", accepted, key);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(post(project, "/analysis-requests", body, key).getStatusCode().value())
        .isEqualTo(200);
    assertThat(rows("analysis_request", project)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call", Integer.class))
        .isEqualTo(calls);
  }

  /** 数量约束给字段和明确上限，格式错误不回显输入；没有模型/平台执行。 */
  @Test
  void invalidCountAndJsonAreReadableAndDoNotEchoValues() {
    String project = project();
    var invalid =
        post(
            project,
            "/collections/bilibili",
            Map.of("url", "https://www.bilibili.com/video/BV1ntuF64EhF/", "rootLimit", 201),
            "invalid-count");
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
    assertThat(invalid.getBody().path("issues").toString())
        .contains("rootLimit", "200")
        .doesNotContain("https://");
    var raw = post(project, "/analysis-requests", "{private-value", "invalid-json");
    assertThat(raw.getStatusCode().value()).isEqualTo(400);
    assertThat(raw.getBody().toString()).doesNotContain("private-value");
  }

  /** 背景预检与失败查询都必须校验项目资格，不能借新路径越界读取资料。 */
  @Test
  void scopedReadPathsCannotBorrowAnotherProject() {
    String a = project(), b = project(), snapshot = snapshot(a, 1);
    assertThat(
            post(b, "/analysis-requests/preview", Map.of("snapshotId", snapshot), "scope")
                .getStatusCode()
                .value())
        .isEqualTo(404);
    var request = requests.create(a, UUID.randomUUID().toString(), snapshot, "总结讨论观点").request();
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + b + "/analysis-requests/" + request.id() + "/failure",
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  /** 证据越界留下具体原因，发送后仍UNKNOWN并保留预留，页面不允许危险重试。 */
  @Test
  void rejectedEvidenceRetainsUnknownCostAndReadableCause() {
    String project = project(), snapshot = snapshot(project, 1);
    var request =
        requests.create(project, UUID.randomUUID().toString(), snapshot, "总结讨论观点").request();
    String token = jobs.claim(request.jobId());
    var provider =
        new DiscussionModelContract.Provider() {
          public String id() {
            return "SPRING_AI";
          }

          public String modelVersion() {
            return "stability-v1";
          }

          public void validatePlan(ModelContract.Plan plan) {}

          public DiscussionModelContract.Response analyze(
              DiscussionModelContract.Request input, Duration timeout) {
            return new DiscussionModelContract.Response(
                List.of(
                    new DiscussionModelContract.Issue(
                        "i", "合成问题", "合成总结", List.of("not-in-input"))),
                List.of(),
                List.of(),
                List.of(),
                10,
                1,
                "synthetic-request");
          }
        };
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider))) {
      var input =
          new DiscussionModelContract.Request(
              snapshot,
              "总结观点",
              "合成",
              "SYNTHETIC",
              false,
              1,
              List.of(new DiscussionModelContract.Text("c0", null, "合成主张")),
              128000,
              512);
      assertThatThrownBy(() -> gateway.discuss(request.jobId(), token, plan(), input))
          .hasMessage("MODEL_OUTCOME_UNKNOWN");
    }
    jobs.fail(request.jobId(), token, "MODEL_OUTCOME_UNKNOWN");
    var detail =
        http.getForEntity(
                "/api/v1/projects/" + project + "/analysis-requests/" + request.id() + "/failure",
                JsonNode.class)
            .getBody();
    assertThat(detail.path("causeCode").asText()).isEqualTo("MODEL_EVIDENCE_INVALID");
    assertThat(detail.path("feeUncertain").asBoolean()).isTrue();
    assertThat(detail.path("canRetry").asBoolean()).isFalse();
    assertThat(ledger.view(request.jobId(), 1, 10).budget().reservedUnits()).isPositive();
    assertThatThrownBy(() -> jobs.retry(request.jobId()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 非失败请求不虚构原因，预检保存数和实际选择都准确。 */
  @Test
  void ordinaryRequestHasNoInventedFailure() {
    String project = project(), snapshot = snapshot(project, 1);
    var request =
        requests.create(project, UUID.randomUUID().toString(), snapshot, "总结讨论观点").request();
    var detail =
        http.getForEntity(
                "/api/v1/projects/" + project + "/analysis-requests/" + request.id() + "/failure",
                JsonNode.class)
            .getBody();
    assertThat(detail.path("errorCode").isNull()).isTrue();
    assertThat(detail.path("canRetry").asBoolean()).isFalse();
    assertThat(
            post(project, "/analysis-requests/preview", Map.of("snapshotId", snapshot), "one")
                .getBody()
                .path("requiresPartialConsent")
                .asBoolean())
        .isFalse();
  }
}
