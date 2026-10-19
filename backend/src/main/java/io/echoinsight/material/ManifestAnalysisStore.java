/* 文件职责：把冻结集合的一句话需求原子拆成现有单资料请求，并从子请求派生汇总状态。 */
package io.echoinsight.material;

import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.model.ConfiguredModelPlan;
import io.echoinsight.model.ModelContract;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 组合层只组织输入和结果引用；执行、租约、模型调用和费用继续由原有任务系统负责。 */
@Service
public class ManifestAnalysisStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final InputManifestStore manifests;
  private final AnalysisRequestStore requests;
  private final ConfiguredModelPlan plans;

  /** 依赖现有业务门面，避免集合层重复实现单作品模型流程。 */
  public ManifestAnalysisStore(
      JdbcTemplate db,
      ProjectStore projects,
      InputManifestStore manifests,
      AnalysisRequestStore requests,
      ConfiguredModelPlan plans) {
    this.db = db;
    this.projects = projects;
    this.manifests = manifests;
    this.requests = requests;
    this.plans = plans;
  }

  /** 子结果只存身份，不复制原文和产物；失败原因可从所属单请求继续核查。 */
  public record Item(
      int ordinal,
      String title,
      String snapshotId,
      String requestId,
      String jobId,
      String status,
      String artifactId,
      String lastErrorCode) {}

  /** 汇总状态从当前子请求计算，避免维护一份可能过时的第二状态机。 */
  public record Run(
      String id,
      String projectId,
      String manifestId,
      String instruction,
      String status,
      String sourceMode,
      String budgetUnit,
      long aggregateBudgetLimit,
      String aggregateBudgetUsd,
      int succeeded,
      int failed,
      int pending,
      int cancelled,
      String createdAt,
      List<Item> items,
      List<String> limitations) {}

  /** 1、核对冻结范围；2、锁项目确保幂等；3、冻结同一计划；4、同事务创建全部子请求。 */
  @Transactional
  public Run create(String projectId, String key, String manifestId, String instruction) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效集合分析请求键");
    if (instruction == null || instruction.isBlank() || instruction.strip().length() > 1000)
      throw bad("分析要求为1～1000字");
    // 项目锁先于首次一致性读取，重发不使用其他事务提交前的旧快照。
    projects.lock(projectId);
    var manifest = manifests.get(projectId, manifestId);
    if (manifest.items().isEmpty() || manifest.items().size() > 5)
      throw bad("本轮分作品分析每次1～5个作品，请建立较小集合");
    String clean = instruction.strip();
    var old =
        db.queryForList(
            "SELECT id,manifest_id,instruction FROM manifest_analysis_run WHERE project_id=? AND"
                + " request_key=? FOR UPDATE",
            projectId,
            key);
    if (!old.isEmpty()) {
      var saved = old.getFirst();
      if (!manifestId.equals(saved.get("manifest_id")) || !clean.equals(saved.get("instruction")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已用于其他范围或要求");
      return get(projectId, (String) saved.get("id"));
    }
    String id = UUID.randomUUID().toString();
    var plan = plans.discussion();
    long aggregateLimit = Math.multiplyExact(plan.budgetLimit(), manifest.items().size());
    db.update(
        "INSERT INTO"
            + " manifest_analysis_run(id,project_id,manifest_id,request_key,instruction,execution_plan_json,budget_unit,aggregate_budget_limit)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        projectId,
        manifestId,
        key,
        clean,
        ModelContract.encode(plan),
        plan.unit(),
        aggregateLimit);
    for (var item : manifest.items()) {
      var child =
          requests
              .createWithPlan(
                  projectId,
                  "manifest:" + id + ":" + item.ordinal(),
                  item.snapshotId(),
                  clean,
                  plan)
              .request();
      db.update(
          "INSERT INTO manifest_analysis_item(run_id,ordinal_no,snapshot_id,analysis_request_id)"
              + " VALUES(?,?,?,?)",
          id,
          item.ordinal(),
          item.snapshotId(),
          child.id());
    }
    return get(projectId, id);
  }

  /** 所有子请求在同一只读事务中取状态，避免页面看到混合时刻的汇总。 */
  @Transactional(readOnly = true)
  public Run get(String projectId, String id) {
    projects.get(projectId);
    var rows =
        db.queryForList(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " manifest_analysis_run WHERE id=? AND project_id=?",
            id, projectId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "集合分析不存在");
    var row = rows.getFirst();
    var manifest = manifests.get(projectId, (String) row.get("manifest_id"));
    Map<String, String> titles = new HashMap<>();
    manifest.items().forEach(item -> titles.put(item.snapshotId(), item.title()));
    var items =
        db.query(
            """
            SELECT i.ordinal_no,i.snapshot_id,ar.id request_id,ar.job_id,ar.status,ar.last_error_code,aa.id artifact_id
            FROM manifest_analysis_item i JOIN analysis_request ar ON ar.id=i.analysis_request_id
            LEFT JOIN analysis_artifact aa ON aa.request_id=ar.id WHERE i.run_id=? ORDER BY i.ordinal_no
            """,
            (r, n) ->
                new Item(
                    r.getInt("ordinal_no"),
                    titles.get(r.getString("snapshot_id")),
                    r.getString("snapshot_id"),
                    r.getString("request_id"),
                    r.getString("job_id"),
                    r.getString("status"),
                    r.getString("artifact_id"),
                    r.getString("last_error_code")),
            id);
    if (items.size() != manifest.items().size())
      throw new IllegalStateException("Stored manifest analysis children are incomplete");
    int succeeded = (int) items.stream().filter(item -> "SUCCESS".equals(item.status())).count();
    int failed = (int) items.stream().filter(item -> "FAILED".equals(item.status())).count();
    // 已取消子任务是终态，不能继续计入运行中；聚合只派生展示，不覆盖原子任务。
    int cancelled = (int) items.stream().filter(item -> "CANCELLED".equals(item.status())).count();
    int pending = items.size() - succeeded - failed - cancelled;
    String status =
        succeeded == items.size()
            ? "SUCCESS"
            : pending == 0
                ? cancelled == items.size()
                    ? "CANCELLED"
                    : succeeded > 0 || cancelled > 0 ? "PARTIAL_FAILED" : "FAILED"
                : items.stream().allMatch(item -> "PENDING".equals(item.status()))
                    ? "PENDING"
                    : "RUNNING";
    var plan = ModelContract.plan((String) row.get("execution_plan_json"));
    long budget = ((Number) row.get("aggregate_budget_limit")).longValue();
    String usd =
        "USD_NANOS".equals(plan.unit())
            ? java.math.BigDecimal.valueOf(budget, 9).toPlainString()
            : "0";
    return new Run(
        id,
        projectId,
        manifest.summary().id(),
        (String) row.get("instruction"),
        status,
        plan.reportMode(),
        plan.unit(),
        budget,
        usd,
        succeeded,
        failed,
        pending,
        cancelled,
        (String) row.get("created_time"),
        List.copyOf(items),
        List.of(
            "本次是同一要求的分作品结果集合，不是已经完成跨作品观点归并或整体胜负评议。",
            "总预算为创建时每任务上限乘作品数；原任务账本分别控制，不是账户余额或实际账单。",
            "部分失败保留成功结果，后续可从单任务入口显式重试；结果未知的模型调用仍受原有重试限制。"));
  }

  /** 项目与集合均校验归属，历史只展示明确范围下的运行。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Run> list(String projectId, String manifestId, int page, int size) {
    manifests.get(projectId, manifestId);
    if (page < 1 || page > 10000 || size < 1 || size > 10) throw bad("每页1～10次集合分析");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM manifest_analysis_run WHERE project_id=? AND manifest_id=?",
            Long.class,
            projectId,
            manifestId);
    var ids =
        db.queryForList(
            "SELECT id FROM manifest_analysis_run WHERE project_id=? AND manifest_id=? ORDER BY"
                + " created_at DESC,id DESC LIMIT ? OFFSET ?",
            String.class,
            projectId,
            manifestId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        ids.stream().map(id -> get(projectId, id)).toList(),
        total,
        page,
        size,
        (total + size - 1) / size);
  }

  /** 输入范围超限时明确拒绝，不静默少跑部分作品。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
