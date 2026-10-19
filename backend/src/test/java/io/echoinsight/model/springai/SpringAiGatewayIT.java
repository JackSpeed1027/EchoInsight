/* 文件职责：用真实 Spring AI SDK 和回环 HTTP 验证批次账本、禁重试、缺用量及坏响应边界。 */
package io.echoinsight.model.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportService;
import io.echoinsight.model.BatchClassifier;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelGateway;
import io.echoinsight.model.ModelLedger;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.orchestration.LeaseKeeper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 只追加独立合成来源；任务创建与领取同事务，防止用户开发 Worker 抢占测试任务。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.worker.enabled=false",
      "echoinsight.worker.lease-seconds=2",
      "echoinsight.worker.heartbeat-ms=100"
    })
@ExtendWith(OutputCaptureExtension.class)
class SpringAiGatewayIT {
  private static final String MODEL = "spring-ai-local-v1";
  @Autowired JobStore jobs;
  @Autowired ImportService imports;
  @Autowired io.echoinsight.infrastructure.persistence.ImportMapper importMapper;
  @Autowired ModelLedger ledger;
  @Autowired LeaseKeeper leases;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;

  /** 返回代码同时需要的任务、执行资格和不可变测试输入。 */
  private record Claimed(
      JobStore.Job job, String token, ImportDocument input, ModelContract.Plan plan) {}

  /** 一次 HTTP 响应行为；状态、正文与等待时间由各案例指定。 */
  private record Reply(int status, String body, long delayMs) {}

