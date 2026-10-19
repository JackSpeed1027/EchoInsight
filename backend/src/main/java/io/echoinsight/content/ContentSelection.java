/* 文件职责：确定独立内容的一次有界分析范围，完整段保留，范围/遗漏明确冻结。 */
package io.echoinsight.content;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 内容阅读采用确定性前缀及显式时间范围，不把关键词命中当全篇理解。 */
public record ContentSelection(
    String version,
    String materialId,
    String materialSha256,
    String segmentsSha256,
    Long fromMs,
    Long toMs,
    int totalSegments,
    int eligibleSegments,
    int outsideRange,
    int omittedSegments,
    int selectedChars,
    List<ContentMaterialParser.Segment> segments) {
  public static final String VERSION = "content-prefix-window-v1";
  public static final int MAX_SEGMENTS = 40, MAX_CHARS = 12000;

  /** 1、核对时间范围；2、选完整相交字幕；3、按原序装入有界前缀；4、冻结遗漏数量和身份。 */
  public static ContentSelection select(
      ContentMaterialStore.Material material, Long from, Long to) {
    var candidates = candidates(material, from, to);
    List<ContentMaterialParser.Segment> selected = new ArrayList<>();
    int chars = 0;
    for (var item : candidates) {
      if (selected.size() >= MAX_SEGMENTS || chars + item.text().length() > MAX_CHARS) break;
      selected.add(item);
      chars += item.text().length();
    }
    var s = material.summary();
    return new ContentSelection(
        VERSION,
        s.id(),
        s.rawSha256(),
        s.segmentsSha256(),
        from,
        to,
        material.segments().size(),
        candidates.size(),
        material.segments().size() - candidates.size(),
        candidates.size() - selected.size(),
        chars,
        List.copyOf(selected));
  }

  /** 范围为[fromMs,toMs)，保留相交段整段原文；文本不能被当作带时间的媒体。 */
  public static List<ContentMaterialParser.Segment> candidates(
      ContentMaterialStore.Material material, Long from, Long to) {
    if ((from == null) != (to == null)
        || (from != null && (from < 0 || from >= to || to > 86400000L)))
      throw bad("时间范围需同时提供起止毫秒，0≤起点<终点≤24小时");
    if (from != null && !Set.of("SRT", "VTT").contains(material.summary().format()))
      throw bad("只有字幕支持时间范围");
    if (from == null) return material.segments();
    return material.segments().stream().filter(x -> x.endMs() > from && x.startMs() < to).toList();
  }

  /** 截取的是输入范围，不声称模型已经理解全材料。 */
  public boolean truncated() {
    return omittedSegments > 0;
  }

  /** 范围错误不静默回退到开头。 */
  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
