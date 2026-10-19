/* 文件职责：将只读导出材料排成可读 Markdown，用字面文本块隔离原文与人工输入。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 只负责表达已保存事实；不推断新主题、不修改统计，也不调用外部服务。 */
@Component
public class ReportExportRenderer {
  /**
   * 1、交代来源和范围；2、显示确定性统计；3、列结论及原文；4、单列最新核验和限制。 Markdown 中的自由文本一律进入动态长度围栏，防止原文关闭代码块后伪造页面内容。
   *
   * @param bundle 同一读取事务得到的历史报告、原文及可选核验。
   * @return UTF-8 编码前的完整 Markdown；不创建文件、任务或数据库记录。
   */
  public String markdown(ReportExportService.Bundle bundle) {
    JsonNode report = bundle.report();
    var output = new StringBuilder("# EchoInsight 反馈复盘报告\n\n");
    output.append("这份文件导出自已保存报告。原报告与人工意见分别列出，导出不会重新分析。\n\n");
    output.append("## 1、来源与范围\n\n");
    String mode = report.path("provenance").path("mode").asText();
    output.append("分析来源：").append(sourceName(mode)).append("。\n\n");
    literal(
        output,
        "报告 ID："
            + report.path("reportId").asText()
            + "\n快照 ID："
            + report.path("snapshotId").asText()
            + "\n原报告版本："
            + report.path("version").asText()
            + "\n导出时间："
            + bundle.exportedAt());
    literal(output, report.path("provenance").toPrettyString());

    var coverage = report.path("coverage");
    var metrics = report.path("metrics");
    output.append("## 2、评论统计\n\n");
    output
        .append("| 快照评论 | 已分类 | 无法判断 | 正面 | 负面 | 中立 |\n")
        .append("|---|---|---|---|---|---|\n")
        .append('|')
        .append(coverage.path("eligibleComments").asInt())
        .append('|')
        .append(coverage.path("classifiedComments").asInt())
        .append('|')
        .append(coverage.path("unknown").asInt())
        .append('|')
        .append(metrics.path("positive").asInt())
        .append('|')
        .append(metrics.path("negative").asInt())
        .append('|')
        .append(metrics.path("neutral").asInt())
        .append("|\n\n");
    JsonNode ratio = metrics.path("positiveShareAmongClassified");
    String percentage =
        ratio.isNumber() ? String.format(Locale.ROOT, "%.1f%%", ratio.asDouble() * 100) : "无可用分母";
    output.append("已分类评论的正面占比：").append(percentage).append("。分母为已分类评论，UNKNOWN 不进入分母；不能代表全部观众。\n\n");

    output.append("## 3、原始结论与证据引用\n\n");
    if (report.path("findings").isEmpty()) {
      output.append("当前报告没有可生成结论的已分类评论。\n\n");
    }
    int ordinal = 1;
    for (JsonNode finding : report.path("findings")) {
      output.append("### 结论 ").append(ordinal++).append("\n\n");
      literal(output, finding.path("claim").asText());
      output.append("引用的快照内评论 ID：\n\n");
      literal(output, finding.path("evidenceIds").toString());
    }
    output.append("## 4、报告引用的原文\n\n");
    if (bundle.evidence().isEmpty()) output.append("本报告没有引用原文。\n\n");
    for (var evidence : bundle.evidence()) quote(output, evidence);

    output.append("## 5、最新人工核验附录\n\n");
    output.append("人工意见独立于原报告；这里只展示导出时最新修订及总修订数，完整历史保留在系统。\n\n");
    if (!bundle.reviewsIncluded()) {
      output.append("本次导出未包含人工核验。\n\n");
    } else if (bundle.reviewSummary().isEmpty()) {
      output.append("当前没有可核验的结论。\n\n");
    }
    ordinal = 1;
    for (var summary : bundle.reviewSummary()) {
      output.append("### 结论 ").append(ordinal++).append(" 的核验\n\n");
      literal(output, "稳定结论 ID：" + summary.findingId() + "\n总修订数：" + summary.reviewCount());
      var review = summary.latestReview();
      if (review == null) {
        output.append("尚未核验。\n\n");
        continue;
      }
      literal(
          output,
          "核验结果："
              + verdictName(review.verdict())
              + "\n修订号："
              + review.revision()
              + "\n核验时间："
              + review.createdAt());
      output.append("核验理由：\n\n");
      literal(output, review.reason());
      if (summary.latestCorrectedEvidence().isEmpty()) {
        output.append("最新意见未提出替代原文，不表示删除原引用。\n\n");
      } else {
        output.append("最新意见提出的替代原文（仍属于同一报告快照）：\n\n");
        for (var evidence : summary.latestCorrectedEvidence()) quote(output, evidence);
      }
    }
    output.append("## 6、原报告限制\n\n");
    for (var limitation : report.path("limitations")) literal(output, limitation.asText());
    return output.toString();
  }

  /** 明确合成结果和真实供应商模式；身份匹配与语义准确性是两件事。 */
  private String sourceName(String mode) {
    return switch (mode) {
      case "MOCK" -> "Mock 演示（免费查表，不代表语义准确率）";
      case "LOCAL_STUB" -> "本机替身验证（合成协议响应，未调用外部模型）";
      case "MODEL", "REAL" -> "模型分析（标签仍需核验，费用估算以供应商账单为准）";
      default -> "来源未识别，请核对原报告 provenance";
    };
  }

  /** 给人工结论提供中文解释，保留原始结果代码便于回查。 */
  private String verdictName(String value) {
    return switch (value) {
      case "SUPPORTED" -> "支持结论（SUPPORTED）";
      case "PARTIAL" -> "部分支持（PARTIAL）";
      case "UNSUPPORTED" -> "不支持结论（UNSUPPORTED）";
      case "UNCERTAIN" -> "暂不确定（UNCERTAIN）";
      default -> "未识别结果";
    };
  }

  /** ID 和原文分别表达，避免将评论内容误看成导出模板中的事实或指令。 */
  private void quote(StringBuilder output, ReportExportService.Evidence evidence) {
    literal(output, "评论 ID：" + evidence.commentId() + "\n来源评论 ID：" + evidence.sourceCommentId());
    literal(output, evidence.text());
  }

  /** 围栏必须长于文本中任何连续反引号；只使用固定 text 语言标记。 即使原文包含代码块、HTML 或 Markdown 标题，也只能作为字面材料展示。 */
  private void literal(StringBuilder output, String text) {
    int longest = 0;
    int current = 0;
    for (int index = 0; index < text.length(); index++) {
      current = text.charAt(index) == '`' ? current + 1 : 0;
      longest = Math.max(longest, current);
    }
    String fence = "`".repeat(Math.max(3, longest + 1));
    output.append(fence).append("text\n").append(text).append('\n').append(fence).append("\n\n");
  }
}
