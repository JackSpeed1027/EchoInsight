/* 文件职责：用真实 MySQL 与内存平台页验证断点续跑、范围幂等、原子发布和覆盖缺口，不访问外网或模型。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.project.ProjectStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 所有输入为合成评论；失败记录、页和快照只追加，不清理用户资料。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.enabled=true",
      "echoinsight.collection.bilibili.worker-enabled=false",
      "echoinsight.projects.workspace=collection-page-it"
    })
class BilibiliPagingIT {
  @Autowired CollectionStore batches;
  @Autowired CollectionPageStore pages;
  @Autowired CollectionWorker worker;
  @Autowired PagedBilibiliCollection paging;
  @Autowired ProjectStore projects;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @MockitoBean BilibiliPageClient client;
  private final BilibiliUrlParser urls = new BilibiliUrlParser();
  private static final String URL = "https://www.bilibili.com/video/BV1ntuF64EhF";

  /** 每次上下文建立唯一追加存档目录，避免触及真实采集档案。 */
  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add(
        "echoinsight.collection.bilibili.storage-dir",
        () ->
            Path.of("runtime/collection-page-it", UUID.randomUUID().toString())
                .toAbsolutePath()
                .toString());
  }

  /** 第一回复页成功、第二页失败后恢复：先前页不再出站，全部完成后只发布一次4条完整快照。 */
  @Test
  void resumesFromFailedReplyPageWithoutRefetchingSuccessfulPages() {
    var batch = create();
    String aid = metadata(4);
    when(client.roots(aid, "0")).thenReturn(page(false, "1", comment(10, 0, 0, 2, aid)));
    when(client.roots(aid, "1")).thenReturn(page(true, "", comment(20, 0, 0, 0, aid)));
    when(client.replies(aid, "10", "1")).thenReturn(page(false, "2", comment(11, 10, 10, 0, aid)));
    when(client.replies(aid, "10", "2"))
        .thenThrow(new CollectionFailure("NETWORK_FAILURE"))
        .thenReturn(page(true, "", comment(12, 11, 10, 0, aid)));
    for (int i = 0; i < 5; i++) worker.runOne(batch.id());
    assertThat(batches.get(batch.projectId(), batch.id()).status()).isEqualTo("FAILED");
    assertThat(pages.read(batch.id())).hasSize(4);
    assertThat(projects.snapshots(batch.projectId(), 1, 10).total()).isZero();
    batches.retry(batch.projectId(), batch.id());
    worker.runOne(batch.id());
    worker.runOne(batch.id());
    var saved = batches.get(batch.projectId(), batch.id());
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.completeness()).isEqualTo("VISIBLE_EXHAUSTED");
    assertThat(saved.collectedCommentCount()).isEqualTo(4);
    assertThat(projects.snapshots(batch.projectId(), 1, 10).total()).isEqualTo(1);
    assertThat(paging.progress(saved).storedComments()).isEqualTo(4);
    verify(client, times(1)).metadata(any());
    verify(client, times(1)).roots(aid, "0");
    verify(client, times(1)).roots(aid, "1");
    verify(client, times(1)).replies(aid, "10", "1");
    verify(client, times(2)).replies(aid, "10", "2");
    String body = pages.read(batch.id()).get(4).page().records().toString();
    assertThat(body).contains("\"parent\":11");
  }

  /** 跨页重复置顶只计一次，但任何冲突正文不允许覆盖历史页。 */
  @Test
  void deduplicatesRepeatedPinnedRootAndRejectsConflictingBody() {
    var batch = create();
    String aid = metadata(2);
    ObjectNode pinned = comment(10, 0, 0, 0, aid);
    when(client.roots(aid, "0")).thenReturn(page(false, "1", pinned));
    when(client.roots(aid, "1")).thenReturn(page(true, "", pinned, comment(20, 0, 0, 0, aid)));
    for (int i = 0; i < 4; i++) worker.runOne(batch.id());
    assertThat(batches.get(batch.projectId(), batch.id()).collectedCommentCount()).isEqualTo(2);
    var other = create();
    worker.runOne(other.id());
    worker.runOne(other.id());
    ObjectNode changed = pinned.deepCopy();
    changed.put("content", "采集中发生的修订");
    when(client.roots(aid, "1")).thenReturn(page(true, "", changed));
    worker.runOne(other.id());
    assertThat(batches.get(other.projectId(), other.id()).lastErrorCode())
        .isEqualTo("COLLECTION_SOURCE_CHANGED");
    assertThat(pages.read(other.id())).hasSize(2);
  }

  /** 即使平台返回终止，缺父或声明数量不齐也必须留在PARTIAL_FAILED，不能宣布全部评论。 */
  @Test
  void keepsMissingParentsAndDeclaredCountMismatchExplicit() {
    var batch = create();
    String aid = metadata(5);
    when(client.roots(aid, "0")).thenReturn(page(true, "", comment(10, 0, 0, 2, aid)));
    when(client.replies(aid, "10", "1")).thenReturn(page(true, "", comment(11, 999, 10, 0, aid)));
    for (int i = 0; i < 4; i++) worker.runOne(batch.id());
    var saved = batches.get(batch.projectId(), batch.id());
    assertThat(saved.status()).isEqualTo("SUCCESS");
    assertThat(saved.completeness()).isEqualTo("PARTIAL_FAILED");
    assertThat(saved.collectedCommentCount()).isEqualTo(2);
    assertThat(saved.sourceReportedComments()).isEqualTo(5);
    assertThat(saved.partialThreadCount()).isEqualTo(1);
  }

  /** 根游标回环在新页提交前被拒绝；显式重试不会删除或重新读取第一成功页。 */
  @Test
  void refusesCursorCyclesBeforeCheckpointCommit() {
    var batch = create();
    String aid = metadata(2);
    when(client.roots(aid, "0")).thenReturn(page(false, "1", comment(10, 0, 0, 0, aid)));
    when(client.roots(aid, "1")).thenReturn(page(false, "0", comment(20, 0, 0, 0, aid)));
    for (int i = 0; i < 3; i++) worker.runOne(batch.id());
    assertThat(batches.get(batch.projectId(), batch.id()).lastErrorCode())
        .isEqualTo("INVALID_PAGE_CURSOR");
    assertThat(pages.read(batch.id())).hasSize(2);
  }

  /** 已过期的执行者不能追加检查点；新租约追加与PENDING释放在同一事务。 */
  @Test
  void staleLeaseCannotCommitOrReleaseNewOwner() {
    var batch = create();
    var old = batches.claim(batch.id());
    db.update(
        "UPDATE source_collection_batch SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1"
            + " SECOND) WHERE id=?",
        batch.id());
    var current = batches.claim(batch.id());
    var value = page(true, "", comment(10, 0, 0, 0, "123"));
    assertThatThrownBy(() -> pages.append(old, "META", value))
        .isInstanceOf(CollectionStore.LostLease.class);
    assertThat(pages.read(batch.id())).isEmpty();
    pages.append(current, "META", value);
    assertThat(batches.get(batch.projectId(), batch.id()).status()).isEqualTo("PENDING");
    assertThat(pages.read(batch.id())).hasSize(1);
  }

  /** collectAll与限量参数互斥、不同范围同键409，其他项目无法读取进度。 */
  @Test
  void freezesHttpModeAndEnforcesProjectIsolation() {
    String project = project(), key = UUID.randomUUID().toString();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    String base = "/api/v1/projects/" + project + "/collections/";
    var first =
        http.postForEntity(
            base + "bilibili",
            new HttpEntity<>(Map.of("url", URL, "collectAll", true), headers),
            JsonNode.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String id = first.getBody().path("batch").path("id").asText();
    assertThat(first.getBody().path("progress").path("completedPages").asInt()).isZero();
    var replay =
        http.postForEntity(
            base + "bilibili",
            new HttpEntity<>(Map.of("url", URL, "collectAll", true), headers),
            JsonNode.class);
    assertThat(replay.getBody().path("batch").path("id").asText()).isEqualTo(id);
    assertThat(
            http.postForEntity(
                    base + "bilibili",
                    new HttpEntity<>(Map.of("url", URL, "rootLimit", 50), headers),
                    JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            http.postForEntity(
                    base + "bilibili",
                    new HttpEntity<>(
                        Map.of("url", URL, "collectAll", true, "rootLimit", 50), headers),
                    JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            http.getForEntity(
                    "/api/v1/projects/" + project() + "/collections/" + id, JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** 超过5000的坏页不能成为检查点，既有元数据保留且没有半份项目资料。 */
  @Test
  void capacityFailureRetainsEarlierPagesWithoutPublishingPartialSnapshot() {
    var batch = create();
    String aid = metadata(5001);
    ArrayNode records = json.createArrayNode();
    for (int i = 1; i <= 5001; i++) records.add(comment(i, 0, 0, 0, aid));
    when(client.roots(aid, "0"))
        .thenReturn(new BilibiliPageClient.Page(records, "", true, 0, "a".repeat(64)));
    worker.runOne(batch.id());
    worker.runOne(batch.id());
    assertThat(batches.get(batch.projectId(), batch.id()).lastErrorCode())
        .isEqualTo("COMMENT_COUNT_LIMIT");
    assertThat(pages.read(batch.id())).hasSize(1);
    assertThat(projects.snapshots(batch.projectId(), 1, 10).total()).isZero();
  }

  /** 累计页存储超界在写入前拒绝，事务回滚且不释放当前执行者的租约。 */
  @Test
  void checkpointByteLimitRollsBackWithoutDiscardingExistingPages() {
    var batch = create();
    var lease = batches.claim(batch.id());
    ArrayNode records =
        json.createArrayNode().add("a".repeat(CollectionPageStore.MAX_CHECKPOINT_BYTES));
    var large = new BilibiliPageClient.Page(records, "", true, 0, "a".repeat(64));
    assertThatThrownBy(() -> pages.append(lease, "META", large))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("COLLECTION_STORAGE_LIMIT");
    assertThat(pages.read(batch.id())).isEmpty();
    assertThat(batches.get(batch.projectId(), batch.id()).status()).isEqualTo("RUNNING");
  }

  /** 作品总数恰好匹配也不能忽略楼中楼独立声明的短缺。 */
  @Test
  void subthreadDeclaredCountIsReconciledIndependentlyFromVideoTotal() {
    var batch = create();
    String aid = metadata(2);
    when(client.roots(aid, "0")).thenReturn(page(true, "", comment(10, 0, 0, 1, aid)));
    var sub = page(true, "", comment(11, 10, 10, 0, aid));
    when(client.replies(aid, "10", "1"))
        .thenReturn(new BilibiliPageClient.Page(sub.records(), "", true, 4, sub.sourceHash()));
    for (int i = 0; i < 4; i++) worker.runOne(batch.id());
    var saved = batches.get(batch.projectId(), batch.id());
    assertThat(saved.collectedCommentCount()).isEqualTo(saved.sourceReportedComments());
    assertThat(saved.completeness()).isEqualTo("PARTIAL_FAILED");
    assertThat(saved.partialThreadCount()).isEqualTo(1);
  }

  /** 大量重复页仍受页数边界，检查点保留但不会无限访问平台或发布假完整。 */
  @Test
  void pageCapacityStopsBeforeAnotherPlatformRequest() throws Exception {
    var batch = create();
    String aid = metadata(2);
    worker.runOne(batch.id());
    String insert =
        "INSERT INTO source_collection_page(batch_id,ordinal_no,page_key,page_json)"
            + " VALUES(?,?,?,?)";
    java.util.List<Object[]> rows = new java.util.ArrayList<>();
    for (int i = 0; i < PagedBilibiliCollection.MAX_PAGES - 1; i++)
      rows.add(
          new Object[] {
            batch.id(),
            i + 1,
            "ROOT:" + i,
            json.writeValueAsString(page(false, Integer.toString(i + 1), comment(10, 0, 0, 0, aid)))
          });
    db.batchUpdate(insert, rows);
    worker.runOne(batch.id());
    assertThat(batches.get(batch.projectId(), batch.id()).lastErrorCode())
        .isEqualTo("COLLECTION_PAGE_LIMIT");
    assertThat(pages.read(batch.id())).hasSize(PagedBilibiliCollection.MAX_PAGES);
    verify(client, never()).roots(anyString(), anyString());
  }

  /** 独立项目避免与本机真实作品关联。 */
  private String project() {
    return projects.create(UUID.randomUUID().toString(), "合成分页恢复验收", "不代表真实平台或语义成绩").project().id();
  }

  /** 新范围通过原批次事务冻结。 */
  private CollectionStore.Batch create() {
    return batches.createWithMode(
        project(), UUID.randomUUID().toString(), urls.parse(URL), 50, true);
  }

  /** 合成作品身份在测试间唯一，避免复用其他快照的内容修订。 */
  private String metadata(int declared) {
    String aid =
        Long.toString(
            8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
    ObjectNode video =
        json.createObjectNode()
            .put("aid", Long.parseLong(aid))
            .put("bvid", "BV1ntuF64EhF")
            .put("owner_mid", 1)
            .put("title", "合成分页材料")
            .put("pubdate", 1785810000)
            .put("reply_count", declared);
    when(client.metadata(any())).thenReturn(page(true, "", video));
    return aid;
  }

  /** 最小白名单评论，允许回复引用同楼另一条回复作为真实parent。 */
  private ObjectNode comment(long id, long parent, long root, int count, String aid) {
    return json.createObjectNode()
        .put("rpid", id)
        .put("oid", Long.parseLong(aid))
        .put("type", 1)
        .put("parent", parent)
        .put("root", root)
        .put("content", "合成观点" + id)
        .put("ctime", 1785814540)
        .put("reply_count", count);
  }

  /** 合成页明确携带终止标记，不通过空数组猜测结束。 */
  private BilibiliPageClient.Page page(boolean end, String next, JsonNode... nodes) {
    ArrayNode records = json.createArrayNode();
    for (JsonNode node : nodes) records.add(node);
    return new BilibiliPageClient.Page(records, next, end, 0, "a".repeat(64));
  }
}