  /** 创建唯一合成材料并原子领取；费用全为模拟单位，不连接真实模型供应商。 */
  private Claimed claim(int count, long timeout) {
    var created = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int index = 0; index < count; index++) {
      comments.add(
          new ImportDocument.Comment(
              "c" + index,
              "source" + index,
              null,
              "PRIVATE_PROMPT_SENTINEL 合成材料，不是真实用户评论。",
              created));
    }
    var input =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content(
                "local",
                "SYNTHETIC",
                "spring-ai-it-" + UUID.randomUUID(),
                "test",
                "Spring AI 协议合成试验",
                created),
            comments,
            List.of());
    var plan =
        new ModelContract.Plan(
            SpringAiClassifierProvider.ID,
            MODEL,
            SentimentPrompt.VERSION,
            SentimentPrompt.TAXONOMY,
            1,
            timeout,
            64,
            "SIMULATED_UNITS",
            "spring-ai-test-price-v1",
            1,
            1,
            100000);
    String snapshotId = imports.ingest(input).snapshotId();
    return new TransactionTemplate(transactions)
        .execute(
            status -> {
              var job = jobs.create(snapshotId, UUID.randomUUID().toString(), plan);
              return new Claimed(job, jobs.claim(job.id()), input, plan);
            });
  }

  /** 单批请求直接使用本任务评论；显式输入上界只用于本机模拟账本，不是实际分词器。 */
  private ModelContract.Request request(Claimed claimed) {
    var comment = claimed.input().comments().getFirst();
    return new ModelContract.Request(
        claimed.job().snapshotId(),
        0,
        "SYNTHETIC",
        List.of(new ModelContract.Text(comment.id(), comment.text(), null, null)),
        2048,
        claimed.plan().maxOutputTokens());
  }

  /** 为每个 HTTP 请求生成对应评论的标签，验证真实 SDK 的请求编码和响应转换。 */
  private String valid(JsonNode request) {
    try {
      JsonNode data = json.readTree(request.path("messages").get(1).path("content").asText());
      String id = data.path("comments").get(0).path("commentId").asText();
      return envelope(
          "{\"classifications\":[{\"commentId\":\"" + id + "\",\"label\":\"UNKNOWN\"}]}");
    } catch (Exception error) {
      throw new IllegalStateException("Invalid local test request");
    }
  }

  /** 模拟 OpenAI 兼容协议外壳；用量 100/5/105 都是测试输入，不是实际模型消耗。 */
  private String envelope(String content) {
    return ModelContract.encode(
        Map.of(
            "id",
            "local-spring-request",
            "object",
            "chat.completion",
            "created",
            1770000000,
            "model",
            MODEL,
            "choices",
            List.of(
                Map.of(
                    "index",
                    0,
                    "message",
                    Map.of("role", "assistant", "content", content),
                    "finish_reason",
                    "stop")),
            "usage",
            Map.of("prompt_tokens", 100, "completion_tokens", 5, "total_tokens", 105)));
  }

  /** 本机服务记录真实 HTTP 次数与请求结构；结束只停止自身端口和线程。 */
  private final class Stub implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();

    /** 在回环动态端口提供 SDK 所用的固定 /v1/chat/completions 路径。 */
    private Stub(Function<JsonNode, Reply> action) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            try {
              JsonNode request = json.readTree(exchange.getRequestBody().readNBytes(65536));
              lastRequest.set(request);
              calls.incrementAndGet();
              Reply reply = action.apply(request);
              if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
              }
              if (reply.status() == 302) {
                exchange.getResponseHeaders().set("Location", "http://example.invalid/private");
              }
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(reply.status(), bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } catch (Exception ignored) {
              // 超时客户端可能已关闭连接；测试不打印原始请求或响应。
            } finally {
              exchange.close();
            }
          });
      server.start();
    }

    /** 将真实 SDK 实例显式交给测试网关；应用上下文的生产供应商仍只有 Mock。 */
    private ModelGateway gateway(long timeout) {
      var provider =
          LocalSpringAiFactory.create(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
              MODEL,
              Duration.ofMillis(timeout));
      return new ModelGateway(ledger, json, List.of(provider));
    }

    /** 释放本测试资源，数据库历史、用户服务和本轮记录全部保留。 */
    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  /** SDK 经批次分类器和账本完整走通，第二次读取复用已成功批次而不重发 HTTP。 */
  @Test
  void sdkFlowsThroughBatchCheckpointsAndSettlesOnlyValidatedUsage() throws Exception {
    try (var stub = new Stub(body -> new Reply(200, valid(body), 0));
        var gateway = stub.gateway(3000)) {
      var claimed = claim(2, 3000);
      try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
        var classifier = new BatchClassifier(jobs, gateway);
        var result =
            classifier.classify(claimed.job(), claimed.token(), claimed.input(), lease::check);
        assertThat(result).hasSize(2);
        assertThat(stub.calls.get()).isEqualTo(2);
        classifier.classify(claimed.job(), claimed.token(), claimed.input(), lease::check);
        assertThat(stub.calls.get()).isEqualTo(2);
        var view = ledger.view(claimed.job().id(), 1, 20);
        assertThat(view.budget().settledUnits()).isEqualTo(210);
        assertThat(view.budget().reservedUnits()).isZero();
        assertThat(view.items())
            .allSatisfy(
                batch -> {
                  assertThat(batch.status()).isEqualTo("SUCCEEDED");
                  assertThat(batch.attempts()).isEqualTo(1);
                  assertThat(batch.recentCalls().getFirst().inputTokens()).isEqualTo(100);
                });
        assertThat(stub.lastRequest.get().path("model").asText()).isEqualTo(MODEL);
        assertThat(stub.lastRequest.get().path("max_tokens").asInt()).isEqualTo(64);
        assertThat(stub.lastRequest.get().has("tools")).isFalse();
      }
    }
  }

  /** 固定计划的提示与分类版本必须对应真实模板，错误版本不能预留额度或访问 SDK。 */
  @Test
  void mismatchedPromptPlanIsRejectedBeforeLedgerOrHttp() throws Exception {
    try (var stub = new Stub(body -> new Reply(200, valid(body), 0));
        var gateway = stub.gateway(3000)) {
      var claimed = claim(1, 3000);
      var plan = claimed.plan();
      var wrong =
          new ModelContract.Plan(
              plan.providerId(),
              plan.modelVersion(),
              "old-prompt",
              plan.taxonomyVersion(),
              plan.batchSize(),
              plan.timeoutMs(),
              plan.maxOutputTokens(),
              plan.unit(),
              plan.priceVersion(),
              plan.inputUnitRate(),
              plan.outputUnitRate(),
              plan.budgetLimit());
      try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
        assertThatThrownBy(
                () ->
                    gateway.classify(claimed.job().id(), claimed.token(), wrong, request(claimed)))
            .isInstanceOfSatisfying(
                ModelContract.Failure.class,
                error -> assertThat(error.code()).isEqualTo("MODEL_UNSUPPORTED_VERSION"));
        assertThat(stub.calls.get()).isZero();
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM model_budget WHERE job_id=?",
                    Integer.class,
                    claimed.job().id()))
            .isZero();
      }
    }
  }

  /** 真实 SDK 收到 429/503 也不自动补发请求；未知尝试不能直接重试并释放预留。 */
  @Test
  void sdkDoesNotRetryRateLimitOrServerErrors() throws Exception {
    for (int status : List.of(429, 503)) {
      try (var stub =
              new Stub(body -> new Reply(status, "{\"error\":{\"message\":\"private\"}}", 0));
          var gateway = stub.gateway(3000)) {
        var claimed = claim(1, 3000);
        try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
          assertThatThrownBy(
                  () ->
                      gateway.classify(
                          claimed.job().id(), claimed.token(), claimed.plan(), request(claimed)))
              .isInstanceOfSatisfying(
                  ModelContract.Failure.class,
                  error -> assertThat(error.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN"));
          assertThat(stub.calls.get()).isEqualTo(1);
          assertThatThrownBy(
                  () ->
                      gateway.classify(
                          claimed.job().id(), claimed.token(), claimed.plan(), request(claimed)))
              .isInstanceOf(ModelContract.Failure.class);
          assertThat(stub.calls.get()).isEqualTo(1);
          assertThat(ledger.view(claimed.job().id(), 1, 20).budget().reservedUnits())
              .isEqualTo(2112);
        }
      }
    }
  }

  /** SDK 解析后仍须检查原始用量、结束原因及逐条身份，任一错误都不能进入成功检查点。 */
  @Test
  void sdkInvalidUsageAndLabelsKeepUnknownOutcome() throws Exception {
    AtomicReference<String> body = new AtomicReference<>();
    try (var stub = new Stub(request -> new Reply(200, body.get(), 0));
        var gateway = stub.gateway(3000)) {
      for (String variant :
          List.of(
              "no-usage",
              "missing-input",
              "missing-output",
              "missing-total",
              "duplicate",
              "outside",
              "truncated",
              "tool-call")) {
        var claimed = claim(1, 3000);
        var envelope =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree(
                    envelope(
                        "{\"classifications\":[{\"commentId\":\"c0\",\"label\":\"UNKNOWN\"}]}"));
        switch (variant) {
          case "no-usage" -> envelope.remove("usage");
          case "missing-input" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("usage"))
                  .remove("prompt_tokens");
          case "missing-output" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("usage"))
                  .remove("completion_tokens");
          case "missing-total" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("usage"))
                  .remove("total_tokens");
          case "duplicate" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode)
                      envelope.path("choices").get(0).path("message"))
                  .put(
                      "content",
                      "{\"classifications\":[{\"commentId\":\"c0\",\"label\":\"UNKNOWN\"},"
                          + "{\"commentId\":\"c0\",\"label\":\"UNKNOWN\"}]}");
          case "outside" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode)
                      envelope.path("choices").get(0).path("message"))
                  .put(
                      "content",
                      "{\"classifications\":[{\"commentId\":\"not-this-snapshot\",\"label\":\"UNKNOWN\"}]}");
          case "truncated" ->
              ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("choices").get(0))
                  .put("finish_reason", "length");
          case "tool-call" -> {
            var message =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                    envelope.path("choices").get(0).path("message");
            message.put("content", "");
            message.set(
                "tool_calls",
                json.valueToTree(
                    List.of(
                        Map.of(
                            "id",
                            "t1",
                            "type",
                            "function",
                            "function",
                            Map.of("name", "not_registered", "arguments", "{}")))));
            ((com.fasterxml.jackson.databind.node.ObjectNode) envelope.path("choices").get(0))
                .put("finish_reason", "tool_calls");
          }
          default -> throw new IllegalStateException("Unknown test case");
        }
        body.set(envelope.toString());
        try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
          assertThatThrownBy(
                  () ->
                      gateway.classify(
                          claimed.job().id(), claimed.token(), claimed.plan(), request(claimed)))
              .isInstanceOfSatisfying(
                  ModelContract.Failure.class,
                  failure -> assertThat(failure.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN"));
          var call =
              ledger.view(claimed.job().id(), 1, 20).items().getFirst().recentCalls().getFirst();
          assertThat(call.state()).isEqualTo("OUTCOME_UNKNOWN");
          assertThat(call.inputTokens()).isNull();
          assertThat(call.outputTokens()).isNull();
          assertThat(
                  db.queryForObject(
                      "SELECT COUNT(*) FROM analysis_report WHERE job_id=?",
                      Integer.class,
                      claimed.job().id()))
              .isZero();
        }
      }
    }
  }

  /** 请求已经到达本机服务后超时，未知状态与预留必须保留，不能被晚到响应重新结算。 */
  @Test
  void sdkTimeoutKeepsReservationAndLateResponseCannotCommit() throws Exception {
    CountDownLatch arrived = new CountDownLatch(1);
    try (var stub =
            new Stub(
                body -> {
                  arrived.countDown();
                  return new Reply(200, valid(body), 500);
                });
        var gateway = stub.gateway(1000)) {
      var claimed = claim(1, 100);
      try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
        assertThatThrownBy(
                () ->
                    gateway.classify(
                        claimed.job().id(), claimed.token(), claimed.plan(), request(claimed)))
            .isInstanceOfSatisfying(
                ModelContract.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN"));
        assertThat(arrived.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(650);
        var view = ledger.view(claimed.job().id(), 1, 20);
        assertThat(view.budget().settledUnits()).isZero();
        assertThat(view.budget().reservedUnits()).isEqualTo(2112);
        assertThat(view.items().getFirst().status()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(stub.calls.get()).isEqualTo(1);
      }
    }
  }

  /** 有界读取、禁止跳转和空 choices 预检发生在 SDK 之前，避免其输出含原文的 Prompt 日志。 */
  @Test
  void sdkTransportRejectsOversizedRedirectAndEmptyEnvelopeWithoutPromptLog(CapturedOutput output)
      throws Exception {
    for (Reply reply :
        List.of(
            new Reply(200, "{\"padding\":\"" + "x".repeat(1024 * 1024) + "\"}", 0),
            new Reply(302, "redirect", 0),
            new Reply(503, "{\"error\":\"PRIVATE_PROMPT_SENTINEL\"}", 0),
            new Reply(200, "{\"choices\":[]}", 0))) {
      try (var stub = new Stub(body -> reply);
          var gateway = stub.gateway(3000)) {
        var claimed = claim(1, 3000);
        try (var lease = leases.keep(claimed.job().id(), claimed.token())) {
          assertThatThrownBy(
                  () ->
                      gateway.classify(
                          claimed.job().id(), claimed.token(), claimed.plan(), request(claimed)))
              .isInstanceOf(ModelContract.Failure.class);
          assertThat(stub.calls.get()).isEqualTo(1);
          assertThat(ledger.view(claimed.job().id(), 1, 20).items().getFirst().status())
              .isEqualTo("OUTCOME_UNKNOWN");
        }
      }
    }
    assertThat(output.getAll()).doesNotContain("PRIVATE_PROMPT_SENTINEL");
  }

  /**
   * 1、构造美元估算的本机替身计划；2、完整执行 Worker；3、核对账本和报告；4、确认不冒充真实模型。 同时检查默认 JSON 和禁思考参数经过实际 Spring AI
   * 请求合并后仍然存在。
   */
  @Test
  void localCurrencyPipelinePublishesHonestSourceAndPinnedOptions() throws Exception {
    try (var stub = new Stub(body -> new Reply(200, valid(body), 0))) {
      var original = claim(1, 3000);
      // 原测试材料可复用；新任务有独立请求键和固定来源。旧测试任务的历史全部保留。
      jobs.fail(original.job().id(), original.token());
      var plan =
          new ModelContract.Plan(
              "SPRING_AI",
              MODEL,
              SentimentPrompt.VERSION,
              SentimentPrompt.TAXONOMY,
              1,
              3000,
              64,
              "USD_NANOS",
              "local-test-price-v1",
              2,
              3,
              100000,
              2048,
              MODEL,
              "LOCAL_STUB");
      var api =
          org.springframework.ai.openai.api.OpenAiApi.builder()
              .baseUrl("http://127.0.0.1:" + stub.server.getAddress().getPort())
              .apiKey("local-stub-unused")
              .restClientBuilder(BoundedModelHttp.create(Duration.ofSeconds(3)))
              .build();
      var sdk =
          org.springframework.ai.openai.OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(DeepSeekSpringAiFactory.options(MODEL))
              .retryTemplate(
                  org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
              .build();
      try (var gateway =
          new ModelGateway(ledger, json, List.of(new SpringAiClassifierProvider(sdk, MODEL)))) {
        var worker =
            new io.echoinsight.orchestration.AnalysisWorker(
                jobs,
                importMapper,
                new BatchClassifier(jobs, gateway),
                new io.echoinsight.report.ReportBuilder(),
                json,
                leases);
        var job = jobs.create(original.job().snapshotId(), UUID.randomUUID().toString(), plan);
        worker.runOne(job.id());
        assertThat(jobs.get(job.id()).status()).isEqualTo("SUCCESS");
        var body = json.readTree(jobs.reportForJob(job.id()).json());
        assertThat(body.path("version").asInt()).isEqualTo(2);
        assertThat(body.path("provenance").path("mode").asText()).isEqualTo("LOCAL_STUB");
        assertThat(body.path("provenance").path("verifiedResponseModel").asText()).isEqualTo(MODEL);
        assertThat(body.path("provenance").path("billingUnit").asText()).isEqualTo("USD_NANOS");
        var view = ledger.view(job.id(), 1, 20);
        assertThat(view.budget().unit()).isEqualTo("USD_NANOS");
        assertThat(view.budget().settledUnits()).isEqualTo(215);
        assertThat(view.budget().reservedUnits()).isZero();
        assertThat(stub.calls.get()).isEqualTo(1);
        assertThat(stub.lastRequest.get().path("response_format").path("type").asText())
            .isEqualTo("json_object");
        assertThat(stub.lastRequest.get().path("thinking").path("type").asText())
            .isEqualTo("disabled");
        assertThat(stub.lastRequest.get().has("tools")).isFalse();
      }
    }
  }

  /** 不允许调用方降低已固定的 Token 上界来少预留额度；拒绝发生在账本和 HTTP 之前。 */
  @Test
  void pinnedCurrencyBoundaryCannotBeReducedByCaller() throws Exception {
    try (var stub = new Stub(body -> new Reply(200, valid(body), 0));
        var gateway = stub.gateway(3000)) {
      var original = claim(1, 3000);
      jobs.fail(original.job().id(), original.token());
      var plan =
          new ModelContract.Plan(
              "SPRING_AI",
              MODEL,
              SentimentPrompt.VERSION,
              SentimentPrompt.TAXONOMY,
              1,
              3000,
              64,
              "USD_NANOS",
              "local-test-v1",
              2,
              3,
              100000,
              4096,
              MODEL,
              "LOCAL_STUB");
      var claimed =
          new TransactionTemplate(transactions)
              .execute(
                  status -> {
                    var job =
                        jobs.create(
                            original.job().snapshotId(), UUID.randomUUID().toString(), plan);
                    return new Claimed(job, jobs.claim(job.id()), original.input(), plan);
                  });
      assertThatThrownBy(
              () -> gateway.classify(claimed.job().id(), claimed.token(), plan, request(claimed)))
          .isInstanceOfSatisfying(
              ModelContract.Failure.class,
              failure -> assertThat(failure.code()).isEqualTo("MODEL_INVALID_REQUEST_BOUNDARY"));
      assertThat(stub.calls.get()).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM model_budget WHERE job_id=?",
                  Integer.class,
                  claimed.job().id()))
          .isZero();
    }
  }
}
