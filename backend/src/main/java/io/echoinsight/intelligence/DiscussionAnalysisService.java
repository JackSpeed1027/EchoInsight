/* 文件职责：在结构演示与真实讨论模型之间选择执行路径，并把模型结果映射成可核查产物。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.DiscussionModelContract;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelGateway;
import io.echoinsight.orchestration.JobStore;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 讨论分析的业务门面：关闭模型时保留结构演示，显式启用后才调用共享模型网关。
 *
 * <p>本类负责范围裁剪和证据映射；任务状态、租约、预算和外部调用失败语义仍由原有组件处理。
 */
@Service
public class DiscussionAnalysisService {
  private final FullStanceAnalyzer full;
  private final StructuralDiscussionAnalyzer structural;
  private final ModelGateway models;
  private final ThreadContextSelector selector;

  /** 复用既有结构分析器和统一模型网关，不创建第二套任务或调用系统。 */
  public DiscussionAnalysisService(
      StructuralDiscussionAnalyzer structural,
      ModelGateway models,
      ThreadContextSelector selector) {
    this.full = new FullStanceAnalyzer(models);
    this.structural = structural;
    this.models = models;
    this.selector = selector;
  }

  /**
   * 根据任务创建时固化的计划执行讨论分析。
   *
   * <p>1、非 MODEL 计划继续生成诚实的结构演示；2、MODEL 计划选择有界评论；3、通过共享网关完成调用和校验；4、只把经过引用校验的内容发布为 MODEL 产物。
   */
  public DiscussionArtifact analyze(
      JobStore.Job job,
      String token,
      ModelContract.Plan plan,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId,
      Runnable leaseCheck) {
    return analyze(
        job, token, plan, request, input, artifactId, leaseCheck, AnalysisContextStore.View.none());
  }

  /** 带背景的请求仍使用原调用账本：先核对固定范围，再按角色命名证据，绝不把参考文当作评论。 */
  public DiscussionArtifact analyze(
      JobStore.Job job,
      String token,
      ModelContract.Plan plan,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId,
      Runnable leaseCheck,
      AnalysisContextStore.View context) {
    return analyze(job, token, plan, request, input, artifactId, leaseCheck, context, null);
  }

  /** 命题规格来自已冻结请求；不从最新页面状态或项目资料推断，旧协议继续原路径。 */
  public DiscussionArtifact analyze(
      JobStore.Job job,
      String token,
      ModelContract.Plan plan,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId,
      Runnable leaseCheck,
      AnalysisContextStore.View context,
      AnalysisSpecification specification) {
    return analyze(
        job, token, plan, request, input, artifactId, leaseCheck, context, specification, null);
  }

