/* 文件职责：验证有界线程选择的父闭包、确定性、字符边界、非法树和真实样本可验证属性。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.ModelContract;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 合成结构只验证工程契约，不证明LLM已经理解回复或得出正确评议。 */
class ThreadContextSelectorTest {
  private final ThreadContextSelector selector = new ThreadContextSelector();
  private final OffsetDateTime time = OffsetDateTime.parse("2026-10-17T08:00:00Z");

  /** 输入顺序任意，选中的回复仍必须在其完整祖先之后，两个根线程先获得覆盖。 */
  @Test
  void keepsAncestorsAndStableBreadthAcrossThreads() {
    var input =
        List.of(
            row("c", "b", "继续"), row("b", "a", "回复"), row("x", null, "另一线程"), row("a", null, "问题"));
    var result = selector.select(input, 3, 100);
    assertThat(result.comments())
        .extracting(ImportDocument.Comment::id)
        .containsExactly("a", "x", "b");
    assertThat(result.selectedThreads()).isEqualTo(2);
    assertThat(result.truncated()).isTrue();
    assertParentClosure(result.comments());
    var reversed = new ArrayList<>(input);
    java.util.Collections.reverse(reversed);
    assertThat(selector.select(reversed, 3, 100)).isEqualTo(result);
  }

  /** 太长的父节点不能被截成短文再带上回复；没有上下文的后代一起排除。 */
  @Test
  void skipsOversizedParentAndItsDescendantsWithoutOrphaningReply() {
    var result =
        selector.select(
            List.of(row("a", null, "过长的根评论"), row("b", "a", "短"), row("x", null, "可用")), 5, 3);
    assertThat(result.comments()).extracting(ImportDocument.Comment::id).containsExactly("x");
    assertThat(result.selectedTextChars()).isEqualTo(2);
    assertParentClosure(result.comments());
  }

  /** 超过条数上限时准确记录120条，保留的每个后代都能回到已选根。 */
  @Test
  void enforcesHardLimitsAndReportsActualSelection() {
    var input = new ArrayList<ImportDocument.Comment>();
    input.add(row("root", null, "根"));
    for (int i = 0; i < 200; i++) input.add(row("c" + i, "root", "回复"));
    var result = selector.select(input);
    assertThat(result.comments()).hasSize(120);
    assertThat(result.totalComments()).isEqualTo(201);
    assertThat(result.selectedThreads()).isEqualTo(1);
    assertThat(result.truncated()).isTrue();
    assertParentClosure(result.comments());
  }

  /** 断裂、纯循环、混合循环或重复ID不能被当成普通截断样本。 */
  @Test
  void rejectsInvalidGraphInsteadOfSilentlyHidingRows() {
    for (var input :
        List.of(
            List.of(row("a", "missing", "断裂")),
            List.of(row("a", "b", "环"), row("b", "a", "环")),
            List.of(row("x", null, "根"), row("a", "b", "环"), row("b", "a", "环")),
            List.of(row("a", null, "一"), row("a", null, "二")))) {
      assertThatThrownBy(() -> selector.select(input)).isInstanceOf(ModelContract.Failure.class);
    }
  }

  /** 深链不使用递归调用栈，预算截断后依然保持父闭包。 */
  @Test
  void handlesFiveThousandDeepRepliesIteratively() {
    var input = new ArrayList<ImportDocument.Comment>();
    for (int i = 0; i < 5000; i++) input.add(row("c" + i, i == 0 ? null : "c" + (i - 1), "正文"));
    var result = selector.select(input);
    assertThat(result.comments()).hasSize(120);
    assertParentClosure(result.comments());
  }

  /** 独立检查闭包，而非重复生产选择算法；任意后代之前必须已出现直接父级。 */
  private void assertParentClosure(List<ImportDocument.Comment> rows) {
    var seen = new HashSet<String>();
    for (var row : rows) {
      if (row.parentId() != null) assertThat(seen).contains(row.parentId());
      seen.add(row.id());
    }
  }

  private ImportDocument.Comment row(String id, String parent, String text) {
    return new ImportDocument.Comment(id, id, parent, text, time);
  }
}
