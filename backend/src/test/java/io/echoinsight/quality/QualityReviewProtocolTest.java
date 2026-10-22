/* 文件职责：用合成原文验证确定性抽样、完整父语境和人工提交边界，不生成真实标注。 */
package io.echoinsight.quality;

import static io.echoinsight.quality.QualityReviewProtocol.*;
import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 这些是工程契约用例，不能当独立人工判断或语义质量成绩。 */
class QualityReviewProtocolTest {
  private final OffsetDateTime time = OffsetDateTime.parse("2026-10-21T00:00:00Z");

  /** 同一seed和来源身份不受原列表顺序影响，不依赖预测引用。 */
  @Test
  void deterministicAcrossInputOrder() {
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int i = 0; i < 100; i++) comments.add(c("c" + i, null, "原文" + i));
    var ids = new HashSet<String>();
    comments.forEach(c -> ids.add(c.id()));
    var a = sample(doc(comments), ids, 12, "fixed");
    Collections.reverse(comments);
    assertThat(sample(doc(comments), ids, 12, "fixed")).isEqualTo(a);
    assertThat(a.stream().filter(Comment::target)).hasSize(12);
  }

  /** 只标记所选目标，祖先先于子节点且不重复。 */
  @Test
  void parentClosureIsSeparateFromTargetDenominator() {
    var result =
        sample(
            doc(List.of(c("a", null, "根"), c("b", "a", "回复"), c("c", "b", "深层回复"))),
            Set.of("c"),
            1,
            "fixed");
    assertThat(result.stream().map(Comment::id)).containsExactly("a", "b", "c");
    assertThat(result.stream().filter(Comment::target).map(Comment::id)).containsExactly("c");
  }

  /** 缺父和循环无法成为独立阅读样本，不假造根节点。 */
  @Test
  void brokenAncestorsCannotBeSampled() {
    assertThatThrownBy(() -> sample(doc(List.of(c("a", "missing", "原文"))), Set.of("a"), 1, "x"))
        .hasMessageContaining("父语境");
    assertThatThrownBy(
            () -> sample(doc(List.of(c("a", "b", "原文"), c("b", "a", "原文"))), Set.of("a"), 1, "x"))
        .hasMessageContaining("父语境");
  }

  /** 单目标闭包超界整条拒绝，不截断后声称读过。 */
  @Test
  void oversizedParentClosureIsNotTruncated() {
    var cs = new ArrayList<ImportDocument.Comment>();
    for (int i = 0; i < 9; i++)
      cs.add(c("c" + i, i == 0 ? null : "c" + (i - 1), "字".repeat(10000)));
    assertThatThrownBy(() -> sample(doc(cs), Set.of("c8"), 1, "x")).hasMessageContaining("边界");
  }

  /** 抽样限制与标记格式先验证，不能无限加载正文。 */
  @Test
  void rejectsInvalidSampleParameters() {
    var d = doc(List.of(c("a", null, "原文")));
    assertThatThrownBy(() -> sample(d, Set.of("a"), 21, "x")).hasMessageContaining("1～20");
    assertThatThrownBy(() -> sample(d, Set.of("a"), 1, "bad/seed")).hasMessageContaining("抽样标记");
  }

  /** 草稿可以未填，提交不能把空白或缺目标变成UNKNOWN。 */
  @Test
  void incompleteDraftCannotBeSubmitted() {
    var empty = new Label("a", "", List.of(), "", "", "", List.of("a"));
    assertThat(validate(input("DISCUSSION"), body("DRAFT", empty)).labels()).hasSize(1);
    assertThatThrownBy(() -> validate(input("DISCUSSION"), body("SUBMISSION", empty)))
        .hasMessageContaining("提交前");
    assertThatThrownBy(
            () ->
                validate(
                    input("DISCUSSION"),
                    new RecordBody("评审", "NOT_VIEWED", "SUBMISSION", null, List.of())))
        .hasMessageContaining("全部抽样目标");
  }

  /** 普通讨论没有默认支持/反对标准，不允许混入命题立场。 */
  @Test
  void discussionDoesNotInventStanceAxis() {
    var label = new Label("a", "CLAIMS", List.of("SUPPORT"), "观点", "", "理由", List.of("a"));
    assertThatThrownBy(() -> validate(input("DISCUSSION"), body("SUBMISSION", label)))
        .hasMessageContaining("不填写命题立场");
  }

  /** 限定支持必须写条件，引用不能只挑其他人的原文。 */
  @Test
  void qualifiedRequiresConditionAndOwnEvidence() {
    var label = new Label("a", "CLAIMS", List.of("QUALIFIED"), "观点", "", "理由", List.of("a"));
    assertThatThrownBy(() -> validate(input("STANCE"), body("SUBMISSION", label)))
        .hasMessageContaining("限定支持");
    var valid = new Label("a", "CLAIMS", List.of("QUALIFIED"), "观点", "给定前提下", "理由", List.of("a"));
    assertThat(validate(input("STANCE"), body("SUBMISSION", valid)).labels()).hasSize(1);
    assertThatThrownBy(
            () ->
                validate(
                    input("STANCE"),
                    body(
                        "SUBMISSION",
                        new Label("a", "UNKNOWN", List.of(), "", "", "理由", List.of()))))
        .hasMessageContaining("依据");
  }

  /** 重复目标/引用和跨输入身份均拒绝，不能反复标同一条冒充覆盖。 */
  @Test
  void rejectsDuplicateOrForeignIdentities() {
    var label = new Label("a", "UNKNOWN", List.of(), "", "", "理由", List.of("a"));
    assertThatThrownBy(
            () ->
                validate(
                    input("DISCUSSION"),
                    new RecordBody("评审", "UNKNOWN", "DRAFT", null, List.of(label, label))))
        .hasMessageContaining("目标");
    assertThatThrownBy(
            () ->
                validate(
                    input("DISCUSSION"),
                    body(
                        "DRAFT",
                        new Label("a", "UNKNOWN", List.of(), "", "", "理由", List.of("outside")))))
        .hasMessageContaining("引用");
  }

  /** 构造纯原文输入，没有模型结果字段。 */
  private Input input(String mode) {
    return new Input(
        VERSION,
        "p",
        "r",
        "j",
        "s",
        "hash",
        "plan",
        "问题",
        "标题",
        "SYNTHETIC",
        "source",
        mode,
        mode.equals("STANCE") ? "命题" : null,
        "fixed",
        1,
        1,
        List.of("a"),
        List.of(new Comment("a", "source-a", null, "原文", true)),
        List.of(),
        List.of());
  }

  private RecordBody body(String kind, Label label) {
    return new RecordBody("评审", "NOT_VIEWED", kind, null, List.of(label));
  }

  private ImportDocument.Comment c(String id, String parent, String text) {
    return new ImportDocument.Comment(id, "source-" + id, parent, text, time);
  }

  private ImportDocument doc(List<ImportDocument.Comment> cs) {
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("content", "SYNTHETIC", "source", "creator", "标题", time),
        cs,
        List.of());
  }
}
