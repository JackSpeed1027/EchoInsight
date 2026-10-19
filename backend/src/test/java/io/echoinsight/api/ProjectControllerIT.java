/* 文件职责：验证项目幂等、范围隔离、快照归属、原子导入及项目历史过滤。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportService;
import io.echoinsight.orchestration.JobStore;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** 测试项目放独立服务器范围，不出现在本机 local 项目首页；只追加唯一测试数据。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.projects.workspace=project-it"
    })
class ProjectControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired ImportService imports;
  @Autowired JobStore jobs;
  @Autowired JdbcTemplate db;

  /** 同一作品允许文本修订，ID 每次测试唯一，不覆盖真实材料。 */
  private ImportDocument document(String source, String text) {
    var time = OffsetDateTime.parse("2026-10-16T14:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("local", "SYNTHETIC", source, "fixture", "项目归档合成验收", time),
        List.of(new ImportDocument.Comment("a", "1", null, text, time)),
        List.of());
  }

  /** 每次逻辑创建用唯一请求键，测试网络重发时明确复用。 */
  private String key() {
    return "project-it-" + UUID.randomUUID();
  }

  /** 真实 HTTP 创建，保留状态码以检查首次和重发语义。 */
  private ResponseEntity<JsonNode> create(String key, String name, String goal) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.postForEntity(
        "/api/v1/projects",
        new HttpEntity<>(Map.of("name", name, "analysisGoal", goal), headers),
        JsonNode.class);
  }

  /** 合成项目只在测试范围创建。 */
  private String project() {
    var result = create(key(), "合成项目", "核对输入范围与旧快照");
    assertThat(result.getStatusCode().value()).isEqualTo(201);
    return result.getBody().path("id").asText();
  }

  /** 挂载不重新导入或复制原文，返回这个明确版本。 */
  private ResponseEntity<JsonNode> attach(String project, String snapshot) {
    return http.postForEntity(
        "/api/v1/projects/" + project + "/snapshots",
        Map.of("snapshotId", snapshot),
        JsonNode.class);
  }

  /** 通过既有上传入口添加项目字段，测试服务事务是否整体绑定。 */
  private ResponseEntity<JsonNode> upload(String project, ImportDocument input) throws Exception {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("projectId", project);
    parts.add(
        "file",
        new ByteArrayResource(json.writeValueAsBytes(input)) {
          @Override
          public String getFilename() {
            return "input.json";
          }
        });
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity("/api/v1/imports", new HttpEntity<>(parts, headers), JsonNode.class);
  }

  /** 并发重发同键只生成一条项目，同键不同目标拒绝，不重写名称。 */
  @Test
  void idempotentProjectCreationSurvivesConcurrentRetries() throws Exception {
    String request = key();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures =
          new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
      for (int i = 0; i < 8; i++)
        futures.add(pool.submit(() -> create(request, "并发合成项目", "同键不重复")));
      var ids = new java.util.HashSet<String>();
      int created = 0;
      for (var future : futures) {
        var response = future.get();
        assertThat(response.getStatusCode().value()).isIn(200, 201);
        if (response.getStatusCode().value() == 201) created++;
        ids.add(response.getBody().path("id").asText());
      }
      assertThat(ids).hasSize(1);
      assertThat(created).isEqualTo(1);
    }
    assertThat(create(request, "并发合成项目", "不同目标").getStatusCode().value()).isEqualTo(409);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM business_project WHERE request_key=? AND"
                    + " workspace_scope='project-it'",
                Long.class,
                request))
        .isEqualTo(1);
  }

  /** 服务器范围不能由客户端跨越；不泄露另一个范围的项目或成员。 */
  @Test
  void serverWorkspaceKeepsOtherProjectScopesOutOfListAndDetails() {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO business_project(id,workspace_scope,request_key,name,analysis_goal)"
            + " VALUES(?,?,?,?,?)",
        id,
        "other-project-it",
        key(),
        "其他范围合成项目",
        "范围核对");
    assertThat(http.getForEntity("/api/v1/projects/" + id, JsonNode.class).getStatusCode().value())
        .isEqualTo(404);
    assertThat(attach(id, UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(404);
    var listing = http.getForObject("/api/v1/projects?page=1&size=50", JsonNode.class);
    for (var item : listing.path("items")) assertThat(item.path("id").asText()).isNotEqualTo(id);
    assertThat(
            http.getForEntity("/api/v1/analysis-jobs?projectId=" + id, JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  /** 重复挂载不增数，新版本未显式挂载时不自动进入，旧正文保持不变。 */
  @Test
  void mountingIsIdempotentAndNeverAutomaticallyTracksNewerSnapshot() throws Exception {
    String p = project(), source = key();
    var old = imports.ingest(document(source, "原文"));
    var original = http.getForObject("/api/v1/snapshots/" + old.snapshotId(), JsonNode.class);
    assertThat(attach(p, old.snapshotId()).getStatusCode().value()).isEqualTo(200);
    var again = attach(p, old.snapshotId()).getBody();
    assertThat(again.path("snapshotId").asText()).isEqualTo(old.snapshotId());
    var latest = imports.ingest(document(source, "新版本正文"));
    var before = http.getForObject("/api/v1/projects/" + p + "/snapshots", JsonNode.class);
    assertThat(before.path("total").asInt()).isEqualTo(1);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + p + "/snapshots/" + latest.snapshotId(), JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    attach(p, latest.snapshotId());
    var after = http.getForObject("/api/v1/projects/" + p + "/snapshots?size=1", JsonNode.class);
    assertThat(after.path("total").asInt()).isEqualTo(2);
    assertThat(after.path("items").get(0).path("snapshotId").asText())
        .isEqualTo(latest.snapshotId());
    var detail = http.getForObject("/api/v1/projects/" + p, JsonNode.class);
    assertThat(detail.path("workCount").asInt()).isEqualTo(1);
    assertThat(detail.path("snapshotCount").asInt()).isEqualTo(2);
    assertThat(http.getForObject("/api/v1/snapshots/" + old.snapshotId(), JsonNode.class))
        .isEqualTo(original);
  }

  /** 有效项目与上传原子归档；缺项目先拒绝，不产生孤立导入/快照。 */
  @Test
  void projectImportIsAtomicAndKeepsLegacyUploadCompatible() throws Exception {
    String p = project();
    var uploaded = upload(p, document(key(), "项目内正文"));
    assertThat(uploaded.getStatusCode().value()).isEqualTo(201);
    assertThat(
            http.getForObject("/api/v1/projects/" + p, JsonNode.class)
                .path("snapshotCount")
                .asInt())
        .isEqualTo(1);
    long receipts = db.queryForObject("SELECT COUNT(*) FROM ingestion_import", Long.class);
    long snapshots = db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class);
    assertThat(
            upload(UUID.randomUUID().toString(), document(key(), "不能落库")).getStatusCode().value())
        .isEqualTo(404);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ingestion_import", Long.class))
        .isEqualTo(receipts);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class))
        .isEqualTo(snapshots);
  }

  /** 项目历史基于挂载的明确快照，其他版本/作品任务不会混入。 */
  @Test
  void projectHistoryIncludesOnlyMountedSnapshotsAndRemainsReadOnly() {
    String p = project(), other = project();
    var one = imports.ingest(document(key(), "项目一"));
    var two = imports.ingest(document(key(), "未挂载"));
    var job = jobs.create(one.snapshotId(), key());
    jobs.create(two.snapshotId(), key());
    attach(p, one.snapshotId());
    long count = db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class);
    var history =
        http.getForObject(
            "/api/v1/analysis-jobs?projectId=" + p + "&status=PENDING", JsonNode.class);
    assertThat(history.path("total").asInt()).isEqualTo(1);
    assertThat(history.path("items").get(0).path("id").asText()).isEqualTo(job.id());
    assertThat(
            http.getForObject("/api/v1/analysis-jobs?projectId=" + other, JsonNode.class)
                .path("total")
                .asInt())
        .isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class)).isEqualTo(count);
  }

  /** 参数、项目和资料不存在时清晰失败，未挂载资料不通过深链接混入。 */
  @Test
  void invalidProjectInputAndMissingMembershipFailClearly() {
    assertThat(create(key(), " ", "目标").getStatusCode().value()).isEqualTo(400);
    assertThat(create(key(), "a".repeat(121), "目标").getStatusCode().value()).isEqualTo(400);
    assertThat(create("bad key", "名称", "目标").getStatusCode().value()).isEqualTo(400);
    assertThat(http.getForEntity("/api/v1/projects?page=0", JsonNode.class).getStatusCode().value())
        .isEqualTo(400);
    assertThat(
            http.getForEntity("/api/v1/projects?size=51", JsonNode.class).getStatusCode().value())
        .isEqualTo(400);
    String p = project();
    assertThat(attach(p, UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(404);
    assertThat(
            http.getForObject("/api/v1/projects/" + p + "/snapshots?page=10000", JsonNode.class)
                .path("items")
                .isEmpty())
        .isTrue();
  }

  /** 平台双文件入口同样可选项目，返回旧回执格式但事务内完成挂载。 */
  @Test
  void platformFilesCanBeImportedDirectlyIntoProject() {
    String p = project(), source = key();
    String contents =
        "[{\"video_id\":\""
            + source
            + "\",\"title\":\"项目内平台合成\",\"creator_hash\":\"fixture\",\"create_time\":1760000000}]";
    String comments =
        "[{\"video_id\":\""
            + source
            + "\",\"comment_id\":\"1\",\"content\":\"合成评论\",\"create_time\":1760000001}]";
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("platform", "BILIBILI");
    parts.add("sourceContentId", source);
    parts.add("projectId", p);
    for (var entry : Map.of("contentsFile", contents, "commentsFile", comments).entrySet()) {
      parts.add(
          entry.getKey(),
          new ByteArrayResource(entry.getValue().getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
              return "export.json";
            }
          });
    }
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    var response =
        http.postForEntity(
            "/api/v1/imports/mediacrawler", new HttpEntity<>(parts, headers), JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    var snapshot = response.getBody().path("receipt").path("snapshotId").asText();
    assertThat(
            http.getForObject("/api/v1/projects/" + p + "/snapshots/" + snapshot, JsonNode.class)
                .path("sourceType")
                .asText())
        .isEqualTo("BILIBILI");
  }
}
