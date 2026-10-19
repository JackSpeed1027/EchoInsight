package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ObjectMapper json;
  @Autowired ImportService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  private ImportDocument document(String source, String text) {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("local", "SYNTHETIC", source, "test", "集成测试合成数据", time),
        List.of(
            new ImportDocument.Comment("a", "1", null, text, time),
            new ImportDocument.Comment("b", "2", "a", "测试回复", time)),
        List.of());
  }

  private String source() {
    return "it-" + UUID.randomUUID();
  }

  private ResponseEntity<JsonNode> upload(byte[] bytes, String filename) {
    var body = new LinkedMultiValueMap<String, Object>();
    body.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return http.postForEntity("/api/v1/imports", new HttpEntity<>(body, headers), JsonNode.class);
  }

  private JsonNode upload(ImportDocument input) throws Exception {
    var response = upload(json.writeValueAsBytes(input), "input.json");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  @Test
  void duplicateImportsReuseSnapshotAndEditedCommentPreservesOriginal() throws Exception {
    String source = source();
    var first = upload(document(source, "原始文本"));
    var reordered = document(source, "原始文本");
    var duplicate =
        upload(
            new ImportDocument(
                reordered.schemaVersion(),
                reordered.content(),
                reordered.comments().reversed(),
                reordered.transcriptSegments()));
    assertThat(duplicate.path("snapshotId").asText()).isEqualTo(first.path("snapshotId").asText());
    assertThat(duplicate.path("snapshotReused").asBoolean()).isTrue();
    assertThat(duplicate.path("newRevisions").asInt()).isZero();
    var edited = upload(document(source, "已修改文本"));
    assertThat(edited.path("snapshotId").asText()).isNotEqualTo(first.path("snapshotId").asText());
    assertThat(edited.path("newRevisions").asInt()).isEqualTo(1);
    var old =
        http.getForObject("/api/v1/snapshots/" + first.path("snapshotId").asText(), JsonNode.class);
    assertThat(old.path("document").path("comments").get(0).path("text").asText())
        .isEqualTo("原始文本");
    var receipt = http.getForEntity("/api/v1/imports/" + first.path("id").asText(), JsonNode.class);
    assertThat(receipt.getBody().path("id").asText()).isEqualTo(first.path("id").asText());
  }

  @Test
  void duplicateRowsAreCountedButDistinctSourceCommentsRemain() throws Exception {
    var input = document(source(), "相同文本");
    var withDuplicate =
        new ImportDocument(
            input.schemaVersion(),
            input.content(),
            List.of(input.comments().get(0), input.comments().get(0), input.comments().get(1)),
            input.transcriptSegments());
    var result = upload(withDuplicate);
    assertThat(result.path("importedComments").asInt()).isEqualTo(3);
    assertThat(result.path("eligibleComments").asInt()).isEqualTo(2);
    assertThat(result.path("duplicateComments").asInt()).isEqualTo(1);
  }

  @Test
  void fourConcurrentImportsCreateOneSnapshotAndTwoRevisions() throws Exception {
    var input = document(source(), "并发测试");
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<ImportReceipt>>();
      for (int i = 0; i < 4; i++) tasks.add(() -> service.ingest(input));
      var results = new ArrayList<ImportReceipt>();
      for (var future : pool.invokeAll(tasks)) results.add(future.get(30, TimeUnit.SECONDS));
      assertThat(results.stream().map(ImportReceipt::snapshotId).distinct().count()).isEqualTo(1);
      assertThat(results.stream().mapToInt(ImportReceipt::newRevisions).sum()).isEqualTo(2);
    }
  }

  @Test
  void transactionRollbackDoesNotLeaveContentRevisionsOrReceipts() {
    String source = source();
    var tx = new TransactionTemplate(transactions);
    ImportReceipt result =
        tx.execute(
            status -> {
              var receipt = service.ingest(document(source, "回滚测试"));
              status.setRollbackOnly();
              return receipt;
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM content_item WHERE source_content_id=?",
                Integer.class,
                source))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment_revision WHERE content_id=?",
                Integer.class,
                result.contentId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM ingestion_import WHERE id=?", Integer.class, result.id()))
        .isZero();
  }

  @Test
  void invalidFieldsRejectBeforeWriting() throws Exception {
    String source = source();
    var broken = document(source, " ");
    var response = upload(json.writeValueAsBytes(broken), "input.json");
    assertThat(response.getStatusCode().value()).isEqualTo(422);
    assertThat(response.getBody().path("issues").get(0).path("path").asText())
        .contains("comments[0].text");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM content_item WHERE source_content_id=?",
                Integer.class,
                source))
        .isZero();
  }

  @Test
  void handlesMalformedEncodingUnknownFieldsAndOversizedFiles() throws Exception {
    assertThat(upload("{".getBytes(StandardCharsets.UTF_8), "input.json").getStatusCode().value())
        .isEqualTo(422);
    assertThat(upload(new byte[] {(byte) 0xC3, (byte) 0x28}, "input.json").getStatusCode().value())
        .isEqualTo(422);
    assertThat(upload("{}".getBytes(StandardCharsets.UTF_8), "input.csv").getStatusCode().value())
        .isEqualTo(422);
    var tree = json.valueToTree(document(source(), "原文"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("labels", "not allowed");
    assertThat(upload(json.writeValueAsBytes(tree), "input.json").getStatusCode().value())
        .isEqualTo(422);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("labels");
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("comments").get(0))
        .put("text", 123);
    assertThat(upload(json.writeValueAsBytes(tree), "input.json").getStatusCode().value())
        .isEqualTo(422);
    assertThat(upload(new byte[0], "input.json").getStatusCode().value()).isEqualTo(422);
    assertThat(upload(new byte[2 * 1024 * 1024 + 1], "large.json").getStatusCode().value())
        .isEqualTo(413);
  }

  @Test
  void missingResourcesReturn404() {
    assertThat(
            http.getForEntity("/api/v1/imports/" + UUID.randomUUID(), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            http.getForEntity("/api/v1/snapshots/" + UUID.randomUUID(), String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }
}
