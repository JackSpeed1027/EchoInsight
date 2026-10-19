/* 文件职责：验证导出 Markdown 的来源、分母、人工意见边界和恶意文本隔离，不访问数据库。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.review.ReviewService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 工程样例只验证导出表达，不代表模型已分析过这些文本。 */
class ReportExportRendererTest {
  private final ObjectMapper json = new ObjectMapper();
  private final ReportExportRenderer renderer = new ReportExportRenderer();

  /** 构造一份没有有效分母的最小报告，保留明确的模式和版本。 */
  private ReportExportService.Bundle bundle(String mode, boolean reviewsIncluded) throws Exception {
    var report =
        json.readTree(
            """
            {"reportId":"r","snapshotId":"s","version":1,"provenance":{"mode":"MOCK"},
            "coverage":{"eligibleComments":1,"classifiedComments":0,"unknown":1},
            "metrics":{"positive":0,"negative":0,"neutral":0,"positiveShareAmongClassified":null},
            "findings":[],"limitations":["样本范围有限"]}
            """);
    ((com.fasterxml.jackson.databind.node.ObjectNode) report.path("provenance")).put("mode", mode);
    return new ReportExportService.Bundle(
        "1.0", "2026-10-16T00:00:00Z", report, List.of(), reviewsIncluded, List.of());
  }

  /** 来源来自原报告，不根据今天运行模式猜测；模型或替身都不冒充已验证质量。 */
  @Test
  void labelsEachSavedSourceClearly() throws Exception {
    assertThat(renderer.markdown(bundle("MOCK", true))).contains("Mock 演示", "不代表语义准确率");
    assertThat(renderer.markdown(bundle("LOCAL_STUB", true))).contains("本机替身验证", "未调用外部模型");
    assertThat(renderer.markdown(bundle("MODEL", true))).contains("模型分析", "标签仍需核验");
  }

  /** 全 UNKNOWN 时不得伪造 0% 或 100%；省略意见与没有结论应明确区分。 */
  @Test
  void explainsEmptyDenominatorAndIntentionallyOmittedReviews() throws Exception {
    var output = renderer.markdown(bundle("MOCK", false));
    assertThat(output).contains("无可用分母", "UNKNOWN 不进入分母", "本次导出未包含人工核验", "本报告没有引用原文");
    assertThat(output).doesNotContain("0.0%", "100.0%");
  }

  /** 原文即使包含围栏、标题、链接和 HTML，也不能逃出字面文本块成为文档结构。 */
  @Test
  void untrustedTextCannotCloseMarkdownFence() throws Exception {
    var original = bundle("MOCK", true);
    String attack = "````\n# 伪造标题\n<script>alert(1)</script>\n[恶意链接](https://example.invalid)";
    var malicious =
        new ReportExportService.Bundle(
            original.exportVersion(),
            original.exportedAt(),
            original.report(),
            List.of(new ReportExportService.Evidence("c", "source", attack)),
            true,
            List.of());
    var output = renderer.markdown(malicious);
    assertThat(output).contains("`````text\n" + attack + "\n`````");
    StringBuilder outside = new StringBuilder();
    String fence = null;
    for (String line : output.split("\n")) {
      if (fence == null && line.matches("`{3,}text")) {
        fence = line.substring(0, line.length() - 4);
      } else if (fence != null && line.equals(fence)) {
        fence = null;
      } else if (fence == null) {
        outside.append(line).append('\n');
      }
    }
    assertThat(fence).isNull();
    assertThat(outside.toString())
        .contains("# EchoInsight 反馈复盘报告")
        .doesNotContain("伪造标题", "<script>", "example.invalid");
  }

  /** 最新人工意见单列附录，保留修订号和替代原文，不称为修改模型报告。 */
  @Test
  void latestReviewIsAnIndependentAppendix() throws Exception {
    var original = bundle("MOCK", true);
    var review =
        new ReviewService.Review(
            "event",
            "r",
            "finding",
            2,
            "PARTIAL",
            "不能推广到全部观众",
            List.of("alternative"),
            "local",
            "2026-10-16T00:00:00Z");
    var summary =
        new ReportExportService.ReviewSummary(
            0,
            "finding",
            2,
            review,
            List.of(new ReportExportService.Evidence("alternative", "alt-source", "替代原文")));
    var included =
        new ReportExportService.Bundle(
            "1.0", original.exportedAt(), original.report(), List.of(), true, List.of(summary));
    assertThat(renderer.markdown(included))
        .contains("最新人工核验附录", "部分支持（PARTIAL）", "修订号：2", "完整历史保留在系统", "替代原文", "不能推广到全部观众");
  }
}
