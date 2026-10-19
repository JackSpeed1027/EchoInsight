/* 文件职责：用回环 HTTP Stub 验证分批复用、预算、超时、慢调用续租和过期写入隔离。 */
package io.echoinsight.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.echoinsight.analysis.FixtureClassifier.*;
import io.echoinsight.ingestion.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.report.ReportBuilder;
import io.echoinsight.review.FindingCatalog;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 测试任务在同一事务中创建并领取，避免外部已运行的开发派发器抢走测试任务。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false",
      "echoinsight.worker.lease-seconds=2",
      "echoinsight.worker.heartbeat-ms=100"
    })
class ModelGatewayIT {
  @Autowired JobStore jobs;
  @Autowired ImportService imports;
  @Autowired ModelLedger ledger;
  @Autowired ModelGateway gateway;
  @Autowired BatchClassifier classifier;
  @Autowired LeaseKeeper leases;
  @Autowired ReportBuilder reports;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager manager;
  @Autowired TestRestTemplate http;

  /** 任务输入和当前执行 token，全部来自本测试的新来源。 */
  private record Claimed(
      JobStore.Job job, String token, ImportDocument input, ModelContract.Plan plan) {}

  /** 创建独立合成材料；不删除用户数据，不修改已有来源。 */
  private Claimed claim(ModelContract.Plan plan, int count) {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    List<ImportDocument.Comment> comments = new ArrayList<>();
    for (int i = 0; i < count; i++)
      comments.add(
          new ImportDocument.Comment("c" + i, "s" + i, null, "线程池与拒绝策略的例子很清楚，帮助我理解了。", time));
    var input =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content(
                "local", "SYNTHETIC", "model-it-" + UUID.randomUUID(), "test", "模型调用边界合成测试", time),
            comments,
            List.of());
    String snapshot = imports.ingest(input).snapshotId();
    return new TransactionTemplate(manager)
        .execute(
            s -> {
              var job = jobs.create(snapshot, UUID.randomUUID().toString(), plan);
              return new Claimed(job, jobs.claim(job.id()), input, plan);
            });
  }

  /** 本机模拟价格只服务于账本试验，不是真实供应商报价。 */
  private ModelContract.Plan stubPlan(long timeout, long budget) {
    return new ModelContract.Plan(
        "LOCAL_HTTP_STUB",
        "local-http-stub-v1",
        "stub-prompt-v1",
        "sentiment-v1",
        1,
        timeout,
        32,
        "SIMULATED_UNITS",
        "stub-price-test-v1",
        2,
        3,
        budget);
  }

  /** 构造网关单批请求，输入上界是明确的本机 Stub 契约。 */
  private ModelContract.Request request(Claimed job, int index) {
    var comment = job.input().comments().get(index);
    return new ModelContract.Request(
        job.job().snapshotId(),
        index,
        "SYNTHETIC",
        List.of(new ModelContract.Text(comment.id(), comment.text(), null, null)),
        2048,
        job.plan().maxOutputTokens());
  }

  /** 返回合法响应，供 HTTP 传输及严格分类成员校验。 */
  private String valid(JsonNode request) {
    List<Classification> result = new ArrayList<>();
    request
        .path("comments")
        .forEach(c -> result.add(new Classification(c.path("commentId").asText(), Label.POSITIVE)));
    return ModelContract.encode(new ModelContract.Response(result, 10, 2, "local-request"));
  }

  /** 本地服务只监听回环，关闭时停止测试线程，不接触任何用户文件。 */
  private final class Stub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor =
        Executors.newFixedThreadPool(
            4,
            r -> {
              var t = new Thread(r, "model-stub-test");
              t.setDaemon(true);
              return t;
            });
    private final AtomicInteger requests = new AtomicInteger();

    /** 注入响应行为以模拟慢响应、坏输出或连接断开。 */
    private Stub(Function<JsonNode, String> response) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/classify",
          exchange -> {
            try {
              var body = json.readTree(exchange.getRequestBody().readNBytes(65536));
              requests.incrementAndGet();
              String result = response.apply(body);
              if (result != null) {
                byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              }
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    /** 获取动态分配的本机测试地址。 */
    private URI endpoint() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/classify");
    }

    /** 只停止本测试创建的服务和线程。 */
    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  /** 1、分三批保存；2、发布 Mock 报告；3、只读诊断不新增调用。 */
  @Test
  void mockPipelinePublishesWithBoundedCheckpointsAndZeroUsage() throws Exception {
    var base = ModelContract.Plan.mock();
    var plan =
        new ModelContract.Plan(
            base.providerId(),
            base.modelVersion(),
            base.promptVersion(),
            base.taxonomyVersion(),
            2,
            3000,
            512,
            base.unit(),
            base.priceVersion(),
            0,
            0,
            0);
    var job = claim(plan, 5);
    try (var lease = leases.keep(job.job().id(), job.token())) {
      jobs.startStep(job.job().id(), job.token(), "CLASSIFY");
      var labels = classifier.classify(job.job(), job.token(), job.input(), lease::check);
      jobs.saveClassification(job.job().id(), job.token(), json.writeValueAsString(labels));
      jobs.startStep(job.job().id(), job.token(), "REPORT");
      var report = reports.build(job.job(), job.input(), labels);
      jobs.publish(
          job.job().id(),
          job.token(),
          report.reportId(),
          job.job().snapshotId(),
          json.writeValueAsString(report));
    }
    var view =
        http.getForObject(
            "/api/v1/analysis-jobs/" + job.job().id() + "/execution?size=2", JsonNode.class);
    assertThat(view.path("total").asInt()).isEqualTo(3);
    assertThat(view.path("items").size()).isEqualTo(2);
    assertThat(view.path("budget").path("settledUnits").asLong()).isZero();
    assertThat(jobs.get(job.job().id()).status()).isEqualTo("SUCCESS");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, job.job().id()))
        .isEqualTo(3);
    assertThat(
            http.getForEntity(
                    "/api/v1/analysis-jobs/" + job.job().id() + "/execution?page=0", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(
            http.getForEntity(
                    "/api/v1/analysis-jobs/" + UUID.randomUUID() + "/execution", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
  }

  /** 首批成功后模拟应用逻辑中断，恢复不会再次调用已确认成功的首批。 */
  @Test
  void retryReusesPersistedBatchInsteadOfCallingSupplierAgain() throws Exception {
    try (var stub = new Stub(this::valid);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(3000, 100000), 2);
      var pipeline = new BatchClassifier(jobs, local);
      var checks = new AtomicInteger();
      jobs.startStep(job.job().id(), job.token(), "CLASSIFY");
      assertThatThrownBy(
              () ->
                  pipeline.classify(
                      job.job(),
                      job.token(),
                      job.input(),
                      () -> {
                        if (checks.incrementAndGet() == 2)
                          throw new IllegalStateException(
                              "Synthetic interruption before second batch");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(stub.requests.get()).isEqualTo(1);
      jobs.fail(job.job().id(), job.token());
      jobs.retry(job.job().id());
      String next = jobs.claim(job.job().id());
      var labels = pipeline.classify(jobs.get(job.job().id()), next, job.input(), () -> {});
      assertThat(labels).hasSize(2);
      assertThat(stub.requests.get()).isEqualTo(2);
      assertThat(ledger.view(job.job().id(), 1, 20).budget().settledUnits()).isEqualTo(52);
      jobs.fail(job.job().id(), next);
    }
  }

  /** 预算不足在发请求之前拒绝，不产生 HTTP 或物理调用记录。 */
  @Test
  void insufficientBudgetDoesNotReachSupplier() throws Exception {
    try (var stub = new Stub(this::valid);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(1000, 1), 1);
      assertThatThrownBy(
              () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)))
          .isInstanceOf(ModelContract.Failure.class)
          .hasMessage("MODEL_BUDGET_EXCEEDED");
      assertThat(stub.requests.get()).isZero();
      assertThat(ledger.view(job.job().id(), 1, 10).budget().reservedUnits()).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, job.job().id()))
          .isZero();
      jobs.fail(job.job().id(), job.token(), "MODEL_BUDGET_EXCEEDED");
    }
  }

  /** 已发出请求超时保留预留、未知 Token，重试入口拒绝而不重复收费。 */
  @Test
  void timeoutKeepsUnknownReservationAndRejectsBlindRetry() throws Exception {
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var stub =
            new Stub(
                body -> {
                  entered.countDown();
                  try {
                    finish.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return valid(body);
                });
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(700, 100000), 1);
      try (var lease = leases.keep(job.job().id(), job.token())) {
        assertThatThrownBy(
                () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)))
            .isInstanceOf(ModelContract.Failure.class)
            .hasMessage("MODEL_OUTCOME_UNKNOWN");
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var view = ledger.view(job.job().id(), 1, 10);
        assertThat(view.budget().reservedUnits()).isGreaterThan(0);
        assertThat(view.budget().settledUnits()).isZero();
        assertThat(view.items().getFirst().recentCalls().getFirst().inputTokens()).isNull();
        assertThatThrownBy(
                () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)))
            .hasMessage("MODEL_OUTCOME_UNKNOWN");
        jobs.fail(job.job().id(), job.token(), "MODEL_OUTCOME_UNKNOWN");
        assertThat(
                http.postForEntity(
                        "/api/v1/analysis-jobs/" + job.job().id() + "/retry", null, String.class)
                    .getStatusCode()
                    .value())
            .isEqualTo(409);
        assertThat(stub.requests.get()).isEqualTo(1);
      } finally {
        finish.countDown();
      }
    }
  }

  /** 真实回环 HTTP 等待超过两秒租约期间，续租器持续工作，数据库行锁仍能被其他线程取得。 */
  @Test
  void slowHttpRenewsLeaseWithoutHoldingDatabaseTransaction() throws Exception {
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var stub =
            new Stub(
                body -> {
                  entered.countDown();
                  try {
                    finish.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return valid(body);
                });
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())));
        var pool = Executors.newFixedThreadPool(2)) {
      var job = claim(stubPlan(4500, 100000), 1);
      try (var lease = leases.keep(job.job().id(), job.token())) {
        var result =
            pool.submit(
                () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var lock =
            pool.submit(
                () ->
                    new TransactionTemplate(manager)
                        .execute(
                            s ->
                                db.queryForObject(
                                    "SELECT id FROM analysis_job WHERE id=? FOR UPDATE",
                                    String.class,
                                    job.job().id())));
        assertThat(lock.get(1, TimeUnit.SECONDS)).isEqualTo(job.job().id());
        Thread.sleep(2200);
        lease.check();
        jobs.renew(job.job().id(), job.token());
        finish.countDown();
        assertThat(result.get(2, TimeUnit.SECONDS).classifications()).hasSize(1);
        assertThat(ledger.view(job.job().id(), 1, 10).budget().settledUnits()).isEqualTo(26);
        jobs.fail(job.job().id(), job.token());
      } finally {
        finish.countDown();
      }
    }
  }

  /** 坏格式、未知标签、越界/重复成员及缺失用量均不得产生成功检查点。 */
  @Test
  void malformedAndSemanticallyInvalidResponsesStayUnpublished() throws Exception {
    List<String> invalid =
        List.of(
            "{",
            "{\"classifications\":[{\"commentId\":\"c0\",\"label\":\"MADE_UP\"}],\"inputTokens\":1,\"outputTokens\":1,\"requestId\":\"x\"}",
            "{\"classifications\":[{\"commentId\":\"outside\",\"label\":\"POSITIVE\"}],\"inputTokens\":1,\"outputTokens\":1,\"requestId\":\"x\"}",
            "{\"classifications\":[{\"commentId\":\"c0\",\"label\":\"POSITIVE\"},{\"commentId\":\"c0\",\"label\":\"NEGATIVE\"}],\"inputTokens\":1,\"outputTokens\":1,\"requestId\":\"x\"}",
            "{\"classifications\":[],\"inputTokens\":1,\"outputTokens\":1,\"requestId\":\"x\"}",
            "{\"classifications\":[{\"commentId\":\"c0\",\"label\":\"POSITIVE\"}],\"outputTokens\":1,\"requestId\":\"x\"}");
    for (String body : invalid)
      try (var stub = new Stub(request -> body);
          var local =
              new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
        var job = claim(stubPlan(1500, 100000), 1);
        assertThatThrownBy(
                () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)))
            .hasMessage("MODEL_OUTCOME_UNKNOWN");
        assertThat(ledger.view(job.job().id(), 1, 10).items().getFirst().status())
            .isEqualTo("OUTCOME_UNKNOWN");
        assertThat(jobs.reportForJob(job.job().id())).isNull();
        jobs.fail(job.job().id(), job.token(), "MODEL_OUTCOME_UNKNOWN");
      }
  }

  /** 连接在响应前断开也可能已经计费，不能释放已发送请求的预留。 */
  @Test
  void disconnectedHttpKeepsConservativeUnknownOutcome() throws Exception {
    try (var stub = new Stub(request -> null);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(1500, 100000), 1);
      assertThatThrownBy(
              () -> local.classify(job.job().id(), job.token(), job.plan(), request(job, 0)))
          .hasMessage("MODEL_OUTCOME_UNKNOWN");
      assertThat(ledger.view(job.job().id(), 1, 10).budget().reservedUnits()).isPositive();
      jobs.fail(job.job().id(), job.token(), "MODEL_OUTCOME_UNKNOWN");
    }
  }

  /** 旧执行者只完成预留但未发送时，新租约释放旧预留后可以安全调用一次。 */
  @Test
  void recoveryReleasesUnsentReservationAndFencesOldSender() throws Exception {
    try (var stub = new Stub(this::valid);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(2000, 100000), 1);
      var request = request(job, 0);
      var reserved =
          ledger.prepare(
              job.job().id(),
              job.token(),
              0,
              FindingCatalog.hash(ModelContract.encode(job.plan()) + ModelContract.encode(request)),
              4192,
              job.plan());
      db.update(
          "UPDATE analysis_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
          job.job().id());
      String next = jobs.claim(job.job().id());
      local.classify(job.job().id(), next, job.plan(), request);
      assertThat(stub.requests.get()).isEqualTo(1);
      assertThat(ledger.view(job.job().id(), 1, 20).budget().reservedUnits()).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT state FROM model_call WHERE id=?", String.class, reserved.callId()))
          .isEqualTo("RELEASED");
      assertThatThrownBy(() -> ledger.sent(job.job().id(), job.token(), reserved.callId()))
          .isInstanceOf(JobStore.LostLease.class);
      jobs.fail(job.job().id(), next);
    }
  }

  /** 旧执行者已标记发送的崩溃恢复保持未知，不能让新旧执行者各发一次。 */
  @Test
  void recoveryOfSentAttemptDoesNotIssueAnotherRequestOrAcceptStaleResult() throws Exception {
    try (var stub = new Stub(this::valid);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(2000, 100000), 1);
      var request = request(job, 0);
      var reserved =
          ledger.prepare(
              job.job().id(),
              job.token(),
              0,
              FindingCatalog.hash(ModelContract.encode(job.plan()) + ModelContract.encode(request)),
              4192,
              job.plan());
      ledger.sent(job.job().id(), job.token(), reserved.callId());
      db.update(
          "UPDATE analysis_job SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=?",
          job.job().id());
      String next = jobs.claim(job.job().id());
      assertThatThrownBy(() -> local.classify(job.job().id(), next, job.plan(), request))
          .hasMessage("MODEL_OUTCOME_UNKNOWN");
      assertThat(stub.requests.get()).isZero();
      assertThat(ledger.view(job.job().id(), 1, 10).budget().reservedUnits()).isPositive();
      assertThatThrownBy(
              () ->
                  ledger.complete(
                      job.job().id(),
                      job.token(),
                      0,
                      reserved.callId(),
                      new ModelContract.Response(
                          List.of(new Classification("c0", Label.POSITIVE)), 1, 1, "late"),
                      5))
          .isInstanceOf(JobStore.LostLease.class);
      jobs.fail(job.job().id(), next, "MODEL_OUTCOME_UNKNOWN");
    }
  }

  /** 两个批次同时预留同一任务预算时，只允许余额足够的一方进入出站准备。 */
  @Test
  void concurrentReservationsCannotSpendTheSameRemainingBudget() throws Exception {
    var job = claim(stubPlan(2000, 5000), 2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var ready = new CountDownLatch(2);
      var go = new CountDownLatch(1);
      List<Future<ModelLedger.Prepared>> pending = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final int index = i;
        pending.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!go.await(2, TimeUnit.SECONDS)) throw new IllegalStateException();
                  return ledger.prepare(
                      job.job().id(),
                      job.token(),
                      index,
                      (index == 0 ? "a" : "b").repeat(64),
                      4192,
                      job.plan());
                }));
      }
      assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      var results =
          List.of(pending.get(0).get(2, TimeUnit.SECONDS), pending.get(1).get(2, TimeUnit.SECONDS));
      assertThat(results.stream().filter(r -> r.callId() != null).count()).isEqualTo(1);
      assertThat(results.stream().filter(r -> "MODEL_BUDGET_EXCEEDED".equals(r.failure())).count())
          .isEqualTo(1);
      assertThat(ledger.view(job.job().id(), 1, 20).budget().reservedUnits()).isEqualTo(4192);
      for (var result : results)
        if (result.callId() != null) ledger.abort(result.callId(), "TEST_NOT_SENT");
      assertThat(ledger.view(job.job().id(), 1, 20).budget().reservedUnits()).isZero();
      jobs.fail(job.job().id(), job.token());
    }
  }

  /** 两个实际出站线程被占用时第三个请求不排无限队列，未发送的预留会被释放。 */
  @Test
  void outboundCapacityIsBoundedAndRejectedReservationIsReleased() throws Exception {
    var entered = new CountDownLatch(2);
    var finish = new CountDownLatch(1);
    try (var stub =
            new Stub(
                body -> {
                  entered.countDown();
                  try {
                    finish.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return valid(body);
                });
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())));
        var pool = Executors.newFixedThreadPool(2)) {
      var first = claim(stubPlan(3500, 100000), 1);
      var second = claim(stubPlan(3500, 100000), 1);
      var third = claim(stubPlan(3500, 100000), 1);
      try (var lease1 = leases.keep(first.job().id(), first.token());
          var lease2 = leases.keep(second.job().id(), second.token());
          var lease3 = leases.keep(third.job().id(), third.token())) {
        var one =
            pool.submit(
                () ->
                    local.classify(
                        first.job().id(), first.token(), first.plan(), request(first, 0)));
        var two =
            pool.submit(
                () ->
                    local.classify(
                        second.job().id(), second.token(), second.plan(), request(second, 0)));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(
                () ->
                    local.classify(
                        first.job().id(), first.token(), first.plan(), request(first, 0)))
            .hasMessage("MODEL_BATCH_BUSY");
        assertThatThrownBy(
                () ->
                    local.classify(
                        third.job().id(), third.token(), third.plan(), request(third, 0)))
            .hasMessage("MODEL_CAPACITY");
        assertThat(ledger.view(third.job().id(), 1, 10).budget().reservedUnits()).isZero();
        assertThat(
                ledger
                    .view(third.job().id(), 1, 10)
                    .items()
                    .getFirst()
                    .recentCalls()
                    .getFirst()
                    .state())
            .isEqualTo("RELEASED");
        finish.countDown();
        one.get(2, TimeUnit.SECONDS);
        two.get(2, TimeUnit.SECONDS);
        assertThat(stub.requests.get()).isEqualTo(2);
        jobs.fail(first.job().id(), first.token());
        jobs.fail(second.job().id(), second.token());
        jobs.fail(third.job().id(), third.token(), "MODEL_CAPACITY");
      } finally {
        finish.countDown();
      }
    }
  }

  /** 调用方不能在首次出站前把已保存计划的预算或单价悄悄换掉。 */
  @Test
  void savedPlanCannotBeRepricedByCaller() throws Exception {
    try (var stub = new Stub(this::valid);
        var local =
            new ModelGateway(ledger, json, List.of(new LocalStubHttpProvider(stub.endpoint())))) {
      var job = claim(stubPlan(1000, 100000), 1);
      var changed =
          new ModelContract.Plan(
              "LOCAL_HTTP_STUB",
              "local-http-stub-v1",
              "stub-prompt-v1",
              "sentiment-v1",
              1,
              1000,
              32,
              "SIMULATED_UNITS",
              "changed-price",
              0,
              0,
              1000000);
      assertThatThrownBy(
              () -> local.classify(job.job().id(), job.token(), changed, request(job, 0)))
          .hasMessage("MODEL_PLAN_CHANGED");
      assertThat(stub.requests.get()).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, job.job().id()))
          .isZero();
      jobs.fail(job.job().id(), job.token(), "MODEL_PLAN_CHANGED");
    }
  }

  /** 讨论抽取与分类共用调用账本；成功检查点复用，证据身份仍由网关校验。 */
  @Test
  void discussionUsesSharedLedgerAndReusesValidatedCheckpoint() {
    var calls = new AtomicInteger();
    DiscussionModelContract.Provider provider =
        new DiscussionModelContract.Provider() {
          @Override
          public String id() {
            return "SPRING_AI";
          }

          @Override
          public String modelVersion() {
            return "discussion-test-model";
          }

          @Override
          public void validatePlan(ModelContract.Plan plan) {
            if (!"discussion-test-prompt".equals(plan.promptVersion()))
              throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
          }

          @Override
          public DiscussionModelContract.Response analyze(
              DiscussionModelContract.Request request, java.time.Duration timeout) {
            calls.incrementAndGet();
            return new DiscussionModelContract.Response(
                List.of(
                    new DiscussionModelContract.Issue(
                        "issue-1", "为什么这样选择？", "评论围绕选择依据展开。", List.of("c0"))),
                List.of(
                    new DiscussionModelContract.Claim(
                        "claim-1",
                        "issue-1",
                        "世界线变化限制了选择。",
                        DiscussionModelContract.ReasoningStatus.REASONED,
                        List.of("c0"))),
                List.of(),
                List.of(
                    new DiscussionModelContract.Assessment(
                        "issue-1",
                        DiscussionModelContract.Verdict.INSUFFICIENT,
                        "当前只有一个观点。",
                        "缺少可比较的反方论据。",
                        List.of("c0"))),
                10,
                2,
                "discussion-request-1");
          }
        };
    var plan =
        new ModelContract.Plan(
            "SPRING_AI",
            "discussion-test-model",
            "discussion-test-prompt",
            "argument-map-v1",
            1,
            2000,
            512,
            "USD_NANOS",
            "discussion-test-price",
            1,
            1,
            1000000,
            1000,
            "discussion-test-model",
            "MODEL");
    var job = claim(plan, 1);
    var request =
        new DiscussionModelContract.Request(
            job.job().snapshotId(),
            "总结观点并判断谁更有道理",
            "讨论测试",
            "SYNTHETIC",
            true,
            1,
            List.of(new DiscussionModelContract.Text("c0", null, job.input().comments().getFirst().text())),
            1000,
            512);
    try (var local = new ModelGateway(ledger, json, List.of(), List.of(provider))) {
      assertThat(local.discuss(job.job().id(), job.token(), plan, request).issues()).hasSize(1);
      assertThat(local.discuss(job.job().id(), job.token(), plan, request).issues()).hasSize(1);
      assertThat(calls.get()).isEqualTo(1);
      assertThat(ledger.view(job.job().id(), 1, 10).budget().settledUnits()).isEqualTo(12);
      jobs.fail(job.job().id(), job.token());
    }
  }
}
