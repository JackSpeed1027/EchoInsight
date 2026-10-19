/* 文件职责：按来源评论身份比较两份版本，先区分正文/父语境变化，再对可比较的命题标签计数。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.material.ReferenceMaterialStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** 不做语义归并或跨版本模型结果复用；缺失一律UNKNOWN_MISSING，不推断平台删除。 */
@Component
public class StanceRevisionDiffer {
  /** 状态互斥；标签变化只对COMPARABLE且原文/完整祖先一致的条目判断。 */
  public record Row(
      String sourceCommentId,
      String category,
      ImportDocument.Comment beforeComment,
      ImportDocument.Comment afterComment,
      JsonNode beforeDecision,
      JsonNode afterDecision,
      String beforeLabel,
      String afterLabel,
      boolean labelChanged) {}

  /** counts之和等于来源ID并集；transitions只含可比条目，不是全部观众的立场迁移。 */
  public record Result(
      List<Row> rows, Map<String, Integer> counts, Map<String, Integer> transitions) {}

  /** 1、对齐来源ID；2、比较自身及祖先指纹；3、核对可比资格；4、计算原标签差异，绝不重判语义。 */
  public Result compare(
      ImportDocument before,
      ImportDocument after,
      JsonNode beforeArtifact,
      JsonNode afterArtifact,
      boolean compatible) {
    var old = bySource(before);
    var now = bySource(after);
    var oldOwn = ownHashes(before);
    var newOwn = ownHashes(after);
    var oldContext = contextHashes(before, oldOwn);
    var newContext = contextHashes(after, newOwn);
    var oldDecisions = decisions(beforeArtifact);
    var newDecisions = decisions(afterArtifact);
    Set<String> identities = new TreeSet<>(old.keySet());
    identities.addAll(now.keySet());
    List<Row> rows = new ArrayList<>();
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String category :
        List.of(
            "ADDED",
            "UNKNOWN_MISSING",
            "REVISED",
            "CONTEXT_CHANGED",
            "COMPARABLE",
            "NOT_COMPARABLE")) {
      counts.put(category, 0);
    }
    Map<String, Integer> transitions = new TreeMap<>();
    for (String id : identities) {
      var previous = old.get(id);
      var current = now.get(id);
      JsonNode left = previous == null ? null : oldDecisions.get(previous.id());
      JsonNode right = current == null ? null : newDecisions.get(current.id());
      String category;
      if (previous == null) {
        category = "ADDED";
      } else if (current == null) {
        category = "UNKNOWN_MISSING";
      } else if (!Objects.equals(oldOwn.get(previous.id()), newOwn.get(current.id()))) {
        category = "REVISED";
      } else if (!Objects.equals(oldContext.get(previous.id()), newContext.get(current.id()))) {
        category = "CONTEXT_CHANGED";
      } else {
        category = compatible && left != null && right != null ? "COMPARABLE" : "NOT_COMPARABLE";
      }
      String beforeLabel = label(left), afterLabel = label(right);
      boolean changed = category.equals("COMPARABLE") && !beforeLabel.equals(afterLabel);
      if (category.equals("COMPARABLE")) {
        transitions.merge(beforeLabel + " → " + afterLabel, 1, Integer::sum);
      }
      rows.add(
          new Row(id, category, previous, current, left, right, beforeLabel, afterLabel, changed));
      counts.merge(category, 1, Integer::sum);
    }
    return new Result(List.copyOf(rows), Map.copyOf(counts), Map.copyOf(transitions));
  }

  /** 按覆盖类型和立场集合记录标签；同义措辞或理由改写不冒充语义变化检测。 */
  public static String label(JsonNode decision) {
    if (decision == null) {
      return "NOT_ANALYZED";
    }
    String coverage = decision.path("coverage").asText();
    Set<String> positions = new TreeSet<>();
    for (var claim : decision.path("claims")) {
      positions.add(claim.path("position").asText());
    }
    return coverage + (positions.isEmpty() ? "" : ":" + String.join("+", positions));
  }

  /** 只索引原产物中的决定，不把证据出现当成已分类。 */
  private Map<String, JsonNode> decisions(JsonNode artifact) {
    Map<String, JsonNode> result = new HashMap<>();
    for (var decision : artifact.path("stance").path("decisions")) {
      if (result.put(decision.path("commentId").asText(), decision) != null) {
        throw new IllegalStateException("Duplicate stored decision");
      }
    }
    return result;
  }

  /** 源ID而不是内部ID决定同一条评论；导入生成不同内部ID不应变成新增。 */
  private Map<String, ImportDocument.Comment> bySource(ImportDocument input) {
    Map<String, ImportDocument.Comment> result = new TreeMap<>();
    for (var comment : input.comments()) {
      if (result.put(comment.sourceCommentId(), comment) != null) {
        throw new IllegalStateException("Duplicate source comment");
      }
    }
    return result;
  }

  /** 发生时间统一到Instant；父引用缺口保留原身份，不假造父节点。 */
  private Map<String, String> ownHashes(ImportDocument input) {
    Map<String, ImportDocument.Comment> byId = new HashMap<>();
    input.comments().forEach(c -> byId.put(c.id(), c));
    Map<String, String> result = new HashMap<>();
    for (var c : input.comments()) {
      var parent = byId.get(c.parentId());
      String identity =
          c.parentId() == null
              ? "ROOT"
              : parent == null ? "MISSING:" + c.parentId() : "PARENT:" + parent.sourceCommentId();
      result.put(
          c.id(),
          hash(
              List.of(
                  c.sourceCommentId(), identity, c.text(), c.occurredAt().toInstant().toString())));
    }
    return result;
  }

  /** 迭代记忆化全祖先指纹，5000层不使用递归；缺父身份会传播到依赖后代。 */
  private Map<String, String> contextHashes(ImportDocument input, Map<String, String> own) {
    Map<String, ImportDocument.Comment> byId = new HashMap<>();
    input.comments().forEach(c -> byId.put(c.id(), c));
    Map<String, String> memo = new HashMap<>();
    for (var comment : input.comments()) {
      List<String> chain = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String cursor = comment.id();
      while (cursor != null && byId.containsKey(cursor) && !memo.containsKey(cursor)) {
        if (!seen.add(cursor)) {
          throw new IllegalStateException("Stored comment cycle");
        }
        chain.add(cursor);
        cursor = byId.get(cursor).parentId();
      }
      String parentHash = cursor == null ? "ROOT" : memo.getOrDefault(cursor, "MISSING:" + cursor);
      for (int i = chain.size() - 1; i >= 0; i--) {
        String id = chain.get(i);
        parentHash = hash(List.of(own.get(id), parentHash));
        memo.put(id, parentHash);
      }
    }
    return memo;
  }

  /** JSON数组的长度/转义边界避免正文内分隔符碰撞。 */
  private String hash(List<String> fields) {
    return ReferenceMaterialStore.hash(io.echoinsight.model.ModelContract.encode(fields));
  }
}
