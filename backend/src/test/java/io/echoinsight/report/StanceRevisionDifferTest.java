/* 文件职责：用合成命题判断验证来源对齐、父依赖与比较分母，不把夹具标签当语义成绩。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.ingestion.ImportDocument;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 算法不依赖数据库或模型；关注漏重、未知缺失和祖先变化等无法靠页面检查保证的约束。 */
class StanceRevisionDifferTest {
  private final StanceRevisionDiffer differ = new StanceRevisionDiffer();
  private final ObjectMapper json = new ObjectMapper();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-10-18T00:00:00Z");

  /** 内部ID变了但来源身份相同，不得误报新增/缺失。 */
  @Test
  void alignsSourceIdsAndCountsOnlyComparableLabelChanges() {
    var old = doc(List.of(c("old", "source", null, "合成原文")));
    var now = doc(List.of(c("new", "source", null, "合成原文")));
    var result =
        differ.compare(old, now, artifact("old", "SUPPORT"), artifact("new", "OPPOSE"), true);
    assertThat(result.counts().get("COMPARABLE")).isEqualTo(1);
    assertThat(result.rows().getFirst().labelChanged()).isTrue();
    assertThat(result.transitions()).containsEntry("CLAIMS:SUPPORT → CLAIMS:OPPOSE", 1);
  }

  /** 新增/未知缺失/正文修订互斥，并集分母不能只数共有评论。 */
  @Test
  void sourceUnionSeparatesAddedMissingAndRevised() {
    var old = doc(List.of(c("a", "a", null, "旧正文"), c("b", "b", null, "未再见")));
    var now = doc(List.of(c("a", "a", null, "修订正文"), c("c", "c", null, "新评论")));
    var result = differ.compare(old, now, artifact("a", "SUPPORT"), artifact("a", "OPPOSE"), true);
    assertThat(result.rows()).hasSize(3);
    assertThat(result.counts())
        .containsEntry("REVISED", 1)
        .containsEntry("UNKNOWN_MISSING", 1)
        .containsEntry("ADDED", 1);
    assertThat(result.transitions()).isEmpty();
  }

  /** 父正文变了，即使回复自身相同也不能拿旧判断直接复用。 */
  @Test
  void ancestorRevisionInvalidatesUnchangedChild() {
    var old = doc(List.of(c("r", "root", null, "前提A"), c("c", "child", "r", "同意")));
    var now = doc(List.of(c("r", "root", null, "前提B"), c("c", "child", "r", "同意")));
    var result = differ.compare(old, now, artifact("c", "SUPPORT"), artifact("c", "SUPPORT"), true);
    assertThat(result.counts()).containsEntry("REVISED", 1).containsEntry("CONTEXT_CHANGED", 1);
    assertThat(result.rows().stream().anyMatch(StanceRevisionDiffer.Row::labelChanged)).isFalse();
  }

  /** 问题/背景条件不同会由上游拒绝标签资格，资料仍可对照。 */
  @Test
  void incompatibleContractsNeverProduceTransitions() {
    var input = doc(List.of(c("c", "c", null, "原文不变")));
    var result =
        differ.compare(input, input, artifact("c", "SUPPORT"), artifact("c", "OPPOSE"), false);
    assertThat(result.counts().get("NOT_COMPARABLE")).isEqualTo(1);
    assertThat(result.transitions()).isEmpty();
    assertThat(result.rows().getFirst().labelChanged()).isFalse();
  }

