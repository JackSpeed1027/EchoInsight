/* 文件职责：使用本机MySQL验证发现租约、批量接受、原子性与显式冻结成功子集。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** 工作空间隔离、平台工具替换、真实模型关闭；不使用用户项目执行验收。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=creator-workflow-it",
      "echoinsight.collection.bilibili.enabled=true",
      "echoinsight.collection.bilibili.worker-enabled=false",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false"
    })
class CreatorWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired CreatorDiscoveryStore discoveries;
  @Autowired CreatorDiscovery adapter;
  @Autowired CreatorDiscoveryWorker worker;
  @Autowired CollectionGroupStore groups;
  @Autowired CollectionStore batches;
  @Autowired CollectionPublicationService publication;
  @Autowired BilibiliCliAdapter imports;
  @Autowired BilibiliUrlParser urls;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @MockitoBean BilibiliCliCollector tools;

  /** 并发重发一份发现任务，参数变化409；HTTP范围校验和项目资格一致。 */
  @Test
  void concurrentDiscoveryIsIdempotentAndHttpIsScoped() throws Exception {
    String project = project(), key = key();
    Set<String> ids = new HashSet<>();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<java.util.concurrent.Future<CreatorDiscoveryStore.Run>>();
      for (int n = 0; n < 6; n++)
        tasks.add(pool.submit(() -> discoveries.create(project, key, url(), "命运石之门", 10)));
      for (var task : tasks) ids.add(task.get().id());
    }
    assertThat(ids).hasSize(1);
    assertThatThrownBy(() -> discoveries.create(project, key, url(), "动漫", 10))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/"
                        + project()
                        + "/creator-discoveries/"
                        + ids.iterator().next(),
                    JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** 过期接管阻止旧token发布，最终耗尽收敛FAILED；原始字节与哈希仅由新租约绑定。 */
  @Test
  void staleTokenCannotPublishAndExhaustionIsTerminal() {
    String project = project();
    var run = discoveries.create(project, key(), url(), "", 10);
    var stale = discoveries.claim(run.id());
    expire(run.id());
    var current = discoveries.claim(run.id());
    byte[] raw = CreatorDiscoveryTest.rows("123");
    assertThat(discoveries.succeed(stale, raw, adapter.parse(raw, "123", 10))).isFalse();
    assertThat(discoveries.succeed(current, raw, adapter.parse(raw, "123", 10))).isTrue();
    assertThat(discoveries.get(project, run.id()).rawSha256()).hasSize(64);
    var exhausted = discoveries.create(project, key(), url(), "", 10);
    discoveries.claim(exhausted.id());
    db.update("UPDATE creator_discovery SET attempts=3 WHERE id=?", exhausted.id());
    expire(exhausted.id());
    discoveries.candidates();
    assertThat(discoveries.get(project, exhausted.id()).lastErrorCode())
        .isEqualTo("DISCOVERY_LEASE_EXHAUSTED");
  }

  /** 另一个服务器工作空间不扫描、不认领，防止保留的自动验收任务被主服务拿走。 */
  @Test
  void discoveryExecutionScopeCannotBeBypassed() {
    String project = project();
    var run = discoveries.create(project, key(), url(), "", 10);
    db.update("UPDATE business_project SET workspace_scope='other-creator-it' WHERE id=?", project);
    assertThat(discoveries.candidates()).doesNotContain(run.id());
    assertThat(discoveries.claim(run.id())).isNull();
    assertThatThrownBy(() -> discoveries.get(project, run.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 平台拒绝是失败而非空候选；工作者不会自行重试或发起评论采集。 */
  @Test
  void refusedDiscoveryIsNotSuccessfulEmptyResult() {
    String project = project();
    var run = discoveries.create(project, key(), url(), "动漫", 3);
    doThrow(new CollectionFailure("PLATFORM_RISK_CONTROL")).when(tools).discover("123", "动漫", 3);
    worker.runOne(run.id());
    var failed = discoveries.get(project, run.id());
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(failed.result()).isNull();
    assertThat(failed.lastErrorCode()).isEqualTo("PLATFORM_RISK_CONTROL");
    assertThat(batches.list(project, 1, 10).total()).isZero();
  }

  /** 原子创建两个采集，顺序变化复用；未结束不能冻结，失败结果必须显式排除。 */
  @Test
  void selectionFansOutAndFreezesOnlyExplicitSuccessfulSubset() {
    String project = project();
    var discovery = ready(project);
    String key = key();
    var bv = List.of("BV1ntuF64EhF", "BV1xx411c7mD");
    var group = groups.create(project, key, discovery.id(), bv, 20);
    assertThat(groups.create(project, key, discovery.id(), bv.reversed(), 20).id())
        .isEqualTo(group.id());
    assertThatThrownBy(
            () ->
                groups.freeze(
                    project,
                    group.id(),
                    key(),
                    "未结束",
                    List.of(group.items().getFirst().batch().id())))
        .isInstanceOf(ResponseStatusException.class);
    var good = group.items().getFirst().batch();
    var bad = group.items().get(1).batch();
    String aid =
        Long.toString(
            8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
    var converted =
        imports.convert(
            urls.parse(good.canonicalUrl()),
            20,
            new String(BilibiliCollectionTest.video(aid), StandardCharsets.UTF_8)
                .replace("BV1ntuF64EhF", good.requestedSourceId())
                .getBytes(StandardCharsets.UTF_8),
            BilibiliCollectionTest.comments(aid, 1));
    publication.publish(batches.claim(good.id()), converted, "synthetic-creator-it");
    // 同正文后续采集复用快照；冻结本组时必须仍绑定用户选择的原采集来源。
    var later = batches.create(project, key(), urls.parse(good.canonicalUrl()), 50);
    publication.publish(batches.claim(later.id()), converted, "synthetic-later-creator-it");
    assertThat(
            projects.snapshot(project, batches.get(project, good.id()).snapshotId()).collectionId())
        .isEqualTo(later.id());
    var failedLease = batches.claim(bad.id());
    batches.fail(bad.id(), failedLease.token(), "SYNTHETIC_FAILURE");
    assertThat(groups.get(project, group.id()).status()).isEqualTo("PARTIAL_FAILED");
    assertThatThrownBy(
            () -> groups.freeze(project, group.id(), key(), "错误范围", List.of(good.id(), bad.id())))
        .isInstanceOf(ResponseStatusException.class);
    String freezeKey = key();
    var manifest = groups.freeze(project, group.id(), freezeKey, "明确成功子集", List.of(good.id()));
    assertThat(manifest.items()).hasSize(1);
    assertThat(manifest.items().getFirst().collectionId()).isEqualTo(good.id());
    assertThat(
            groups
                .freeze(project, group.id(), freezeKey, "明确成功子集", List.of(good.id()))
                .summary()
                .id())
        .isEqualTo(manifest.summary().id());
    assertThatThrownBy(() -> groups.get(project(), group.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 非候选/重复作品以及不同参数重发均拒绝，不留下半组采集。 */
  @Test
  void invalidSelectionDoesNotCreateAnyChild() {
    String project = project();
    var discovery = ready(project);
    assertThatThrownBy(
            () ->
                groups.create(
                    project, key(), discovery.id(), List.of("BV1ntuF64EhF", "BVnotexists0"), 20))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                groups.create(
                    project, key(), discovery.id(), List.of("BV1ntuF64EhF", "BV1ntuF64EhF"), 20))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(batches.list(project, 1, 10).total()).isZero();
    String key = key();
    groups.create(project, key, discovery.id(), List.of("BV1ntuF64EhF"), 20);
    assertThatThrownBy(
            () -> groups.create(project, key, discovery.id(), List.of("BV1ntuF64EhF"), 21))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 合成两个同作者候选，直接走严格适配与发布，避免真实平台调用。 */
  private CreatorDiscoveryStore.Run ready(String project) {
    var run = discoveries.create(project, key(), url(), "", 10);
    String first = new String(CreatorDiscoveryTest.rows("123"), StandardCharsets.UTF_8);
    String row = first.substring(1, first.length() - 1);
    byte[] raw =
        ("["
                + row
                + ","
                + row.replace("BV1ntuF64EhF", "BV1xx411c7mD").replace("\"aid\":123", "\"aid\":456")
                + "]")
            .getBytes(StandardCharsets.UTF_8);
    discoveries.succeed(discoveries.claim(run.id()), raw, adapter.parse(raw, "123", 10));
    return discoveries.get(project, run.id());
  }

  private void expire(String id) {
    db.update(
        "UPDATE creator_discovery SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND)"
            + " WHERE id=?",
        id);
  }

  private String project() {
    return projects.create(key(), "UP主合成验收", "检查范围与恢复").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private String url() {
    return "https://space.bilibili.com/123";
  }
}
