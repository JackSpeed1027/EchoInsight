/* 文件职责：维护本机项目与明确快照的挂载，保留旧资料，不自动追随全局最新版本。 */
package io.echoinsight.project;

import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 项目是长期业务容器；本机范围来自服务器而非请求，不等于完整多租户鉴权。 */
@Service
public class ProjectStore {
  private final JdbcTemplate db;
  private final String workspace;
  private static final String SUMMARY =
      """
      SELECT p.id,p.name,p.analysis_goal,DATE_FORMAT(p.created_at,'%Y-%m-%d %H:%i:%s') created_at,
       (SELECT COUNT(*) FROM project_snapshot ps WHERE ps.project_id=p.id) snapshot_count,
       (SELECT COUNT(DISTINCT s.content_id) FROM project_snapshot ps
        JOIN data_snapshot s ON s.id=ps.snapshot_id WHERE ps.project_id=p.id) work_count,
       (SELECT COUNT(*) FROM project_snapshot ps JOIN analysis_job j ON j.snapshot_id=ps.snapshot_id
        WHERE ps.project_id=p.id)+(SELECT COUNT(*) FROM content_analysis_input ci WHERE ci.project_id=p.id) job_count,
       (SELECT COUNT(*) FROM content_material cm WHERE cm.project_id=p.id) content_material_count
      FROM business_project p
      """;
  private static final String SNAPSHOT_SELECT =
      """
      SELECT ps.snapshot_id,s.content_id,c.source_type,c.source_content_id,
       JSON_UNQUOTE(JSON_EXTRACT(s.document_json,'$.content.title')) title,
       JSON_LENGTH(JSON_EXTRACT(s.document_json,'$.comments')) comment_count,
       DATE_FORMAT(s.created_at,'%Y-%m-%d %H:%i:%s') snapshot_created_at,
       DATE_FORMAT(ps.added_at,'%Y-%m-%d %H:%i:%s') added_at,
       j.id latest_job_id,j.status latest_job_status,r.id report_id,
       cb.id collection_id,cb.completeness source_completeness,
       cb.source_reported_comments source_reported_comments
      FROM project_snapshot ps
      JOIN data_snapshot s ON s.id=ps.snapshot_id
      JOIN content_item c ON c.id=s.content_id
      LEFT JOIN analysis_job j ON j.id=(
       SELECT x.id FROM analysis_job x WHERE x.snapshot_id=s.id AND x.job_kind='FEEDBACK'
       ORDER BY x.created_at DESC,x.id DESC LIMIT 1)
      LEFT JOIN analysis_report r ON r.job_id=j.id
      LEFT JOIN source_collection_batch cb ON cb.id=(
       SELECT b.id FROM source_collection_batch b WHERE b.project_id=ps.project_id
        AND b.snapshot_id=ps.snapshot_id AND b.status='SUCCESS'
       ORDER BY b.created_at DESC,b.id DESC LIMIT 1)
      """;

