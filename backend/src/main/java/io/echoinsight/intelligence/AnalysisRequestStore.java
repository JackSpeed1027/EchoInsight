/* 文件职责：保存自然语言分析请求、绑定项目快照，并提供请求历史与产物读取。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.ConfiguredModelPlan;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自然语言请求是用户意图的持久边界，项目是长期容器，任务负责可恢复执行。
 *
 * <p>服务端选择分析配方和资料范围；页面不能提交模型、工具、SQL 或文件路径。请求与任务一一关联，重发不会重复创建调用或产物。
 */
@Service
public class AnalysisRequestStore {
  private final ContinuationPlanner continuations;
  private final SourceCoverageStore sourceCoverage;
  private final AnalysisScopeStore scopes;
  private final AnalysisSpecificationStore specifications;
  private final AnalysisPreflight preflight;
  private final AnalysisContextStore contexts;
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final JobStore jobs;
  private final IntentPlanner planner;
  private final ConfiguredModelPlan plans;
  private final ObjectMapper json;

  /** 复用既有项目、任务、计划与数据库，不创建第二套调度系统。 */
  public AnalysisRequestStore(
      JdbcTemplate db,
      ProjectStore projects,
      JobStore jobs,
      IntentPlanner planner,
      ConfiguredModelPlan plans,
      ObjectMapper json,
      AnalysisContextStore contexts,
      AnalysisPreflight preflight,
      AnalysisSpecificationStore specifications,
      AnalysisScopeStore scopes,
      SourceCoverageStore sourceCoverage,
      ContinuationPlanner continuations) {
    this.continuations = continuations;
    this.sourceCoverage = sourceCoverage;
    this.scopes = scopes;
    this.specifications = specifications;
    this.preflight = preflight;
    this.contexts = contexts;
    this.db = db;
    this.projects = projects;
    this.jobs = jobs;
    this.planner = planner;
    this.plans = plans;
    this.json = json;
  }

  /** 页面可理解的请求状态；内部配方身份不作为交互选项。 */
  public record RequestRow(
      String id,
      String projectId,
      String snapshotId,
      String instruction,
      String intent,
      boolean includeAssessment,
      String status,
      String jobId,
      String artifactId,
      String lastErrorCode,
      String createdAt) {}

  /** 首次创建返回201，逻辑相同的网络重发返回既有请求。 */
  public record Created(RequestRow request, boolean created) {}

  /** 请求历史使用稳定分页，避免一次加载全部原话和结果。 */
  public record Page<T>(List<T> items, long total, int page, int size, long totalPages) {}

  /** 已发布的类型化产物正文；只读接口解析后返回 JSON。 */
  public record ArtifactRow(String id, String requestId, String json) {}

  /**
   * 保存请求并创建同一套分析任务。
   *
   * <p>1、校验幂等键并由服务端识别意图；2、解析明确快照，只有一份资料时可自动选择；3、锁定项目内请求键并核对重发内容；4、新请求创建 DISCUSSION
   * 任务并回填关联，整个过程同事务提交。
   */
  @Transactional
  public Created create(String projectId, String key, String snapshotId, String instruction) {
    return createWithPlan(projectId, key, snapshotId, instruction, plans.discussion());
  }

