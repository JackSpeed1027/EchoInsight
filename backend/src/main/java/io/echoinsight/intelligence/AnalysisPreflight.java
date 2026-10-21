/* 文件职责：只读估算实际有界讨论输入，校验项目资格与背景，不创建请求、任务或费用记录。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 预检与Worker共用选择器；提交仍在事务内重查，不能将浏览器预检当权限或冻结依据。 */
@Service
public class AnalysisPreflight {
  private final SourceCoverageStore sourceCoverage;
  private final AnalysisScopeStore scopes;
  private final ProjectStore projects;
  private final ImportMapper imports;
  private final ObjectMapper json;
  private final ThreadContextSelector selector;
  private final AnalysisContextStore contexts;
  private final ConfiguredModelPlan plans;

  /** 只读依赖已有存储，不引入另一份工作流或缓存。 */
  public AnalysisPreflight(
      ProjectStore projects,
      ImportMapper imports,
      ObjectMapper json,
      ThreadContextSelector selector,
      AnalysisContextStore contexts,
      ConfiguredModelPlan plans,
      AnalysisScopeStore scopes,
      SourceCoverageStore sourceCoverage) {
    this.sourceCoverage = sourceCoverage;
    this.scopes = scopes;
    this.projects = projects;
    this.imports = imports;
    this.json = json;
    this.selector = selector;
    this.contexts = contexts;
    this.plans = plans;
  }

  /** selected是安全上限选择，不是准确率/平台覆盖率；免费模式没有发送任何模型输入。 */
  public record View(
      String snapshotId,
      String mode,
      int savedComments,
      int selectedComments,
      int omittedComments,
      int selectedTextChars,
      int missingParentCount,
      boolean requiresPartialConsent,
      boolean fullAnalysisAvailable,
      int referenceDocuments,
      int transcriptSegments,
      List<String> notes,
      AnalysisSpecification.Resolution goal,
      AnalysisScopeStore.Estimate fullScope,
      SourceCoverageStore.Coverage sourceCoverage,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          AnalysisContextStore.ContentBinding content,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          DiscussionDigestReuse.Receipt reuse) {}

  /** 1、核对项目成员；2、核对背景版本；3、同规则选择；4、返回业务范围，不调用模型。 */
  @Transactional(readOnly = true)
  public View preview(
      String projectId, String snapshotId, boolean transcript, List<String> references) {
    return preview(projectId, snapshotId, transcript, references, null, null);
  }

  /** 目标澄清与输入范围一起返回；普通旧预检允许没有原话，仍不写入数据库。 */
  @Transactional(readOnly = true)
  public View preview(
      String projectId,
      String snapshotId,
      boolean transcript,
      List<String> references,
      String instruction,
      String proposition) {
    return preview(projectId, snapshotId, transcript, references, instruction, proposition, null);
  }

  /** 业务范围可由“全部/所有”原话或显式选择决定；完整预检不会创建账本或发起模型调用。 */
  @Transactional(readOnly = true)
  public View preview(
      String projectId,
      String snapshotId,
      boolean transcript,
      List<String> references,
      String instruction,
      String proposition,
      String analysisScope) {
    return previewWithMaterial(
        projectId,
        snapshotId,
        transcript,
        references,
        instruction,
        proposition,
        analysisScope,
        null);
  }

  /** 预检检索与提交使用同一原文和策略，仍然只读且不出站。 */
  @Transactional(readOnly = true)
  public View previewWithMaterial(
      String projectId,
      String snapshotId,
      boolean transcript,
      List<String> references,
      String instruction,
      String proposition,
      String analysisScope,
      String transcriptMaterialId) {
    return previewWithContent(
        projectId,
        snapshotId,
        transcript,
        references,
        instruction,
        proposition,
        analysisScope,
        transcriptMaterialId,
        null,
        null,
        null);
  }

  /** 独立内容选择同提交规则，返回选择/遗漏和策略，不把词法命中当语义质量。 */
  @Transactional(readOnly = true)
  public View previewWithContent(
      String projectId,
      String snapshotId,
      boolean transcript,
      List<String> references,
      String instruction,
      String proposition,
      String analysisScope,
      String transcriptMaterialId,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs) {
    return previewWithContent(
        projectId,
        snapshotId,
        transcript,
        references,
        instruction,
        proposition,
        analysisScope,
        transcriptMaterialId,
        contentMaterialId,
        contentFromMs,
        contentToMs,
        null);
  }

