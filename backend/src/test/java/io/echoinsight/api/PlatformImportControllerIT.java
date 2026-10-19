/* 文件职责：验证平台上传到真实数据库快照的闭环、原始身份、去重、历史隔离和失败原子性。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.analysis.FixtureClassifier;
import io.echoinsight.ingestion.ImportDocument;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** 只追加标明合成用途的唯一平台格式材料，不联网、不使用真实账号或收费模型。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"echoinsight.worker.enabled=false", "echoinsight.model.enabled=false"})
class PlatformImportControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired FixtureClassifier fixture;

  /** 合成作品 ID 不借用真实平台内容，避免测试与用户来源身份碰撞。 */
  private String source() {
    return "platform-it-" + UUID.randomUUID();
  }

  /** 当前字段映射下两种平台唯一差异是作品身份字段与时间单位。 */
  private String content(String id, boolean xhs) throws Exception {
    return json.writeValueAsString(
        java.util.List.of(
            java.util.Map.of(
                xhs ? "note_id" : "video_id",
                id,
                "title",
                "平台格式合成验收",
                "creator_hash",
                "fixture-author",
                xhs ? "time" : "create_time",
                xhs ? 1760000000123L : 1760000000L,
                "xsec_token",
                "PRIVATE_PLATFORM_TOKEN")));
  }

  /** 与 Mock 已知文本完全相同仍不应给平台来源查表标签。 */
  private String comments(String id, String text, boolean xhs, String parent) throws Exception {
    return json.writeValueAsString(
        java.util.List.of(
            java.util.Map.of(
                xhs ? "note_id" : "video_id",
                id,
                "comment_id",
                "11",
                "content",
                text,
                "parent_comment_id",
                "0",
                "create_time",
                xhs ? 1760000000456L : 1760000001L),
            java.util.Map.of(
                xhs ? "note_id" : "video_id",
                id,
                "comment_id",
                "12",
                "content",
                "合成回复",
                "parent_comment_id",
                parent,
                "create_time",
                xhs ? 1760000000789L : 1760000002L)));
  }

  /** Multipart 经真实 HTTP 上传，文件名只指定格式，不作为服务器保存路径。 */
  private org.springframework.http.ResponseEntity<JsonNode> upload(
      String platform, String id, byte[] contents, byte[] comments, String filename) {
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("platform", platform);
    parts.add("sourceContentId", id);
    parts.add("contentsFile", file(contents, "contents.json"));
    parts.add("commentsFile", file(comments, filename));
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity(
        "/api/v1/imports/mediacrawler", new HttpEntity<>(parts, headers), JsonNode.class);
  }

  /** 明确 UTF-8，不依赖 Windows 默认编码。 */
  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** 上传资源名可以是本地文件名，响应及错误不应回显整个原始文件。 */
  private ByteArrayResource file(byte[] data, String name) {
    return new ByteArrayResource(data) {
      @Override
      public String getFilename() {
        return name;
      }
    };
  }

  /** 两个平台都进入既有快照链，额外字段不持久化，平台来源不能伪装 SYNTHETIC。 */
  @Test
  void uploadsBothPlatformsAndNeverUsesSyntheticLabelsOrModelCalls() throws Exception {
    long calls = db.queryForObject("SELECT COUNT(*) FROM model_call", Long.class);
    long jobs = db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class);
    for (boolean xhs : new boolean[] {false, true}) {
      String id = source();
      var response =
          upload(
              xhs ? "XHS" : "BILIBILI",
              id,
              bytes(content(id, xhs)),
              bytes(comments(id, "线程池与拒绝策略的例子很清楚，帮助我理解了。", xhs, "11")),
              "comments.json");
      assertThat(response.getStatusCode().value()).isEqualTo(201);
      var body = response.getBody();
      assertThat(body.path("sourceTotalComments").isNull()).isTrue();
      assertThat(body.path("selectedCommentRows").asInt()).isEqualTo(2);
      assertThat(body.path("contentFileSha256").asText()).hasSize(64);
      assertThat(response.getHeaders().getLocation().toString()).startsWith("/api/v1/imports/");
      String snapshot = body.path("receipt").path("snapshotId").asText();
      var stored =
          http.getForObject("/api/v1/snapshots/" + snapshot, JsonNode.class).path("document");
      assertThat(stored.path("content").path("sourceType").asText())
          .isEqualTo(xhs ? "XHS" : "BILIBILI");
      assertThat(stored.toString()).doesNotContain("PRIVATE_PLATFORM_TOKEN", "xsec_token");
      var input = json.treeToValue(stored, ImportDocument.class);
      assertThat(fixture.classify(input, () -> {}))
          .allSatisfy(c -> assertThat(c.label().name()).isEqualTo("UNKNOWN"));
      assertThat(stored.path("comments").get(1).path("parentId").asText())
          .isEqualTo((xhs ? "mc-xhs-" : "mc-bili-") + "11");
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM model_call", Long.class)).isEqualTo(calls);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class)).isEqualTo(jobs);
  }

  /** 同一文件复用快照，后续评论编辑产生修订；旧快照原文不变。 */
  @Test
  void reusesSnapshotsAndPreservesHistoricalOriginalText() throws Exception {
    String id = source();
    byte[] contents = bytes(content(id, false));
    var first =
        upload("BILIBILI", id, contents, bytes(comments(id, "原始文字", false, "11")), "comments.json")
            .getBody()
            .path("receipt");
    var again =
        upload("BILIBILI", id, contents, bytes(comments(id, "原始文字", false, "11")), "comments.json")
            .getBody()
            .path("receipt");
    assertThat(again.path("snapshotId")).isEqualTo(first.path("snapshotId"));
    assertThat(again.path("snapshotReused").asBoolean()).isTrue();
    assertThat(again.path("newRevisions").asInt()).isZero();
    var edited =
        upload("BILIBILI", id, contents, bytes(comments(id, "编辑文字", false, "11")), "comments.json")
            .getBody()
            .path("receipt");
    assertThat(edited.path("snapshotId")).isNotEqualTo(first.path("snapshotId"));
    assertThat(edited.path("newRevisions").asInt()).isEqualTo(1);
    var original =
        http.getForObject("/api/v1/snapshots/" + first.path("snapshotId").asText(), JsonNode.class);
    assertThat(original.path("document").path("comments").get(0).path("text").asText())
        .isEqualTo("原始文字");
  }

  /** 选中作品缺父评论时整体拒绝，不能脱离上下文后静默导入。 */
  @Test
  void missingParentOrInvalidFieldsDoNotCreatePartialImport() throws Exception {
    String id = source();
    long imports = db.queryForObject("SELECT COUNT(*) FROM ingestion_import", Long.class);
    long snapshots = db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class);
    for (String parent : new String[] {"missing", "12"}) {
      var response =
          upload(
              "BILIBILI",
              id,
              bytes(content(id, false)),
              bytes(comments(id, "正文", false, parent)),
              "comments.json");
      assertThat(response.getStatusCode().value()).isEqualTo(422);
      assertThat(response.getBody().toString()).doesNotContain("PRIVATE_PLATFORM_TOKEN");
    }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM ingestion_import", Long.class))
        .isEqualTo(imports);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class))
        .isEqualTo(snapshots);
  }

  /** 后缀、大小、编码和参数都走清晰错误，错误格式与原导入入口一致。 */
  @Test
  void rejectsOversizedInvalidEncodingAndUnknownPlatform() throws Exception {
    String id = source();
    byte[] good = bytes(comments(id, "正文", false, "11"));
    assertThat(
            upload("BILIBILI", id, bytes(content(id, false)), good, "comments.csv")
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            upload("OTHER", id, bytes(content(id, false)), good, "comments.json")
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            upload(
                    "BILIBILI",
                    id,
                    bytes(content(id, false)),
                    new byte[] {(byte) 0xff},
                    "comments.json")
                .getStatusCode()
                .value())
        .isEqualTo(422);
    assertThat(
            upload(
                    "BILIBILI",
                    id,
                    bytes(content(id, false)),
                    new byte[2 * 1024 * 1024 + 1],
                    "comments.json")
                .getStatusCode()
                .value())
        .isEqualTo(413);
    var partial = new LinkedMultiValueMap<String, Object>();
    partial.add("platform", "BILIBILI");
    partial.add("sourceContentId", id);
    var partialHeaders = new HttpHeaders();
    partialHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            http.postForEntity(
                    "/api/v1/imports/mediacrawler",
                    new HttpEntity<>(partial, partialHeaders),
                    JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
  }
}
