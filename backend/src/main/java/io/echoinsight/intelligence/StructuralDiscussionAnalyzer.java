/* 文件职责：在免费模式下把评论线程整理为可核查结构，不伪造主题归纳或论证胜负。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.orchestration.JobStore;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 无外部模型的讨论结构分析器。
 *
 * <p>它提供可运行的请求、任务、产物和证据链，用于验证工程流程。语义总结和“谁更有道理”仍需真实模型与独立质量验收，因此结果会明确标记 STRUCTURAL_DEMO。
 */
@Service
public class StructuralDiscussionAnalyzer {
  private static final int MAX_THREADS = 5;
  private static final int MAX_REPLIES_PER_THREAD = 6;

  /**
   * 从不可变快照生成有界产物。
   *
   * <p>1、按 parentId 恢复根评论与回复；2、优先选择含回复或疑问的讨论串；3、保留原文观点和确定的回复关系；4、评议统一返回证据不足并写出能力边界。
   */
  public DiscussionArtifact analyze(
      JobStore.Job job,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId) {
    Map<String, ImportDocument.Comment> byId = new LinkedHashMap<>();
    Map<String, List<ImportDocument.Comment>> children = new LinkedHashMap<>();
    for (var comment : input.comments()) {
      byId.put(comment.id(), comment);
      if (comment.parentId() != null)
        children.computeIfAbsent(comment.parentId(), ignored -> new ArrayList<>()).add(comment);
    }
    List<ImportDocument.Comment> roots =
        input.comments().stream()
            .filter(c -> c.parentId() == null)
            .sorted(
                Comparator.<ImportDocument.Comment>comparingInt(
                        c -> discussionScore(c, children.getOrDefault(c.id(), List.of())))
                    .reversed()
                    .thenComparing(ImportDocument.Comment::occurredAt))
            .toList();

    List<DiscussionArtifact.Issue> issues = new ArrayList<>();
    List<DiscussionArtifact.Claim> claims = new ArrayList<>();
    List<DiscussionArtifact.Relation> relations = new ArrayList<>();
    LinkedHashMap<String, DiscussionArtifact.Evidence> evidence = new LinkedHashMap<>();
    List<DiscussionArtifact.Assessment> assessments = new ArrayList<>();
    int threadCount = Math.min(MAX_THREADS, roots.size());
    for (int index = 0; index < threadCount; index++) {
      var root = roots.get(index);
      String issueId = "issue-" + (index + 1);
      List<ImportDocument.Comment> replies = descendants(root.id(), children);
      List<ImportDocument.Comment> selectedReplies =
          replies.subList(0, Math.min(MAX_REPLIES_PER_THREAD, replies.size()));
      remember(evidence, root);
      issues.add(
          new DiscussionArtifact.Issue(
              issueId,
              question(root.text()),
              "该讨论串包含 " + replies.size() + " 条回复。当前免费模式保留讨论结构和原文，尚未执行语义归并。",
              List.of(root.id())));
      Map<String, String> claimByComment = new HashMap<>();
      for (int replyIndex = 0; replyIndex < selectedReplies.size(); replyIndex++) {
        var reply = selectedReplies.get(replyIndex);
        String claimId = "claim-" + (index + 1) + "-" + (replyIndex + 1);
        claimByComment.put(reply.id(), claimId);
        remember(evidence, reply);
        claims.add(
            new DiscussionArtifact.Claim(
                claimId, issueId, abbreviate(reply.text(), 300), "UNKNOWN", List.of(reply.id())));
      }
      for (var reply : selectedReplies) {
        String childClaim = claimByComment.get(reply.id());
        String parentClaim = claimByComment.get(reply.parentId());
        if (childClaim != null && parentClaim != null) {
          relations.add(
              new DiscussionArtifact.Relation(
                  childClaim,
                  parentClaim,
                  "REPLY_TO",
                  "UNKNOWN",
                  List.of(reply.id(), reply.parentId())));
        }
      }
      if (request.includeAssessment()) {
        assessments.add(
            new DiscussionArtifact.Assessment(
                issueId,
                "INSUFFICIENT",
                "当前模式不能判断哪一方更有道理。",
                "回复结构只能证明谁回复了谁，不能证明支持、反驳、事实真假或推理充分性。",
                List.of(root.id())));
      }
    }
    int replyCount = input.comments().size() - roots.size();
    boolean truncated = roots.size() > MAX_THREADS || claims.size() < replyCount;
    var result =
        new DiscussionArtifact(
            "discussion-artifact-v1",
            artifactId,
            request.id(),
            job.id(),
            job.snapshotId(),
            request.instruction(),
            input.content().title(),
            input.content().sourceType(),
            "STRUCTURAL_DEMO",
            new DiscussionArtifact.Scope(
                input.comments().size(), roots.size(), replyCount, threadCount, truncated),
            List.copyOf(issues),
            List.copyOf(claims),
            List.copyOf(relations),
            List.copyOf(assessments),
            List.copyOf(evidence.values()),
            List.of(
                "当前资料快照的范围不等于平台全量；平台声明计数、已采集数量和本次展示数量需要分别阅读。",
                "当前未调用真实大模型，议题标题来自原始根评论，不代表已经完成语义总结。",
                "REPLY_TO 仅表示回复结构；支持、反驳、事实真实性和推理充分性均保持未知。",
                "每次最多展示5个讨论串、每串6条回复；scope.truncated=true 时结果不是全量展示。"));
    if (input.contextGaps().isEmpty()) return result;
    var limits = new ArrayList<>(result.limitations());
    limits.add("原资料有缺失父评论；已保留原始父引用，断边评论不被冒充根线程或新的回复关系。");
    return new DiscussionArtifact(
        result.schemaVersion(),
        result.artifactId(),
        result.requestId(),
        result.jobId(),
        result.snapshotId(),
        result.instruction(),
        result.title(),
        result.sourceType(),
        result.sourceMode(),
        result.scope(),
        result.issues(),
        result.claims(),
        result.relations(),
        result.assessments(),
        result.evidence(),
        List.copyOf(limits));
  }

