/* 文件职责：规划普通讨论的唯一目标分片和有界归并树；规则版本随冻结输入保存。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.ModelContract;
import java.util.*;

/** 固定分片和归并属于持久工作流，不是模型动态派工或另一套调度器。 */
public final class DiscussionDigestPlan {
  public static final String VERSION = "discussion-tree-v1",
      PROMPT = "discussion-json-v7-tree",
      TAXONOMY = "argument-tree-v1";
  public static final int TARGETS = 40, FAN_IN = 8, MAX_CALLS = 512;

  /** 无状态规划器。 */
  private DiscussionDigestPlan() {}

  /** 子节点均先于本节点，索引复用原model_batch.batch_index。 */
  public record Node(int index, String phase, List<Integer> children) {}

  /** 各阶段短摘要及依赖，用于原文回看；不是第二份任务状态。 */
  public record NodeResult(
      int index,
      String phase,
      List<Integer> children,
      List<String> targetIds,
      io.echoinsight.model.DiscussionDigestContract.Summary summary) {}

  /** 产物只声明已处理的冻结输入，不声明平台齐全或语义准确。 */
  public record Processing(
      String strategy,
      int mapCount,
      int reductionCount,
      int completedMaps,
      int completedReductions,
      int targetComments,
      int contextOccurrences,
      List<NodeResult> nodes,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          DiscussionDigestReuse.Receipt reuse) {
    /** 未选择基准仍保留旧构造方式。 */
    public Processing(
        String strategy,
        int mapCount,
        int reductionCount,
        int completedMaps,
        int completedReductions,
        int targetComments,
        int contextOccurrences,
        List<NodeResult> nodes) {
      this(
          strategy,
          mapCount,
          reductionCount,
          completedMaps,
          completedReductions,
          targetComments,
          contextOccurrences,
          nodes,
          null);
    }
  }

  /** 两个版本字段必须成对匹配，旧v3～v6不能按新协议解释。 */
  public static boolean enabled(ModelContract.Plan plan) {
    return PROMPT.equals(plan.promptVersion()) && TAXONOMY.equals(plan.taxonomyVersion());
  }

  /** 1、核对回复图；2、每目标只归属一片；3、保留祖先；4、核对整树容量。 */
  public static StanceChunkPlanner.Manifest plan(String snapshotId, ImportDocument input) {
    var result = StanceChunkPlanner.plan(snapshotId, input, TARGETS, VERSION);
    if (result.chunks().isEmpty()
        || result.chunks().size() + reductions(result.chunks().size()).size() > MAX_CALLS)
      throw new ModelContract.Failure("DIGEST_CAPACITY_EXCEEDED");
    return result;
  }

  /** 按8路逐层归并，尾组不丢弃；即使只有一片仍执行一次独立FINAL。 */
  public static List<Node> reductions(int maps) {
    if (maps < 1 || maps > MAX_CALLS) throw new ModelContract.Failure("DIGEST_CAPACITY_EXCEEDED");
    var current = new ArrayList<Integer>();
    for (int i = 0; i < maps; i++) current.add(i);
    List<Node> result = new ArrayList<>();
    int next = maps;
    while (current.size() > FAN_IN) {
      var parents = new ArrayList<Integer>();
      for (int from = 0; from < current.size(); from += FAN_IN) {
        var children = List.copyOf(current.subList(from, Math.min(from + FAN_IN, current.size())));
        result.add(new Node(next, "REDUCE", children));
        parents.add(next++);
      }
      current = parents;
    }
    result.add(new Node(next, "FINAL", List.copyOf(current)));
    return List.copyOf(result);
  }
}