  /** 合法缺父记录不是虚构节点；同一缺口不导致算法崩溃，缺口身份变化会失效。 */
  @Test
  void declaredMissingParentIsPreservedAndDifferentGapInvalidates() {
    var old =
        new ImportDocument(
            "1.0",
            content(),
            List.of(c("c", "c", "absent-a", "回复")),
            List.of(),
            List.of(new ImportDocument.MissingParent("c", "absent-a")));
    var now =
        new ImportDocument(
            "1.0",
            content(),
            List.of(c("c", "c", "absent-b", "回复")),
            List.of(),
            List.of(new ImportDocument.MissingParent("c", "absent-b")));
    assertThat(
            differ
                .compare(old, old, artifact("c", "SUPPORT"), artifact("c", "SUPPORT"), false)
                .rows())
        .hasSize(1);
    assertThat(
            differ
                .compare(old, now, artifact("c", "SUPPORT"), artifact("c", "SUPPORT"), false)
                .counts()
                .get("REVISED"))
        .isEqualTo(1);
  }

  /** 5000层采用迭代祖先指纹，不能栈溢出，也不能重复累计共同父链。 */
  @Test
  void deepChainRemainsBoundedAndCountsFiveThousand() {
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int i = 0; i < 5000; i++) {
      comments.add(c("c" + i, "s" + i, i == 0 ? null : "c" + (i - 1), "合成"));
    }
    var input = doc(comments);
    var result =
        differ.compare(input, input, json.createObjectNode(), json.createObjectNode(), false);
    assertThat(result.rows()).hasSize(5000);
    assertThat(result.counts().get("NOT_COMPARABLE")).isEqualTo(5000);
  }

  /** 支持/反对混合按集合规范化，与主张顺序无关；理由改写不是本算法的语义变化。 */
  @Test
  void labelIsCoverageAndPositionSetNotWordingOrOrder() {
    ObjectNode node = json.createObjectNode();
    node.put("coverage", "CLAIMS");
    var claims = node.putArray("claims");
    claims.addObject().put("position", "SUPPORT");
    claims.addObject().put("position", "OPPOSE");
    assertThat(StanceRevisionDiffer.label(node)).isEqualTo("CLAIMS:OPPOSE+SUPPORT");
    assertThat(StanceRevisionDiffer.label(null)).isEqualTo("NOT_ANALYZED");
  }

  /** 提问/无法判断单独作为覆盖标签，不能合并为中立。 */
  @Test
  void nonClaimCoverageRemainsDistinct() {
    assertThat(StanceRevisionDiffer.label(json.createObjectNode().put("coverage", "QUESTION")))
        .isEqualTo("QUESTION");
    assertThat(StanceRevisionDiffer.label(json.createObjectNode().put("coverage", "UNKNOWN")))
        .isEqualTo("UNKNOWN");
  }

  /** 同时区表示同一Instant不构成评论修订。 */
  @Test
  void equivalentTimestampOffsetDoesNotCreateRevision() {
    var old = doc(List.of(c("c", "c", null, "原文")));
    var now =
        doc(
            List.of(
                new ImportDocument.Comment(
                    "c",
                    "c",
                    null,
                    "原文",
                    time.withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)))));
    assertThat(
            differ
                .compare(old, now, artifact("c", "SUPPORT"), artifact("c", "SUPPORT"), true)
                .counts()
                .get("COMPARABLE"))
        .isEqualTo(1);
  }

  /** 明确构造单条合成标签，不请求供应商。 */
  private ObjectNode artifact(String id, String position) {
    var root = json.createObjectNode();
    var decision = root.putObject("stance").putArray("decisions").addObject();
    decision.put("commentId", id);
    decision.put("coverage", "CLAIMS");
    decision.putArray("claims").addObject().put("position", position);
    return root;
  }

  /** 原文夹具的稳定时间与来源。 */
  private ImportDocument.Comment c(String id, String source, String parent, String text) {
    return new ImportDocument.Comment(id, source, parent, text, time);
  }

  /** 每个单元用同作品资料，实际业务资格在集成测试核对。 */
  private ImportDocument.Content content() {
    return new ImportDocument.Content("work", "SYNTHETIC", "work", "fixture", "合成验收", time);
  }

  /** 只为算法构建输入，未写入生产资料。 */
  private ImportDocument doc(List<ImportDocument.Comment> comments) {
    return new ImportDocument("1.0", content(), comments, List.of());
  }
}
