/* 文件职责：从用户明确选择的独立材料取有界完整原段，保留字符/时间和词法选择事实。 */
package io.echoinsight.content;

import io.echoinsight.material.ReferenceMaterialStore;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 小材料完整选入；大材料按问题字词和邻域选取，不能称语义检索或全片理解。 */
public final class ContentEvidenceSelector {
  public static final int MAX_SEGMENTS = 16, MAX_CHARS = 8000;
  public static final String VERSION = "content-evidence-lexical-v1";

  /** 无网络选择器。 */
  private ContentEvidenceSelector() {}

  /** 缺口独立声明，未选原文不推断为不存在。 */
  public record Result(
      String strategy,
      String querySha256,
      int totalSegments,
      int eligibleSegments,
      int matchedSegments,
      int selectedChars,
      int oversizedMatches,
      List<ContentMaterialParser.Segment> segments,
      List<String> notes) {}

  /** 1、核对显式窗口；2、小材料完整；3、大材料按命中字词和邻域取整段；4、返回遗漏/超界事实。 */
  public static Result select(
      ContentMaterialStore.Material material, String question, Long from, Long to) {
    var scope = ContentSelection.full(material, from, to);
    var segments = scope.segments();
    String hash = ReferenceMaterialStore.hash(Objects.toString(question, "").strip());
    if (scope.selectedChars() <= MAX_CHARS && segments.size() <= MAX_SEGMENTS)
      return result("CONTENT_COMPLETE_SMALL", hash, scope, segments.size(), 0, segments);
    if (from != null) {
      List<ContentMaterialParser.Segment> chosen = new ArrayList<>();
      int chars = 0, large = 0;
      for (var segment : segments) {
        if (segment.text().length() > MAX_CHARS) {
          large++;
          break;
        }
        if (chosen.size() == MAX_SEGMENTS || chars + segment.text().length() > MAX_CHARS) break;
        chosen.add(segment);
        chars += segment.text().length();
      }
      return result("CONTENT_EXPLICIT_WINDOW_PREFIX", hash, scope, segments.size(), large, chosen);
    }
    var terms = terms(question);
    Map<Integer, Integer> scores = new HashMap<>();
    int large = 0;
    for (int i = 0; i < segments.size(); i++) {
      String body = normalized(segments.get(i).text());
      int score = 0;
      for (String term : terms) if (body.contains(term)) score += term.length();
      if (score > 0) {
        scores.put(i, score);
        if (segments.get(i).text().length() > MAX_CHARS) large++;
      }
    }
    var ranked = new ArrayList<>(scores.keySet());
    ranked.sort(Comparator.<Integer>comparingInt(scores::get).reversed().thenComparingInt(i -> i));
    Set<Integer> selected = new LinkedHashSet<>();
    int chars = 0;
    for (int at : ranked) {
      for (int i : new int[] {at, at - 1, at + 1}) {
        if (i < 0
            || i >= segments.size()
            || selected.contains(i)
            || selected.size() == MAX_SEGMENTS) continue;
        var segment = segments.get(i);
        if (chars + segment.text().length() > MAX_CHARS) continue;
        selected.add(i);
        chars += segment.text().length();
      }
    }
    var picked = selected.stream().sorted().map(segments::get).toList();
    return result(VERSION, hash, scope, scores.size(), large, picked);
  }

  /** 原文定位直接沿用解析结果，不截断句子或伪造媒体偏移。 */
  private static Result result(
      String strategy,
      String query,
      ContentSelection scope,
      int matched,
      int large,
      List<ContentMaterialParser.Segment> chosen) {
    var notes = new ArrayList<String>();
    notes.add("用户明确选择的背景不证明它属于当前作品或认证创作者意图，需自行核对来源。");
    if (chosen.isEmpty()) notes.add("本次没有可纳入的内容原段；不代表原材料没有相关信息，可调整问题或字幕时间范围。");
    if (chosen.size() < scope.eligibleSegments()) notes.add("仅选入有限完整段，未选范围没有语义结论；词法命中不是语义相关性评分。");
    if (large > 0) notes.add("部分命中原段超过单次8000字符背景容量，未截断该段，请改用较小材料或独立内容分析。");
    return new Result(
        strategy,
        query,
        scope.totalSegments(),
        scope.eligibleSegments(),
        matched,
        chosen.stream().mapToInt(x -> x.text().length()).sum(),
        large,
        List.copyOf(chosen),
        List.copyOf(notes));
  }

  /** 去掉泛化操作词后提取有界字词/汉字邻接片，规则固定，不借LLM猜检索目标。 */
  static List<String> terms(String question) {
    String value =
        normalized(Objects.toString(question, ""))
            .replaceAll(
                "请问|请你|一下|这个|视频|下面|用户|所有|全部|评论|分析|总结|整理|观点|是否|关于|根据|内容|材料|给我|对照|误解|创作者|意图", " ");
    var matcher = Pattern.compile("[\\p{IsHan}]{2,}|[a-z0-9_]{2,}").matcher(value);
    Set<String> result = new LinkedHashSet<>();
    while (matcher.find() && result.size() < 48) {
      String token = matcher.group();
      if (token.length() <= 24) result.add(token);
      if (token
          .codePoints()
          .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
        for (int n = 0; n + 2 <= token.length() && result.size() < 48; n++)
          result.add(token.substring(n, n + 2));
    }
    return List.copyOf(result);
  }

  /** 规范化仅用于匹配，不改变保存与展示原文。 */
  private static String normalized(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }
}
