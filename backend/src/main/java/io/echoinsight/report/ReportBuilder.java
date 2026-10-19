/* 文件职责：校验分类覆盖和原文证据，再用确定性程序生成注明来源的统计报告。 */
package io.echoinsight.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.echoinsight.analysis.FixtureClassifier.*;
import io.echoinsight.domain.FeedbackMetrics;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.ModelContract;
import io.echoinsight.orchestration.JobStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
/** 校验分类覆盖和原文证据，再用确定性程序生成注明来源的统计报告。 */
public class ReportBuilder {
  /** 结论及其在当前快照中的证据标识。 */
  public record Finding(String claim, List<String> evidenceIds) {}

  /** 明确已分类、未知与源平台覆盖范围。 */
  public record Coverage(
      int importedComments,
      int eligibleComments,
      int classifiedComments,
      int unknown,
      Integer sourceTotalComments,
      Double sourceCoverage) {
    /** 报告只知道绑定快照的评论数，不能反推某次上传去重前的行数。 */
    @com.fasterxml.jackson.annotation.JsonProperty("snapshotCommentCount")
    public int snapshotCommentCount() {
      return eligibleComments;
    }
  }

  /** 由程序计算的数量和比例。 */
  public record Metrics(
      int positive, int negative, int neutral, Double positiveShareAmongClassified) {}

  /** 固定分析来源、经逐批核对的响应身份和计费口径；不是语义质量认证。 */
  public record Provenance(
      String modelVersion,
      String promptVersion,
      String taxonomyVersion,
      String generatedAt,
      String mode,
      @JsonInclude(JsonInclude.Include.NON_NULL) String providerId,
      @JsonInclude(JsonInclude.Include.NON_NULL) String verifiedResponseModel,
      @JsonInclude(JsonInclude.Include.NON_NULL) String priceVersion,
      @JsonInclude(JsonInclude.Include.NON_NULL) String billingUnit) {}

  /** 不可变的结构化报告。 */
  public record Report(
      String reportId,
      String snapshotId,
      int version,
      Provenance provenance,
      Coverage coverage,
      Metrics metrics,
      List<Finding> findings,
      List<String> limitations) {}

  /** 1、校验快照成员和覆盖；2、计算分母；3、绑定证据；4、标明演示模式和局限。 */
  public Report build(
      JobStore.Job job, ImportDocument input, List<Classification> classifications) {
    return build(job, input, classifications, ModelContract.Plan.mock());
  }

  /**
   * 使用创建时保存的计划说明分类来源，禁止用当前服务器配置改写历史报告。 1、验证版本与覆盖；2、程序统计；3、引用原文；4、按真实执行类型声明限制。 MODEL
   * 只说明供应商调用已通过协议校验，不保证语义准确，也不代表固定模型权重。
   */
  public Report build(
      JobStore.Job job,
      ImportDocument input,
      List<Classification> classifications,
      ModelContract.Plan plan) {
    if (!plan.modelVersion().equals(job.modelVersion())
        || !plan.promptVersion().equals(job.promptVersion())
        || !plan.taxonomyVersion().equals(job.taxonomyVersion())) {
      throw new IllegalArgumentException("Report plan does not match job");
    }
    String mode = plan.reportMode();
    Set<String> expected = new HashSet<>();
    input.comments().forEach(c -> expected.add(c.id()));
    Set<String> seen = new HashSet<>();
    EnumMap<Label, Integer> counts = new EnumMap<>(Label.class);
    for (var c : classifications) {
      if (c == null
          || c.label() == null
          || !expected.contains(c.commentId())
          || !seen.add(c.commentId()))
        throw new IllegalArgumentException("Invalid classification evidence");
      counts.merge(c.label(), 1, Integer::sum);
    }
    if (!seen.equals(expected)) throw new IllegalArgumentException("Incomplete snapshot coverage");
    var m =
        new FeedbackMetrics(
            counts.getOrDefault(Label.POSITIVE, 0),
            counts.getOrDefault(Label.NEGATIVE, 0),
            counts.getOrDefault(Label.NEUTRAL, 0),
            counts.getOrDefault(Label.UNKNOWN, 0));
    List<Finding> findings = new ArrayList<>();
    for (var label : List.of(Label.POSITIVE, Label.NEGATIVE, Label.NEUTRAL)) {
      var ids =
          classifications.stream()
              .filter(c -> c.label() == label)
              .limit(2)
              .map(Classification::commentId)
              .toList();
      if (!ids.isEmpty())
        findings.add(
            new Finding(
                ("MOCK".equals(mode) ? "演示分类器标记为" : "LOCAL_STUB".equals(mode) ? "本机替身标记为" : "模型标记为")
                    + switch (label) {
                      case POSITIVE -> "正面";
                      case NEGATIVE -> "负面";
                      default -> "中立";
                    }
                    + ("MODEL".equals(mode) ? "的评论样本；请结合原文核验。" : "的样本，仅用于验证流程。"),
                ids));
    }
    List<String> limitations =
        new ArrayList<>(
            List.of(
                switch (mode) {
                  case "MODEL" -> "MODEL：模型标签可能误判，证据存在不等于语义支持；响应身份核对不代表权重固定。费用为本地估算，非供应商账单。";
                  case "LOCAL_STUB" -> "LOCAL_STUB：本机替身仅验证协议与费用流程，未调用外部模型，不代表真实语义准确率。";
                  default -> "MOCK：仅对内置合成文本查表，未调用大模型，不代表真实语义判断或准确率。";
                },
                "比例仅指当前快照中已分类评论，不能代表所有观众；源平台总量未知。",
                "报告只统计评论，未进行视频语义理解或评论与章节对齐。"));
    if (input.transcriptSegments().isEmpty()) limitations.add("本快照没有字幕。");
    return new Report(
        UUID.randomUUID().toString(),
        job.snapshotId(),
        "MOCK".equals(mode) ? 1 : 2,
        new Provenance(
            job.modelVersion(),
            job.promptVersion(),
            job.taxonomyVersion(),
            Instant.now().toString(),
            mode,
            "MOCK".equals(mode) ? null : plan.providerId(),
            "MOCK".equals(mode) ? null : plan.expectedResponseModel(),
            "MOCK".equals(mode) ? null : plan.priceVersion(),
            "MOCK".equals(mode) ? null : plan.unit()),
        new Coverage(
            expected.size(),
            expected.size(),
            (int) m.classifiedComments(),
            m.unknown(),
            null,
            null),
        new Metrics(m.positive(), m.negative(), m.neutral(), m.positiveShareAmongClassified()),
        List.copyOf(findings),
        List.copyOf(limitations));
  }
}
