/* 文件职责：将原分析/采集任务汇总为项目任务中心，只读查询不派工、不初始化费用账本。 */
package io.echoinsight.orchestration;

import io.echoinsight.collection.CollectionStore;
import io.echoinsight.model.ModelLedger;
import io.echoinsight.project.ProjectStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 项目和执行范围来自服务器；本地范围隔离不能替代未来的账号鉴权。 */
@Service
public class ProjectTaskQuery {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final JobStore jobs;
  private final ModelLedger ledger;
  private final CollectionStore collections;
  private final String executionScope;

  /** 共用原存储与账本，不查询报告正文、供应商地址或凭据。 */
  public ProjectTaskQuery(
      JdbcTemplate db,
      ProjectStore projects,
      JobStore jobs,
      ModelLedger ledger,
      CollectionStore collections,
      @Value("${echoinsight.worker.execution-scope:local}") String executionScope) {
    this.db = db;
    this.projects = projects;
    this.jobs = jobs;
    this.ledger = ledger;
    this.collections = collections;
    this.executionScope = executionScope;
  }

  /** 最小任务摘要；费用未决单独呈现，标题/问题只来自当前项目的原记录。 */
  public record Item(
      String kind,
      String id,
      String status,
      String title,
      String instruction,
      String snapshotId,
      String requestId,
      String reportId,
      String createdAt,
      int attempts,
      String errorCode,
      boolean feeUncertain,
      boolean canCancel,
      boolean canResume,
      boolean canRetry) {}

  /** 统计始终覆盖本项目全部任务，不随当前列表筛选改变；未知费用不换算成零。 */
  public record Counts(
      long total,
      long pending,
      long running,
      long success,
      long failed,
      long cancelled,
      long feeUncertain) {}

  /** 同一事务计数/取页，页码总数属于筛选范围，counts属于整个项目。 */
  public record Page(
      List<Item> items, long total, int page, int size, long totalPages, Counts counts) {}

  /** 控制事件只记状态转换，不存用户原文或供应商响应，旧操作不会被事后补写。 */
  public record Event(String action, String previousStatus, String nextStatus, String createdAt) {}

  /** 详情限制模型前20批、每批最近3次，事件最新20条；不下载整份报告或采集页。 */
  public record Detail(
      Item task,
      List<JobStore.Step> steps,
      ModelLedger.View model,
      int completedPages,
      long checkpointBytes,
      List<Event> recentEvents,
      long eventCount,
      List<String> notes) {}

  /**
   * 仅构造服务端固定SQL片段。1、约束项目与执行范围；2、排除其他项目讨论；3、反馈任务按挂载成员核对。 UNION ALL保留两个类型的身份，不能将碰巧相同的UUID当成同一任务。
   * MySQL5.7会让JSON字符串与ASCII URL的UNION采用ASCII；两侧标题明确转utf8mb4，防止中文变问号。
   */
  private String source() {
    return """
    SELECT 'ANALYSIS' kind,j.id,j.status,
      CONVERT(JSON_UNQUOTE(JSON_EXTRACT(s.document_json,'$.content.title')) USING utf8mb4) title,
      ar.instruction,j.snapshot_id,ar.id request_id,r.id report_id,j.created_at,j.attempts,
      j.last_error_code error_code,
      EXISTS(SELECT 1 FROM model_call mc WHERE mc.job_id=j.id
        AND mc.state IN ('SENT','OUTCOME_UNKNOWN')) fee_uncertain
    FROM analysis_job j JOIN data_snapshot s ON s.id=j.snapshot_id
    LEFT JOIN analysis_request ar ON ar.id=j.analysis_request_id
    LEFT JOIN analysis_report r ON r.job_id=j.id
    WHERE j.execution_scope=? AND ((j.job_kind='DISCUSSION' AND ar.project_id=?)
      OR (j.job_kind='FEEDBACK' AND EXISTS(SELECT 1 FROM project_snapshot ps
        WHERE ps.project_id=? AND ps.snapshot_id=j.snapshot_id)))
    UNION ALL
    SELECT 'COLLECTION',b.id,b.status,CONVERT(b.canonical_url USING utf8mb4),NULL,b.snapshot_id,NULL,NULL,b.created_at,
      b.attempts,b.last_error_code,0
    FROM source_collection_batch b WHERE b.project_id=? AND b.workspace_scope=?
    """;
  }

  /** 与source中的固定占位符一一对应，用户值不会直接拼接为SQL。 */
  private List<Object> arguments(String projectId) {
    return new ArrayList<>(
        List.of(executionScope, projectId, projectId, projectId, projects.workspace()));
  }

