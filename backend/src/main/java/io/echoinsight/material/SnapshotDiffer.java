/* 文件职责：按稳定来源评论身份比较两个快照，并识别父上下文修订引起的依赖失效。 */
package io.echoinsight.material;

import io.echoinsight.ingestion.ImportDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

/** 确定性比较不调用模型，不推断平台删除，也不把潜在可复用计数当实际模型节省。 */
@Component
public class SnapshotDiffer {
  /** 所有计数基于来源身份；样本最多100条，完整计数仍由全量输入得到。 */
  public record Difference(
      int added,
      int revised,
      int unknownMissing,
      int unchanged,
      int parentChanged,
      int contextInvalidated,
      int contextStableCandidates,
      List<String> addedSamples,
      List<String> revisedSamples,
      List<String> missingSamples) {}

  /** 1、建立来源与父来源映射；2、比较自身修订；3、比较祖先上下文；4、输出有界诊断。 */
  public Difference compare(ImportDocument before, ImportDocument after) {
    var oldRows = index(before);
    var newRows = index(after);
    var oldContext = contexts(oldRows);
    var newContext = contexts(newRows);
    List<String> added = new ArrayList<>(),
        revised = new ArrayList<>(),
        missing = new ArrayList<>();
    int unchanged = 0, parentChanged = 0, contextInvalidated = 0, stable = 0;
    for (var entry : newRows.entrySet()) {
      Row previous = oldRows.get(entry.getKey());
      Row current = entry.getValue();
      if (previous == null) added.add(entry.getKey());
      else if (!previous.equals(current)) {
        revised.add(entry.getKey());
        if (!Objects.equals(previous.parentSourceId(), current.parentSourceId())) parentChanged++;
      } else {
        unchanged++;
        if (!Objects.equals(oldContext.get(entry.getKey()), newContext.get(entry.getKey())))
          contextInvalidated++;
        else stable++;
      }
    }
    for (String id : oldRows.keySet()) if (!newRows.containsKey(id)) missing.add(id);
    return new Difference(
        added.size(),
        revised.size(),
        missing.size(),
        unchanged,
        parentChanged,
        contextInvalidated,
        stable,
        sample(added),
        sample(revised),
        sample(missing));
  }

  /** 比较来源ID而非数据库内ID，避免导入渠道差异被误认为正文修订。 */
  private Map<String, Row> index(ImportDocument input) {
    Map<String, String> sourceById = new HashMap<>();
    input.comments().forEach(comment -> sourceById.put(comment.id(), comment.sourceCommentId()));
    Map<String, Row> result = new TreeMap<>();
    for (var comment : input.comments()) {
      String parent = comment.parentId() == null ? null : sourceById.get(comment.parentId());
      if (comment.parentId() != null && parent == null)
        throw new IllegalStateException("Frozen snapshot has missing parent");
      var row =
          new Row(
              comment.sourceCommentId(),
              parent,
              comment.text(),
              comment.occurredAt().toInstant().toString());
      if (result.putIfAbsent(comment.sourceCommentId(), row) != null)
        throw new IllegalStateException("Frozen snapshot has duplicate source identity");
    }
    return result;
  }

  /** 用迭代记忆化计算祖先链指纹，避免5000层回复树导致Java栈溢出。 */
  private Map<String, String> contexts(Map<String, Row> rows) {
    Map<String, String> memo = new HashMap<>();
    for (String id : rows.keySet()) {
      List<String> chain = new ArrayList<>();
      Set<String> visited = new HashSet<>();
      String cursor = id;
      while (cursor != null && !memo.containsKey(cursor)) {
        if (!visited.add(cursor)) throw new IllegalStateException("Frozen snapshot has a cycle");
        Row row = rows.get(cursor);
        if (row == null) throw new IllegalStateException("Frozen snapshot has missing ancestor");
        chain.add(cursor);
        cursor = row.parentSourceId();
      }
      String parentHash = cursor == null ? "ROOT" : memo.get(cursor);
      for (int index = chain.size() - 1; index >= 0; index--) {
        Row row = rows.get(chain.get(index));
        parentHash =
            hash(row.sourceId(), row.parentSourceId(), row.text(), row.occurredAt(), parentHash);
        memo.put(row.sourceId(), parentHash);
      }
    }
    return memo;
  }

  /** 长度前缀避免正文包含分隔符时产生相同拼接；哈希只表示确定性输入身份。 */
  private String hash(String... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String field : fields) {
        byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** 样本稳定排序且有界；界面显示数量时不能只数这100条。 */
  private List<String> sample(List<String> sourceIds) {
    return sourceIds.stream().sorted().limit(100).toList();
  }

  /** 自身修订包含正文、直接父来源和发生时间，时间相同时区差异不构成修订。 */
  private record Row(String sourceId, String parentSourceId, String text, String occurredAt) {}
}