  /** 回复多、带疑问的根评论优先，排序仍由代码确定且可复现。 */
  private int discussionScore(ImportDocument.Comment root, List<ImportDocument.Comment> direct) {
    return direct.size() * 10 + (root.text().matches(".*[?？].*") ? 5 : 0);
  }

  /** 广度优先收集全部后代，保证父级通常先于子级出现；seen 防止异常环导致重复。 */
  private List<ImportDocument.Comment> descendants(
      String rootId, Map<String, List<ImportDocument.Comment>> children) {
    List<ImportDocument.Comment> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    ArrayDeque<ImportDocument.Comment> queue = new ArrayDeque<>();
    queue.addAll(children.getOrDefault(rootId, List.of()));
    while (!queue.isEmpty()) {
      var next = queue.removeFirst();
      if (!seen.add(next.id())) continue;
      result.add(next);
      queue.addAll(children.getOrDefault(next.id(), List.of()));
    }
    return result;
  }

  /** 议题标题保留可读原文；非疑问句用“围绕”表达，避免改写成模型结论。 */
  private String question(String text) {
    String clean = abbreviate(text, 180);
    return clean.matches(".*[?？].*") ? clean : "围绕“" + clean + "”的讨论";
  }

  /** 展示摘要有界，完整原文仍保存在 Evidence 中。 */
  private String abbreviate(String text, int max) {
    String clean = text.strip().replaceAll("\\s+", " ");
    return clean.length() <= max ? clean : clean.substring(0, max - 1) + "…";
  }

  /** 同一条原文只保存一次，引用身份来自当前快照而不是模型生成。 */
  private void remember(
      Map<String, DiscussionArtifact.Evidence> evidence, ImportDocument.Comment comment) {
    evidence.putIfAbsent(
        comment.id(),
        new DiscussionArtifact.Evidence(
            comment.id(), comment.sourceCommentId(), comment.parentId(), comment.text()));
  }
}
