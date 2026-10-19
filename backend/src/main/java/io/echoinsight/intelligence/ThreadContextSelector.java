/* 文件职责：按线程广度稳定选择有界评论，确保任何被发送的回复都同时带有完整祖先上下文。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.ModelContract;
import java.util.*;
import org.springframework.stereotype.Component;

/** 选择器不评判语义重要性；公平覆盖根线程，记录截断，拒绝断裂或循环输入。 */
@Component
public class ThreadContextSelector {
  public static final String VERSION = "thread-context-bfs-v1";

  /** 缺口输入采用独立选择规则身份；无缺口历史任务保持原版本和输入。 */
  public static String versionFor(ImportDocument input) {
    return input.contextGaps().isEmpty() ? VERSION : "thread-context-bfs-v2-gaps";
  }

  public static final int MAX_COMMENTS = 120;
  public static final int MAX_TEXT_CHARS = 80000;

  /** 记录选中的完整文本、线程数和字符数，不能把selected数量说成平台全部数量。 */
  public record Selection(
      List<ImportDocument.Comment> comments,
      int totalComments,
      int selectedThreads,
      int selectedTextChars,
      boolean truncated) {}

  /** 生产入口固定安全边界；输入必须来自已经校验并冻结的快照。 */
  public Selection select(List<ImportDocument.Comment> input) {
    return select(input, MAX_COMMENTS, MAX_TEXT_CHARS, Set.of());
  }

  /** 文档入口允许显式声明缺口，但不发送缺父评论及其后代，维持模型输入的祖先闭包。 */
  public Selection select(ImportDocument document) {
    return select(document, MAX_COMMENTS, MAX_TEXT_CHARS);
  }

  /** 命题协议使用更小输出边界，但沿用同一祖先闭包、缺口和稳定排序规则。 */
  public Selection select(ImportDocument document, int commentLimit, int textLimit) {
    Map<String, ImportDocument.Comment> comments = new HashMap<>();
    document.comments().forEach(c -> comments.put(c.id(), c));
    Set<String> gaps = new HashSet<>();
    for (var gap : document.contextGaps()) {
      var comment = comments.get(gap.commentId());
      if (comment == null
          || !Objects.equals(comment.parentId(), gap.parentCommentId())
          || comments.containsKey(gap.parentCommentId())
          || !gaps.add(gap.commentId()))
        throw new ModelContract.Failure("MODEL_INVALID_INPUT_TREE");
    }
    return select(document.comments(), commentLimit, textLimit, gaps);
  }

  /** 测试用边界入口仍严格拒绝未声明断边，不降低既有导入契约。 */
  Selection select(List<ImportDocument.Comment> input, int commentLimit, int textLimit) {
    return select(input, commentLimit, textLimit, Set.of());
  }

  /** 1、建立回复树；2、根线程优先并广度展开；3、仅接受祖先已选且容量足够的节点；4、核对整图与范围。 */
  private Selection select(
      List<ImportDocument.Comment> input, int commentLimit, int textLimit, Set<String> gaps) {
    if (input == null
        || input.isEmpty()
        || input.size() > 5000
        || commentLimit < 1
        || commentLimit > MAX_COMMENTS
        || textLimit < 1
        || textLimit > MAX_TEXT_CHARS) throw new ModelContract.Failure("MODEL_INVALID_INPUT_SCOPE");
    Map<String, ImportDocument.Comment> byId = new HashMap<>();
    for (var comment : input)
      if (byId.putIfAbsent(comment.id(), comment) != null)
        throw new ModelContract.Failure("MODEL_INVALID_INPUT_TREE");
    Map<String, List<ImportDocument.Comment>> children = new HashMap<>();
    List<ImportDocument.Comment> roots = new ArrayList<>();
    for (var comment : input) {
      if (comment.parentId() == null) roots.add(comment);
      else {
        if (!byId.containsKey(comment.parentId())) {
          if (!gaps.contains(comment.id()))
            throw new ModelContract.Failure("MODEL_INVALID_INPUT_TREE");
          // 仅把断边作为遍历起点用于验证后代；选择条件会排除它及全部依赖后代。
          roots.add(comment);
        } else
          children.computeIfAbsent(comment.parentId(), ignored -> new ArrayList<>()).add(comment);
      }
    }
    var ordering =
        Comparator.comparing(ImportDocument.Comment::sourceCommentId)
            .thenComparing(ImportDocument.Comment::id);
    roots.sort(ordering);
    children.values().forEach(rows -> rows.sort(ordering));
    Deque<ImportDocument.Comment> queue = new ArrayDeque<>(roots);
    List<ImportDocument.Comment> selected = new ArrayList<>();
    Set<String> selectedIds = new HashSet<>(), visited = new HashSet<>();
    int characters = 0, threads = 0;
    while (!queue.isEmpty()) {
      var comment = queue.removeFirst();
      if (!visited.add(comment.id())) throw new ModelContract.Failure("MODEL_INVALID_INPUT_TREE");
      queue.addAll(children.getOrDefault(comment.id(), List.of()));
      if (selected.size() >= commentLimit
          || (comment.parentId() != null && !selectedIds.contains(comment.parentId()))) continue;
      int length = comment.text().length();
      if (length > textLimit - characters) continue;
      selected.add(comment);
      selectedIds.add(comment.id());
      characters += length;
      if (comment.parentId() == null) threads++;
    }
    if (visited.size() != input.size()) throw new ModelContract.Failure("MODEL_INVALID_INPUT_TREE");
    if (selected.isEmpty()) throw new ModelContract.Failure("MODEL_EMPTY_INPUT");
    return new Selection(
        List.copyOf(selected), input.size(), threads, characters, selected.size() < input.size());
  }
}
