/* 文件职责：使用本机专用数据库验证任务幂等、失败重试和租约恢复。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.analysis.FixtureClassifier;
import io.echoinsight.ingestion.*;
import io.echoinsight.orchestration.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 禁用定时派发，由测试精确控制每次崩溃和重试发生的位置。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "echoinsight.worker.enabled=false")
class AnalysisJobControllerIT {
  @Autowired TestRestTemplate http;
  @Autowired ImportService imports;
  @Autowired JobStore store;
  @Autowired AnalysisWorker worker;
  @Autowired FixtureClassifier classifier;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;

  /** 项目列表隔离不能替代执行隔离；调度候选、直接认领和同键重发均检查服务器范围。 */
  @Test
  void executionScopePreventsLiveAndTestWorkersFromClaimingEachOthersJobs() {
    String snapshot = imports.ingest(input()).snapshotId();
    String key = UUID.randomUUID().toString();
    var job = store.create(snapshot, key);
    assertThat(
            db.queryForObject(
                "SELECT execution_scope FROM analysis_job WHERE id=?", String.class, job.id()))
        .isEqualTo("integration-test");
    db.update(
        "UPDATE analysis_job SET execution_scope='foreign-execution-it' WHERE id=?", job.id());
    assertThat(store.candidates()).doesNotContain(job.id());
    assertThat(store.claim(job.id())).isNull();
    assertThat(create(snapshot, key).getStatusCode().value()).isEqualTo(409);
  }

  /** 为每个测试创建独立来源，不删除既有测试或用户数据。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    var texts =
        List.of(
            "线程池与拒绝策略的例子很清楚，帮助我理解了。",
            "线程池与拒绝策略的边界条件缺少完整案例，希望补充。",
            "请问线程池与拒绝策略的示例代码在哪里下载？",
            "任意未知文本");
    List<ImportDocument.Comment> comments = new ArrayList<>();
    for (int i = 0; i < texts.size(); i++)
      comments.add(new ImportDocument.Comment("c" + i, "s" + i, null, texts.get(i), time));
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content(
            "local", "SYNTHETIC", "job-it-" + UUID.randomUUID(), "test", "任务合成测试", time),
        comments,
        List.of());
  }

  /** 创建 HTTP 请求时固定请求键，便于模拟重发与冲突。 */
  private ResponseEntity<JsonNode> create(String snapshot, String key) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.postForEntity(
        "/api/v1/analysis-jobs",
        new HttpEntity<>(Map.of("snapshotId", snapshot), headers),
        JsonNode.class);
  }

  /** 相同键只创建一个任务；键与快照不匹配时明确拒绝。 */
  @Test
  void requestKeyIsIdempotentAndConflictsAreExplicit() {
    String snapshot = imports.ingest(input()).snapshotId(), key = UUID.randomUUID().toString();
    var first = create(snapshot, key);
    var repeat = create(snapshot, key);
    assertThat(first.getStatusCode().value()).isEqualTo(202);
    assertThat(repeat.getBody().path("id")).isEqualTo(first.getBody().path("id"));
    assertThat(create(imports.ingest(input()).snapshotId(), key).getStatusCode().value())
        .isEqualTo(409);
    assertThat(
            create(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  /** 1、执行；2、核对分母与原文；3、重复执行不重复发布。 */
  @Test
  void publishesOneTraceableReportAndRejectsRetryOfSuccess() {
    var job = store.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    worker.runOne(job.id());
    worker.runOne(job.id());
    var state = http.getForObject("/api/v1/analysis-jobs/" + job.id(), JsonNode.class);
    assertThat(state.path("status").asText()).isEqualTo("SUCCESS");
    assertThat(state.path("progress").asInt()).isEqualTo(100);
    String reportId = state.path("reportId").asText();
    var report = http.getForObject("/api/v1/reports/" + reportId, JsonNode.class);
    assertThat(report.path("coverage").path("classifiedComments").asInt()).isEqualTo(3);
    assertThat(report.path("coverage").path("unknown").asInt()).isEqualTo(1);
    assertThat(report.path("metrics").path("positiveShareAmongClassified").asDouble())
        .isEqualTo(1.0 / 3);
    assertThat(http.getForObject("/api/v1/reports/" + reportId + "/evidence", String.class))
        .contains("例子很清楚", "边界条件");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_report WHERE job_id=?", Integer.class, job.id()))
        .isEqualTo(1);
    assertThat(
            http.postForEntity("/api/v1/analysis-jobs/" + job.id() + "/retry", null, String.class)
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  /** 报告步骤失败后重试，只恢复失败步骤，保留分类结果与失败记录。 */
  @Test
  void retriesFailedReportWithoutReplacingSuccessfulClassification() throws Exception {
    var data = input();
    var job = store.create(imports.ingest(data).snapshotId(), UUID.randomUUID().toString());
    String token = store.claim(job.id());
    store.startStep(job.id(), token, "CLASSIFY");
    String classified = json.writeValueAsString(classifier.classify(data, () -> {}));
    store.saveClassification(job.id(), token, classified);
    store.startStep(job.id(), token, "REPORT");
    store.fail(job.id(), token);
    assertThat(store.get(job.id()).status()).isEqualTo("FAILED");
    assertThat(
            http.postForEntity("/api/v1/analysis-jobs/" + job.id() + "/retry", null, String.class)
                .getStatusCode()
                .value())
        .isEqualTo(202);
    worker.runOne(job.id());
    assertThat(store.get(job.id()).status()).isEqualTo("SUCCESS");
    assertThat(store.steps(job.id()).getFirst().resultJson()).isEqualTo(classified);
    assertThat(store.attempts(job.id()).getFirst().status()).isEqualTo("FAILED");
    assertThat(store.get(job.id()).attempts()).isEqualTo(2);
  }

  /** 租约过期后新执行者接管；旧 token 不允许写入或覆盖新执行者。 */
  @Test
  void expiredLeaseIsRecoveredAndStaleOwnerIsFenced() {
    var job = store.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    String old = store.claim(job.id());
    store.startStep(job.id(), old, "CLASSIFY");
    db.update(
        "UPDATE analysis_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
        job.id());
    assertThatThrownBy(() -> store.saveClassification(job.id(), old, "[]"))
        .isInstanceOf(JobStore.LostLease.class);
    worker.runOne(job.id());
    assertThat(store.get(job.id()).status()).isEqualTo("SUCCESS");
    assertThat(store.get(job.id()).attempts()).isEqualTo(2);
    assertThat(store.attempts(job.id()).getFirst().status()).isEqualTo("ABANDONED");
    assertThatThrownBy(() -> store.fail(job.id(), old)).isInstanceOf(JobStore.LostLease.class);
  }

  /** 连续恢复达到上限后停止自动派发，用户仍可显式重试。 */
  @Test
  void repeatedLeaseLossHasBoundedRecovery() {
    var job = store.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    for (int i = 0; i < 4; i++) {
      assertThat(store.claim(job.id())).isNotNull();
      db.update(
          "UPDATE analysis_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
          job.id());
    }
    assertThat(store.claim(job.id())).isNull();
    assertThat(store.get(job.id()).status()).isEqualTo("FAILED");
    assertThat(store.get(job.id()).lastErrorCode()).isEqualTo("RECOVERY_LIMIT");
    store.retry(job.id());
    worker.runOne(job.id());
    assertThat(store.get(job.id()).status()).isEqualTo("SUCCESS");
  }

  /** 同一任务只能由一个有效 token 执行，非法步骤顺序不能提交。 */
  @Test
  void activeClaimAndDependencyCannotBeBypassed() {
    var job = store.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    String token = store.claim(job.id());
    assertThat(store.claim(job.id())).isNull();
    assertThatThrownBy(() -> store.startStep(job.id(), token, "REPORT"))
        .isInstanceOf(IllegalStateException.class);
    store.fail(job.id(), token);
  }

  /** 两个执行者同时收到同一任务时，只允许一次有效执行和发布。 */
  @Test
  void simultaneousWorkersPublishOnlyOnce() throws Exception {
    var job = store.create(imports.ingest(input()).snapshotId(), UUID.randomUUID().toString());
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var ready = new java.util.concurrent.CountDownLatch(2);
      var go = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Void> task =
          () -> {
            ready.countDown();
            if (!go.await(10, java.util.concurrent.TimeUnit.SECONDS))
              throw new IllegalStateException("Barrier timeout");
            worker.runOne(job.id());
            return null;
          };
      var first = pool.submit(task);
      var second = pool.submit(task);
      assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      go.countDown();
      first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      second.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(store.get(job.id()).status()).isEqualTo("SUCCESS");
    assertThat(store.get(job.id()).attempts()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_report WHERE job_id=?", Integer.class, job.id()))
        .isEqualTo(1);
  }

  /** 新服务器配置不改变相同请求键绑定的原任务计划；内部严格入口仍拒绝计划变化。 */
  @Test
  void apiReplayRetainsOriginalPlanAcrossConfigurationChanges() {
    var snapshot = imports.ingest(input()).snapshotId();
    var key = UUID.randomUUID().toString();
    var old = io.echoinsight.model.ModelContract.Plan.mock();
    var changed =
        new io.echoinsight.model.ModelContract.Plan(
            "MOCK",
            old.modelVersion(),
            old.promptVersion(),
            old.taxonomyVersion(),
            10,
            old.timeoutMs(),
            old.maxOutputTokens(),
            old.unit(),
            old.priceVersion(),
            0,
            0,
            0);
    var first = store.createFromApi(snapshot, key, changed);
    assertThat(create(snapshot, key).getBody().path("id").asText()).isEqualTo(first.id());
    assertThat(store.plan(first.id())).isEqualTo(changed);
    assertThatThrownBy(() -> store.create(snapshot, key, old))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 默认状态是脱敏只读摘要；查询不创建任务，系统概要使用同一份当前模式。 */
  @Test
  void modelStatusIsSafeReadOnlyAndConsistentWithSystemInfo() {
    var before = db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class);
    var response = http.getForEntity("/api/v1/model/status", JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().path("mode").asText()).isEqualTo("MOCK");
    assertThat(response.getBody().path("taskBudgetUsd").asText()).isEqualTo("0");
    assertThat(response.getBody().size()).isEqualTo(6);
    assertThat(response.getBody().toString()).doesNotContain("apiKey", "endpoint", "password");
    assertThat(http.getForObject("/api/v1/system/info", JsonNode.class).path("aiMode").asText())
        .isEqualTo("MOCK");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM analysis_job", Long.class))
        .isEqualTo(before);
  }
}
