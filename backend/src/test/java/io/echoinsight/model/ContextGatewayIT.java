/* 文件职责：验证角色输入的出站边界、背景不能独自证明观众观点，以及原账本检查点与未知费用语义。 */
package io.echoinsight.model;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.*;
import io.echoinsight.model.springai.DiscussionPrompt;
import io.echoinsight.orchestration.JobStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 供应商完全为内存替身；USD_NANOS只验证计账单位，测试不会连接付费平台。 */
@SpringBootTest(
    properties = {
      "echoinsight.projects.workspace=context-gateway-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ContextGatewayIT {
  @Autowired JobStore jobs;
  @Autowired ImportService imports;
  @Autowired ModelLedger ledger;
  @Autowired ObjectMapper json;

  /** 合法评论＋背景引用只物理调用一次，复用检查点后保持用量结算不重复。 */
  @Test
  void validContextUsesExistingCheckpointAndBudget() {
    var plan = plan();
    var job = job(plan);
    String token = jobs.claim(job.id());
    var calls = new AtomicInteger();
    try (var gateway =
        new ModelGateway(
            ledger,
            json,
            List.of(),
            List.of(provider(calls, List.of("comment-1", "context-reference-1"))))) {
      var input = request(job.snapshotId(), "context-reference-1");
      gateway.discuss(job.id(), token, plan, input);
      gateway.discuss(job.id(), token, plan, input);
      assertThat(calls).hasValue(1);
      assertThat(ledger.view(job.id(), 1, 10).budget().settledUnits()).isEqualTo(12);
      jobs.fail(job.id(), token, "SYNTHETIC_COMPLETE");
    }
  }

  /** 背景ID与评论碰撞在费用预留前拒绝，无出站、无账本批次。 */
  @Test
  void invalidContextBoundaryIsRejectedBeforeReservation() {
    var plan = plan();
    var job = job(plan);
    String token = jobs.claim(job.id());
    var calls = new AtomicInteger();
    try (var gateway =
        new ModelGateway(ledger, json, List.of(), List.of(provider(calls, List.of("comment-1"))))) {
      assertThatThrownBy(
              () -> gateway.discuss(job.id(), token, plan, request(job.snapshotId(), "comment-1")))
          .isInstanceOfSatisfying(
              ModelContract.Failure.class,
              e -> assertThat(e.code()).isEqualTo("MODEL_INVALID_REQUEST_BOUNDARY"));
      assertThat(calls).hasValue(0);
      assertThat(ledger.view(job.id(), 1, 10).total()).isZero();
      jobs.fail(job.id(), token, "SYNTHETIC_REJECTED");
    }
  }

  /** 只有背景引用的观众观点不发布；已发送后的非法响应仍保留未知费用，不能免费自动重试。 */
  @Test
  void backgroundOnlyOpinionCannotPassAndSentCostRemainsUnknown() {
    var plan = plan();
    var job = job(plan);
    String token = jobs.claim(job.id());
    var calls = new AtomicInteger();
    try (var gateway =
        new ModelGateway(
            ledger, json, List.of(), List.of(provider(calls, List.of("context-reference-1"))))) {
      assertThatThrownBy(
              () ->
                  gateway.discuss(
                      job.id(), token, plan, request(job.snapshotId(), "context-reference-1")))
          .isInstanceOfSatisfying(
              ModelContract.Failure.class,
              e -> assertThat(e.code()).isEqualTo("MODEL_OUTCOME_UNKNOWN"));
      var saved = ledger.view(job.id(), 1, 10);
      assertThat(saved.items().getFirst().status()).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(saved.budget().reservedUnits()).isGreaterThan(0);
      assertThat(calls).hasValue(1);
      jobs.fail(job.id(), token, "MODEL_OUTCOME_UNKNOWN");
    }
  }

  /** 不接网络的供应商，仍由ModelGateway负责响应验证和计账。 */
  private DiscussionModelContract.Provider provider(AtomicInteger calls, List<String> ids) {
    return new DiscussionModelContract.Provider() {
      public String id() {
        return "SPRING_AI";
      }

      public String modelVersion() {
        return "context-local";
      }

      public void validatePlan(ModelContract.Plan plan) {}

      public DiscussionModelContract.Response analyze(
          DiscussionModelContract.Request input, Duration timeout) {
        calls.incrementAndGet();
        return new DiscussionModelContract.Response(
            List.of(new DiscussionModelContract.Issue("i", "问题", "说明", ids)),
            List.of(
                new DiscussionModelContract.Claim(
                    "c", "i", "观众观点", DiscussionModelContract.ReasoningStatus.ASSERTED, ids)),
            List.of(),
            List.of(),
            10,
            2,
            "local-request");
      }
    };
  }

  /** 所有调用都使用同一显式v4计划，预算足够但供应商为内存替身。 */
  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "context-local",
        DiscussionPrompt.CONTEXT_VERSION,
        DiscussionPrompt.CONTEXT_TAXONOMY,
        1,
        3000,
        512,
        "USD_NANOS",
        "synthetic",
        1,
        1,
        1000000,
        1000,
        "context-local",
        "MODEL");
  }

  /** 创建独立输入及原分析任务，执行范围由测试配置隔离。 */
  private JobStore.Job job(ModelContract.Plan plan) {
    var time = OffsetDateTime.parse("2026-10-17T08:00:00Z");
    var input =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content(
                "local", "SYNTHETIC", UUID.randomUUID().toString(), "fixture", "角色网关合成验收", time),
            List.of(new ImportDocument.Comment("original", "original", null, "观众评论", time)),
            List.of());
    return jobs.create(imports.ingest(input).snapshotId(), UUID.randomUUID().toString(), plan);
  }

  /** 单独背景ID使测试能够切换为碰撞输入，而不改服务器凭据或其他配置。 */
  private DiscussionModelContract.Request request(String snapshot, String backgroundId) {
    return new DiscussionModelContract.Request(
        snapshot,
        "总结观点",
        "标题",
        "SYNTHETIC",
        false,
        1,
        List.of(new DiscussionModelContract.Text("comment-1", null, "评论")),
        1000,
        512,
        List.of(
            new DiscussionModelContract.ContextText(
                backgroundId, "REFERENCE_TEXT", "背景", "用户说明", null, null, "参考文本")),
        DiscussionPrompt.CONTEXT_VERSION);
  }
}