  /** 同时校验资格、合法筛选和分页；条件改变只影响当前items，不改变Counts。 */
  @Transactional(readOnly = true)
  public Page list(String projectId, String kind, String status, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码为1～10000，每页1～50条");
    if (!Set.of("ALL", "ANALYSIS", "COLLECTION").contains(kind)
        || !Set.of("ALL", "ACTIVE", "PENDING", "RUNNING", "SUCCESS", "FAILED", "CANCELLED")
            .contains(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务筛选");
    String union = "(" + source() + ") t";
    Counts counts =
        db.queryForObject(
            """
            SELECT COUNT(*),COALESCE(SUM(status='PENDING'),0),COALESCE(SUM(status='RUNNING'),0),
             COALESCE(SUM(status='SUCCESS'),0),COALESCE(SUM(status='FAILED'),0),
             COALESCE(SUM(status='CANCELLED'),0),COALESCE(SUM(fee_uncertain),0) FROM
            """
                + union,
            (r, n) ->
                new Counts(
                    r.getLong(1),
                    r.getLong(2),
                    r.getLong(3),
                    r.getLong(4),
                    r.getLong(5),
                    r.getLong(6),
                    r.getLong(7)),
            arguments(projectId).toArray());
    String where = " WHERE 1=1";
    List<Object> args = arguments(projectId);
    if (!"ALL".equals(kind)) {
      where += " AND kind=?";
      args.add(kind);
    }
    if ("ACTIVE".equals(status)) where += " AND status IN ('PENDING','RUNNING')";
    else if (!"ALL".equals(status)) {
      where += " AND status=?";
      args.add(status);
    }
    long total =
        db.queryForObject("SELECT COUNT(*) FROM " + union + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var items =
        db.query(
            "SELECT t.*,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM "
                + union
                + where
                + " ORDER BY created_at DESC,kind,id DESC LIMIT ? OFFSET ?",
            this::row,
            args.toArray());
    return new Page(List.copyOf(items), total, page, size, (total + size - 1) / size, counts);
  }

  /** 严格按项目与类型查找；控制服务先锁项目，调用本方法不会把同快照其他项目讨论纳入。 */
  public Item item(String projectId, String kind, String id) {
    TaskPolicy.kind(kind);
    projects.get(projectId);
    List<Object> args = arguments(projectId);
    args.add(kind);
    args.add(id);
    return db
        .query(
            "SELECT t.*,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM ("
                + source()
                + ") t WHERE kind=? AND id=?",
            this::row,
            args.toArray())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "项目任务不存在"));
  }

  /** 同一只读快照读取状态、费用和有限审计，不随查询初始化预算，也不返回步骤草稿正文。 */
  @Transactional(readOnly = true)
  public Detail detail(String projectId, String kind, String id) {
    Item task = item(projectId, kind, id);
    var events =
        db.query(
            """
            SELECT action,previous_status,next_status,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time
            FROM task_control_event WHERE project_id=? AND task_kind=? AND task_id=?
            ORDER BY created_at DESC,id DESC LIMIT 20
            """,
            (r, n) -> new Event(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
            projectId,
            kind,
            id);
    long count =
        db.queryForObject(
            "SELECT COUNT(*) FROM task_control_event WHERE project_id=? AND task_kind=? AND"
                + " task_id=?",
            Long.class,
            projectId,
            kind,
            id);
    if ("ANALYSIS".equals(kind)) {
      var steps =
          jobs.steps(id).stream()
              .map(step -> new JobStore.Step(step.key(), step.status(), null))
              .toList();
      return new Detail(
          task,
          steps,
          ledger.view(id, 1, 20),
          0,
          0,
          List.copyOf(events),
          count,
          List.of(
              "取消阻止后续派发和发布，不能撤回已发出的模型请求；未决费用保留预留。",
              "继续复用原快照、问题、计划、成功检查点及任务预算，不创建新任务；可能发生后续费用。",
              "模型最多展示前20批摘要；成功报告和已保存输入不会因取消被删除。"));
    }
    collections.get(projectId, id);
    var storage =
        db.queryForMap(
            "SELECT COUNT(*) pages,COALESCE(SUM(OCTET_LENGTH(page_json)),0) bytes FROM"
                + " source_collection_page WHERE batch_id=?",
            id);
    return new Detail(
        task,
        List.of(),
        null,
        ((Number) storage.get("pages")).intValue(),
        ((Number) storage.get("bytes")).longValue(),
        List.copyOf(events),
        count,
        List.of(
            "取消使旧租约失效；正在进行的网络或CLI可能运行到自身超时，不能保证物理请求立即停止。",
            "成功页和历史存档保留；继续公开分页从未成功页开始，旧限量CLI可能重新采集。",
            "采集取消不是完成，不发布半份资料；已有成功资料不可取消或覆盖。"));
  }

  /** 把数据库布尔事实和状态一起映射，按钮资格只是当前读视图，写入时必须再次核对。 */
  private Item row(java.sql.ResultSet r, int ignored) throws java.sql.SQLException {
    String state = r.getString("status");
    boolean uncertain = r.getBoolean("fee_uncertain");
    return new Item(
        r.getString("kind"),
        r.getString("id"),
        state,
        r.getString("title"),
        r.getString("instruction"),
        r.getString("snapshot_id"),
        r.getString("request_id"),
        r.getString("report_id"),
        r.getString("created_time"),
        r.getInt("attempts"),
        r.getString("error_code"),
        uncertain,
        TaskPolicy.canCancel(state),
        TaskPolicy.canResume(state, uncertain),
        TaskPolicy.canRetry(state, uncertain));
  }
}
