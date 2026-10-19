/* 文件职责：为完整命题分析分配唯一评论归属，重复父语境仅供理解，不重复统计。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.StanceProtocol;
import io.echoinsight.review.FindingCatalog;
import java.util.*;

/** 稳定、无网络的分片规划器：不截断正文、不猜缺父，不因分片边界改变统计分母。 */
public final class StanceChunkPlanner {
  private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  public static final String VERSION = "stance-partition-v1";
  public static final String PROMPT = "discussion-json-v6-partition";
  public static final String TAXONOMY = "proposition-partition-v1";

  /** 无状态规划器不持有项目、连接或模型客户端。 */
  private StanceChunkPlanner() {}

  /** 每片最多12个归属目标，输入可包含最多120条/80000字符的完整父语境。 */
  public record Chunk(int index, List<String> targetIds, List<String> inputIds, int textChars) {}

  /** 快照和正文指纹固定后，重试不得按当前资料或新版算法重新定义目标。 */
  public record Manifest(
      String version,
      String snapshotId,
      String documentHash,
      int targetComments,
      int contextOccurrences,
      List<Chunk> chunks) {}

  /** 1、校验整个回复图和缺口；2、按稳定广度顺序逐条归属；3、按父闭包/字符边界分片；4、返回无遗漏清单。 单条所需祖先已经超界时明确失败，不能把它伪装为无关或跳过后宣称完整。 */
  public static Manifest plan(String snapshotId, ImportDocument document) {
    if (!document.contextGaps().isEmpty()) fail("FULL_CONTEXT_MISSING");
    new ThreadContextSelector().select(document);
    Map<String, ImportDocument.Comment> byId = new LinkedHashMap<>();
    Map<String, List<ImportDocument.Comment>> children = new HashMap<>();
    List<ImportDocument.Comment> roots = new ArrayList<>();
    for (var comment : document.comments()) {
      byId.put(comment.id(), comment);
      if (comment.parentId() == null) roots.add(comment);
      else children.computeIfAbsent(comment.parentId(), ignored -> new ArrayList<>()).add(comment);
    }
    var ordering =
        Comparator.comparing(ImportDocument.Comment::sourceCommentId)
            .thenComparing(ImportDocument.Comment::id);
    roots.sort(ordering);
    children.values().forEach(list -> list.sort(ordering));
    Deque<ImportDocument.Comment> queue = new ArrayDeque<>(roots);
    List<String> ordered = new ArrayList<>();
    while (!queue.isEmpty()) {
      var comment = queue.removeFirst();
      ordered.add(comment.id());
      queue.addAll(children.getOrDefault(comment.id(), List.of()));
    }
    List<Chunk> chunks = new ArrayList<>();
    List<String> targets = new ArrayList<>();
    Set<String> inputs = new HashSet<>();
    for (String target : ordered) {
      Set<String> candidate = new HashSet<>(inputs);
      addAncestors(candidate, target, byId);
      if (!targets.isEmpty()
          && (targets.size() == StanceProtocol.MAX_COMMENTS || !fits(candidate, byId))) {
        chunks.add(chunk(chunks.size(), targets, inputs, ordered, byId));
        targets = new ArrayList<>();
        inputs = new HashSet<>();
        candidate = new HashSet<>();
        addAncestors(candidate, target, byId);
      }
      if (!fits(candidate, byId)) fail("FULL_CONTEXT_TOO_LARGE");
      targets.add(target);
      inputs = candidate;
    }
    if (!targets.isEmpty()) chunks.add(chunk(chunks.size(), targets, inputs, ordered, byId));
    int occurrences =
        chunks.stream().mapToInt(c -> c.inputIds().size() - c.targetIds().size()).sum();
    return new Manifest(
        VERSION,
        snapshotId,
        documentHash(document),
        document.comments().size(),
        occurrences,
        List.copyOf(chunks));
  }

  /** 分片专用快照指纹明确支持时间，不修改旧模型协议的JSON配置或检查点指纹。 */
  private static String documentHash(ImportDocument document) {
    try {
      return FindingCatalog.hash(SNAPSHOT_JSON.writeValueAsString(document));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("Invalid partition snapshot", error);
    }
  }

  /** 完整祖先只作为输入依赖，不为其复制一个本片目标。 */
  private static void addAncestors(
      Set<String> ids, String id, Map<String, ImportDocument.Comment> byId) {
    String current = id;
    while (current != null && ids.add(current)) current = byId.get(current).parentId();
  }

  /** 输入边界与旧讨论网关相同，输出归属边界独立控制，避免放大输出上限。 */
  private static boolean fits(Set<String> ids, Map<String, ImportDocument.Comment> byId) {
    return ids.size() <= ThreadContextSelector.MAX_COMMENTS
        && ids.stream().mapToInt(id -> byId.get(id).text().length()).sum()
            <= ThreadContextSelector.MAX_TEXT_CHARS;
  }

  /** 记录稳定输入顺序和字符数，恢复时得到相同指纹。 */
  private static Chunk chunk(
      int index,
      List<String> targets,
      Set<String> inputs,
      List<String> ordered,
      Map<String, ImportDocument.Comment> byId) {
    var inputIds = ordered.stream().filter(inputs::contains).toList();
    return new Chunk(
        index,
        List.copyOf(targets),
        inputIds,
        inputIds.stream().mapToInt(id -> byId.get(id).text().length()).sum());
  }

  /** 固定受控码，正文与模型输入不进入异常信息。 */
  private static void fail(String code) {
    throw new ModelContract.Failure(code);
  }
}
