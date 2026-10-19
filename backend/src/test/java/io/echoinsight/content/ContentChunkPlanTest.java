/* 文件职责：验证完整范围的唯一覆盖、硬容量、顺序与冻结计划防损坏，不调用模型或数据库。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.material.SubtitleParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 完整是输入覆盖约束，不是语义质量声明。 */
class ContentChunkPlanTest {
  /** 最大文本完整进入17片，无尾部截断、重复或重排。 */
  @Test
  void coversMaximumTextExactlyOnceInOrder() {
    var selection = selection("界".repeat(200000));
    var plan = ContentChunkPlan.create(selection);
    assertThat(plan.chunks()).hasSize(17);
    assertThat(plan.textChars()).isEqualTo(200000);
    assertThat(plan.chunks().stream().flatMap(x -> x.segmentIds().stream()).toList())
        .containsExactlyElementsOf(
            selection.segments().stream().map(ContentMaterialParser.Segment::id).toList());
    assertThat(plan.chunks())
        .allSatisfy(
            x -> {
              assertThat(x.textChars()).isLessThanOrEqualTo(12000);
              assertThat(x.segmentIds()).hasSizeLessThanOrEqualTo(100);
              assertThat(plan.select(selection, x).segments()).hasSize(x.segmentIds().size());
            });
    plan.verify(selection);
  }

  /** 极短字幕仍受条数约束，2400段可完整，2401段必须拒绝而非裁剪。 */
  @Test
  void acceptsTwentyFourChunksAndRejectsTheNext() {
    assertThat(ContentChunkPlan.create(many(2400)).chunks()).hasSize(24);
    assertThatThrownBy(() -> ContentChunkPlan.create(many(2401))).hasMessageContaining("24片");
  }

  /** 单条完整原段不能被拆开，片内计数由实际段求和。 */
  @Test
  void keepsWholeLargeSegments() {
    var segments = List.of(segment("a", 10000), segment("b", 3000), segment("c", 9000));
    var selection = scope(segments);
    var plan = ContentChunkPlan.create(selection);
    assertThat(plan.chunks()).hasSize(2);
    assertThat(plan.chunks().getLast().segmentIds()).containsExactly("b", "c");
  }

  /** 冻结计划的版本/内容/计数损坏不能进入恢复。 */
  @Test
  void refusesCorruptedCoverage() {
    var scope = selection("界".repeat(14000));
    var good = ContentChunkPlan.create(scope);
    assertThatThrownBy(
            () ->
                new ContentChunkPlan("other", good.chunks(), good.segmentCount(), good.textChars())
                    .verify(scope))
        .hasMessageContaining("coverage mismatch");
    assertThatThrownBy(
            () ->
                new ContentChunkPlan(
                        good.version(),
                        List.of(good.chunks().getLast()),
                        good.segmentCount(),
                        good.textChars())
                    .verify(scope))
        .hasMessageContaining("coverage mismatch");
  }

  /** 时间窗口只决定原段资格，完整仍保留相交字幕整段与范围外分母。 */
  @Test
  void fullWindowKeepsWholeIntersectingCue() {
    var summary = summary("SRT", 2, 2);
    var material =
        new ContentMaterialStore.Material(
            summary,
            List.of(
                new ContentMaterialParser.Segment("a", "甲", null, null, 0L, 2000L),
                new ContentMaterialParser.Segment("b", "乙", null, null, 2000L, 4000L)));
    var full = ContentSelection.full(material, 1000L, 2000L);
    assertThat(full.segments()).extracting(ContentMaterialParser.Segment::id).containsExactly("a");
    assertThat(full.outsideRange()).isEqualTo(1);
    assertThat(full.omittedSegments()).isZero();
    ContentChunkPlan.create(full).verify(full);
  }

  /** 空正文、重复身份拒绝，不能生成假完整任务。 */
  @Test
  void rejectsEmptyAndDuplicateSegments() {
    assertThatThrownBy(() -> ContentChunkPlan.create(scope(List.of())))
        .hasMessageContaining("没有正文");
    assertThatThrownBy(
            () -> ContentChunkPlan.create(scope(List.of(segment("a", 1), segment("a", 1)))))
        .hasMessageContaining("原段无效");
  }

  /** 旧有限选择仍只取前12000字，完整选择不改变旧策略。 */
  @Test
  void preservesLegacyPrefixPolicy() {
    var all = selection("界".repeat(14000));
    var material = new ContentMaterialStore.Material(summary("TXT", 7, 14000), all.segments());
    var old = ContentSelection.select(material, null, null);
    assertThat(old.version()).isEqualTo(ContentSelection.VERSION);
    assertThat(old.selectedChars()).isEqualTo(12000);
    assertThat(old.omittedSegments()).isEqualTo(1);
    assertThat(all.selectedChars()).isEqualTo(14000);
  }

  /** 复用生产解析得到真实字符段。 */
  private ContentSelection selection(String text) {
    var parsed =
        new ContentMaterialParser(new SubtitleParser())
            .parse("TXT", text.getBytes(StandardCharsets.UTF_8));
    return ContentSelection.full(
        new ContentMaterialStore.Material(
            summary("TXT", parsed.segments().size(), text.length()), parsed.segments()),
        null,
        null);
  }

  /** 条数压力用合成单字符原段，避免无关大文本开销。 */
  private ContentSelection many(int count) {
    var list = new ArrayList<ContentMaterialParser.Segment>();
    for (int i = 0; i < count; i++) list.add(segment("s" + i, 1));
    return scope(list);
  }

  /** 所选范围均完整，无窗口外片段。 */
  private ContentSelection scope(List<ContentMaterialParser.Segment> list) {
    return new ContentSelection(
        "content-full-window-v1",
        "m",
        "a",
        "b",
        null,
        null,
        list.size(),
        list.size(),
        0,
        0,
        list.stream().mapToInt(x -> x.text().length()).sum(),
        list);
  }

  /** 简单原段保持可核验身份。 */
  private ContentMaterialParser.Segment segment(String id, int chars) {
    return new ContentMaterialParser.Segment(id, "字".repeat(chars), 0, chars, null, null);
  }

  /** 元数据属于测试夹具，不认证来源身份。 */
  private ContentMaterialStore.Summary summary(String format, int count, int chars) {
    return new ContentMaterialStore.Summary(
        "m", "p", null, 1, "合成", "用户声明", format, count, chars, "a", "b", "fixture");
  }
}
