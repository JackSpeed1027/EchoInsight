/* 文件职责：原DISCUSSION步骤内执行完整普通讨论的MAP/分层REDUCE/FINAL，复用原账本与租约。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.JobStore;
import java.util.*;
import java.util.function.Function;

/** 所有节点成功才返回可发布结果；失败不发布局部报告，恢复沿用原成功检查点。 */
public final class FullDiscussionAnalyzer {
  private final ModelGateway gateway;

  /** 所有出站使用同一个网关，不创建线程或新任务。 */
  public FullDiscussionAnalyzer(ModelGateway gateway) {
    this.gateway = gateway;
  }

  /** 完成语义响应和纯产物元数据，状态仍由原任务负责。 */
  public record Result(
      DiscussionModelContract.Response response, DiscussionDigestPlan.Processing processing) {}

  /** 模型局部评论与当前报告别名映射；背景拥有独立context命名空间。 */
  public record MapInput(
      DiscussionDigestContract.Request request, Map<String, String> localToCurrent) {}

  /** 1、核对冻结清单；2、逐片调用/恢复并映射引用；3、按确定归并树处理全部子片；4、只返回完整结果。 */
  public Result analyze(
      JobStore.Job job,
      String token,
      ModelContract.Plan plan,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      AnalysisContextStore.View context,
      StanceChunkPlanner.Manifest manifest,
      Runnable check) {
    if (!DiscussionDigestPlan.enabled(plan)
        || manifest == null
        || !manifest.base().equals(DiscussionDigestPlan.plan(job.snapshotId(), input)))
      throw new ModelContract.Failure("FULL_SCOPE_CHANGED");
    if (context.selection() != null
        && (!job.snapshotId().equals(context.selection().snapshotId())
            || !AnalysisContextStore.supported(context.selection().version())))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_CONTEXT_VERSION");
    DiscussionDigestReuse.verify(
        input, manifest, context, plan, request.instruction(), request.includeAssessment());
    var background = background(context);
    Map<Integer, DiscussionDigestContract.Summary> summaries = new LinkedHashMap<>();
    List<DiscussionDigestPlan.NodeResult> nodes = new ArrayList<>();
    for (var chunk : manifest.chunks()) {
      check.run();
      var current =
          mapInput(
              input,
              manifest,
              chunk,
              context,
              plan,
              request.instruction(),
              request.includeAssessment());
      var cached = manifest.reuse().entries().get(chunk.index()).cached();
      var response =
          cached == null
              ? gateway.digest(job.id(), token, plan, current.request())
              : cached.response();
      DiscussionDigestContract.validate(current.request(), response);
      check.run();
      response = mapEvidence(response, id -> current.localToCurrent().getOrDefault(id, id));
      var summary = DiscussionDigestContract.summary(chunk.index(), response);
      summaries.put(chunk.index(), summary);
      nodes.add(
          new DiscussionDigestPlan.NodeResult(
              chunk.index(),
              "MAP",
              List.of(),
              current.request().targetCommentIds().stream()
                  .map(current.localToCurrent()::get)
                  .toList(),
              summary));
    }
    DiscussionModelContract.Response finalResponse = null;
    var reductions = DiscussionDigestPlan.reductions(manifest.chunks().size());
    for (var node : reductions) {
      check.run();
      var children = node.children().stream().map(summaries::get).toList();
      var modelRequest =
          new DiscussionDigestContract.Request(
              DiscussionDigestPlan.PROMPT,
              job.snapshotId(),
              request.instruction(),
              input.content().title(),
              input.content().sourceType(),
              request.includeAssessment(),
              input.comments().size(),
              node.index(),
              node.phase(),
              List.of(),
              List.of(),
              background,
              children,
              plan.inputTokenCeiling(),
              plan.maxOutputTokens());
      var response = gateway.digest(job.id(), token, plan, modelRequest);
      check.run();
      var summary = DiscussionDigestContract.summary(node.index(), response);
      summaries.put(node.index(), summary);
      nodes.add(
          new DiscussionDigestPlan.NodeResult(
              node.index(), node.phase(), node.children(), List.of(), summary));
      if ("FINAL".equals(node.phase())) finalResponse = response;
    }
    if (finalResponse == null) throw new IllegalStateException("Missing final discussion result");
    return new Result(
        finalResponse,
        new DiscussionDigestPlan.Processing(
            DiscussionDigestPlan.VERSION,
            manifest.chunks().size(),
            reductions.size(),
            manifest.chunks().size(),
            reductions.size(),
            manifest.targetComments(),
            manifest.contextOccurrences(),
            List.copyOf(nodes),
            DiscussionDigestReuse.receipt(manifest.reuse())));
  }

