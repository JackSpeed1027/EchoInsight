/* 文件职责：验证自然语言请求的项目范围、幂等、任务复用、产物证据和诚实能力边界。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportService;
import io.echoinsight.orchestration.AnalysisWorker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

/** 测试项目使用独立 workspace，只追加唯一合成记录，不读取或修改用户项目。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.projects.workspace=intelligence-it"
    })
class AnalysisRequestControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired AnalysisWorker worker;

  /** 一条根评论和两级回复用于核对线程、原文证据及结构关系。 */
  private ImportDocument document(String source, String suffix) {
    var time = OffsetDateTime.parse("2026-10-16T15:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content(
            "content-" + suffix,
            "SYNTHETIC",
            source,
            "fixture",
            "命运石之门讨论合成资料" + suffix,
            time),
        List.of(
            new ImportDocument.Comment("root-" + suffix, "s1", null, "冈部为什么要这样选择？", time),
            new ImportDocument.Comment(
                "reply-" + suffix, "s2", "root-" + suffix, "因为前面的世界线变化限制了选择。", time.plusSeconds(1)),
            new ImportDocument.Comment(
                "nested-" + suffix, "s3", "reply-" + suffix, "但这个理由没有解释另一个反例。", time.plusSeconds(2))),
        List.of());
  }

  /** 创建隔离项目并挂载给定快照。 */
  private String project(String snapshot) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", "intelligence-project-" + UUID.randomUUID());
    String id =
        http.postForEntity(
                "/api/v1/projects",
                new HttpEntity<>(Map.of("name", "讨论项目", "analysisGoal", "理解评论争议"), headers),
                JsonNode.class)
            .getBody()
            .path("id")
            .asText();
    assertThat(
            http.postForEntity(
                    "/api/v1/projects/" + id + "/snapshots",
                    Map.of("snapshotId", snapshot),
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    return id;
  }

  /** 发送带幂等键的自然语言请求，保留真实状态码。 */
  private ResponseEntity<JsonNode> create(
      String project, String key, String instruction, String snapshot) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    var body = new java.util.HashMap<String, Object>();
    body.put("instruction", instruction);
    if (snapshot != null) body.put("snapshotId", snapshot);
    return http.postForEntity(
        "/api/v1/projects/" + project + "/analysis-requests",
        new HttpEntity<>(body, headers),
        JsonNode.class);
  }

  /** 单资料项目自动绑定，网络重发复用同一请求和任务。 */
  @Test
  void createsIdempotentRequestAndAutomaticallySelectsOnlySnapshot() {
    String snapshot = imports.ingest(document(UUID.randomUUID().toString(), "a")).snapshotId();
    String project = project(snapshot), key = "intelligence-request-" + UUID.randomUUID();
    var first = create(project, key, "总结用户讨论的逻辑和观点", null);
    var repeat = create(project, key, "总结用户讨论的逻辑和观点", null);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(repeat.getStatusCode().value()).isEqualTo(200);
    assertThat(repeat.getBody().path("id")).isEqualTo(first.getBody().path("id"));
    assertThat(repeat.getBody().path("jobId")).isEqualTo(first.getBody().path("jobId"));
    assertThat(first.getBody().path("snapshotId").asText()).isEqualTo(snapshot);
    assertThat(create(project, key, "换一个讨论目标", snapshot).getStatusCode().value())
        .isEqualTo(409);
  }

  /** Worker 使用同一任务引擎发布带原文、回复关系和不足判断的结构产物。 */
  @Test
  void publishesTraceableStructuralArtifactWithoutPretendingSemanticJudgment() {
    String snapshot = imports.ingest(document(UUID.randomUUID().toString(), "b")).snapshotId();
    String project = project(snapshot);
    var created =
        create(project, "intelligence-request-" + UUID.randomUUID(), "总结观点并判断谁更有道理", snapshot)
            .getBody();
    String requestId = created.path("id").asText();
    worker.runOne(created.path("jobId").asText());
    var state =
        http.getForObject(
            "/api/v1/projects/" + project + "/analysis-requests/" + requestId,
            JsonNode.class);
    assertThat(state.path("status").asText()).isEqualTo("SUCCESS");
    assertThat(state.path("artifactId").asText()).isNotBlank();
    var artifact =
        http.getForObject(
            "/api/v1/projects/"
                + project
                + "/analysis-requests/"
                + requestId
                + "/artifact",
            JsonNode.class);
    assertThat(artifact.path("sourceMode").asText()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(artifact.path("scope").path("totalComments").asInt()).isEqualTo(3);
    assertThat(artifact.path("issues").get(0).path("question").asText()).contains("为什么");
    assertThat(artifact.path("claims")).hasSize(2);
    assertThat(artifact.path("relations").get(0).path("type").asText()).isEqualTo("REPLY_TO");
    assertThat(artifact.path("assessments").get(0).path("verdict").asText())
        .isEqualTo("INSUFFICIENT");
    assertThat(artifact.toString()).contains("世界线变化限制了选择", "不能判断哪一方更有道理");
  }

  /** 多资料时必须选择分析对象；其他项目的快照不能通过请求字段混入。 */
  @Test
  void requiresExplicitSnapshotForMultipleMaterialsAndEnforcesMembership() {
    String first = imports.ingest(document(UUID.randomUUID().toString(), "c1")).snapshotId();
    String second = imports.ingest(document(UUID.randomUUID().toString(), "c2")).snapshotId();
    String outsider = imports.ingest(document(UUID.randomUUID().toString(), "c3")).snapshotId();
    String project = project(first);
    http.postForEntity(
        "/api/v1/projects/" + project + "/snapshots",
        Map.of("snapshotId", second),
        JsonNode.class);
    assertThat(
            create(
                    project,
                    "intelligence-request-" + UUID.randomUUID(),
                    "总结讨论观点",
                    null)
                .getStatusCode()
                .value())
        .isEqualTo(409);
    assertThat(
            create(
                    project,
                    "intelligence-request-" + UUID.randomUUID(),
                    "总结讨论观点",
                    outsider)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  /** 不支持的指令与未发布产物均明确失败，历史查询不会触发任务。 */
  @Test
  void rejectsUnsupportedIntentAndPrematureArtifactRead() {
    String snapshot = imports.ingest(document(UUID.randomUUID().toString(), "d")).snapshotId();
    String project = project(snapshot);
    assertThat(
            create(
                    project,
                    "intelligence-request-" + UUID.randomUUID(),
                    "帮我生成视频封面",
                    snapshot)
                .getStatusCode()
                .value())
        .isEqualTo(422);
    var request =
        create(
                project,
                "intelligence-request-" + UUID.randomUUID(),
                "总结大家的讨论观点",
                snapshot)
            .getBody();
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/"
                        + project
                        + "/analysis-requests/"
                        + request.path("id").asText()
                        + "/artifact",
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(409);
    var history =
        http.getForObject(
            "/api/v1/projects/" + project + "/analysis-requests?page=1&size=10",
            JsonNode.class);
    assertThat(history.path("total").asInt()).isEqualTo(1);
    assertThat(history.path("items").get(0).path("status").asText()).isEqualTo("PENDING");
  }
}
