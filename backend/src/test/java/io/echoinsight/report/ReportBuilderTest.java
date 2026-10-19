/* 文件职责：验证报告的分母、证据成员关系与演示模式声明。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.analysis.FixtureClassifier.*;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.orchestration.JobStore;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 不依赖数据库，直接验证发布前必须满足的报告规则。 */
class ReportBuilderTest {
  /** 构造包含一条评论的不可变输入样本。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("local", "USER_IMPORT", "x", "c", "标题", time),
        List.of(new ImportDocument.Comment("a", "1", null, "用户文本", time)),
        List.of());
  }

  /** 构造固定版本任务，避免测试隐式依赖运行配置。 */
  private JobStore.Job job() {
    return new JobStore.Job(
        "j",
        "s",
        "RUNNING",
        1,
        0,
        "token",
        null,
        JobStore.MODEL,
        JobStore.PROMPT,
        JobStore.TAXONOMY);
  }

  /** 无法判断的评论不进入已分类分母，零分母返回 null。 */
  @Test
  void unknownCommentsHaveNoInventedRatio() {
    var report =
        new ReportBuilder().build(job(), input(), List.of(new Classification("a", Label.UNKNOWN)));
    assertThat(report.metrics().positiveShareAmongClassified()).isNull();
    assertThat(report.coverage().unknown()).isEqualTo(1);
    assertThat(report.provenance().mode()).isEqualTo("MOCK");
    assertThat(report.findings()).isEmpty();
  }

  /** 非快照成员、重复分类与遗漏分类都不得发布。 */
  @Test
  void invalidOrIncompleteEvidenceCannotBePublished() {
    var builder = new ReportBuilder();
    assertThatThrownBy(
            () ->
                builder.build(
                    job(), input(), List.of(new Classification("missing", Label.POSITIVE))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> builder.build(job(), input(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                builder.build(
                    job(),
                    input(),
                    List.of(
                        new Classification("a", Label.POSITIVE),
                        new Classification("a", Label.POSITIVE))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 模型模式按固定计划发布，旧 Mock 不增加来源字段，不把替身或模型结果统一写成演示。 */
  @Test
  void distinguishesModelStubAndLegacyReportProvenance() {
    for (String mode : List.of("MODEL", "LOCAL_STUB")) {
      var plan =
          new io.echoinsight.model.ModelContract.Plan(
              "SPRING_AI",
              "deepseek-flash",
              "sentiment-json-v1",
              "sentiment-v1",
              1,
              3000,
              64,
              "USD_NANOS",
              "test-price-v1",
              1,
              2,
              10000,
              2048,
              "verified-model",
              mode);
      var job =
          new JobStore.Job(
              "j",
              "s",
              "RUNNING",
              1,
              0,
              "t",
              null,
              plan.modelVersion(),
              plan.promptVersion(),
              plan.taxonomyVersion());
      var report =
          new ReportBuilder()
              .build(job, input(), List.of(new Classification("a", Label.NEGATIVE)), plan);
      assertThat(report.provenance().mode()).isEqualTo(mode);
      assertThat(report.provenance().verifiedResponseModel()).isEqualTo("verified-model");
      assertThat(report.version()).isEqualTo(2);
      assertThat(report.findings().getFirst().claim()).doesNotContain("演示分类器");
      assertThat(report.limitations().getFirst()).contains(mode);
    }
    var legacy =
        new ReportBuilder().build(job(), input(), List.of(new Classification("a", Label.UNKNOWN)));
    assertThat(io.echoinsight.model.ModelContract.encode(legacy.provenance()))
        .doesNotContain("providerId", "billingUnit", "verifiedResponseModel", "priceVersion");
    assertThat(legacy.version()).isEqualTo(1);
  }
}
