/* 文件职责：用独立预期与深回复树验证直接修订、未知缺失和祖先依赖失效。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 确定性对账只评价差分算法，不给模型语义准确率或节省数字。 */
class SnapshotDifferTest {
  private final SnapshotDiffer differ = new SnapshotDiffer();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-10-17T08:00:00Z");

  /** 根评论正文变化，原文不变的子孙仍需失效；另一个独立线程保持可候选。 */
  @Test
  void parentRevisionInvalidatesUnchangedDescendants() {
    var before =
        document(
            List.of(
                row("a", null, "旧前提"),
                row("b", "a", "回复"),
                row("c", "b", "继续回复"),
                row("x", null, "独立线程")));
    var after =
        document(
            List.of(
                row("a", null, "新前提"),
                row("b", "a", "回复"),
                row("c", "b", "继续回复"),
                row("x", null, "独立线程")));
    var result = differ.compare(before, after);
    assertThat(result.revised()).isEqualTo(1);
    assertThat(result.unchanged()).isEqualTo(3);
    assertThat(result.contextInvalidated()).isEqualTo(2);
    assertThat(result.contextStableCandidates()).isEqualTo(1);
  }

  /** 未出现只叫未知缺失；移接父评论算直接修订，新增只按来源身份统计。 */
  @Test
  void separatesAddedMissingAndParentChangeWithoutInferringDeletion() {
    var before =
        document(
            List.of(
                row("a", null, "A"),
                row("b", null, "B"),
                row("c", "a", "C"),
                row("missing", null, "未知去向")));
    var after =
        document(
            List.of(
                row("a", null, "A"),
                row("b", null, "B"),
                row("c", "b", "C"),
                row("new", "b", "新增")));
    var result = differ.compare(before, after);
    assertThat(result.added()).isEqualTo(1);
    assertThat(result.unknownMissing()).isEqualTo(1);
    assertThat(result.revised()).isEqualTo(1);
    assertThat(result.parentChanged()).isEqualTo(1);
    assertThat(result.missingSamples()).containsExactly("missing");
    // 全量分母对账：目标=新增+修订+自身未变；原输入=修订+自身未变+未知缺失。
    assertThat(result.added() + result.revised() + result.unchanged())
        .isEqualTo(after.comments().size());
    assertThat(result.revised() + result.unchanged() + result.unknownMissing())
        .isEqualTo(before.comments().size());
  }

  /** 5000层回复树不依赖Java递归栈，样本有界而完整计数不截断。 */
  @Test
  void handlesDeepTreesAndBoundsSamplesWhileCountingEveryRow() {
    var rows = new ArrayList<ImportDocument.Comment>();
    for (int index = 0; index < 5000; index++)
      rows.add(row("c" + index, index == 0 ? null : "c" + (index - 1), "正文"));
    var result = differ.compare(document(List.of()), document(rows));
    assertThat(result.added()).isEqualTo(5000);
    assertThat(result.addedSamples()).hasSize(100);
    var same = differ.compare(document(rows), document(rows));
    assertThat(same.contextStableCandidates()).isEqualTo(5000);
  }

  private ImportDocument.Comment row(String id, String parent, String text) {
    return new ImportDocument.Comment(id, id, parent, text, time);
  }

  private ImportDocument document(List<ImportDocument.Comment> rows) {
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("local", "SYNTHETIC", "1", "fixture", "合成对账", time),
        rows,
        List.of());
  }
}
