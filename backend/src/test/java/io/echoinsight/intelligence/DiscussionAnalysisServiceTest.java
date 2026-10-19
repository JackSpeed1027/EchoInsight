/* 文件职责：验证真实讨论模型结果会映射成带原文证据、评议边界和明确限制的长期产物。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.DiscussionModelContract;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelGateway;
import io.echoinsight.orchestration.JobStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 本测试使用内存替身，不联网、不访问数据库，也不把合成结论当成模型质量。 */
class DiscussionAnalysisServiceTest {
  /** MODEL 结果只能引用快照原文，且完整范围与外部事实限制会进入产物。 */
  @Test
  void mapsValidatedModelResponseToTraceableArtifact() {
    var models = mock(ModelGateway.class);
    var structural = mock(StructuralDiscussionAnalyzer.class);
    var service = new DiscussionAnalysisService(structural, models, new ThreadContextSelector());
    var plan = plan();
    when(models.discuss(eq("job-1"), eq("token-1"), eq(plan), any()))
        .thenReturn(
            new DiscussionModelContract.Response(
                List.of(
                    new DiscussionModelContract.Issue(
                        "i1", "为什么这样选择？", "讨论围绕选择约束。", List.of("c1", "c2"))),
                List.of(
                    new DiscussionModelContract.Claim(
                        "a1",
                        "i1",
                        "世界线变化限制了选择。",
                        DiscussionModelContract.ReasoningStatus.REASONED,
                        List.of("c2"))),
                List.of(),
                List.of(
                    new DiscussionModelContract.Assessment(
                        "i1",
                        DiscussionModelContract.Verdict.INSUFFICIENT,
                        "当前只有一方给出理由。",
                        "缺少可比较的另一方论据。",
                        List.of("c2"))),
                50,
                20,
                "provider-request"));
    var checks = new AtomicInteger();

    var artifact =
        service.analyze(
            job(plan), "token-1", plan, request(), input(), "artifact-1", checks::incrementAndGet);

    assertThat(artifact.schemaVersion()).isEqualTo("discussion-artifact-v3");
    assertThat(artifact.scope().selectedComments()).isEqualTo(2);
    assertThat(artifact.scope().selectionVersion()).isEqualTo(ThreadContextSelector.VERSION);
    assertThat(artifact.sourceMode()).isEqualTo("MODEL");
    assertThat(artifact.scope().truncated()).isFalse();
    assertThat(artifact.evidence())
        .extracting(DiscussionArtifact.Evidence::text)
        .containsExactly("为什么这样选择？", "因为世界线变化限制了选择。");
    assertThat(artifact.limitations()).anyMatch(text -> text.contains("外部事实"));
    assertThat(checks.get()).isEqualTo(2);
    verifyNoInteractions(structural);
  }

  /** 描述性话题不能被包装成输赢；误写在正文中的内部ID会转成证据引用并从展示文本移除。 */
  @Test
  void normalizesUnsupportedComparisonAndInternalIds() {
    var models = mock(ModelGateway.class);
    var service =
        new DiscussionAnalysisService(
            mock(StructuralDiscussionAnalyzer.class), models, new ThreadContextSelector());
    var plan = plan();
    when(models.discuss(eq("job-1"), eq("token-1"), eq(plan), any()))
        .thenReturn(
            new DiscussionModelContract.Response(
                List.of(
                    new DiscussionModelContract.Issue(
                        "i1", "大家喜欢什么？", "评论表达了共同感受。", List.of("c1"))),
                List.of(
                    new DiscussionModelContract.Claim(
                        "a1",
                        "i1",
                        "观众喜欢作品设定。",
                        DiscussionModelContract.ReasoningStatus.ASSERTED,
                        List.of("c1"))),
                List.of(),
                List.of(
                    new DiscussionModelContract.Assessment(
                        "i1",
                        DiscussionModelContract.Verdict.MORE_SUPPORTED,
                        "c1 比 c2 更有道理。",
                        "c2 给出了更充分的说明。",
                        List.of())),
                50,
                20,
                "provider-request"));

    var artifact =
        service.analyze(job(plan), "token-1", plan, request(), input(), "artifact-1", () -> {});

    assertThat(artifact.assessments())
        .singleElement()
        .satisfies(
            assessment -> {
              assertThat(assessment.verdict()).isEqualTo("INSUFFICIENT");
              assertThat(assessment.conclusion()).doesNotContain("c1", "c2");
              assertThat(assessment.reason()).doesNotContain("c1", "c2");
              assertThat(assessment.evidenceIds()).containsExactly("c1", "c2");
            });
    assertThat(artifact.evidence())
        .extracting(DiscussionArtifact.Evidence::id)
        .containsExactly("c1", "c2");
  }

  /** 构造固定模型计划，避免测试用例重复非核心的定价与供应商字段。 */
  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "deepseek-flash",
        "discussion-json-v3",
        "argument-map-v1",
        1,
        60000,
        4096,
        "USD_NANOS",
        "test-price",
        1,
        2,
        1000000,
        128000,
        "deepseek-flash",
        "MODEL");
  }

  /** 构造已经取得执行租约的任务；计划身份必须和本轮调用一致。 */
  private JobStore.Job job(ModelContract.Plan plan) {
    return new JobStore.Job(
        "job-1",
        "snapshot-1",
        "DISCUSSION",
        "request-1",
        "RUNNING",
        1,
        0,
        "token-1",
        null,
        plan.modelVersion(),
        plan.promptVersion(),
        plan.taxonomyVersion());
  }

  /** 构造用户在项目内提交的自然语言分析请求。 */
  private AnalysisRequestStore.RequestRow request() {
    return new AnalysisRequestStore.RequestRow(
        "request-1",
        "project-1",
        "snapshot-1",
        "总结观点并判断谁更有道理",
        "DISCUSSION",
        true,
        "RUNNING",
        "job-1",
        null,
        null,
        "2026-10-16 15:00:00");
  }

  /** 构造包含父评论与回复的不可变资料快照。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-16T15:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("content-1", "BILIBILI", "BV-test", "owner", "命运石之门讨论", time),
        List.of(
            new ImportDocument.Comment("c1", "source-1", null, "为什么这样选择？", time),
            new ImportDocument.Comment(
                "c2", "source-2", "c1", "因为世界线变化限制了选择。", time.plusSeconds(1))),
        List.of());
  }
}