  /** 只读核对显式基准及真实可复用分片，任何资格错误都不静默变更收费方式。 */
  @Transactional(readOnly = true)
  public View previewWithContent(
      String projectId,
      String snapshotId,
      boolean transcript,
      List<String> references,
      String instruction,
      String proposition,
      String analysisScope,
      String transcriptMaterialId,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs,
      String baselineRequestId) {
    var goal = instruction == null ? null : AnalysisSpecification.resolve(instruction, proposition);
    boolean stance = goal != null && goal.stanceRequested();
    var input = load(projectId, snapshotId);
    var context =
        contexts.prepareAll(
            projectId,
            snapshotId,
            transcript,
            references,
            transcriptMaterialId,
            instruction,
            contentMaterialId,
            contentFromMs,
            contentToMs);
    if (stance && contentMaterialId != null && !contentMaterialId.isBlank())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "独立内容对照当前用于讨论梳理；逐条命题分析请使用作品字幕或参考文本背景");
    boolean model = "MODEL".equals(plans.discussion().reportMode());
    boolean full =
        stance ? AnalysisScopeStore.full(instruction, analysisScope) : "FULL".equals(analysisScope);
    if (analysisScope != null) AnalysisScopeStore.full(instruction, analysisScope);
    if (baselineRequestId != null && !baselineRequestId.isBlank() && (!full || stance))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "讨论基准复用仅支持普通完整讨论");
    boolean assessment =
        !stance
            && instruction != null
            && !instruction.isBlank()
            && new IntentPlanner().plan(instruction.strip()).includeAssessment();
    var digest =
        full && !stance
            ? scopes.previewFull(
                projectId,
                snapshotId,
                plans.discussionDigest(),
                instruction,
                assessment,
                context,
                baselineRequestId)
            : null;
    var estimate =
        full
            ? stance
                ? scopes.estimate(projectId, snapshotId, plans.discussionFull())
                : digest.estimate()
            : null;
    var selection = selection(input, stance);
    var notes = new ArrayList<String>();
    notes.add(
        full
            ? stance
                ? "完整命题每片最多12条归属评论，父语境独立；全部成功才发布统计。"
                : "完整讨论每片最多40目标/120条含祖先/80000字符，8路分层归并；全部成功才发布，不是逐条立场统计。"
            : stance
                ? "命题分析最多选12条、12000字符，逐条覆盖并保留多主张；未选部分不推断立场。"
                : "这是讨论梳理，不是逐条全量立场分类；不自动使用正面、负面、中立回答你的问题。");
    if (context.selection() != null && context.selection().retrieval() != null)
      notes.add(
          context.selection().selectedTranscriptSegments() == 0
              ? "上传字幕没有字词命中，本次没有选入字幕背景，不代表视频没有相关内容。"
              : "字幕仅选本问题字词命中及前后邻段；时间引用保留，但不证明理解全片或创作者意图。");
    if (goal != null) notes.add(goal.explanation());
    if (full) notes.add("完整范围仅指本快照全部已保存评论，不等于平台全量；父语境可重复输入但目标只归属一次。普通讨论会压缩后汇总，不能保证穷尽每个观点或语义准确。");
    notes.add(
        model ? "启用模型后按下列有限输入执行；全部评论已发送也不代表每条都形成结论。" : "当前为免费结构演示，没有向模型发送评论；下列选择数量仅说明启用模型后的输入边界。");
    if (!full && selection.truncated()) notes.add("部分评论因数量、字符或父上下文边界未选入；不能回答为所有评论的总体结论。");
    if (!input.contextGaps().isEmpty()) notes.add("本次存在未采到的父评论，原引用已保留；相关断边和依赖后代不会送入模型。");
    return new View(
        snapshotId,
        model ? "MODEL" : "STRUCTURAL_DEMO",
        input.comments().size(),
        full && estimate.available() ? input.comments().size() : selection.comments().size(),
        full && estimate.available() ? 0 : input.comments().size() - selection.comments().size(),
        full && estimate.available()
            ? input.comments().stream().mapToInt(c -> c.text().length()).sum()
            : selection.selectedTextChars(),
        input.contextGaps().size(),
        !full && model && selection.truncated(),
        full && estimate.available(),
        context.selection() == null ? 0 : context.selection().references().size(),
        context.selection() == null ? 0 : context.selection().selectedTranscriptSegments(),
        List.copyOf(notes),
        goal,
        estimate,
        sourceCoverage.prepare(projectId, snapshotId),
        context.selection() == null ? null : context.selection().content(),
        digest == null || digest.manifest() == null
            ? null
            : DiscussionDigestReuse.receipt(digest.manifest().reuse()));
  }

  /** 接受新HTTP请求时再次检查实际MODEL输入；未同意裁剪时拒绝且外层事务回滚。 */
  public void requireConsent(
      String projectId, String snapshotId, ModelContract.Plan plan, boolean consent) {
    requireConsent(projectId, snapshotId, plan, consent, false);
  }

  /** 命题输入边界与Worker共用，不能用普通讨论120条预检替代12条命题处理。 */
  public void requireConsent(
      String projectId,
      String snapshotId,
      ModelContract.Plan plan,
      boolean consent,
      boolean stance) {
    if (!"MODEL".equals(plan.reportMode())) return;
    var chosen = selection(load(projectId, snapshotId), stance);
    if (chosen.truncated() && !consent)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "需要确认有限输入范围后再提交分析");
  }

  /** 没有可用输入时给业务错误；其他图损坏仍拒绝，不降低校验。 */
  private ThreadContextSelector.Selection selection(ImportDocument input, boolean stance) {
    try {
      return stance
          ? selector.select(input, StanceProtocol.MAX_COMMENTS, StanceProtocol.MAX_CHARS)
          : selector.select(input);
    } catch (ModelContract.Failure error) {
      if ("MODEL_EMPTY_INPUT".equals(error.code()))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "当前没有父上下文完整且符合大小边界的评论可供分析");
      throw error;
    }
  }

  /** 不读项目最新版本，必须取得调用方明确的成员快照。 */
  private ImportDocument load(String projectId, String snapshotId) {
    projects.snapshot(projectId, snapshotId);
    try {
      return json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid stored preflight input", error);
    }
  }
}
