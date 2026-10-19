/* 文件职责：用隔离MySQL和内存平台验证任务控制、费用事实、迟到执行者及分页恢复；不访问真实模型/平台。 */
package io.echoinsight.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.collection.*;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.material.InputManifestStore;
import io.echoinsight.material.ManifestAnalysisStore;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelLedger;
import io.echoinsight.project.ProjectImportService;
import io.echoinsight.project.ProjectStore;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 只追加合成项目，不删除旧数据；新接口的读写资格同时验证项目和服务器执行范围。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=task-control-it",
      "echoinsight.worker.execution-scope=task-control-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=true",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class TaskControlWorkflowIT {
  @Autowired InputManifestStore manifests;
  @Autowired ManifestAnalysisStore runs;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisRequestStore requests;
  @Autowired JobStore jobs;
  @Autowired AnalysisWorker worker;
  @Autowired TaskControlService controls;
  @Autowired ProjectTaskQuery query;
  @Autowired ModelLedger ledger;
  @Autowired CollectionStore collections;
  @Autowired CollectionPageStore pages;
  @Autowired CollectionWorker collector;
  @Autowired PagedBilibiliCollection paging;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @MockitoBean BilibiliPageClient platform;

  /** 每次运行创建独立追加存档目录，不写用户真实采集档案。 */
  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add(
        "echoinsight.collection.bilibili.storage-dir",
        () ->
            Path.of("runtime/task-control-it", UUID.randomUUID().toString())
                .toAbsolutePath()
                .toString());
  }

  /** 排队任务取消可重发，未领取不产生尝试/模型调用；显式继续后原Worker完成原请求。 */
  @Test
  void queuedCancellationIsIdempotentAndResumesOriginalRequest() {
    String p = project(), s = snapshot(p);
    var request = requests.create(p, key(), s, "总结讨论").request();
    String plan = ModelContract.encode(jobs.plan(request.jobId()));
    assertThat(controls.cancel(p, "ANALYSIS", request.jobId()).task().status())
        .isEqualTo("CANCELLED");
    controls.cancel(p, "ANALYSIS", request.jobId());
    assertThat(query.detail(p, "ANALYSIS", request.jobId()).eventCount()).isEqualTo(1);
    assertThat(jobs.claim(request.jobId())).isNull();
    assertThat(requests.get(p, request.id()).status()).isEqualTo("CANCELLED");
    assertThat(jobs.attempts(request.jobId())).isEmpty();
    controls.resume(p, "ANALYSIS", request.jobId());
    worker.runOne(request.jobId());
    assertThat(requests.get(p, request.id()).status()).isEqualTo("SUCCESS");
    assertThat(ModelContract.encode(jobs.plan(request.jobId()))).isEqualTo(plan);
    assertThat(jobs.get(request.jobId()).snapshotId()).isEqualTo(s);
    assertThat(query.detail(p, "ANALYSIS", request.jobId()).eventCount()).isEqualTo(2);
  }

  /** RESERVED尚未出站才释放；旧token既不能发送也不能续租或发布。 */
  @Test
  void cancellationReleasesOnlyUnsentReservationsAndRevokesOldOwner() {
    Fixture f = feedback();
    String token = jobs.claim(f.job());
    var prepared = ledger.prepare(f.job(), token, 0, "a".repeat(64), 100, jobs.plan(f.job()));
    var result = controls.cancel(f.project(), "ANALYSIS", f.job());
    assertThat(result.model().budget().reservedUnits()).isZero();
    assertThat(state(prepared.callId())).isEqualTo("RELEASED");
    assertThatThrownBy(() -> ledger.sent(f.job(), token, prepared.callId()))
        .isInstanceOf(JobStore.LostLease.class);
    assertThatThrownBy(() -> jobs.renew(f.job(), token)).isInstanceOf(JobStore.LostLease.class);
    assertThatThrownBy(() -> jobs.fail(f.job(), token, "PIPELINE_ERROR"))
        .isInstanceOf(JobStore.LostLease.class);
    assertThat(jobs.attempts(f.job()).getFirst().status()).isEqualTo("CANCELLED");
    assertThat(result.task().canResume()).isTrue();
  }

  /** 已发送不能假定免费；取消保留未知和100预留，旧结果不能结算或写回成功。 */
  @Test
  void sentCallRemainsUnknownAndBlocksResumeAndBothRetryEntrypoints() {
    Fixture f = feedback();
    String token = jobs.claim(f.job());
    var prepared = ledger.prepare(f.job(), token, 0, "a".repeat(64), 100, jobs.plan(f.job()));
    ledger.sent(f.job(), token, prepared.callId());
    var result = controls.cancel(f.project(), "ANALYSIS", f.job());
    assertThat(state(prepared.callId())).isEqualTo("OUTCOME_UNKNOWN");
    assertThat(result.model().budget().reservedUnits()).isEqualTo(100);
    assertThat(result.model().budget().settledUnits()).isZero();
    assertThat(result.task().feeUncertain()).isTrue();
    assertThat(result.task().canResume()).isFalse();
    assertThatThrownBy(() -> controls.resume(f.project(), "ANALYSIS", f.job()))
        .hasMessageContaining("未决");
    assertThatThrownBy(() -> controls.retry(f.project(), "ANALYSIS", f.job()))
        .hasMessageContaining("未决");
    assertThatThrownBy(() -> jobs.retry(f.job())).hasMessageContaining("尚未确认");
    assertThatThrownBy(
            () -> ledger.completeJson(f.job(), token, 0, prepared.callId(), "{}", 1, 1, "late", 2))
        .isInstanceOf(JobStore.LostLease.class);
    assertThat(ledger.abort(prepared.callId(), "MODEL_TIMEOUT")).isEqualTo("OUTCOME_UNKNOWN");
    assertThat(query.detail(f.project(), "ANALYSIS", f.job()).eventCount()).isEqualTo(1);
  }

  /** 已成功批次复用，未发送失败片重新准备；原累计结算和预算保持，不为继续新开额度。 */
  @Test
  void resumedTaskReusesSuccessfulModelCheckpointAndOriginalBudget() {
    Fixture f = feedback();
    var plan = jobs.plan(f.job());
    String token = jobs.claim(f.job());
    var first = ledger.prepare(f.job(), token, 0, "a".repeat(64), 100, plan);
    ledger.sent(f.job(), token, first.callId());
    ledger.completeJson(f.job(), token, 0, first.callId(), "{\"fixed\":true}", 2, 3, "fixture", 5);
    var second = ledger.prepare(f.job(), token, 1, "b".repeat(64), 100, plan);
    controls.cancel(f.project(), "ANALYSIS", f.job());
    controls.resume(f.project(), "ANALYSIS", f.job());
    String resumed = jobs.claim(f.job());
    assertThat(resumed).isNotEqualTo(token);
    assertThat(ledger.prepare(f.job(), resumed, 0, "a".repeat(64), 100, plan).cached())
        .isEqualTo("{\"fixed\":true}");
    assertThat(state(second.callId())).isEqualTo("RELEASED");
    assertThat(ledger.view(f.job(), 1, 20).budget().settledUnits()).isEqualTo(5);
    assertThat(ledger.view(f.job(), 1, 20).budget().limitUnits()).isEqualTo(plan.budgetLimit());
    assertThat(ledger.prepare(f.job(), resumed, 1, "b".repeat(64), 100, plan).callId()).isNotNull();
    controls.cancel(f.project(), "ANALYSIS", f.job());
  }

  /** 已保存步骤不清空，继续仅恢复未完成步骤；迟到发布与原取消互斥。 */
  @Test
  void savedStepSurvivesAndLatePublicationIsRejected() {
    Fixture f = feedback();
    String token = jobs.claim(f.job());
    jobs.startStep(f.job(), token, "CLASSIFY");
    jobs.saveClassification(f.job(), token, "[]");
    jobs.startStep(f.job(), token, "REPORT");
    controls.cancel(f.project(), "ANALYSIS", f.job());
    assertThatThrownBy(() -> jobs.publish(f.job(), token, key(), f.snapshot(), "{}"))
        .isInstanceOf(JobStore.LostLease.class);
    assertThat(jobs.reportForJob(f.job())).isNull();
    controls.resume(f.project(), "ANALYSIS", f.job());
    assertThat(jobs.steps(f.job()).getFirst().resultJson()).isEqualTo("[]");
    assertThat(jobs.steps(f.job()).getFirst().status()).isEqualTo("SUCCEEDED");
    assertThat(jobs.steps(f.job()).getLast().status()).isEqualTo("READY");
  }

  /** 发布先提交则取消409，成功报告和原JSON不能被控制操作覆盖。 */
  @Test
  void completedTaskCannotBeCancelledResumedOrRetried() {
    String p = project(), s = snapshot(p);
    var request = requests.create(p, key(), s, "总结讨论").request();
    worker.runOne(request.jobId());
    String original = requests.artifact(p, request.id()).toString();
    assertThatThrownBy(() -> controls.cancel(p, "ANALYSIS", request.jobId()))
        .hasMessageContaining("只有等待");
    assertThatThrownBy(() -> controls.resume(p, "ANALYSIS", request.jobId()))
        .hasMessageContaining("只有已取消");
    assertThatThrownBy(() -> controls.retry(p, "ANALYSIS", request.jobId()))
        .hasMessageContaining("只有没有");
    assertThat(requests.artifact(p, request.id()).toString()).isEqualTo(original);
    assertThat(query.detail(p, "ANALYSIS", request.jobId()).eventCount()).isZero();
  }

  /** 失败重试仍委托原任务引擎，控制历史记RETRY，不能把失败冒充取消。 */
  @Test
  void failedTaskUsesOriginalRetry() {
    Fixture f = feedback();
    String token = jobs.claim(f.job());
    jobs.fail(f.job(), token, "PIPELINE_ERROR");
    assertThatThrownBy(() -> controls.cancel(f.project(), "ANALYSIS", f.job()))
        .hasMessageContaining("只有等待");
    var result = controls.retry(f.project(), "ANALYSIS", f.job());
    assertThat(result.task().status()).isEqualTo("PENDING");
    assertThat(result.recentEvents().getFirst().action()).isEqualTo("RETRY");
    assertThat(jobs.attempts(f.job()).getFirst().errorCode()).isEqualTo("PIPELINE_ERROR");
  }

  /** 非本项目、非执行范围或伪造类型无法读取或操作；没有提前释放任何预约。 */
  @Test
  void projectAndExecutionScopeIsolationIsEnforced() {
    Fixture f = feedback();
    String another = project(), token = jobs.claim(f.job());
    var call = ledger.prepare(f.job(), token, 0, "a".repeat(64), 100, jobs.plan(f.job()));
    assertThatThrownBy(() -> controls.cancel(another, "ANALYSIS", f.job()))
        .hasMessageContaining("不存在");
    assertThat(state(call.callId())).isEqualTo("RESERVED");
    db.update("UPDATE analysis_job SET execution_scope='foreign-task-it' WHERE id=?", f.job());
    assertThatThrownBy(() -> controls.cancel(f.project(), "ANALYSIS", f.job()))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> query.detail(f.project(), "ANALYSIS", f.job()))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> query.detail(f.project(), "analysis_job", f.job()))
        .hasMessageContaining("任务类型");
    assertThat(query.list(f.project(), "ALL", "ALL", 1, 20).total()).isZero();
  }

  /** 同快照挂到另一个项目也不能带走原讨论请求；旧反馈则按明确成员资格展示。 */
  @Test
  void sharedSnapshotDoesNotExposeForeignDiscussion() {
    String p = project(), s = snapshot(p), another = project();
    projects.attach(another, s);
    var request = requests.create(p, key(), s, "总结讨论").request();
    assertThat(query.list(another, "ALL", "ALL", 1, 20).total()).isZero();
    assertThatThrownBy(() -> controls.cancel(another, "ANALYSIS", request.jobId()))
        .hasMessageContaining("不存在");
  }

  /** 两个并发取消串行化为一个状态转换、一个事件，不重复释放同一100预约。 */
  @Test
  void concurrentCancellationDoesNotDoubleReleaseOrDuplicateEvents() throws Exception {
    Fixture f = feedback();
    String token = jobs.claim(f.job());
    ledger.prepare(f.job(), token, 0, "a".repeat(64), 100, jobs.plan(f.job()));
    CountDownLatch start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return controls.cancel(f.project(), "ANALYSIS", f.job());
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return controls.cancel(f.project(), "ANALYSIS", f.job());
              });
      start.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS).task().status()).isEqualTo("CANCELLED");
      assertThat(b.get(10, TimeUnit.SECONDS).task().status()).isEqualTo("CANCELLED");
    }
    var result = query.detail(f.project(), "ANALYSIS", f.job());
    assertThat(result.eventCount()).isEqualTo(1);
    assertThat(result.model().budget().reservedUnits()).isZero();
  }

  /** 两种原任务同页显示，筛选不改变全项目计数；详情查询不初始化模型账本。 */
  @Test
  void taskCenterFiltersPagesAndReadsWithoutSideEffects() {
    Fixture f = feedback();
    collection(f.project());
    // UNION同时含ASCII平台URL与中文JSON标题，必须保留中文原文，不能被MySQL5.7降为ASCII。
    assertThat(query.item(f.project(), "ANALYSIS", f.job()).title()).isEqualTo("任务控制合成资料");
    var all = query.list(f.project(), "ALL", "ALL", 1, 1);
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.totalPages()).isEqualTo(2);
    assertThat(query.list(f.project(), "ALL", "ALL", 2, 1).items().getFirst().id())
        .isNotEqualTo(all.items().getFirst().id());
    controls.cancel(f.project(), "ANALYSIS", f.job());
    var active = query.list(f.project(), "ALL", "ACTIVE", 1, 20);
    assertThat(active.total()).isEqualTo(1);
    assertThat(active.counts().cancelled()).isEqualTo(1);
    assertThat(active.counts().total()).isEqualTo(2);
    assertThat(query.detail(f.project(), "ANALYSIS", f.job()).steps())
        .allSatisfy(step -> assertThat(step.resultJson()).isNull());
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_budget WHERE job_id=?", Integer.class, f.job()))
        .isZero();
    assertThatThrownBy(() -> query.list(f.project(), "ALL", "ALL", 0, 20))
        .hasMessageContaining("页码");
    assertThatThrownBy(() -> query.list(f.project(), "ALL", "BOGUS", 1, 20))
        .hasMessageContaining("筛选");
  }

  /** 详情仅保留最近20条控制记录，计数仍完整；重复读取不会追加事件。 */
  @Test
  void eventHistoryIsBoundedWithoutLosingAuditRows() {
    Fixture f = feedback();
    for (int i = 0; i < 11; i++) {
      controls.cancel(f.project(), "ANALYSIS", f.job());
      controls.resume(f.project(), "ANALYSIS", f.job());
    }
    var detail = query.detail(f.project(), "ANALYSIS", f.job());
    assertThat(detail.eventCount()).isEqualTo(22);
    assertThat(detail.recentEvents()).hasSize(20);
    assertThat(query.detail(f.project(), "ANALYSIS", f.job()).eventCount()).isEqualTo(22);
  }

  /** 已领取的分页任务取消后，旧step不能发平台请求，旧append/fail也不能把状态改回去。 */
  @Test
  void cancelledCollectorCannotRequestAppendOrFailWithOldLease() {
    String p = project();
    var batch = collection(p);
    var lease = collections.claim(batch.id());
    controls.cancel(p, "COLLECTION", batch.id());
    assertThatThrownBy(
            () -> paging.step(lease, new BilibiliUrlParser().parse(batch.canonicalUrl())))
        .isInstanceOf(CollectionStore.LostLease.class);
    assertThatThrownBy(() -> pages.append(lease, "META", page(json.createObjectNode())))
        .isInstanceOf(CollectionStore.LostLease.class);
    collections.fail(batch.id(), lease.token(), "NETWORK_FAILURE");
    assertThat(collections.get(p, batch.id()).status()).isEqualTo("CANCELLED");
    verifyNoInteractions(platform);
  }

  /** 取消保留META页；继续仍为原批次、只请求根页，成功后一次发布，同键重发仍返回该批次。 */
  @Test
  void collectionResumesFromCommittedPageAndDoesNotRefetchMetadata() {
    String p = project();
    var batch = collection(p);
    var lease = collections.claim(batch.id());
    String aid =
        Long.toString(
            9_000_000_000L
                + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
    var video =
        json.createObjectNode()
            .put("aid", Long.parseLong(aid))
            .put("bvid", "BV1ntuF64EhF")
            .put("owner_mid", 1)
            .put("title", "任务恢复合成资料")
            .put("pubdate", 1785810000)
            .put("reply_count", 1);
    pages.append(lease, "META", page(video));
    controls.cancel(p, "COLLECTION", batch.id());
    assertThat(query.detail(p, "COLLECTION", batch.id()).completedPages()).isEqualTo(1);
    var root =
        json.createObjectNode()
            .put("rpid", 10)
            .put("oid", Long.parseLong(aid))
            .put("type", 1)
            .put("parent", 0)
            .put("root", 0)
            .put("content", "合成观点")
            .put("ctime", 1785814540)
            .put("reply_count", 0);
    when(platform.roots(aid, "0")).thenReturn(page(root));
    controls.resume(p, "COLLECTION", batch.id());
    collector.runOne(batch.id());
    collector.runOne(batch.id());
    assertThat(collections.get(p, batch.id()).status()).isEqualTo("SUCCESS");
    assertThat(pages.read(batch.id())).hasSize(2);
    assertThat(projects.snapshots(p, 1, 20).total()).isEqualTo(1);
    verify(platform, never()).metadata(any());
    verify(platform, times(1)).roots(aid, "0");
    assertThatThrownBy(() -> controls.cancel(p, "COLLECTION", batch.id()))
        .hasMessageContaining("只有等待");
  }

  /** HTTP参数/越项目/状态冲突可读，取消及继续返回持久状态，不暴露报告正文或存档路径。 */
  @Test
  void httpContractAndInvalidFiltersAreEnforced() {
    Fixture f = feedback();
    String path = "/api/v1/projects/" + f.project() + "/tasks/ANALYSIS/" + f.job();
    assertThat(
            http.postForEntity(path + "/cancel", null, JsonNode.class)
                .getBody()
                .path("task")
                .path("status")
                .asText())
        .isEqualTo("CANCELLED");
    assertThat(
            http.postForEntity(path + "/resume", null, JsonNode.class)
                .getBody()
                .path("task")
                .path("status")
                .asText())
        .isEqualTo("PENDING");
    assertThat(http.postForEntity(path + "/resume", null, JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            http.getForEntity("/api/v1/projects/" + f.project() + "/tasks?size=51", JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            http.getForEntity(path.replace(f.project(), project()), JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(http.getForObject(path, String.class))
        .doesNotContain("result_json", "storage-dir", "api-key");
  }

  /** 全部取消是终态，部分取消也必须结束汇总；恢复子任务后汇总重新派生为活动。 */
  @Test
  void aggregateDoesNotTreatCancelledChildrenAsRunning() {
    String p = project();
    var manifest = manifests.create(p, key(), "两个合成作品", List.of(snapshot(p), snapshot(p)));
    var run = runs.create(p, key(), manifest.summary().id(), "总结讨论");
    controls.cancel(p, "ANALYSIS", run.items().getFirst().jobId());
    controls.cancel(p, "ANALYSIS", run.items().getLast().jobId());
    var cancelled = runs.get(p, run.id());
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelled()).isEqualTo(2);
    assertThat(cancelled.pending()).isZero();
    controls.resume(p, "ANALYSIS", run.items().getFirst().jobId());
    worker.runOne(run.items().getFirst().jobId());
    var partial = runs.get(p, run.id());
    assertThat(partial.status()).isEqualTo("PARTIAL_FAILED");
    assertThat(partial.succeeded() + partial.failed() + partial.pending() + partial.cancelled())
        .isEqualTo(2);
    controls.resume(p, "ANALYSIS", run.items().getLast().jobId());
    assertThat(runs.get(p, run.id()).pending()).isEqualTo(1);
  }

  /** 取消发生在平台正在返回根页时：成功META保留，迟到根页不提交，不发布半份快照。 */
  @Test
  void inFlightCollectionCannotCommitAfterCancellation() {
    String p = project();
    var batch = collection(p);
    var lease = collections.claim(batch.id());
    String aid = "9000000011";
    var video =
        json.createObjectNode()
            .put("aid", Long.parseLong(aid))
            .put("bvid", "BV1ntuF64EhF")
            .put("owner_mid", 1)
            .put("title", "取消竞态合成资料")
            .put("pubdate", 1785810000)
            .put("reply_count", 1);
    pages.append(lease, "META", page(video));
    var root =
        json.createObjectNode()
            .put("rpid", 10)
            .put("oid", Long.parseLong(aid))
            .put("type", 1)
            .put("parent", 0)
            .put("root", 0)
            .put("content", "合成观点")
            .put("ctime", 1785814540)
            .put("reply_count", 0);
    when(platform.roots(aid, "0"))
        .thenAnswer(
            invocation -> {
              controls.cancel(p, "COLLECTION", batch.id());
              return page(root);
            });
    collector.runOne(batch.id());
    assertThat(collections.get(p, batch.id()).status()).isEqualTo("CANCELLED");
    assertThat(pages.read(batch.id())).hasSize(1);
    assertThat(projects.snapshots(p, 1, 20).total()).isZero();
    assertThat(collections.claim(batch.id())).isNull();
  }

  /** 所有测试fixture使用全新的来源和不可变快照。 */
  private record Fixture(String project, String snapshot, String job) {}

  private Fixture feedback() {
    String p = project(), s = snapshot(p);
    var plan =
        new ModelContract.Plan(
            "MOCK",
            "synthetic-fixture-v1",
            "no-prompt-v1",
            "sentiment-v1",
            50,
            3000,
            512,
            "SIMULATED_UNITS",
            "fixture-units",
            1,
            1,
            10000);
    return new Fixture(p, s, jobs.create(s, key(), plan).id());
  }

  /** 资料仅为合成根评论，不含用户真实作品内容。 */
  private String snapshot(String p) {
    var time = OffsetDateTime.parse("2026-10-19T00:00:00Z");
    return imports
        .ingest(
            p,
            new ImportDocument(
                "1.0",
                new ImportDocument.Content("work", "SYNTHETIC", key(), "fixture", "任务控制合成资料", time),
                List.of(new ImportDocument.Comment("c1", "s1", null, "合成讨论", time)),
                List.of()))
        .snapshotId();
  }

  /** 采集仅创建批次，所有平台输出由内存替身提供。 */
  private CollectionStore.Batch collection(String p) {
    return collections.createWithMode(
        p,
        key(),
        new BilibiliUrlParser().parse("https://www.bilibili.com/video/BV1ntuF64EhF"),
        50,
        true);
  }

  /** 页声明明确结束，哈希仅代表合成输出身份。 */
  private BilibiliPageClient.Page page(JsonNode node) {
    return new BilibiliPageClient.Page(
        json.createArrayNode().add(node), "", true, 0, "a".repeat(64));
  }

  /** 原调用状态只读核验，不通过模拟数据库改账单规避取消语义。 */
  private String state(String call) {
    return db.queryForObject("SELECT state FROM model_call WHERE id=?", String.class, call);
  }

  /** 独立工作空间项目不污染个人工作台。 */
  private String project() {
    return projects.create(key(), "任务控制（合成）", "工程边界，不是语义质量").project().id();
  }

  /** 唯一追加身份，不清理或复用过去测试数据。 */
  private String key() {
    return UUID.randomUUID().toString();
  }
}
