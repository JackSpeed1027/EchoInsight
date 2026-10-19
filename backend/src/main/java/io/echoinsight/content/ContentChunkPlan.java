/* 文件职责：把明确范围的全部原文确定性分片，冻结唯一覆盖；不扩大解析容量或丢弃尾部。 */
package io.echoinsight.content;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 分片只负责输入覆盖，不代表模型已理解；24片上限同时约束任务调用数和后续汇总上下文。 */
public record ContentChunkPlan(
    String version, List<Chunk> chunks, int segmentCount, int textChars) {
  public static final String VERSION = "content-chunks-v1";
  public static final int MAX_CHUNKS = 24, MAX_SEGMENTS = 100;

  /** 分片只保存原段身份，不复制正文或建立第二状态机。 */
  public record Chunk(int index, List<String> segmentIds, int textChars) {}

  /** 1、按原顺序装完整段；2、达到100段/12000字符换片；3、超过24片明确拒绝；4、冻结覆盖。 */
  public static ContentChunkPlan create(ContentSelection selection) {
    if (selection == null || selection.segments() == null || selection.segments().isEmpty())
      throw invalid("所选范围没有正文，请调整材料或时间范围");
    List<Chunk> chunks = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int chars = 0, total = 0;
    for (var segment : selection.segments()) {
      if (segment == null
          || segment.id() == null
          || !seen.add(segment.id())
          || segment.text() == null
          || segment.text().isBlank()
          || segment.text().length() > ContentSelection.MAX_CHARS)
        throw invalid("原段无效或不能完整放入分片，请检查材料");
      if (!ids.isEmpty()
          && (ids.size() == MAX_SEGMENTS
              || chars + segment.text().length() > ContentSelection.MAX_CHARS)) {
        chunks.add(new Chunk(chunks.size(), List.copyOf(ids), chars));
        ids.clear();
        chars = 0;
      }
      ids.add(segment.id());
      chars += segment.text().length();
      total += segment.text().length();
    }
    chunks.add(new Chunk(chunks.size(), List.copyOf(ids), chars));
    if (chunks.size() > MAX_CHUNKS)
      throw invalid("完整分析最多24片，每片100完整段/12000字符；请缩小字幕时间范围或拆分材料，不能静默截取");
    return new ContentChunkPlan(VERSION, List.copyOf(chunks), seen.size(), total);
  }

  /** 恢复前重新计算并逐字段相等核对，防止遗漏、重复、重排或版本漂移。 */
  public void verify(ContentSelection selection) {
    if (!equals(create(selection))
        || selection.truncated()
        || segmentCount != selection.eligibleSegments()
        || textChars != selection.selectedChars())
      throw new IllegalStateException("Content chunk coverage mismatch");
  }

  /** 获得该片真实原文，沿用原身份/范围；片外数量只用于该次调用，最终统计由完整清单决定。 */
  public ContentSelection select(ContentSelection full, Chunk chunk) {
    var byId = new HashMap<String, ContentMaterialParser.Segment>();
    full.segments().forEach(x -> byId.put(x.id(), x));
    var segments = chunk.segmentIds().stream().map(byId::get).toList();
    if (segments.contains(null)) throw new IllegalStateException("Missing content chunk segment");
    return new ContentSelection(
        full.version(),
        full.materialId(),
        full.materialSha256(),
        full.segmentsSha256(),
        full.fromMs(),
        full.toMs(),
        full.totalSegments(),
        full.eligibleSegments(),
        full.outsideRange(),
        full.eligibleSegments() - segments.size(),
        chunk.textChars(),
        segments);
  }

  /** 计划能力不足在派工前拒绝，不把容量问题伪装成完整成功。 */
  private static ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }
}
