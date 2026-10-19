/* 文件职责：验证字幕格式、原文、时间边界与确定性检索，不读取真实作品或访问模型。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 所有字幕为合成数据，匹配测试仅证明字词算法，不证明语义召回质量。 */
class SubtitleParserTest {
  private final SubtitleParser parser = new SubtitleParser();

  /** BOM/CRLF与原始正文保留，规范时间按毫秒排序，不把文件段号当视频时间。 */
  @Test
  void parsesSrtAndRetainsRawIdentity() {
    String raw =
        "\ufeff2\r\n"
            + "00:00:02,000 --> 00:00:03,000\r\n"
            + "第二段\r\n\r\n"
            + "1\r\n"
            + "00:00:00,000 --> 00:00:01,200\r\n"
            + "第一段\r\n";
    var parsed = parser.parse("SRT", bytes(raw));
    assertThat(parsed.raw()).isEqualTo(raw);
    assertThat(parsed.segments().getFirst().startMs()).isZero();
    assertThat(parsed.segments().getFirst().endMs()).isEqualTo(1200L);
    assertThat(parsed.segments().getFirst().text()).isEqualTo("第一段");
  }

  /** VTT cue名、设置与NOTE块不混入字幕，重叠发言仍可合法保存。 */
  @Test
  void parsesVttWithSettingsNotesAndOverlap() {
    String raw =
        "WEBVTT\n\n"
            + "NOTE 合成备注\n"
            + "无需解析\n\n"
            + "first\n"
            + "00:01.000 --> 00:03.000 align:start\n"
            + "<v 角色>时间机器\n\n"
            + "00:02.000 --> 00:04.000\n"
            + "另一位发言\n";
    var result = parser.parse("VTT", bytes(raw));
    assertThat(result.segments()).hasSize(2);
    assertThat(result.segments().getFirst().text()).isEqualTo("<v 角色>时间机器");
  }

  /** 时间倒置、无效分钟、空正文和错误VTT头使整份失败，不悄悄忽略坏段。 */
  @Test
  void rejectsMalformedTimelineAndEmptyText() {
    for (String raw :
        List.of(
            "1\n00:00:02,000 --> 00:00:01,000\n坏段",
            "1\n00:60:00,000 --> 00:61:01,000\n坏段",
            "1\n00:00:00,000 --> 00:00:01,000\n"))
      assertThatThrownBy(() -> parser.parse("SRT", bytes(raw)))
          .hasMessageContaining(SubtitleParser.INVALID);
    assertThatThrownBy(() -> parser.parse("VTT", bytes("HEADER\n\n00:00.000 --> 00:01.000\n文本")))
        .hasMessageContaining(SubtitleParser.INVALID);
  }

  /** 非UTF8/NUL、超字节、超段数与超长段不允许成为可用版本。 */
  @Test
  void rejectsInvalidEncodingAndCapacity() {
    assertThatThrownBy(() -> parser.parse("SRT", new byte[] {(byte) 0xc3, 0x28}))
        .hasMessageContaining(SubtitleParser.INVALID);
    assertThatThrownBy(() -> parser.parse("SRT", new byte[SubtitleParser.MAX_BYTES + 1]))
        .hasMessageContaining(SubtitleParser.LIMIT);
    assertThatThrownBy(
            () ->
                parser.parse(
                    "SRT", bytes("1\n00:00:00,000 --> 00:00:01,000\n" + "字".repeat(10001))))
        .hasMessageContaining(SubtitleParser.LIMIT);
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 5001; i++)
      many.append(i + 1).append("\n00:00:00,000 --> 00:00:01,000\n合成\n\n");
    assertThatThrownBy(() -> parser.parse("SRT", bytes(many.toString())))
        .hasMessageContaining(SubtitleParser.LIMIT);
  }

  /** 超过前40段的直接命中仍可选入，前后邻域保留时间，不盲截前缀。 */
  @Test
  void retrievalFindsLateCueAndNeighborsDeterministically() {
    List<ImportDocument.Segment> input = new ArrayList<>();
    for (int i = 0; i < 100; i++)
      input.add(
          new ImportDocument.Segment(
              "cue-" + i, i * 1000L, i * 1000L + 900, i == 70 ? "时间机器的因果问题" : "无关联的合成语句"));
    var retriever = new TranscriptRetriever();
    var selected = retriever.select(input, "讨论时间机器的因果");
    assertThat(selected.segments())
        .extracting(ImportDocument.Segment::id)
        .containsExactly("cue-69", "cue-70", "cue-71");
    assertThat(selected.matchedSegments()).isEqualTo(1);
    Collections.reverse(input);
    assertThat(retriever.select(input, "讨论时间机器的因果")).isEqualTo(selected);
  }

  /** 没有命中不回退成“已理解前缀”，长命中段也不能被截断塞进上下文。 */
  @Test
  void retrievalNoMatchAndUnsplittableSegmentStayExplicit() {
    var retriever = new TranscriptRetriever();
    assertThat(
            retriever
                .select(List.of(new ImportDocument.Segment("1", 0L, 100L, "无关")), "时间机器")
                .segments())
        .isEmpty();
    var result =
        retriever.select(
            List.of(new ImportDocument.Segment("1", 0L, 100L, "时间机器" + "字".repeat(8000))), "时间机器");
    assertThat(result.matchedSegments()).isEqualTo(1);
    assertThat(result.segments()).isEmpty();
  }

  /** 多个命中与邻域总数/总字符受统一边界约束。 */
  @Test
  void retrievalBoundsSelectedCountAndChars() {
    var input = new ArrayList<ImportDocument.Segment>();
    for (int i = 0; i < 100; i++)
      input.add(
          new ImportDocument.Segment(
              "cue-" + i, i * 1000L, i * 1000L + 900, "机器" + "字".repeat(300)));
    var selected = new TranscriptRetriever().select(input, "机器");
    assertThat(selected.segments()).hasSizeLessThanOrEqualTo(40);
    assertThat(selected.segments().stream().mapToInt(s -> s.text().length()).sum())
        .isLessThanOrEqualTo(8000);
  }

  /** 测试字节固定UTF8，避免本机默认编码影响契约。 */
  private byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