  /** 集合业务一次冻结同一模型计划后创建子请求；仅内部调用，不从HTTP接受模型或价格。 */
  @Transactional
  public Created createWithPlan(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      io.echoinsight.model.ModelContract.Plan fixedPlan) {
    if (AnalysisSpecification.resolve(instruction, null).stanceRequested())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "命题分析暂仅支持单份资料，请在智能分析页明确命题");
    return createInternal(
        projectId,
        key,
        snapshotId,
        instruction,
        fixedPlan,
        false,
        List.of(),
        true,
        null,
        false,
        false,
        null,
        null);
  }

  /** 页面可选字幕与参考资料；模型计划仍由服务器决定，默认调用保持原纯评论语义。 */
  @Transactional
  public Created createWithContext(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds) {
    return createWithContext(
        projectId, key, snapshotId, instruction, includeTranscript, referenceIds, true);
  }

  /** HTTP新请求的有限输入必须明确确认；网络重发仍复用既有冻结请求，不产生第二次调用。 */
  @Transactional
  public Created createWithContext(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput) {
    return createWithSpecification(
        projectId,
        key,
        snapshotId,
        instruction,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        null);
  }

  /** HTTP命题从用户原话或澄清取得；未明确参照时拒绝，绝不调用模型替用户猜标准。 */
  @Transactional
  public Created createWithSpecification(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      String proposition) {
    return createWithScope(
        projectId,
        key,
        snapshotId,
        instruction,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        proposition,
        null,
        false);
  }

  /** 用户要求全部命题范围时固定v6分片计划，成本确认是业务接受边界，不从页面接受预算值。 */
  @Transactional
  public Created createWithScope(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      String proposition,
      String analysisScope,
      boolean confirmFullScope) {
    return createWithMaterials(
        projectId,
        key,
        snapshotId,
        instruction,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        proposition,
        analysisScope,
        confirmFullScope,
        null);
  }

  /** 上传字幕也在原接受事务冻结；同键换字幕/问题检索范围会409，不改变已接受请求。 */
  @Transactional
  public Created createWithMaterials(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      String proposition,
      String analysisScope,
      boolean confirmFullScope,
      String transcriptMaterialId) {
    return createWithMaterials(
        projectId,
        key,
        snapshotId,
        instruction,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        proposition,
        analysisScope,
        confirmFullScope,
        transcriptMaterialId,
        null,
        null,
        null);
  }

  /** 独立内容关联在原项目锁内冻结；模型计划/语义角色与幂等一并确定，不从页面接收原文或模型。 */
  @Transactional
  public Created createWithMaterials(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      String proposition,
      String analysisScope,
      boolean confirmFullScope,
      String transcriptMaterialId,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs) {
    return createWithMaterials(
        projectId,
        key,
        snapshotId,
        instruction,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        proposition,
        analysisScope,
        confirmFullScope,
        transcriptMaterialId,
        contentMaterialId,
        contentFromMs,
        contentToMs,
        null);
  }

  /** 显式复用基准参与原请求幂等；不选基准时全部新分析，旧调用形状继续兼容。 */
  @Transactional
  public Created createWithMaterials(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      String proposition,
      String analysisScope,
      boolean confirmFullScope,
      String transcriptMaterialId,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs,
      String baselineRequestId) {
    if (baselineRequestId != null && baselineRequestId.isBlank()) baselineRequestId = null;
    var resolution = AnalysisSpecification.resolve(instruction, proposition);
    // 普通旧客户端省略范围仍保留单次有界语义；新页面明确发送FULL并核对全部费用。
    boolean full =
        resolution.stanceRequested()
            ? AnalysisScopeStore.full(instruction, analysisScope)
            : "FULL".equals(analysisScope);
    if (analysisScope != null) AnalysisScopeStore.full(instruction, analysisScope);
    if (resolution.needsProposition())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请先明确需要判断支持或反对的命题");
    if (baselineRequestId != null && (!full || resolution.stanceRequested()))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "讨论基准复用仅支持普通完整讨论");
    boolean contentSelected = contentMaterialId != null && !contentMaterialId.isBlank();
    if (contentSelected && resolution.stanceRequested())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "独立内容对照当前用于讨论梳理；逐条命题分析请使用作品字幕或参考文本背景");
    if (!contentSelected && (contentFromMs != null || contentToMs != null))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容时间范围需要先选择独立材料");
    AnalysisContextStore.View prepared = null;
    if (contentSelected) {
      projects.lock(projectId);
      snapshotId = selectSnapshot(projectId, snapshotId);
      prepared =
          contexts.prepareAll(
              projectId,
              snapshotId,
              includeTranscript,
              referenceIds,
              transcriptMaterialId,
              instruction,
              contentMaterialId,
              contentFromMs,
              contentToMs);
    }
    boolean hasContext =
        contentSelected
            || includeTranscript
            || (transcriptMaterialId != null && !transcriptMaterialId.isBlank())
            || (referenceIds != null && !referenceIds.isEmpty());
    return createInternalWithBaseline(
        projectId,
        key,
        snapshotId,
        instruction,
        full
            ? resolution.stanceRequested() ? plans.discussionFull() : plans.discussionDigest()
            : resolution.specification() != null
                ? plans.discussionWithProposition()
                : contentSelected
                    ? plans.discussionWithContent()
                    : hasContext ? plans.discussionWithContext() : plans.discussion(),
        includeTranscript,
        referenceIds,
        allowPartialInput,
        resolution.specification(),
        full,
        confirmFullScope,
        transcriptMaterialId,
        prepared,
        baselineRequestId);
  }

  /** 追问沿用原快照及冻结背景，重新执行原讨论任务；不会把上一轮结论当新事实或复用旧费用。 */
  @Transactional
  public Created createFollowup(
      String projectId,
      String key,
      String parentId,
      String anchorId,
      String question,
      boolean allowPartialInput) {
    // 1、先取得项目锁；2、只读准备父产物和导航；3、原事务接受；4、追加关系。
    projects.lock(projectId);
    var prepared = continuations.prepare(projectId, parentId, anchorId, question);
    var created =
        createInternal(
            projectId,
            key,
            prepared.snapshotId(),
            question,
            prepared.context().selection() != null
                    && prepared.context().selection().content() != null
                ? plans.discussionWithContent()
                : plans.discussionWithContext(),
            false,
            List.of(),
            allowPartialInput,
            null,
            false,
            false,
            null,
            prepared.context());
    continuations.save(created.request().id(), prepared);
    return created;
  }

  /** 同一短事务接受请求、冻结背景与创建原任务，任一步失败全部回滚。 */
  private Created createInternal(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      io.echoinsight.model.ModelContract.Plan fixedPlan,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      AnalysisSpecification specification,
      boolean full,
      boolean confirmFullScope,
      String transcriptMaterialId,
      AnalysisContextStore.View preparedContext) {
    return createInternalWithBaseline(
        projectId,
        key,
        snapshotId,
        instruction,
        fixedPlan,
        includeTranscript,
        referenceIds,
        allowPartialInput,
        specification,
        full,
        confirmFullScope,
        transcriptMaterialId,
        preparedContext,
        null);
  }

  /** 复用清单和原任务同事务保存，重发只核对既有身份，不重验新配置或重新派工。 */
  private Created createInternalWithBaseline(
      String projectId,
      String key,
      String snapshotId,
      String instruction,
      io.echoinsight.model.ModelContract.Plan fixedPlan,
      boolean includeTranscript,
      List<String> referenceIds,
      boolean allowPartialInput,
      AnalysisSpecification specification,
      boolean full,
      boolean confirmFullScope,
      String transcriptMaterialId,
      AnalysisContextStore.View preparedContext,
      String baselineRequestId) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效的分析请求键");
    }
    // 同一项目先串行接受输入，再读取快照/背景与幂等状态；不在锁内调用外部模型。
    projects.lock(projectId);
    String cleanInstruction = instruction == null ? null : instruction.strip();
    var spec =
        specification != null
            ? new IntentPlanner.Spec("PROPOSITION_V1", false)
            : preparedContext != null && preparedContext.selection().history() != null
                ? new IntentPlanner.Spec(IntentPlanner.DISCUSSION, true)
                : planner.plan(cleanInstruction);
    String selectedSnapshot = selectSnapshot(projectId, snapshotId);
    var context =
        preparedContext != null
            ? preparedContext
            : contexts.prepareWithMaterial(
                projectId,
                selectedSnapshot,
                includeTranscript,
                referenceIds,
                transcriptMaterialId,
                cleanInstruction);
    String candidate = UUID.randomUUID().toString();
    db.update(
        """
        INSERT INTO analysis_request(
          id,project_id,snapshot_id,request_key,instruction,intent,include_assessment,status)
        VALUES(?,?,?,?,?,?,?,'PENDING') ON DUPLICATE KEY UPDATE id=id
        """,
        candidate,
        projectId,
        selectedSnapshot,
        key,
        cleanInstruction,
        spec.intent(),
        spec.includeAssessment());
    RequestRow stored =
        db.queryForObject(
            """
            SELECT ar.*,aa.id artifact_id,
             DATE_FORMAT(ar.created_at,'%Y-%m-%d %H:%i:%s') created_time
            FROM analysis_request ar LEFT JOIN analysis_artifact aa ON aa.request_id=ar.id
            WHERE ar.project_id=? AND ar.request_key=? FOR UPDATE
            """,
            this::row, projectId, key);
    if (!selectedSnapshot.equals(stored.snapshotId())
        || !cleanInstruction.equals(stored.instruction())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他资料或分析要求");
    }
    if (!candidate.equals(stored.id())
        && !java.util.Objects.equals(
            context.fingerprint(), contexts.get(projectId, stored.id()).fingerprint())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他分析背景");
    }
    if (!candidate.equals(stored.id())
        && !java.util.Objects.equals(specification, specifications.get(projectId, stored.id())))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他命题");
    if (!candidate.equals(stored.id()) && full != (scopes.get(projectId, stored.id()) != null))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他分析范围");
    if (!candidate.equals(stored.id())) {
      var frozenScope = scopes.get(projectId, stored.id());
      String oldBaseline =
          frozenScope == null || frozenScope.reuse() == null
              ? null
              : frozenScope.reuse().baselineRequestId();
      if (!java.util.Objects.equals(oldBaseline, baselineRequestId))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他讨论复用基准");
    }
    if (candidate.equals(stored.id())) {
      if (full)
        scopes.save(
            stored.id(),
            DiscussionDigestPlan.enabled(fixedPlan)
                ? scopes.requireFull(
                    projectId,
                    selectedSnapshot,
                    fixedPlan,
                    confirmFullScope,
                    cleanInstruction,
                    spec.includeAssessment(),
                    context,
                    baselineRequestId)
                : scopes.require(projectId, selectedSnapshot, fixedPlan, confirmFullScope));
      else
        preflight.requireConsent(
            projectId, selectedSnapshot, fixedPlan, allowPartialInput, specification != null);
      specifications.save(stored.id(), specification);
      contexts.save(stored.id(), context);
      sourceCoverage.save(stored.id(), sourceCoverage.prepare(projectId, selectedSnapshot));
      var job =
          jobs.createDiscussion(selectedSnapshot, "natural:" + stored.id(), fixedPlan, stored.id());
      db.update("UPDATE analysis_request SET job_id=? WHERE id=?", job.id(), stored.id());
    }
    return new Created(get(projectId, stored.id()), candidate.equals(stored.id()));
  }

  /** 不显式选资料时仅在项目恰好一份资料的情况下自动绑定，避免分析错对象。 */
  private String selectSnapshot(String projectId, String snapshotId) {
    projects.get(projectId);
    if (snapshotId != null && !snapshotId.isBlank()) {
      return projects.snapshot(projectId, snapshotId).snapshotId();
    }
    var page = projects.snapshots(projectId, 1, 2);
    if (page.total() == 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "项目还没有资料，请先添加资料");
    }
    if (page.total() > 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "项目有多份资料，请先选择本次分析对象");
    }
    return page.items().getFirst().snapshotId();
  }

  /** 请求详情同时核对项目范围，不能用其他项目路径读取原话或结果身份。 */
  @Transactional(readOnly = true)
  public RequestRow get(String projectId, String requestId) {
    projects.get(projectId);
    return db
        .query(
            """
            SELECT ar.*,aa.id artifact_id,
             DATE_FORMAT(ar.created_at,'%Y-%m-%d %H:%i:%s') created_time
            FROM analysis_request ar LEFT JOIN analysis_artifact aa ON aa.request_id=ar.id
            WHERE ar.id=? AND ar.project_id=?
            """,
            this::row, requestId, projectId)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing("分析请求不存在"));
  }

  /** Worker 依据任务关联读取请求；HTTP 页面不能调用这个跨项目入口。 */
  @Transactional(readOnly = true)
  public RequestRow byJob(String jobId) {
    return db
        .query(
            """
            SELECT ar.*,aa.id artifact_id,
             DATE_FORMAT(ar.created_at,'%Y-%m-%d %H:%i:%s') created_time
            FROM analysis_request ar LEFT JOIN analysis_artifact aa ON aa.request_id=ar.id
            WHERE ar.job_id=?
            """,
            this::row, jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Discussion job has no request"));
  }

  /** 项目智能分析页按新到旧读取请求，查询不触发执行。 */
  @Transactional(readOnly = true)
  public Page<RequestRow> list(String projectId, int page, int size) {
    return list(projectId, page, size, null);
  }

  /** 基准候选限定同项目/作品/执行范围，稳定分页只含成功v7语义报告，最终资格由预检再次核对。 */
  @Transactional(readOnly = true)
  public Page<RequestRow> list(String projectId, int page, int size, String baselineForSnapshotId) {
    validatePage(page, size);
    projects.get(projectId);
    var args = new java.util.ArrayList<Object>(List.of(projectId));
    String from = " FROM analysis_request ar LEFT JOIN analysis_artifact aa ON aa.request_id=ar.id";
    String where = " WHERE ar.project_id=?";
    if (baselineForSnapshotId != null) {
      var target = projects.snapshot(projectId, baselineForSnapshotId);
      from +=
          " JOIN analysis_job j ON j.id=ar.job_id JOIN data_snapshot ds ON ds.id=ar.snapshot_id";
      where +=
          " AND ds.content_id=? AND ar.status='SUCCESS' AND j.status='SUCCESS' AND"
              + " j.execution_scope=? AND j.prompt_version=? AND"
              + " JSON_UNQUOTE(JSON_EXTRACT(j.execution_plan_json,'$.executionMode')) IN"
              + " ('MODEL','LOCAL_STUB')";
      args.add(target.contentId());
      args.add(scopes.executionScope());
      args.add(DiscussionDigestPlan.PROMPT);
    }
    long total = db.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var items =
        db.query(
            "SELECT ar.*,aa.id artifact_id,DATE_FORMAT(ar.created_at,'%Y-%m-%d %H:%i:%s')"
                + " created_time"
                + from
                + where
                + " ORDER BY ar.created_at DESC,ar.id DESC LIMIT ? OFFSET ?",
            this::row,
            args.toArray());
    return new Page<>(List.copyOf(items), total, page, size, (total + size - 1) / size);
  }

  /** 只允许从同项目、已成功的请求读取不可变产物。 */
  @Transactional(readOnly = true)
  public JsonNode artifact(String projectId, String requestId) {
    RequestRow request = get(projectId, requestId);
    if (!"SUCCESS".equals(request.status()) || request.artifactId() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "分析结果尚未发布");
    }
    String content =
        db.queryForObject(
            "SELECT artifact_json FROM analysis_artifact WHERE request_id=?",
            String.class,
            requestId);
    try {
      return json.readTree(content);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored analysis artifact", e);
    }
  }

  /** 工作者从已接受请求读取固定背景；不会按当前项目最新资料重新选取。 */
  public AnalysisContextStore.View context(String projectId, String requestId) {
    return contexts.get(projectId, requestId);
  }

  /** 项目内只读规格；缺失请求404，旧讨论返回null，不回填命题。 */
  public AnalysisSpecification specification(String projectId, String requestId) {
    get(projectId, requestId);
    return specifications.get(projectId, requestId);
  }

  /** Worker读取冻结清单；旧请求返回null，不能自行扩为完整范围。 */
  public StanceChunkPlanner.Manifest scope(String projectId, String requestId) {
    get(projectId, requestId);
    return scopes.get(projectId, requestId);
  }

  /** 只读真实批次进度；成功发布与分片完成分别显示，不返回原模型正文。 */
  public AnalysisScopeStore.Progress progress(String projectId, String requestId) {
    var request = get(projectId, requestId);
    return scopes.progress(
        projectId, requestId, request.jobId(), "SUCCESS".equals(request.status()));
  }

  /** 数据库行只映射公开状态，不返回请求键或执行计划正文。 */
  private RequestRow row(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new RequestRow(
        row.getString("id"),
        row.getString("project_id"),
        row.getString("snapshot_id"),
        row.getString("instruction"),
        row.getString("intent"),
        row.getBoolean("include_assessment"),
        row.getString("status"),
        row.getString("job_id"),
        row.getString("artifact_id"),
        row.getString("last_error_code"),
        row.getString("created_time"));
  }

  /** 防止超大分页或整数偏移溢出。 */
  private void validatePage(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 50) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码1～10000，每页1～50条");
    }
  }

  /** 范围内缺失统一返回404，不暴露其他项目是否存在同一请求。 */
  private ResponseStatusException missing(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