  /** 完整范围显式分流到v6，旧请求不读取或推导新的分片模式。 */
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
      StanceChunkPlanner.Manifest scope) {
    if (scope != null)
      return full.analyze(
          job, token, plan, request, input, artifactId, leaseCheck, context, specification, scope);
    boolean stance = specification != null;
    boolean contextual = context.selection() != null;
    if (contextual
        && (!job.snapshotId().equals(context.selection().snapshotId())
            || !AnalysisContextStore.supported(context.selection().version())))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_CONTEXT_VERSION");
    if (!"MODEL".equals(plan.reportMode())) {
      var demo = structural.analyze(job, request, input, artifactId);
      if (contextual) demo = demo.withContext(usage(context, false));
      return stance
          ? demo.withStance(new DiscussionArtifact.Stance(specification, false, null, List.of()))
          : demo;
    }
    // 1、v4评论采用独立命名空间；字幕/参考始终使用context前缀，避免任意导入ID相撞。
    if (contextual || stance) input = withCommentNamespace(input);
    leaseCheck.run();
    var selection =
        stance
            ? selector.select(
                input,
                io.echoinsight.model.StanceProtocol.MAX_COMMENTS,
                io.echoinsight.model.StanceProtocol.MAX_CHARS)
            : selector.select(input);
    List<ImportDocument.Comment> selected = selection.comments();
    Set<String> selectedIds = new HashSet<>();
    selected.forEach(comment -> selectedIds.add(comment.id()));
    var modelRequest =
        new DiscussionModelContract.Request(
            job.snapshotId(),
            request.instruction(),
            input.content().title(),
            input.content().sourceType(),
            request.includeAssessment(),
            input.comments().size(),
            selected.stream()
                .map(
                    comment ->
                        new DiscussionModelContract.Text(
                            comment.id(),
                            selectedIds.contains(comment.parentId()) ? comment.parentId() : null,
                            comment.text()))
                .toList(),
            plan.inputTokenCeiling(),
            plan.maxOutputTokens(),
            contextual
                ? context.selection().sources().stream()
                    .map(
                        source ->
                            new DiscussionModelContract.ContextText(
                                source.id(),
                                source.role().name(),
                                source.title(),
                                source.sourceDescription(),
                                source.startMs(),
                                source.endMs(),
                                source.text()))
                    .toList()
                : stance ? List.of() : null,
            stance
                ? io.echoinsight.model.StanceProtocol.VERSION
                : contextual
                    ? io.echoinsight.model.springai.DiscussionPrompt.CONTEXT_VERSION
                    : null,
            specification);
    var response = models.discuss(job.id(), token, plan, modelRequest);
    leaseCheck.run();
    var result = artifact(job, request, input, artifactId, selection, response, context);
    if (contextual)
      result = result.withContext(usage(context, !context.selection().sources().isEmpty()));
    if (stance)
      result =
          result.withStance(
              new DiscussionArtifact.Stance(
                  specification,
                  true,
                  io.echoinsight.model.StanceProtocol.summarize(response.decisions()),
                  response.decisions()));
    return result;
  }

  /** 把模型 DTO 映射成长期产物 DTO；证据原文始终从当前不可变快照读取。 */
  private DiscussionArtifact artifact(
      JobStore.Job job,
      AnalysisRequestStore.RequestRow request,
      ImportDocument input,
      String artifactId,
      ThreadContextSelector.Selection selection,
      DiscussionModelContract.Response response,
      AnalysisContextStore.View context) {
    List<ImportDocument.Comment> selected = selection.comments();
    Map<String, ImportDocument.Comment> byId = new LinkedHashMap<>();
    input.comments().forEach(comment -> byId.put(comment.id(), comment));
    Set<String> selectedIds = new LinkedHashSet<>();
    selected.forEach(comment -> selectedIds.add(comment.id()));
    Map<String, AnalysisContextStore.Source> background = new LinkedHashMap<>();
    if (context.selection() != null)
      context.selection().sources().forEach(source -> background.put(source.id(), source));
    Set<String> allEvidenceIds = new HashSet<>(selectedIds);
    allEvidenceIds.addAll(background.keySet());
    LinkedHashSet<String> referenced = new LinkedHashSet<>();
    response.issues().forEach(issue -> referenced.addAll(issue.evidenceIds()));
    response.claims().forEach(claim -> referenced.addAll(claim.evidenceIds()));
    response.relations().forEach(relation -> referenced.addAll(relation.evidenceIds()));
    response
        .assessments()
        .forEach(
            assessment ->
                referenced.addAll(
                    evidenceWithTextReferences(
                        assessment.evidenceIds(),
                        allEvidenceIds,
                        assessment.conclusion(),
                        assessment.reason())));

    if (response.decisions() != null) {
      for (var decision : response.decisions()) {
        referenced.add(decision.commentId());
        decision.claims().forEach(claim -> referenced.addAll(claim.evidenceIds()));
      }
      // 已选父语境一并发布，用户可从回复回看原文链，不只看到模型引用的孤立句子。
      referenced.addAll(selectedIds);
    }
    int roots =
        (int) input.comments().stream().filter(comment -> comment.parentId() == null).count();
    int selectedRoots =
        (int)
            selected.stream()
                .filter(
                    comment ->
                        comment.parentId() == null || !selectedIds.contains(comment.parentId()))
                .count();
    boolean truncated = selected.size() < input.comments().size();
    List<String> limitations = new ArrayList<>();
    if ("BILIBILI".equals(input.content().sourceType()))
      limitations.add("平台采集范围与模型输入范围分开判断；本次快照不能证明已覆盖平台全部评论。");
    limitations.add("评议只比较本次评论材料中的论据与回应程度，不验证作品设定或其他外部事实真伪。");
    limitations.add("模型结论必须绑定原评论证据，但绑定关系仍可能存在语义误判，重要结论应回看原文。");
    limitations.add("评论按稳定线程广度选择，被发送的回复保留完整父上下文；该顺序不代表语义重要性或随机抽样。");
    if (!input.contextGaps().isEmpty()) limitations.add("原始资料存在缺失父评论：保留原父引用，未把断边及其依赖后代发送给模型。");
    if (truncated) limitations.add("数量、字符或父上下文边界导致部分输入未选入；本次有界讨论不能代表全部评论。");
    else limitations.add("本次已把当前快照中的全部评论送入讨论分析，结论范围仍仅限该快照。");

    return new DiscussionArtifact(
        "discussion-artifact-v3",
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
            selectedRoots,
            truncated,
            selected.size(),
            selection.selectedTextChars(),
            response.decisions() != null
                ? "thread-context-proposition-v1"
                : ThreadContextSelector.versionFor(input)),
        response.issues().stream()
            .map(
                issue ->
                    new DiscussionArtifact.Issue(
                        issue.id(), issue.question(), issue.summary(), issue.evidenceIds()))
            .toList(),
        response.claims().stream()
            .map(
                claim ->
                    new DiscussionArtifact.Claim(
                        claim.id(),
                        claim.issueId(),
                        claim.statement(),
                        claim.reasoningStatus().name(),
                        claim.evidenceIds()))
            .toList(),
        response.relations().stream()
            .map(
                relation ->
                    new DiscussionArtifact.Relation(
                        relation.fromClaimId(),
                        relation.toClaimId(),
                        relation.type().name(),
                        relation.semanticStatus().name(),
                        relation.evidenceIds()))
            .toList(),
        response.assessments().stream()
            .map(
                assessment -> {
                  List<String> evidenceIds =
                      evidenceWithTextReferences(
                          assessment.evidenceIds(),
                          allEvidenceIds,
                          assessment.conclusion(),
                          assessment.reason());
                  boolean contested =
                      response.claims().stream()
                                  .filter(claim -> claim.issueId().equals(assessment.issueId()))
                                  .count()
                              >= 2
                          && response.relations().stream()
                              .anyMatch(
                                  relation ->
                                      claimBelongsToIssue(
                                              response.claims(),
                                              relation.fromClaimId(),
                                              assessment.issueId())
                                          && (relation.type()
                                                  == DiscussionModelContract.RelationType.REBUTS
                                              || relation.type()
                                                  == DiscussionModelContract.RelationType
                                                      .QUESTIONS));
                  if (!contested
                      && assessment.verdict() != DiscussionModelContract.Verdict.INSUFFICIENT) {
                    return new DiscussionArtifact.Assessment(
                        assessment.issueId(),
                        DiscussionModelContract.Verdict.INSUFFICIENT.name(),
                        "当前议题没有形成可比较的对立观点。",
                        "现有评论可以用于描述共同感受或评价，但不足以判断哪一方论据更充分。",
                        evidenceIds);
                  }
                  return new DiscussionArtifact.Assessment(
                      assessment.issueId(),
                      assessment.verdict().name(),
                      displayText(assessment.conclusion(), allEvidenceIds),
                      displayText(assessment.reason(), allEvidenceIds),
                      evidenceIds);
                })
            .toList(),
        referenced.stream()
            .map(
                id -> {
                  var comment = byId.get(id);
                  if (comment != null)
                    return context.selection() == null
                        ? new DiscussionArtifact.Evidence(
                            comment.id(),
                            comment.sourceCommentId(),
                            comment.parentId(),
                            comment.text())
                        : new DiscussionArtifact.Evidence(
                            comment.id(),
                            comment.sourceCommentId(),
                            comment.parentId(),
                            comment.text(),
                            "COMMENT",
                            job.snapshotId(),
                            input.content().title(),
                            null,
                            null);
                  var source = background.get(id);
                  if (source == null) throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
                  return new DiscussionArtifact.Evidence(
                      source.id(),
                      null,
                      null,
                      source.text(),
                      source.role().name(),
                      source.materialId(),
                      source.title(),
                      source.startMs(),
                      source.endMs());
                })
            .toList(),
        List.copyOf(limitations));
  }

  /** 背景统计独立于评论范围，sentToModel只在模型路径真正调用返回后置为true。 */
  private DiscussionArtifact.ContextUsage usage(AnalysisContextStore.View view, boolean sent) {
    var context = view.selection();
    return new DiscussionArtifact.ContextUsage(
        view.fingerprint(),
        context.version(),
        sent,
        context.references().size(),
        context.totalTranscriptSegments(),
        context.selectedTranscriptSegments(),
        context.transcriptTruncated(),
        context.textChars());
  }

  /** 保留原始来源评论ID和回复结构，仅改变v4证据命名空间；不修改数据库中的快照。 */
  private ImportDocument withCommentNamespace(ImportDocument input) {
    // 1、先检查原始图及显式缺口；2、映射已有评论；3、缺失父身份单独命名，绝不补造父评论。
    selector.select(input);
    Map<String, String> names = new HashMap<>();
    for (int n = 0; n < input.comments().size(); n++)
      names.put(input.comments().get(n).id(), "comment-" + (n + 1));
    Map<String, String> externalParents = new LinkedHashMap<>();
    for (var gap : input.contextGaps())
      externalParents.computeIfAbsent(
          gap.parentCommentId(), id -> "missing-parent-" + (externalParents.size() + 1));
    var comments =
        input.comments().stream()
            .map(
                c ->
                    new ImportDocument.Comment(
                        names.get(c.id()),
                        c.sourceCommentId(),
                        c.parentId() == null
                            ? null
                            : names.getOrDefault(c.parentId(), externalParents.get(c.parentId())),
                        c.text(),
                        c.occurredAt()))
            .toList();
    var gaps =
        input.contextGaps().stream()
            .map(
                g ->
                    new ImportDocument.MissingParent(
                        names.get(g.commentId()), externalParents.get(g.parentCommentId())))
            .toList();
    return new ImportDocument(
        input.schemaVersion(),
        input.content(),
        comments,
        input.transcriptSegments(),
        gaps.isEmpty() ? null : gaps);
  }

  /** 模型若在展示文本误写合法评论ID，仍将它补入证据引用，避免引用与原文脱节。 */
  private List<String> evidenceWithTextReferences(
      List<String> explicit, Set<String> allowed, String... texts) {
    LinkedHashSet<String> result = new LinkedHashSet<>(explicit);
    for (String id : allowed) {
      for (String text : texts) {
        if (text != null && text.contains(id)) {
          result.add(id);
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  /** 页面展示业务语言；内部证据身份只在 evidenceIds 中出现。 */
  private String displayText(String value, Set<String> internalIds) {
    String result = value;
    for (String id : internalIds) result = result.replace(id, "相关评论");
    return result;
  }

  /** 判断关系起点是否属于当前议题；关系闭包已经由模型网关校验。 */
  private boolean claimBelongsToIssue(
      List<DiscussionModelContract.Claim> claims, String claimId, String issueId) {
    return claims.stream()
        .anyMatch(claim -> claim.id().equals(claimId) && claim.issueId().equals(issueId));
  }
}
