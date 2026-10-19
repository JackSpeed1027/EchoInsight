/* 文件职责：验证平台缺失父评论不导致整批丢失，同时保持原引用、模型祖先闭包与严格边界。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.*;
import io.echoinsight.intelligence.ThreadContextSelector;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 全部输入为合成数据；真实存档另作只读回放，不写进仓库测试样本。 */
class PartialCommentContextTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final BilibiliCliAdapter adapter = new BilibiliCliAdapter(json);
  private final BilibiliUrlParser urls = new BilibiliUrlParser();
  private final String video =
      "[{\"aid\":123,\"bvid\":\"BV1ntuF64EhF\",\"owner_mid\":1,\"reply_count\":4,\"title\":\"合成资料\",\"pubdate\":1700000000}]";

  /** 四条评论中两条父闭合，另两条依赖缺失父；不能把它们接到外层JSON容器。 */
  @Test
  void preservesMissingParentAndExcludesDependentSubtree() throws Exception {
    var result =
        convert(
            "["
                + node(
                    100,
                    0,
                    "["
                        + node(101, 100, "null")
                        + ","
                        + node(102, 999, "null")
                        + ","
                        + node(103, 102, "null")
                        + "]")
                + "]");
    assertThat(result.commentCount()).isEqualTo(4);
    assertThat(result.completeness()).isEqualTo("PARTIAL_FAILED");
    var document = normalize(result.document());
    assertThat(document.contextGaps())
        .containsExactly(new ImportDocument.MissingParent("mc-bili-102", "mc-bili-999"));
    assertThat(
            document.comments().stream()
                .filter(c -> c.sourceCommentId().equals("102"))
                .findFirst()
                .orElseThrow()
                .parentId())
        .isEqualTo("mc-bili-999");
    var selected = new ThreadContextSelector().select(document);
    assertThat(selected.comments())
        .extracting(ImportDocument.Comment::sourceCommentId)
        .containsExactly("100", "101");
    assertThat(selected.truncated()).isTrue();
    assertThat(
            new ThreadContextSelector()
                .select(json.readValue(json.writeValueAsString(document), ImportDocument.class)))
        .isEqualTo(selected);
  }

  /** 未声明断边、伪造缺口、重复缺口及循环均拒绝，不能借兼容修复放开任意坏树。 */
  @Test
  void rejectsUndeclaredOrForgedGapsAndCycles() {
    var d = convert("[" + node(100, 0, "[" + node(102, 999, "null") + "]") + "]").document();
    assertThatThrownBy(
            () -> normalize(new ImportDocument("1.0", d.content(), d.comments(), List.of())))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(() -> new ThreadContextSelector().select(d.comments()))
        .isInstanceOf(io.echoinsight.model.ModelContract.Failure.class);
    var fake =
        new ImportDocument(
            "1.0",
            d.content(),
            d.comments(),
            List.of(),
            List.of(new ImportDocument.MissingParent("mc-bili-100", "fake")));
    assertThatThrownBy(() -> normalize(fake)).isInstanceOf(ImportFailure.class);
    var duplicate =
        new ImportDocument(
            "1.0",
            d.content(),
            d.comments(),
            List.of(),
            List.of(d.contextGaps().getFirst(), d.contextGaps().getFirst()));
    assertThatThrownBy(() -> normalize(duplicate)).isInstanceOf(ImportFailure.class);
    var time = OffsetDateTime.now();
    var cycle =
        new ImportDocument(
            "1.0",
            d.content(),
            List.of(
                new ImportDocument.Comment("a", "a", "b", "甲", time),
                new ImportDocument.Comment("b", "b", "a", "乙", time)),
            List.of());
    assertThatThrownBy(() -> normalize(cycle)).isInstanceOf(ImportFailure.class);
  }

  /** null/缺省/空数组回复都是叶节点，不因为合法空值丢掉整批。 */
  @Test
  void acceptsEmptyReplyRepresentationsAndPreservesLegacyJsonShape() throws Exception {
    for (String replies : List.of("null", "[]"))
      assertThat(convert("[" + node(100, 0, replies) + "]").document().missingParents()).isNull();
    var d = convert("[" + node(100, 0, "[]") + "]").document();
    assertThat(json.writeValueAsString(d)).doesNotContain("missingParents", "contextGaps");
    assertThat(
            convert("[" + node(100, 0, "[]").replace(",\"replies\":[]", "") + "]").commentCount())
        .isEqualTo(1);
  }

  /** 数量上限与结构错误使用不同错误码，不能把所有坏树统称数量太多。 */
  @Test
  void distinguishesCountAndDepthLimits() {
    var replies = new ArrayList<String>();
    for (int n = 1; n <= 5000; n++) replies.add(node(100 + n, 100, "null"));
    assertThatThrownBy(
            () -> convert("[" + node(100, 0, "[" + String.join(",", replies) + "]") + "]"))
        .hasMessage("COMMENT_COUNT_LIMIT");
    String deep = node(118, 117, "[]");
    for (int n = 117; n >= 100; n--) deep = node(n, n == 100 ? 0 : n - 1, "[" + deep + "]");
    String value = deep;
    assertThatThrownBy(() -> convert("[" + value + "]")).isInstanceOf(CollectionFailure.class);
  }

  private ImportDocument normalize(ImportDocument value) {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      return new ImportValidator(factory.getValidator()).normalize(value);
    }
  }

  private BilibiliCliAdapter.Converted convert(String comments) {
    return adapter.convert(
        urls.parse("https://www.bilibili.com/video/BV1ntuF64EhF/"),
        200,
        video.getBytes(StandardCharsets.UTF_8),
        comments.getBytes(StandardCharsets.UTF_8));
  }

  private String node(int id, int parent, String replies) {
    return "{\"rpid\":"
        + id
        + ",\"parent\":"
        + parent
        + ",\"oid\":123,\"type\":1,\"content\":\"合成评论\",\"ctime\":1700000000,\"reply_count\":0,\"replies\":"
        + replies
        + "}";
  }
}
