/* 文件职责：隔离MySQL验证原问题持久化、一次子任务关联、版本与费用确认，以及故障回滚。 */
package io.echoinsight.intake;

import static io.echoinsight.intake.AnalysisIntakeProtocol.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.echoinsight.collection.*;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.TaskControlService;
import io.echoinsight.project.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 仅合成项目；采集可建批次但执行器关闭，模型/付费关闭，绝不访问平台或供应商。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=analysis-intake-it",
      "echoinsight.worker.execution-scope=analysis-intake-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=true",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class AnalysisIntakeWorkflowIT {
  @Autowired AnalysisIntakeStore store;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired JdbcTemplate db;
  @Autowired TestRestTemplate http;
  @Autowired TaskControlService control;
  @MockitoSpyBean AnalysisRequestStore requests;
  @MockitoSpyBean ConfiguredModelPlan plans;
  @MockitoSpyBean BilibiliCollectionSettings settings;

  /** 每例恢复替身方法，不让配置漂移影响其他验收。 */
  @AfterEach
  void restoreSpies() {
    reset(requests, plans, settings);
  }

  /** 只读规划不保存入口、采集批次或分析请求。 */
  @Test
  void previewDoesNotDispatchOrPersist() {
    var f = fixture("BILIBILI");
    long before = count("analysis_intake", f.p());
    var p = store.preview(f.p(), urlDraft(f));
    assertThat(p.candidates().total()).isEqualTo(1);
    assertThat(count("analysis_intake", f.p())).isEqualTo(before);
    assertThat(count("source_collection_batch", f.p())).isZero();
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 命题缺失先澄清，不能创建没有判断标准的任务。 */
  @Test
  void missingPropositionRemainsReadOnly() {
    var f = fixture("XHS");
    var draft = new Draft(null, f.sid(), "哪些评论支持这个观点", null, "FULL");
    assertThat(store.preview(f.p(), draft).input().goal().needsProposition()).isTrue();
    assertThatThrownBy(() -> store.create(f.p(), key(), draft)).hasMessageContaining("补充");
    assertThat(count("analysis_intake", f.p())).isZero();
  }

  /** 保存问题不启动子任务；重发返回原身份，改变问题冲突。 */
  @Test
  void frozenQuestionIsIdempotent() {
    var f = fixture("XHS");
    String k = key();
    var a = store.create(f.p(), k, localDraft(f));
    assertThat(a.created()).isTrue();
    assertThat(a.value().phase()).isEqualTo("READY_FOR_ANALYSIS");
    assertThat(store.create(f.p(), k, localDraft(f)).value()).isEqualTo(a.value());
    assertThat(store.create(f.p(), k, localDraft(f)).created()).isFalse();
    assertThatThrownBy(
            () -> store.create(f.p(), k, new Draft(null, f.sid(), "总结不同观点", null, "FULL")))
        .hasMessageContaining("请求键");
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 历史有界并按执行范围隔离，GET不会开始下一步。 */
  @Test
  void historyIsPagedAndScopeQualified() {
    var f = fixture("SYNTHETIC");
    for (int i = 0; i < 3; i++) store.create(f.p(), key(), localDraft(f));
    assertThat(store.list(f.p(), 1, 2).items()).hasSize(2);
    assertThat(store.list(f.p(), 2, 2).items()).hasSize(1);
    var id = store.list(f.p(), 1, 2).items().getFirst().id();
    db.update("UPDATE analysis_intake SET execution_scope='other-intake-test' WHERE id=?", id);
    assertThatThrownBy(() -> store.get(f.p(), id)).hasMessageContaining("不存在");
    assertThat(store.list(f.p(), 1, 2).total()).isEqualTo(2);
    assertThatThrownBy(() -> store.list(f.p(), 0, 21)).hasMessageContaining("页码");
  }

  /** 同项目多版本必须明确选择，不会默认绑定最新快照。 */
  @Test
  void urlCandidatesDoNotChooseLatest() {
    var f = fixture("BILIBILI");
    String second = append(f, 3);
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    assertThat(a.source()).isNull();
    assertThat(store.candidates(f.p(), a.id(), 1, 1).total()).isEqualTo(2);
    assertThat(store.candidates(f.p(), a.id(), 2, 1).items()).hasSize(1);
    assertThat(store.bind(f.p(), a.id(), f.sid()).source().snapshotId()).isEqualTo(f.sid());
    assertThatThrownBy(() -> store.bind(f.p(), a.id(), second)).hasMessageContaining("已固定");
  }

  /** 链接与快照的作品身份不一致必须拒绝。 */
  @Test
  void wrongWorkCannotBeBound() {
    var f = fixture("BILIBILI");
    var other = fixture("BILIBILI");
    projects.attach(f.p(), other.sid());
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    assertThatThrownBy(() -> store.bind(f.p(), a.id(), other.sid())).hasMessageContaining("不一致");
    assertThat(store.get(f.p(), a.id()).source()).isNull();
  }

  /** BV和av对应只能来自同项目成功采集记录，不在入口做算术猜测。 */
  @Test
  void identityMappingRequiresLocalSuccessfulEvidence() {
    var f = fixture("BILIBILI");
    String bvid = "BV1ntuF64EhF";
    var draft = new Draft("https://www.bilibili.com/video/" + bvid, null, "总结观点", null, "FULL");
    assertThat(store.preview(f.p(), draft).candidates().total()).isZero();
    var a = store.create(f.p(), key(), draft).value();
    var batch = store.collect(f.p(), a.id(), new Collect(1, false, true)).collection();
    syntheticSuccess(f, batch.id(), bvid);
    assertThat(store.candidates(f.p(), a.id(), 1, 10).total()).isEqualTo(1);
    var other = projects.create(key(), "另一个项目", "隔离来源映射").project().id();
    projects.attach(other, f.sid());
    assertThat(store.preview(other, draft).candidates().total()).isZero();
  }

  /** 采集必须明确确认，重复提交只关联同一个原批次。 */
  @Test
  void collectionRequiresConsentAndCreatesOnce() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    assertThatThrownBy(() -> store.collect(f.p(), a.id(), new Collect(null, true, false)))
        .hasMessageContaining("确认");
    var first = store.collect(f.p(), a.id(), new Collect(null, true, true));
    assertThat(store.collect(f.p(), a.id(), new Collect(null, true, true)).collection().id())
        .isEqualTo(first.collection().id());
    assertThatThrownBy(() -> store.collect(f.p(), a.id(), new Collect(50, false, true)))
        .hasMessageContaining("其他采集范围");
    assertThat(count("source_collection_batch", f.p())).isEqualTo(1);
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 已经接受的采集在配置关闭后仍可恢复，不重新派工。 */
  @Test
  void collectionReplayPrecedesCurrentEnabledGate() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    var body = new Collect(50, false, true);
    var first = store.collect(f.p(), a.id(), body);
    doReturn(false).when(settings).enabled();
    assertThat(store.collect(f.p(), a.id(), body).collection().id())
        .isEqualTo(first.collection().id());
  }

  /** 成功采集停在资料确认，GET没有自动绑定和分析副作用。 */
  @Test
  void collectionSuccessWaitsForExplicitSource() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    var batch = store.collect(f.p(), a.id(), new Collect(1, false, true)).collection();
    syntheticSuccess(f, batch.id(), null);
    for (int i = 0; i < 3; i++) {
      var v = store.get(f.p(), a.id());
      assertThat(v.phase()).isEqualTo("READY_TO_BIND");
      assertThat(v.source()).isNull();
      assertThat(v.analysis()).isNull();
    }
    assertThat(store.bind(f.p(), a.id(), null).source().snapshotId()).isEqualTo(f.sid());
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 采集链不能偷换其他快照，等待中也不能接受已有版本绕过该批次。 */
  @Test
  void collectionBindingUsesExactSuccessfulResult() {
    var f = fixture("BILIBILI");
    String second = append(f, 4);
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    var b = store.collect(f.p(), a.id(), new Collect(1, false, true)).collection();
    assertThatThrownBy(() -> store.bind(f.p(), a.id(), f.sid())).hasMessageContaining("尚未");
    syntheticSuccess(f, b.id(), null);
    assertThatThrownBy(() -> store.bind(f.p(), a.id(), second)).hasMessageContaining("实际生成");
    assertThat(store.bind(f.p(), a.id(), null).source().snapshotId()).isEqualTo(f.sid());
  }

  /** 原任务取消/继续改变入口投影，不增加入口状态机。 */
  @Test
  void originalCancelAndResumeAreReflected() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    var b = store.collect(f.p(), a.id(), new Collect(1, false, true)).collection();
    control.cancel(f.p(), "COLLECTION", b.id());
    assertThat(store.get(f.p(), a.id()).phase()).isEqualTo("COLLECTION_CANCELLED");
    control.resume(f.p(), "COLLECTION", b.id());
    assertThat(store.get(f.p(), a.id()).phase()).isEqualTo("COLLECTING");
    assertThat(count("source_collection_batch", f.p())).isEqualTo(1);
  }

  /** 原失败原因保留在同一批次，用户安全重试沿用原身份。 */
  @Test
  void originalFailureAndRetryRemainVisible() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    var b = store.collect(f.p(), a.id(), new Collect(1, false, true)).collection();
    db.update(
        "UPDATE source_collection_batch SET status='FAILED',last_error_code='COLLECTION_TIMEOUT'"
            + " WHERE id=?",
        b.id());
    assertThat(store.get(f.p(), a.id()).collection().lastErrorCode())
        .isEqualTo("COLLECTION_TIMEOUT");
    assertThat(store.get(f.p(), a.id()).phase()).isEqualTo("COLLECTION_FAILED");
    control.retry(f.p(), "COLLECTION", b.id());
    assertThat(store.get(f.p(), a.id()).collection().id()).isEqualTo(b.id());
  }

  /** 只读分析预检可重复且不建任务、调用或费用行。 */
  @Test
  void quoteIsDeterministicAndReadOnly() {
    var f = fixture("XHS");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var q = store.quote(f.p(), a.id());
    assertThat(store.quote(f.p(), a.id())).isEqualTo(q);
    assertThat(q.available()).isTrue();
    assertThat(q.cost().maximumUnits()).isEqualTo("0");
    assertThat(q.cost().mode()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 未固定资料时不虚构评论数和费用。 */
  @Test
  void cannotQuoteWithoutSource() {
    var f = fixture("BILIBILI");
    var a = store.create(f.p(), key(), urlDraft(f)).value();
    assertThatThrownBy(() -> store.quote(f.p(), a.id())).hasMessageContaining("固定");
  }

  /** 完整范围需要原服务明确同意；失败时请求和关联一起回滚。 */
  @Test
  void fullScopeConsentIsStillRequired() {
    var f = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var q = store.quote(f.p(), a.id());
    assertThatThrownBy(() -> store.accept(f.p(), a.id(), new Accept(q.sha256(), false, false)))
        .hasMessageContaining("确认");
    assertThat(store.get(f.p(), a.id()).analysis()).isNull();
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 预检指纹不能跨入口或跨资料复用，不接受猜测确认。 */
  @Test
  void staleOrForeignQuoteDoesNotCreateTask() {
    var f = fixture("XHS");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    assertThatThrownBy(() -> store.accept(f.p(), a.id(), new Accept("a".repeat(64), false, true)))
        .hasMessageContaining("重新核对");
    var other = store.create(f.p(), key(), localDraft(f)).value();
    String foreignSha = store.quote(f.p(), other.id()).sha256();
    assertThatThrownBy(() -> store.accept(f.p(), a.id(), new Accept(foreignSha, false, true)))
        .hasMessageContaining("重新核对");
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 完整模型计划变化，即使仍是免费演示也必须重新确认。 */
  @Test
  void configurationDriftInvalidatesOldQuote() {
    var f = fixture("XHS");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var q = store.quote(f.p(), a.id());
    doReturn(changedPlan(plans.discussionDigest())).when(plans).discussionDigest();
    assertThatThrownBy(() -> store.accept(f.p(), a.id(), new Accept(q.sha256(), false, true)))
        .hasMessageContaining("重新核对");
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 已接受后原样重发优先返回原请求，不受新配置影响、不新建费用。 */
  @Test
  void acceptedReplayReturnsOriginalBeforeConfiguration() {
    var f = fixture("XHS");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var body = new Accept(store.quote(f.p(), a.id()).sha256(), false, true);
    var first = store.accept(f.p(), a.id(), body);
    doReturn(changedPlan(plans.discussionDigest())).when(plans).discussionDigest();
    var again = store.accept(f.p(), a.id(), body);
    assertThat(again.created()).isFalse();
    assertThat(again.request().id()).isEqualTo(first.request().id());
    assertThat(store.get(f.p(), a.id()).phase()).isEqualTo("ANALYSIS_ACCEPTED");
    assertThat(count("analysis_request", f.p())).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE job_id=?",
                Long.class,
                first.request().jobId()))
        .isZero();
  }

  /** 双提交在项目锁内串行化，只有一次接受和一个原分析任务。 */
  @Test
  void concurrentAcceptanceCreatesExactlyOneRequest() throws Exception {
    var f = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var body = new Accept(store.quote(f.p(), a.id()).sha256(), false, true);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> store.accept(f.p(), a.id(), body));
      var second = executor.submit(() -> store.accept(f.p(), a.id(), body));
      var x = first.get(20, TimeUnit.SECONDS);
      var y = second.get(20, TimeUnit.SECONDS);
      assertThat(x.request().id()).isEqualTo(y.request().id());
      assertThat(x.created() ^ y.created()).isTrue();
    }
    assertThat(count("analysis_request", f.p())).isEqualTo(1);
  }

  /** 原请求已创建但入口关联前故障，外层事务回滚所有新请求和任务。 */
  @Test
  void childCreationFailureRollsBackWholeAcceptance() {
    var f = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    var body = new Accept(store.quote(f.p(), a.id()).sha256(), false, true);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("Synthetic after child failure");
            })
        .when(requests)
        .createWithScope(
            eq(f.p()),
            anyString(),
            anyString(),
            anyString(),
            eq(false),
            anyList(),
            anyBoolean(),
            nullable(String.class),
            eq("FULL"),
            eq(true));
    assertThatThrownBy(() -> store.accept(f.p(), a.id(), body)).hasMessageContaining("Synthetic");
    assertThat(count("analysis_request", f.p())).isZero();
    assertThat(projects.get(f.p()).jobCount()).isZero();
    assertThat(store.get(f.p(), a.id()).analysis()).isNull();
  }

  /** 冻结原文损坏失败关闭，不重算并掩盖原问题。 */
  @Test
  void corruptInputDoesNotRebuild() {
    var f = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    db.update("UPDATE analysis_intake SET input_sha256=? WHERE id=?", "0".repeat(64), a.id());
    assertThatThrownBy(() -> store.get(f.p(), a.id())).hasMessageContaining("hash mismatch");
    assertThat(count("analysis_request", f.p())).isZero();
  }

  /** 资料哈希与入口绑定不一致时不能分析，旧资料不被“修复”覆盖。 */
  @Test
  void corruptSourceBindingFailsClosed() {
    var f = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    db.update("UPDATE analysis_intake SET snapshot_sha256=? WHERE id=?", "0".repeat(64), a.id());
    assertThatThrownBy(() -> store.quote(f.p(), a.id())).hasMessageContaining("source changed");
  }

  /** 跨项目读取和绑定均不能凭UUID越权。 */
  @Test
  void projectMembershipIsRequired() {
    var f = fixture("XHS");
    var other = fixture("SYNTHETIC");
    var a = store.create(f.p(), key(), localDraft(f)).value();
    assertThatThrownBy(() -> store.get(other.p(), a.id())).hasMessageContaining("不存在");
    assertThatThrownBy(() -> store.create(other.p(), key(), localDraft(f)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** HTTP首次201重发200，公开安全错误不反射原文或供应商配置。 */
  @Test
  void httpCreationAndSafeRejection() {
    var f = fixture("XHS");
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    String path = "/api/v1/projects/" + f.p() + "/analysis-intakes";
    var body = new HttpEntity<>(localDraft(f), headers);
    var first = http.postForEntity(path, body, AnalysisIntakeStore.View.class);
    var again = http.postForEntity(path, body, AnalysisIntakeStore.View.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody().id()).isEqualTo(first.getBody().id());
    var rejected =
        http.postForEntity(
            path + "/" + first.getBody().id() + "/analysis",
            new Accept("a".repeat(64), false, true),
            String.class);
    assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(rejected.getBody())
        .contains("重新核对")
        .doesNotContain("apiKey", "execution_plan_json", "SPRING_AI");
  }

  /** 合成项目和快照单独追加；不改用户资料或共享真实任务。 */
  private Fixture fixture(String source) {
    String p = projects.create(key(), "入口合成验收", "验证问题与任务贯通").project().id();
    String work = Long.toUnsignedString(System.nanoTime());
    var time = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    var doc =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("work-" + key(), source, work, "creator", "入口合成视频", time),
            List.of(
                new ImportDocument.Comment("a", "source-a", null, "讨论主张与理由", time),
                new ImportDocument.Comment("b", "source-b", "a", "基于上文补充理由", time)),
            List.of());
    return new Fixture(p, imports.ingest(p, doc).snapshotId(), work, doc);
  }

  /** 同作品新版本只追加不同评论内容，旧快照留存。 */
  private String append(Fixture f, int count) {
    var items = new ArrayList<>(f.doc().comments());
    for (int i = items.size(); i < count; i++)
      items.add(
          new ImportDocument.Comment(
              "x" + i, "source-x" + i, null, "新增讨论" + i, f.doc().content().publishedAt()));
    return imports
        .ingest(f.p(), new ImportDocument("1.0", f.doc().content(), items, List.of()))
        .snapshotId();
  }

  /** 平台响应的测试替身，仅更新本例刚创建的合成批次；不声称真实采集成功。 */
  private void syntheticSuccess(Fixture f, String batch, String bvid) {
    db.update(
        "UPDATE source_collection_batch SET"
            + " status='SUCCESS',snapshot_id=?,resolved_aid=?,resolved_bvid=?,completeness='NOT_PROVEN',collected_comment_count=2,collected_root_count=1,finished_at=UTC_TIMESTAMP(6)"
            + " WHERE id=? AND project_id=?",
        f.sid(),
        f.work(),
        bvid,
        batch,
        f.p());
  }

  /** 计划只有价格版本变化，确保指纹覆盖完整计划而不是仅金额。 */
  private ModelContract.Plan changedPlan(ModelContract.Plan p) {
    return new ModelContract.Plan(
        p.providerId(),
        p.modelVersion(),
        p.promptVersion(),
        p.taxonomyVersion(),
        p.batchSize(),
        p.timeoutMs(),
        p.maxOutputTokens(),
        p.unit(),
        "intake-drift-test",
        p.inputUnitRate(),
        p.outputUnitRate(),
        p.budgetLimit(),
        p.inputTokenCeiling(),
        p.responseModel(),
        p.executionMode());
  }

  /** 明确选择本例本地资料，不依赖平台链接。 */
  private Draft localDraft(Fixture f) {
    return new Draft(null, f.sid(), "总结用户讨论的观点、理由和分歧", null, "FULL");
  }

  /** 仅构造合成视频身份，不发出平台请求。 */
  private Draft urlDraft(Fixture f) {
    return new Draft(
        "https://www.bilibili.com/video/av" + f.work(), null, "总结用户讨论的观点、理由和分歧", null, "FULL");
  }

  /** 表名白名单与项目条件一起限定测试记录计数。 */
  private long count(String table, String p) {
    if (!Set.of("analysis_intake", "analysis_request", "source_collection_batch").contains(table))
      throw new IllegalArgumentException();
    return db.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE project_id=?", Long.class, p);
  }

  /** 每个测试意图使用独立键，重发用例另行明确复用。 */
  private String key() {
    return UUID.randomUUID().toString();
  }

  /** 汇集本例合成项目、原始快照与作品身份，便于关系断言。 */
  private record Fixture(String p, String sid, String work, ImportDocument doc) {}
}
