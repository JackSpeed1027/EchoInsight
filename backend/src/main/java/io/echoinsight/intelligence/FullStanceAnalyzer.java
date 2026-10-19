/* 文件职责：顺序执行冻结的完整命题分片，复用原模型检查点，完整对账后才返回可发布产物。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.JobStore;
import java.util.*;

/** 无额外状态的业务编排器；不创建连接、线程池、任务或第二套预算，模型调用仍走原网关。 */
public final class FullStanceAnalyzer {
  private final ModelGateway gateway;

  /** 只接收既有网关引用，所有出站仍使用同一费用与租约边界。 */
  public FullStanceAnalyzer(ModelGateway gateway) {
    this.gateway = gateway;
  }

  /** 1、校验冻结清单与原快照；2、为全局评论建立稳定命名空间；3、逐片复用/执行且核验租约； 4、全局核对唯一目标与全部覆盖；5、由Java统计并发布原文，任一失败不返回部分成功产物。 */
  public DiscussionArtifact analyze(
      JobStore.Job job,
      String token,
      ModelContract.Plan plan,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId,
      Runnable leaseCheck,
      AnalysisContextStore.View context,
      AnalysisSpecification specification,
      StanceChunkPlanner.Manifest manifest) {
    if (!"MODEL".equals(plan.reportMode())
        || specification == null
        || !StanceChunkPlanner.PROMPT.equals(plan.promptVersion())
        || !manifest.equals(StanceChunkPlanner.plan(job.snapshotId(), input)))
      throw new ModelContract.Failure("FULL_SCOPE_CHANGED");
    if (context.selection() != null
        && (!job.snapshotId().equals(context.selection().snapshotId())
            || !AnalysisContextStore.supported(context.selection().version())))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_CONTEXT_VERSION");
    Map<String, String> aliases = new LinkedHashMap<>();
    Map<String, ImportDocument.Comment> comments = new HashMap<>();
    for (var comment : input.comments()) {
      aliases.put(comment.id(), "comment-" + (aliases.size() + 1));
      comments.put(comment.id(), comment);
    }
    var background =
        context.selection() == null
            ? List.<DiscussionModelContract.ContextText>of()
            : context.selection().sources().stream()
                .map(
                    s ->
                        new DiscussionModelContract.ContextText(
                            s.id(),
                            s.role().name(),
                            s.title(),
                            s.sourceDescription(),
                            s.startMs(),
                            s.endMs(),
                            s.text()))
                .toList();
    List<StanceProtocol.Decision> decisions = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var chunk : manifest.chunks()) {
      leaseCheck.run();
      var texts =
          chunk.inputIds().stream()
              .map(
                  id -> {
                    var c = comments.get(id);
                    return new DiscussionModelContract.Text(
                        aliases.get(id), aliases.get(c.parentId()), c.text());
                  })
              .toList();
      var targets = chunk.targetIds().stream().map(aliases::get).toList();
      var modelRequest =
          new DiscussionModelContract.Request(
              job.snapshotId(),
              request.instruction(),
              input.content().title(),
              input.content().sourceType(),
              false,
              input.comments().size(),
              texts,
              plan.inputTokenCeiling(),
              plan.maxOutputTokens(),
              background,
              StanceChunkPlanner.PROMPT,
              specification,
              targets,
              chunk.index());
      var response = gateway.discuss(job.id(), token, plan, modelRequest);
      leaseCheck.run();
      // 按冻结目标顺序合并，不能依赖模型返回顺序，也不能把其他分片的父语境重复统计。
      Map<String, StanceProtocol.Decision> local = new HashMap<>();
      response.decisions().forEach(d -> local.put(d.commentId(), d));
      for (String target : targets) {
        if (!seen.add(target) || local.get(target) == null)
          throw new ModelContract.Failure("FULL_COVERAGE_INVALID");
        decisions.add(local.get(target));
      }
    }
    if (!seen.equals(new HashSet<>(aliases.values()))
        || decisions.size() != manifest.targetComments())
      throw new ModelContract.Failure("FULL_COVERAGE_INVALID");
    List<DiscussionArtifact.Evidence> evidence = new ArrayList<>();
    for (var c : input.comments())
      evidence.add(
          new DiscussionArtifact.Evidence(
              aliases.get(c.id()),
              c.sourceCommentId(),
              aliases.get(c.parentId()),
              c.text(),
              "COMMENT",
              job.snapshotId(),
              input.content().title(),
              null,
              null));
    if (context.selection() != null)
      for (var s : context.selection().sources())
        evidence.add(
            new DiscussionArtifact.Evidence(
                s.id(),
                null,
                null,
                s.text(),
                s.role().name(),
                s.materialId(),
                s.title(),
                s.startMs(),
                s.endMs()));
    int roots = (int) input.comments().stream().filter(c -> c.parentId() == null).count();
    int chars = input.comments().stream().mapToInt(c -> c.text().length()).sum();
    var result =
        new DiscussionArtifact(
            "discussion-artifact-v6-stance",
            artifactId,
            request.id(),
            job.id(),
            job.snapshotId(),
            request.instruction(),
            input.content().title(),
            input.content().sourceType(),
            "MODEL",
            new DiscussionArtifact.Scope(
                input.comments().size(),
                roots,
                input.comments().size() - roots,
                roots,
                false,
                input.comments().size(),
                chars,
                manifest.version()),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.copyOf(evidence),
            List.of(
                "完整范围是当前已保存快照，不是平台全量；采集缺口仍需单独核查。",
                "各分片独立判断相同命题；未执行跨片语义同义归并或事实认证，分片语义一致性仍需独立质量评估。"));
    if (context.selection() != null) {
      var c = context.selection();
      result =
          result.withContext(
              new DiscussionArtifact.ContextUsage(
                  context.fingerprint(),
                  c.version(),
                  !c.sources().isEmpty(),
                  c.references().size(),
                  c.totalTranscriptSegments(),
                  c.selectedTranscriptSegments(),
                  c.transcriptTruncated(),
                  c.textChars()));
    }
    return result.withStance(
        new DiscussionArtifact.Stance(
            specification,
            true,
            StanceProtocol.summarize(decisions),
            List.copyOf(decisions),
            new DiscussionArtifact.Completion(
                "FULL",
                manifest.version(),
                manifest.targetComments(),
                decisions.size(),
                manifest.chunks().size(),
                manifest.contextOccurrences(),
                true)));
  }
}
