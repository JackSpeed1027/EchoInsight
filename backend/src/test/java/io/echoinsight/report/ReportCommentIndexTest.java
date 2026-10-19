/* 文件职责：核对报告别名转换不会篡改原文、背景身份或原始历史产物。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ID转换仅用于连接业务读模型，不能对正文作全局字符串替换。 */
class ReportCommentIndexTest {
  private final ObjectMapper json = new ObjectMapper();

  /** v6别名和背景ID相邻出现，只有评论引用被转换，原文里的同名词保持原样。 */
  @Test
  void convertsAliasesWithoutChangingOriginalOrBackground() throws Exception {
    var artifact =
        json.readTree(
            """
            {"evidence":[{"id":"comment-1","sourceCommentId":"source","sourceRole":"COMMENT","text":"comment-1是原文"},{"id":"context-reference-1","sourceRole":"REFERENCE_TEXT","text":"原参考"}],
            "stance":{"decisions":[{"commentId":"comment-1","claims":[{"statement":"comment-1是原文","evidenceIds":["comment-1","context-reference-1"]}]}]}}
            """);
    var result = ReportCommentIndex.normalize(artifact, input());
    assertThat(result.path("evidence").get(0).path("id").asText()).isEqualTo("internal");
    assertThat(result.path("stance").path("decisions").get(0).path("commentId").asText())
        .isEqualTo("internal");
    assertThat(
            result
                .path("stance")
                .path("decisions")
                .get(0)
                .path("claims")
                .get(0)
                .path("evidenceIds")
                .get(1)
                .asText())
        .isEqualTo("context-reference-1");
    assertThat(
            result
                .path("stance")
                .path("decisions")
                .get(0)
                .path("claims")
                .get(0)
                .path("statement")
                .asText())
        .isEqualTo("comment-1是原文");
    assertThat(artifact.path("evidence").get(0).path("id").asText()).isEqualTo("comment-1");
  }

  /** 无sourceRole的旧产物仍视为评论，不要求对旧JSON回填字段。 */
  @Test
  void legacyCommentEvidenceRemainsCompatible() throws Exception {
    var artifact =
        json.readTree("{\"evidence\":[{\"id\":\"internal\",\"sourceCommentId\":\"source\"}]}");
    assertThat(ReportCommentIndex.normalize(artifact, input())).isEqualTo(artifact);
  }

  /** 来源对不上时不能退回猜ID，否则会把其他作品的判断安到本评论。 */
  @Test
  void mismatchedSourceIsRefused() throws Exception {
    var artifact =
        json.readTree("{\"evidence\":[{\"id\":\"comment-1\",\"sourceCommentId\":\"foreign\"}]}");
    assertThatThrownBy(() -> ReportCommentIndex.normalize(artifact, input()))
        .hasMessageContaining("identity mismatch");
  }

  /** 单个来源身份用来检查双命名空间。 */
  private ImportDocument input() {
    var time = OffsetDateTime.parse("2026-10-18T00:00:00Z");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("work", "SYNTHETIC", "work", "fixture", "合成", time),
        List.of(new ImportDocument.Comment("internal", "source", null, "comment-1是原文", time)),
        List.of());
  }
}
