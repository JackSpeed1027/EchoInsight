/* 文件职责：在独立项目范围用真实 MySQL 验证批次幂等、租约隔离、原子发布和失败恢复。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.project.ProjectStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 测试只追加唯一合成资料；平台工具被替换，不触发外部网络或付费模型。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false", "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.enabled=true",
          "echoinsight.collection.bilibili.worker-enabled=false",
      "echoinsight.projects.workspace=collection-it"
    })
class BilibiliCollectionIT {
  @Autowired TestRestTemplate http;
  @Autowired ProjectStore projects;
  @Autowired CollectionStore store;
  @Autowired CollectionWorker worker;
  @Autowired CollectionPublicationService publication;
  @Autowired BilibiliCliAdapter adapter;
  @Autowired BilibiliUrlParser urls;
  @Autowired JdbcTemplate db;
  @MockitoBean BilibiliCliCollector collector;

  /** 每次测试启动建立独立追加目录，防止与本机真实存档混用。 */
  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add(
        "echoinsight.collection.bilibili.storage-dir",
        () ->
            Path.of("runtime", "collection-it", UUID.randomUUID().toString())
                .toAbsolutePath()
                .toString());
  }

  /** 并发重发只产生一批，改变范围会409；URL白名单在持久化前检查。 */
  @Test
  void concurrentHttpRequestsAreIdempotentAndScopeIsValidated() throws Exception {
    String project = project();
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures =
          new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<JsonNode>>>();
      for (int index = 0; index < 6; index++)
        futures.add(pool.submit(() -> create(project, key, 50, url())));
      var ids = new java.util.HashSet<String>();
      for (var future : futures) {
        var response = future.get();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ids.add(response.getBody().path("batch").path("id").asText());
      }
      assertThat(ids).hasSize(1);
    }
    assertThat(create(project, key, 51, url()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            create(project, UUID.randomUUID().toString(), 50, "http://127.0.0.1/").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(store.list(project, 1, 10).total()).isEqualTo(1);
    assertThat(create(project, UUID.randomUUID().toString(), 201, url()).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  /** 采集成功与标准导入、项目挂载同时发布；相同输入复用原快照且不重写旧资料。 */
  @Test
  void workerPublishesSnapshotAndPreservesCoverageAndDeduplication() {
    String project = project();
    String aid = aid();
    doReturn(
            new BilibiliCliCollector.Payload(
                BilibiliCollectionTest.video(aid), BilibiliCollectionTest.comments(aid, 9)))
        .when(collector)
        .collect(any(), anyInt());
    var first = store.create(project, key(), urls.parse(url()), 50);
    worker.runOne(first.id());
    var saved = store.get(project, first.id());
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.collectedRootCount()).isEqualTo(1);
    assertThat(saved.collectedCommentCount()).isEqualTo(2);
    assertThat(saved.completeness()).isEqualTo("PARTIAL_FAILED");
    var member = projects.snapshot(project, saved.snapshotId());
    assertThat(member.collectionId()).isEqualTo(first.id());
    assertThat(member.sourceCompleteness()).isEqualTo("PARTIAL_FAILED");
    var second = store.create(project, key(), urls.parse(url()), 50);
    worker.runOne(second.id());
    assertThat(store.get(project, second.id()).snapshotId()).isEqualTo(saved.snapshotId());
    assertThat(projects.snapshots(project, 1, 10).total()).isEqualTo(1);
  }

  /** 旧执行者即使已完成外部采集，也不能在被接管后创建半份导入或挂载。 */
  @Test
  void staleLeaseRollsBackImportAndOnlyNewOwnerCanPublish() {
    String project = project();
    var batch = store.create(project, key(), urls.parse(url()), 50);
    var oldLease = store.claim(batch.id());
    db.update(
        "UPDATE source_collection_batch SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1"
            + " SECOND) WHERE id=?",
        batch.id());
    var newLease = store.claim(batch.id());
    String aid = aid();
    var converted =
        adapter.convert(
            urls.parse(url()),
            50,
            BilibiliCollectionTest.video(aid),
            BilibiliCollectionTest.comments(aid, 1));
    long before = db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class);
    assertThatThrownBy(() -> publication.publish(oldLease, converted, "stale-attempt"))
        .isInstanceOf(CollectionStore.LostLease.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM data_snapshot", Long.class))
        .isEqualTo(before);
    assertThat(projects.snapshots(project, 1, 10).total()).isZero();
    publication.publish(newLease, converted, "new-attempt");
    assertThat(store.get(project, batch.id()).status()).isEqualTo("SUCCESS");
    assertThat(store.get(project, batch.id()).recoveries()).isEqualTo(1);
  }

  /** 失败和显式重试沿用批次身份，成功后禁止再次重试。 */
  @Test
  void failedWorkerCanBeRetriedWithoutLosingBatchIdentity() {
    String project = project();
    var batch = store.create(project, key(), urls.parse(url()), 50);
    when(collector.collect(any(), anyInt())).thenThrow(new CollectionFailure("RATE_LIMITED"));
    worker.runOne(batch.id());
    assertThat(store.get(project, batch.id()).lastErrorCode()).isEqualTo("RATE_LIMITED");
    assertThat(
            http.postForEntity(base(project) + batch.id() + "/retry", null, JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    String aid = aid();
    doReturn(
            new BilibiliCliCollector.Payload(
                BilibiliCollectionTest.video(aid), BilibiliCollectionTest.comments(aid, 1)))
        .when(collector)
        .collect(any(), anyInt());
    worker.runOne(batch.id());
    assertThat(store.get(project, batch.id()).status()).isEqualTo("SUCCESS");
    assertThat(store.get(project, batch.id()).attempts()).isEqualTo(2);
    assertThat(
            http.postForEntity(base(project) + batch.id() + "/retry", null, JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  /** 其他工作空间的任务不可被扫描、认领或从当前项目读取，恢复耗尽必须转为明确失败。 */
  @Test
  void workspaceAndRecoveryBoundariesAreEnforced() {
    String project = project();
    var own = store.create(project, key(), urls.parse(url()), 50);
    var foreign = store.create(project, key(), urls.parse(url()), 50);
    db.update(
        "UPDATE source_collection_batch SET workspace_scope='other-collection-it' WHERE id=?",
        foreign.id());
    assertThat(store.candidates()).doesNotContain(foreign.id());
    assertThat(store.claim(foreign.id())).isNull();
    assertThat(http.getForEntity(base(project) + foreign.id(), JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    store.claim(own.id());
    db.update(
        "UPDATE source_collection_batch SET"
            + " recoveries=3,lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",
        own.id());
    store.expireExhausted();
    assertThat(store.get(project, own.id()).status()).isEqualTo("FAILED");
    assertThat(store.get(project, own.id()).lastErrorCode()).isEqualTo("RECOVERY_EXHAUSTED");
  }

  private ResponseEntity<JsonNode> create(String project, String key, int limit, String url) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.postForEntity(
        base(project) + "bilibili",
        new HttpEntity<>(Map.of("url", url, "rootLimit", limit), headers),
        JsonNode.class);
  }

  private String project() {
    return projects.create(key(), "合成采集验收", "验证边界，不评价模型").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private String aid() {
    return Long.toString(
        8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
  }

  private String url() {
    return "https://www.bilibili.com/video/BV1ntuF64EhF";
  }

  private String base(String project) {
    return "/api/v1/projects/" + project + "/collections/";
  }
}
