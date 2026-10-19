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

  /** 注入现有数据库、快照与项目存储；不创建新的运行时状态权威。 */
  public AnalysisScopeStore(
      JdbcTemplate db, ObjectMapper json, ImportMapper imports, ProjectStore projects) {
    this.db = db;
    this.json = json;
    this.imports = imports;
    this.projects = projects;
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
      manifest = prepare(projectId, snapshotId);
    } catch (ModelContract.Failure error) {
      return new Estimate(
          "FULL", false, error.code(), 0, 0, 0, 0, plan.budgetLimit(), plan.unit(), true);
    }
    long maximum = maximum(plan, manifest.chunks().size());
    String reason =
        !"MODEL".equals(plan.reportMode())
            ? "FULL_MODEL_REQUIRED"
            : maximum > plan.budgetLimit() ? "FULL_BUDGET_INSUFFICIENT" : null;
    return new Estimate(
        "FULL",
        reason == null,
        reason,
        manifest.targetComments(),
        manifest.chunks().size(),
        manifest.contextOccurrences(),
        maximum,
        plan.budgetLimit(),
        plan.unit(),
        true);
  }

  /** 从明确成员快照规划；不读项目最新版本，不对缺父或超界节点静默降级。 */
  public StanceChunkPlanner.Manifest prepare(String projectId, String snapshotId) {
    projects.snapshot(projectId, snapshotId);
    try {
      return StanceChunkPlanner.plan(
          snapshotId,
          json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class));
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
    return prepare(projectId, snapshotId);
  }

  /** 费用上界使用整数，不估算Tokenizer，不自动扩大服务器预算或降低预留。 */
  private long maximum(ModelContract.Plan plan, int batches) {
    if (!"MODEL".equals(plan.reportMode())) return 0;
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
      return json.readValue(rows.getFirst(), StanceChunkPlanner.Manifest.class);
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
      List<BatchProgress> batches) {}

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
    int completed = 0, comments = 0;
    for (var chunk : manifest.chunks()) {
      String status = states.getOrDefault(chunk.index(), "NOT_STARTED");
      if ("SUCCEEDED".equals(status)) {
        completed++;
        comments += chunk.targetIds().size();
      }
      rows.add(
          new BatchProgress(
              chunk.index(), chunk.targetIds().size(), chunk.inputIds().size(), status));
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