  /** 局部别名与角色是模型真实依赖，快照/片号/全局统计只供原网关检查点使用。 */
  public static MapInput mapInput(
      ImportDocument input,
      StanceChunkPlanner.Manifest manifest,
      StanceChunkPlanner.Chunk chunk,
      AnalysisContextStore.View context,
      ModelContract.Plan plan,
      String instruction,
      boolean assessment) {
    Map<String, ImportDocument.Comment> raw = new HashMap<>();
    Map<String, String> global = new HashMap<>();
    for (int n = 0; n < input.comments().size(); n++) {
      var c = input.comments().get(n);
      raw.put(c.id(), c);
      global.put(c.id(), "comment-" + (n + 1));
    }
    Map<String, String> local = new LinkedHashMap<>(), mapping = new LinkedHashMap<>();
    for (String id : chunk.inputIds()) {
      String alias = "c" + (local.size() + 1);
      local.put(id, alias);
      mapping.put(alias, global.get(id));
    }
    var texts =
        chunk.inputIds().stream()
            .map(
                id -> {
                  var c = raw.get(id);
                  return new DiscussionModelContract.Text(
                      local.get(id),
                      c.parentId() == null ? null : local.get(c.parentId()),
                      c.text());
                })
            .toList();
    var r =
        new DiscussionDigestContract.Request(
            DiscussionDigestPlan.PROMPT,
            manifest.snapshotId(),
            instruction,
            input.content().title(),
            input.content().sourceType(),
            assessment,
            input.comments().size(),
            chunk.index(),
            "MAP",
            texts,
            chunk.targetIds().stream().map(local::get).toList(),
            background(context),
            List.of(),
            plan.inputTokenCeiling() == null ? 0 : plan.inputTokenCeiling(),
            plan.maxOutputTokens());
    return new MapInput(r, Map.copyOf(mapping));
  }

  /** 背景来源独立于评论，冻结资料和时间原样进入上下文。 */
  public static List<DiscussionModelContract.ContextText> background(
      AnalysisContextStore.View view) {
    return view.selection() == null
        ? List.of()
        : view.selection().sources().stream()
            .map(
                s ->
                    new DiscussionModelContract.ContextText(
                        s.id(),
                        s.role().name(),
                        s.title(),
                        s.sourceDescription(),
                        s.startMs(),
                        s.endMs(),
                        s.text(),
                        s.startChar(),
                        s.endChar()))
            .toList();
  }

  /** 原局部引用映射到当前材料别名，议题/主张身份仅在各自摘要内使用。 */
  public static DiscussionModelContract.Response mapEvidence(
      DiscussionModelContract.Response r, Function<String, String> mapper) {
    return new DiscussionModelContract.Response(
        r.issues().stream()
            .map(
                i ->
                    new DiscussionModelContract.Issue(
                        i.id(),
                        i.question(),
                        i.summary(),
                        i.evidenceIds().stream().map(mapper).toList()))
            .toList(),
        r.claims().stream()
            .map(
                c ->
                    new DiscussionModelContract.Claim(
                        c.id(),
                        c.issueId(),
                        c.statement(),
                        c.reasoningStatus(),
                        c.evidenceIds().stream().map(mapper).toList()))
            .toList(),
        r.relations().stream()
            .map(
                x ->
                    new DiscussionModelContract.Relation(
                        x.fromClaimId(),
                        x.toClaimId(),
                        x.type(),
                        x.semanticStatus(),
                        x.evidenceIds().stream().map(mapper).toList()))
            .toList(),
        r.assessments().stream()
            .map(
                a ->
                    new DiscussionModelContract.Assessment(
                        a.issueId(),
                        a.verdict(),
                        a.conclusion(),
                        a.reason(),
                        a.evidenceIds().stream().map(mapper).toList()))
            .toList(),
        r.inputTokens(),
        r.outputTokens(),
        r.requestId());
  }
}
