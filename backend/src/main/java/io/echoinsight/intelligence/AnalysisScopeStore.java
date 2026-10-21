/* 文件职责：准备与冻结完整命题范围，并以原账本查询分片进度，不建立第二份任务状态。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 完整是针对当前冻结快照的覆盖，不表示平台全量；预检不写入、不调用模型。 */
@Service
public class AnalysisScopeStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ImportMapper imports;
  private final ProjectStore projects;
  private final AnalysisContextStore contexts;
  private final String executionScope;

  /** 注入现有数据库、快照与项目存储；不创建新的运行时状态权威。 */
  public AnalysisScopeStore(
      JdbcTemplate db,
      ObjectMapper json,
      ImportMapper imports,
      ProjectStore projects,
      AnalysisContextStore contexts,
      @org.springframework.beans.factory.annotation.Value(
              "${echoinsight.worker.execution-scope:local}")
          String executionScope) {
    this.contexts = contexts;
    this.executionScope = executionScope;
    this.db = db;
    this.json = json;
    this.imports = imports;
    this.projects = projects;
  }

  /** 仅供同服务器的候选查询限定任务范围，不接受客户端传值。 */
  public String executionScope() {
    return executionScope;
  }

  /** 自然语言说全部时默认完整，显式PREVIEW代表用户主动改为有限预览。 */
  public static boolean full(String instruction, String scope) {
    if (scope != null && !Set.of("FULL", "PREVIEW").contains(scope))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析范围只能是FULL或PREVIEW");
    if (scope != null) return "FULL".equals(scope);
    if (instruction == null) return false;
    String operation = instruction.replaceAll("命题\\s*「[^「」]+」", "");
    return List.of("所有评论", "所有的评论", "全部评论", "全部的评论", "全量评论", "每一条评论", "完整分析", "全量分析", "全部已保存")
        .stream()
        .anyMatch(operation::contains);
  }

  /** 保存的是保守费用上界和完整可行性，不是供应商报价或已经产生的费用。 */
  public record Estimate(
      String mode,
      boolean available,
      String reasonCode,
      int targetComments,
      int plannedBatches,
      int contextOccurrences,
      long maximumUnits,
      long budgetLimitUnits,
      String unit,
      boolean requiresConfirmation) {}

  /** 1、核对快照归属；2、只读规划；3、保守计算所有片的输入/输出上界；4、返回可执行性。 */
  public Estimate estimate(String projectId, String snapshotId, ModelContract.Plan plan) {
    StanceChunkPlanner.Manifest manifest;
    try {
      manifest = prepare(projectId, snapshotId, plan);
    } catch (ModelContract.Failure error) {
      return new Estimate(
          "FULL", false, error.code(), 0, 0, 0, 0, plan.budgetLimit(), plan.unit(), true);
    }
    return estimate(plan, manifest);
  }

  /** 跨版本命中只减少新MAP调用，所有归并仍重新预检费用。 */
  private Estimate estimate(ModelContract.Plan plan, StanceChunkPlanner.Manifest manifest) {
    int calls =
        manifest.chunks().size()
            - (manifest.reuse() == null ? 0 : manifest.reuse().reused())
            + (DiscussionDigestPlan.enabled(plan)
                ? DiscussionDigestPlan.reductions(manifest.chunks().size()).size()
                : 0);
    long maximum = maximum(plan, calls);
    String reason =
        !DiscussionDigestPlan.enabled(plan) && !"MODEL".equals(plan.reportMode())
            ? "FULL_MODEL_REQUIRED"
            : maximum > plan.budgetLimit() ? "FULL_BUDGET_INSUFFICIENT" : null;
    return new Estimate(
        "FULL",
        reason == null,
        reason,
        manifest.targetComments(),
        calls,
        manifest.contextOccurrences(),
        maximum,
        plan.budgetLimit(),
        plan.unit(),
        true);
  }

  /** 完整普通讨论预检含合格复用清单，失败不写状态或预留费用。 */
  public record Prepared(StanceChunkPlanner.Manifest manifest, Estimate estimate) {}

  /** 显式基准仅影响MAP；解析/图容量失败返回可读不可执行性，资格错误仍按原项目HTTP边界拒绝。 */
  public Prepared previewFull(
      String p,
      String snapshotId,
      ModelContract.Plan plan,
      String question,
      boolean assessment,
      AnalysisContextStore.View context,
      String baselineRequestId) {
    StanceChunkPlanner.Manifest manifest;
    try {
      manifest = prepare(p, snapshotId, plan);
    } catch (ModelContract.Failure error) {
      return new Prepared(
          null,
          new Estimate(
              "FULL", false, error.code(), 0, 0, 0, 0, plan.budgetLimit(), plan.unit(), true));
    }
    var frozen =
        prepareReuse(
            p,
            manifest,
            plan,
            Objects.toString(question, "").strip(),
            assessment,
            context,
            baselineRequestId);
    return new Prepared(frozen, estimate(plan, frozen));
  }

  /** 项目接受事务内重新核对完整计划和成本确认；不能信任浏览器提供的命中数。 */
  public StanceChunkPlanner.Manifest requireFull(
      String p,
      String snapshotId,
      ModelContract.Plan plan,
      boolean confirmed,
      String question,
      boolean assessment,
      AnalysisContextStore.View context,
      String baselineRequestId) {
    var prepared =
        previewFull(p, snapshotId, plan, question, assessment, context, baselineRequestId);
    if (!prepared.estimate().available())
      throw invalid(
          switch (prepared.estimate().reasonCode()) {
            case "FULL_BUDGET_INSUFFICIENT" -> "本任务预算不足以覆盖完整范围的保守上界，请缩小资料范围";
            case "FULL_CONTEXT_MISSING" -> "资料存在缺失父评论，无法按完整范围分析，请补齐后创建新版本";
            case "DIGEST_CAPACITY_EXCEEDED" -> "完整讨论超过512次节点调用容量，请拆分资料后分析";
            default -> "部分评论的完整父语境超过处理边界，当前无法承诺完整分析";
          });
    if (!confirmed) throw invalid("请先确认完整范围及费用估算上界");
    return prepared.manifest();
  }

  /** 1、同项目/作品/执行范围；2、原问题/背景/全计划一致；3、成功无未决；4、复制匹配局部响应及物理收费出处。 */
  private StanceChunkPlanner.Manifest prepareReuse(
      String p,
      StanceChunkPlanner.Manifest current,
      ModelContract.Plan plan,
      String question,
      boolean assessment,
      AnalysisContextStore.View context,
      String baselineId) {
    if (baselineId != null && baselineId.isBlank()) baselineId = null;
    if (baselineId != null && !baselineId.matches("[A-Za-z0-9._:-]{1,64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "讨论复用基准身份无效");
    ImportDocument input = readDocument(current.snapshotId()), oldInput = null;
    StanceChunkPlanner.Manifest source = null;
    AnalysisContextStore.View oldContext = null;
    String sourceJob = null;
    if (baselineId != null) {
      var rows =
          db.queryForList(
              "SELECT r.snapshot_id,r.instruction,r.include_assessment,r.status,j.id"
                  + " job_id,j.execution_scope,j.execution_plan_json,j.status job_status FROM"
                  + " analysis_request r JOIN analysis_job j ON j.id=r.job_id WHERE r.id=? AND"
                  + " r.project_id=? AND j.execution_scope=? AND j.job_kind='DISCUSSION'",
              baselineId,
              p,
              executionScope);
      if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析请求不存在");
      var row = rows.getFirst();
      sourceJob = (String) row.get("job_id");
      if (!"SUCCESS".equals(row.get("status")) || !"SUCCESS".equals(row.get("job_status")))
        throw invalid("讨论复用基准必须是已完成的完整讨论报告");
      ModelContract.Plan sourcePlan;
      try {
        sourcePlan =
            json.readValue((String) row.get("execution_plan_json"), ModelContract.Plan.class);
      } catch (Exception e) {
        throw new IllegalStateException("Invalid baseline plan", e);
      }
      if (!DiscussionDigestPlan.enabled(sourcePlan) || "MOCK".equals(sourcePlan.reportMode()))
        throw invalid("讨论复用只支持新版完整语义报告，免费演示和旧协议不能作基准");
      if (!sourcePlan.equals(plan)) throw invalid("模型协议或费用计划已变化，请不选基准重新分析");
      Object flag = row.get("include_assessment");
      boolean oldAssessment =
          flag instanceof Boolean value ? value : ((Number) flag).intValue() != 0;
      if (!question.equals(row.get("instruction")) || assessment != oldAssessment)
        throw invalid("讨论复用基准的问题或评议要求不同，请不选基准重新分析");
      String oldSnapshot = (String) row.get("snapshot_id");
      if (!projects
          .snapshot(p, current.snapshotId())
          .contentId()
          .equals(projects.snapshot(p, oldSnapshot).contentId())) throw invalid("讨论复用基准必须属于同一作品");
      oldContext = contexts.get(p, baselineId);
      if (!Objects.equals(oldContext.fingerprint(), context.fingerprint()))
        throw invalid("讨论复用基准的背景不同，请不选基准重新分析");
      if (db.queryForObject(
              "SELECT COUNT(*) FROM model_call WHERE job_id=? AND state IN"
                  + " ('RESERVED','SENT','OUTCOME_UNKNOWN')",
              Long.class,
              sourceJob)
          != 0) throw invalid("复用基准存在未决调用，不能复用");
      source = get(p, baselineId);
      oldInput = readDocument(oldSnapshot);
      if (source == null || !source.base().equals(DiscussionDigestPlan.plan(oldSnapshot, oldInput)))
        throw invalid("讨论基准分片清单不可用，请不选基准重新分析");
      DiscussionDigestReuse.verify(oldInput, source, oldContext, sourcePlan, question, assessment);
    }
    Map<String, DiscussionDigestReuse.Entry> previous = new HashMap<>();
    if (source != null)
      for (var entry : source.reuse().entries()) previous.putIfAbsent(entry.inputHash(), entry);
    var entries = new ArrayList<DiscussionDigestReuse.Entry>();
    for (var chunk : current.chunks()) {
      var request =
          FullDiscussionAnalyzer.mapInput(
                  input, current, chunk, context, plan, question, assessment)
              .request();
      String hash = DiscussionDigestReuse.fingerprint(plan, request);
      var match = previous.get(hash);
      DiscussionDigestReuse.Cached cached = null;
      if (match != null) {
        cached = match.cached();
        if (cached == null) {
          var oldRequest =
              FullDiscussionAnalyzer.mapInput(
                      oldInput,
                      source,
                      source.chunks().get(match.index()),
                      oldContext,
                      plan,
                      question,
                      assessment)
                  .request();
          var rows =
              db.queryForList(
                  "SELECT input_hash,result_json FROM model_batch WHERE job_id=? AND batch_index=?"
                      + " AND status='SUCCEEDED'",
                  sourceJob,
                  match.index());
          String expected =
              io.echoinsight.material.ReferenceMaterialStore.hash(
                  ModelContract.encode(plan) + ModelContract.encode(oldRequest));
          if (rows.size() != 1 || !expected.equals(rows.getFirst().get("input_hash")))
            throw invalid("讨论基准成功检查点不可用，请不选基准重新分析");
          try {
            var response =
                json.readValue(
                    (String) rows.getFirst().get("result_json"),
                    DiscussionModelContract.Response.class);
            DiscussionDigestContract.validate(oldRequest, response);
            cached =
                new DiscussionDigestReuse.Cached(
                    baselineId,
                    sourceJob,
                    match.index(),
                    io.echoinsight.material.ReferenceMaterialStore.hash(
                        ModelContract.encode(response)),
                    response);
          } catch (Exception e) {
            throw invalid("讨论基准成功检查点不可用，请不选基准重新分析");
          }
        }
        DiscussionDigestContract.validate(request, cached.response());
      }
      entries.add(new DiscussionDigestReuse.Entry(chunk.index(), hash, cached));
    }
    return current.withReuse(
        new DiscussionDigestReuse.Plan(
            DiscussionDigestReuse.VERSION, baselineId, List.copyOf(entries)));
  }

  /** 按明确快照读取不可变原文，不查询项目最新版本。 */
  private ImportDocument readDocument(String snapshotId) {
    try {
      return json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid frozen document", e);
    }
  }

  /** 资格/预算错误在接受事务回滚，不静默转成新的付费计划。 */
  private ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
  }

  /** 从明确成员快照规划；不读项目最新版本，不对缺父或超界节点静默降级。 */
  public StanceChunkPlanner.Manifest prepare(String projectId, String snapshotId) {
    return prepare(projectId, snapshotId, null);
  }

  /** 普通完整讨论复用同表清单，独立版本选择40目标及归并规则，旧命题仍12目标。 */
  public StanceChunkPlanner.Manifest prepare(
      String projectId, String snapshotId, ModelContract.Plan plan) {
    projects.snapshot(projectId, snapshotId);
    try {
      var input = json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class);
      return plan != null && DiscussionDigestPlan.enabled(plan)
          ? DiscussionDigestPlan.plan(snapshotId, input)
          : StanceChunkPlanner.plan(snapshotId, input);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid frozen scope input", error);
    }
  }

  /** 新请求在同事务检查成本确认和完整范围可行性，拒绝时不留下半份任务。 */
  public StanceChunkPlanner.Manifest require(
      String projectId, String snapshotId, ModelContract.Plan plan, boolean confirmed) {
    var estimate = estimate(projectId, snapshotId, plan);
    if (!estimate.available())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          switch (estimate.reasonCode()) {
            case "FULL_MODEL_REQUIRED" -> "完整命题分析需要启用模型，免费演示不会生成完整立场";
            case "FULL_BUDGET_INSUFFICIENT" -> "本任务预算不足以覆盖完整范围的保守上界，请缩小资料范围";
            case "FULL_CONTEXT_MISSING" -> "资料存在缺失父评论，无法按完整范围分析，请补齐后创建新版本";
            default -> "部分评论的完整父语境超过处理边界，当前无法承诺完整分析";
          });
    if (!confirmed)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请先确认完整范围及费用估算上界");
    return prepare(projectId, snapshotId, plan);
  }

  /** 费用上界使用整数，不估算Tokenizer，不自动扩大服务器预算或降低预留。 */
  private long maximum(ModelContract.Plan plan, int batches) {
    if ("MOCK".equals(plan.reportMode())) return 0;
    return Math.multiplyExact(
        (long) batches,
        Math.addExact(
            Math.multiplyExact(plan.inputUnitRate(), plan.inputTokenCeiling()),
            Math.multiplyExact(plan.outputUnitRate(), plan.maxOutputTokens())));
  }

  /** 与原请求同事务追加；重发读取既有清单，不重新定义历史范围。 */
  public void save(String requestId, StanceChunkPlanner.Manifest manifest) {
    db.update(
        "INSERT INTO analysis_request_scope(request_id,manifest_json) VALUES(?,?)",
        requestId,
        ModelContract.encode(manifest));
  }

  /** 调用方先核对请求资格，SQL再次限定项目；普通旧请求返回null。 */
  public StanceChunkPlanner.Manifest get(String projectId, String requestId) {
    var rows =
        db.queryForList(
            "SELECT s.manifest_json FROM analysis_request_scope s JOIN analysis_request r ON"
                + " r.id=s.request_id WHERE r.project_id=? AND r.id=?",
            String.class,
            projectId,
            requestId);
    if (rows.isEmpty()) return null;
    try {
      var result = json.readValue(rows.getFirst(), StanceChunkPlanner.Manifest.class);
      if (DiscussionDigestPlan.VERSION.equals(result.version())) {
        var owners =
            db.queryForList(
                "SELECT r.instruction,r.include_assessment,j.execution_plan_json FROM"
                    + " analysis_request r JOIN analysis_job j ON j.id=r.job_id WHERE r.id=? AND"
                    + " r.project_id=? AND j.execution_scope=?",
                requestId,
                projectId,
                executionScope);
        if (owners.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析请求不存在");
        var owner = owners.getFirst();
        var model =
            json.readValue((String) owner.get("execution_plan_json"), ModelContract.Plan.class);
        var input = readDocument(result.snapshotId());
        if (!result.base().equals(DiscussionDigestPlan.plan(result.snapshotId(), input)))
          throw new IllegalStateException("Discussion frozen scope mismatch");
        Object flag = owner.get("include_assessment");
        boolean assessment =
            flag instanceof Boolean value ? value : ((Number) flag).intValue() != 0;
        DiscussionDigestReuse.verify(
            input,
            result,
            contexts.get(projectId, requestId),
            model,
            (String) owner.get("instruction"),
            assessment);
      }
      return result;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid stored scope", error);
    }
  }

  /** 从原model_batch检查点反映真实完成量，失败/未知不算成功，未处理数不能叫中立。 */
  public record Progress(
      String mode,
      int plannedBatches,
      int completedBatches,
      int completedComments,
      int targetComments,
      boolean published,
      List<BatchProgress> batches,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          DigestProgress processing) {
    /** 保持旧v6和有限预览响应形状，不回填归并进度。 */
    public Progress(
        String mode,
        int plannedBatches,
        int completedBatches,
        int completedComments,
        int targetComments,
        boolean published,
        List<BatchProgress> batches) {
      this(
          mode,
          plannedBatches,
          completedBatches,
          completedComments,
          targetComments,
          published,
          batches,
          null);
    }
  }

  /** 目标输入完成和归并完成分开，免费结构演示语义数均为0。 */
  public record DigestProgress(
      String phase,
      int plannedMaps,
      int completedMaps,
      int plannedReductions,
      int completedReductions,
      int reusedMaps) {}

  public record BatchProgress(int index, int targetComments, int inputComments, String status) {}

  /** 项目/请求资格由上层验证；不初始化预算，也不改变任何调用状态。 */
  public Progress progress(String projectId, String requestId, String jobId, boolean published) {
    var manifest = get(projectId, requestId);
    if (manifest == null) return new Progress("PREVIEW", 0, 0, 0, 0, published, List.of());
    Map<Integer, String> states = new HashMap<>();
    db.query(
        "SELECT batch_index,status FROM model_batch WHERE job_id=?",
        rs -> {
          states.put(rs.getInt(1), rs.getString(2));
        },
        jobId);
    List<BatchProgress> rows = new ArrayList<>();
    int completed = 0,
        comments = 0,
        reused = manifest.reuse() == null ? 0 : manifest.reuse().reused();
    for (var chunk : manifest.chunks()) {
      boolean cached =
          manifest.reuse() != null
              && manifest.reuse().entries().get(chunk.index()).cached() != null;
      if (cached && states.containsKey(chunk.index()))
        throw new IllegalStateException("Reused map has unexpected new batch");
      String status = cached ? "REUSED" : states.getOrDefault(chunk.index(), "NOT_STARTED");
      if ("SUCCEEDED".equals(status) || cached) {
        completed++;
        comments += chunk.targetIds().size();
      }
      rows.add(
          new BatchProgress(
              chunk.index(), chunk.targetIds().size(), chunk.inputIds().size(), status));
    }
    if (DiscussionDigestPlan.VERSION.equals(manifest.version())) {
      var reductions = DiscussionDigestPlan.reductions(manifest.chunks().size());
      int reduced = 0;
      for (var node : reductions) {
        String status = states.getOrDefault(node.index(), "NOT_STARTED");
        if ("SUCCEEDED".equals(status)) reduced++;
        rows.add(new BatchProgress(node.index(), 0, 0, status));
      }
      boolean demo =
          "MOCK"
              .equals(
                  db.queryForObject(
                      "SELECT JSON_UNQUOTE(JSON_EXTRACT(execution_plan_json,'$.executionMode'))"
                          + " FROM analysis_job WHERE id=?",
                      String.class,
                      jobId));
      return new Progress(
          "FULL",
          rows.size() - reused,
          completed + reduced - reused,
          comments,
          manifest.targetComments(),
          published,
          List.copyOf(rows),
          new DigestProgress(
              demo
                  ? "STRUCTURAL_DEMO"
                  : reduced == reductions.size()
                      ? "COMPLETE"
                      : completed == manifest.chunks().size() ? "REDUCE" : "MAP",
              manifest.chunks().size(),
              completed,
              reductions.size(),
              reduced,
              reused));
    }
    return new Progress(
        "FULL",
        rows.size(),
        completed,
        comments,
        manifest.targetComments(),
        published,
        List.copyOf(rows));
  }
}