  /** 测试使用独立服务器配置范围，避免把验收项目放进真实项目首页。 */
  public ProjectStore(JdbcTemplate db, Environment environment) {
    String scope = environment.getProperty("echoinsight.projects.workspace", "local");
    if (!scope.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid project workspace");
    this.db = db;
    this.workspace = scope;
  }

  /** 项目目标和真实挂载数量，不在前端虚构仪表盘指标。 */
  public record Project(
      String id,
      String name,
      String analysisGoal,
      String createdAt,
      long workCount,
      long snapshotCount,
      long jobCount,
      long contentMaterialCount) {}

  /** 创建首请求与网络重发分别返回，复用已保存项目。 */
  public record Created(Project project, boolean created) {}

  /** 分页总数只统计当前服务器范围，空列表页数为零。 */
  public record Page<T>(List<T> items, long total, int page, int size, long totalPages) {}

  /** 每项是明确版本，最近分析仅属于该快照，未分析时任务字段为空。 */
  public record Snapshot(
      String snapshotId,
      String contentId,
      String sourceType,
      String sourceContentId,
      String title,
      int commentCount,
      String snapshotCreatedAt,
      String addedAt,
      String latestJobId,
      String latestJobStatus,
      String reportId,
      String collectionId,
      String sourceCompleteness,
      Integer sourceReportedComments) {}

  /** 仅供内部查询复用服务器范围，不从 HTTP 接受工作空间参数。 */
  public String workspace() {
    return workspace;
  }

  /** 1、校验；2、按请求键原子定位；3、核对逻辑内容；4、返回新建或既有项目。 */
  @Transactional
  public Created create(String key, String name, String goal) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效的项目请求键");
    if (name == null
        || goal == null
        || name.isBlank()
        || goal.isBlank()
        || name.length() > 120
        || goal.length() > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目名称最多120字，分析目标最多1000字，均不能为空");
    String cleanName = name.strip(), cleanGoal = goal.strip();
    String candidate = UUID.randomUUID().toString();
    db.update(
        """
        INSERT INTO business_project(id,workspace_scope,request_key,name,analysis_goal) VALUES(?,?,?,?,?)
        ON DUPLICATE KEY UPDATE id=id
        """,
        candidate,
        workspace,
        key,
        cleanName,
        cleanGoal);
    var row =
        db.queryForMap(
            """
            SELECT id,name,analysis_goal FROM business_project WHERE workspace_scope=? AND request_key=? FOR UPDATE
            """,
            workspace,
            key);
    if (!cleanName.equals(row.get("name")) || !cleanGoal.equals(row.get("analysis_goal")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已经用于其他项目内容");
    String id = (String) row.get("id");
    return new Created(get(id), candidate.equals(id));
  }

  /** 同一只读事务计数/取页，以创建时间和 ID 稳定排序。 */
  @Transactional(readOnly = true)
  public Page<Project> list(int page, int size) {
    validatePage(page, size);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM business_project WHERE workspace_scope=?", Long.class, workspace);
    var items =
        db.query(
            SUMMARY
                + " WHERE p.workspace_scope=? ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET"
                + " ?",
            this::projectRow,
            workspace,
            size,
            (page - 1) * size);
    return new Page<>(List.copyOf(items), total, page, size, (total + size - 1) / size);
  }

  /** 不存在或不在服务器范围的项目统一404，不泄露其他范围的信息。 */
  @Transactional(readOnly = true)
  public Project get(String id) {
    return db
        .query(SUMMARY + " WHERE p.id=? AND p.workspace_scope=?", this::projectRow, id, workspace)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing("项目不存在"));
  }

  /** 导入/挂载先锁项目，再写成员；调用方必须处于业务事务。 */
  public void lock(String id) {
    if (db.queryForList(
            "SELECT id FROM business_project WHERE id=? AND workspace_scope=? FOR UPDATE",
            String.class,
            id,
            workspace)
        .isEmpty()) throw missing("项目不存在");
  }

  /** 幂等追加成员，不复制正文、不替换旧快照、不改变第一次挂载时间。 */
  @Transactional
  public Snapshot attach(String projectId, String snapshotId) {
    if (snapshotId == null || snapshotId.isBlank() || snapshotId.length() > 36)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效资料快照 ID");
    lock(projectId);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM data_snapshot WHERE id=?", Integer.class, snapshotId)
        == 0) throw missing("资料快照不存在");
    db.update(
        """
        INSERT INTO project_snapshot(project_id,snapshot_id) VALUES(?,?)
        ON DUPLICATE KEY UPDATE id=id
        """,
        projectId,
        snapshotId);
    return snapshot(projectId, snapshotId);
  }

  /** 稳定按挂载流水倒序列出明确版本，后续全局导入不会偷偷改变成员。 */
  @Transactional(readOnly = true)
  public Page<Snapshot> snapshots(String projectId, int page, int size) {
    validatePage(page, size);
    get(projectId);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM project_snapshot WHERE project_id=?", Long.class, projectId);
    var items =
        db.query(
            SNAPSHOT_SELECT + " WHERE ps.project_id=? ORDER BY ps.id DESC LIMIT ? OFFSET ?",
            this::snapshotRow,
            projectId,
            size,
            (page - 1) * size);
    return new Page<>(List.copyOf(items), total, page, size, (total + size - 1) / size);
  }

  /** 深链接先核对项目成员，不能拿另一个版本代替当前分析输入。 */
  @Transactional(readOnly = true)
  public Snapshot snapshot(String projectId, String snapshotId) {
    get(projectId);
    return db
        .query(
            SNAPSHOT_SELECT + " WHERE ps.project_id=? AND ps.snapshot_id=?",
            this::snapshotRow,
            projectId,
            snapshotId)
        .stream()
        .findFirst()
        .orElseThrow(() -> missing("该资料版本尚未添加到项目"));
  }

  /** 防止超大 LIMIT 或页码计算溢出。 */
  private void validatePage(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码1～10000，每页1～50条");
  }

  /** 摘要不带正文，资料详情按原快照与证据入口按需加载。 */
  private Project projectRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Project(
        row.getString("id"),
        row.getString("name"),
        row.getString("analysis_goal"),
        row.getString("created_at"),
        row.getLong("work_count"),
        row.getLong("snapshot_count"),
        row.getLong("job_count"),
        row.getLong("content_material_count"));
  }

  /** 最近任务与快照一一对齐；尚未分析的资料也能出现在项目中。 */
  private Snapshot snapshotRow(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Snapshot(
        row.getString("snapshot_id"),
        row.getString("content_id"),
        row.getString("source_type"),
        row.getString("source_content_id"),
        row.getString("title"),
        row.getInt("comment_count"),
        row.getString("snapshot_created_at"),
        row.getString("added_at"),
        row.getString("latest_job_id"),
        row.getString("latest_job_status"),
        row.getString("report_id"),
        row.getString("collection_id"),
        row.getString("source_completeness"),
        (Integer) row.getObject("source_reported_comments"));
  }

  /** 统一只报告业务缺失，不回显 SQL 或其他命名空间。 */
  private ResponseStatusException missing(String reason) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, reason);
  }
}
