/* 文件职责：用合成不可变产物验证分页/筛选分母、父证据和Markdown安全转义，不运行模型。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.echoinsight.intelligence.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 此处构造的是报告投影夹具，不是供应商输出或语义准确率样本。 */
class DiscussionReportServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AnalysisRequestStore requests = mock(AnalysisRequestStore.class);
  private final SourceCoverageStore coverage = mock(SourceCoverageStore.class);
  private final DiscussionReportService reports =
      new DiscussionReportService(requests, coverage, json);
  private ObjectNode artifact;

  /** 25条判断含混合立场和跨页父引用，固定全局统计用于检查不被页面过滤改写。 */
  @BeforeEach
  void fixture() {
    artifact =
        json.createObjectNode()
            .put("title", "合成<script>报告")
            .put("instruction", "[链接](javascript:bad)")
            .put("sourceMode", "MODEL");
    var stance = artifact.putObject("stance");
    stance.put("analyzed", true);
    stance.putObject("specification").put("proposition", "合成命题");
    stance.putObject("statistics").put("selectedComments", 25).put("totalClaims", 26);
    ArrayNode decisions = stance.putArray("decisions"), evidence = artifact.putArray("evidence");
    for (int i = 1; i <= 25; i++) {
      var row =
          decisions
              .addObject()
              .put("commentId", "c" + i)
              .put("coverage", "CLAIMS")
              .put("reason", "合成判断");
      var claims = row.putArray("claims");
      var claim =
          claims
              .addObject()
              .put("statement", "合成主张")
              .put("position", i == 2 ? "OPPOSE" : "SUPPORT")
              .put("condition", "")
              .put("reason", "合成理由");
      claim.putArray("evidenceIds").add("c" + i);
      if (i == 1) {
        var extra =
            claims
                .addObject()
                .put("statement", "不同层面")
                .put("position", "OPPOSE")
                .put("condition", "")
                .put("reason", "限定讨论");
        extra.putArray("evidenceIds").add("c1");
      }
      var quote =
          evidence
              .addObject()
              .put("id", "c" + i)
              .put("text", "第" + i + "条<script>原文")
              .put("sourceRole", "COMMENT");
      if (i == 1) quote.putNull("parentCommentId");
      else quote.put("parentCommentId", "c1");
    }
    for (String key : List.of("issues", "claims", "relations", "assessments", "limitations"))
      artifact.putArray(key);
    when(requests.artifact("p", "r")).thenReturn(artifact);
    when(requests.get("p", "r"))
        .thenReturn(
            new AnalysisRequestStore.RequestRow(
                "r", "p", "s", "合成", "PROPOSITION_V1", false, "SUCCESS", "j", "a", null, "2026"));
    when(coverage.get("p", "r", "s"))
        .thenReturn(
            new SourceCoverageStore.View(
                true,
                new SourceCoverageStore.Coverage(
                    "v1", "s", null, null, 25, null, null, 0, false, List.of("平台全量未知"))));
  }

  /** 首屏不携带全部命题原文，原产物字节形状不被报告投影改写。 */
  @Test
  void overviewDoesNotMutateOriginalDecisions() {
    String original = artifact.toString();
    var view = reports.overview("p", "r");
    assertThat(view.decisionCount()).isEqualTo(25);
    assertThat(view.artifact().path("evidence")).isEmpty();
    assertThat(view.artifact().path("stance").path("decisions")).isEmpty();
    assertThat(artifact.toString()).isEqualTo(original);
  }

  /** 第二页5条判断仍带未在本页的父评论，统计始终25/26而不是本页数量。 */
  @Test
  void serverPageIncludesAncestorAndKeepsGlobalDenominator() {
    var page = reports.decisions("p", "r", "ALL", "", 2, 20);
    assertThat(page.items()).hasSize(5);
    assertThat(page.evidence()).extracting(n -> n.path("id").asText()).contains("c1", "c25");
    assertThat(page.globalStatistics().path("selectedComments").asInt()).isEqualTo(25);
    assertThat(page.totalPages()).isEqualTo(2);
  }

  /** 主张过滤允许重叠，混合不是中立；NFKC检索不会改写原文。 */
  @Test
  void mixedFilterAndNormalizedSearchHaveStableCounts() {
    assertThat(reports.decisions("p", "r", "MIXED", "", 1, 20).total()).isEqualTo(1);
    assertThat(reports.decisions("p", "r", "SUPPORT", "", 1, 20).total()).isEqualTo(24);
    assertThat(reports.decisions("p", "r", "OPPOSE", "", 1, 20).total()).isEqualTo(2);
    assertThat(reports.decisions("p", "r", "ALL", "第２条", 1, 20).items()).hasSize(1);
    assertThatThrownBy(() -> reports.decisions("p", "r", "NEUTRAL", "", 1, 20))
        .hasMessageContaining("结果筛选无效");
  }

  /** 下载转义HTML和Markdown链接，JSON保留全部原文和决策；导出不产生任何新请求。 */
  @Test
  void exportEscapesMarkupAndRetainsFullJson() throws Exception {
    String markdown = reports.export("p", "r", "markdown");
    assertThat(markdown)
        .doesNotContain("<script>", "[链接](javascript:bad)")
        .contains("&lt;script&gt;", "原文证据", "选入评论");
    assertThat(
            json.readTree(reports.export("p", "r", "json"))
                .path("artifact")
                .path("stance")
                .path("decisions"))
        .hasSize(25);
    assertThatThrownBy(() -> reports.export("p", "r", "html")).hasMessageContaining("报告格式只能");
  }
}
