/* 文件职责：按当前问题的字词匹配选取字幕及相邻段，返回可说明的检索范围，不冒充语义召回或全片理解。 */
package io.echoinsight.material;

import io.echoinsight.ingestion.ImportDocument;
import java.text.Normalizer;
import java.util.*;
import org.springframework.stereotype.Component;

/** 无外部调用的确定性基线，优先原文匹配，再加时间邻域；所有选段整段保留，未命中明确返回空。 */
@Component
public class TranscriptRetriever {
  public static final String VERSION = "subtitle-lexical-neighbors-v1";

  /** 检索实际量及策略随请求冻结；原片覆盖未证明，不能用selected/total当理解准确率。 */
  public record Result(
      List<ImportDocument.Segment> segments,
      int matchedSegments,
      int omittedSegments,
      String querySha256,
      String strategy) {}

  /** 1、对问题建立英文词/中文二元组；2、按命中数稳定排序；3、命中段优先、再补前后邻段；4、按原时间输出。 */
  public Result select(List<ImportDocument.Segment> input, String query) {
    String question = query == null ? "" : query.strip();
    if (question.length() > 1000)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "分析要求不能为空且最多1000字");
    List<ImportDocument.Segment> ordered =
        input.stream()
            .sorted(
                Comparator.comparing(ImportDocument.Segment::startMs)
                    .thenComparing(ImportDocument.Segment::id))
            .toList();
    Set<String> tokens = tokens(question);
    List<int[]> ranked = new ArrayList<>();
    for (int i = 0; i < ordered.size(); i++) {
      String body = normalize(ordered.get(i).text());
      int score = 0;
      for (String token : tokens) if (body.contains(token)) score++;
      if (score > 0) ranked.add(new int[] {i, score});
    }
    ranked.sort(Comparator.<int[]>comparingInt(row -> -row[1]).thenComparingInt(row -> row[0]));
    Set<Integer> selected = new LinkedHashSet<>();
    int chars = 0;
    for (int[] row : ranked) {
      int index = row[0];
      if (selected.size() < 40 && chars + ordered.get(index).text().length() <= 8000) {
        selected.add(index);
        chars += ordered.get(index).text().length();
      }
    }
    // 先保障命中段，再补邻域，不因前面的一条长邻段挤掉后面的直接证据。
    for (int index : new ArrayList<>(selected))
      for (int adjacent : new int[] {index - 1, index + 1})
        if (adjacent >= 0
            && adjacent < ordered.size()
            && !selected.contains(adjacent)
            && selected.size() < 40
            && chars + ordered.get(adjacent).text().length() <= 8000) {
          selected.add(adjacent);
          chars += ordered.get(adjacent).text().length();
        }
    List<ImportDocument.Segment> segments = selected.stream().sorted().map(ordered::get).toList();
    return new Result(
        segments,
        ranked.size(),
        input.size() - segments.size(),
        ReferenceMaterialStore.hash(question),
        VERSION);
  }

  /** NFKC/大小写只用于检索，不改写引用原文；中文单字噪声不作为召回词。 */
  private Set<String> tokens(String query) {
    String value = normalize(query);
    Set<String> result = new LinkedHashSet<>();
    var words = java.util.regex.Pattern.compile("[a-z0-9]{2,}|[\\p{IsHan}]{2,}").matcher(value);
    while (words.find()) {
      String word = words.group();
      if (word.codePoints()
          .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
        for (int i = 0; i < word.length() - 1; i++) result.add(word.substring(i, i + 2));
      } else result.add(word);
    }
    return result;
  }

  /** 检索规则是精确归一字词匹配，不称语义向量搜索。 */
  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }
}
