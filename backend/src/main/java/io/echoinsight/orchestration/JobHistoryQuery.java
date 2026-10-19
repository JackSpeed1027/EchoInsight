/* 文件职责：分页查询已有任务及报告入口，查询不会创建任务或改写执行状态。 */
package io.echoinsight.orchestration;

import io.echoinsight.project.ProjectStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 将只读历史查询与任务执行分开，便于核对查询是否产生副作用。 */
@Service
public class JobHistoryQuery {
  private final JdbcTemplate db;
  private final ProjectStore projects;

  /** 复用项目数据库连接池，不新增数据库或后台进程。 */
  public JobHistoryQuery(JdbcTemplate db, ProjectStore projects) {
    this.projects = projects;
    this.db = db;
  }

  /** 一条历史记录包含来源标题、状态、原始创建时间及已发布报告入口。 */
  public record Item(
      String id,
      String snapshotId,
      String title,
      String sourceType,
      String status,
      int attempts,
      String createdAt,
      String reportId) {}

  /** total 是当前筛选范围内的总数，totalPages=0 表示没有匹配记录。 */
  public record Page(List<Item> items, long total, int page, int size, long totalPages) {}

  /** 1、校验分页和筛选；2、同一只读事务计数；3、按时间和 ID 稳定排序取页。 */
  @Transactional(readOnly = true)
  public Page list(int page, int size, String status) {
    return list(page, size, status, null);
  }

  /** 项目历史仅纳入显式挂载快照；全局旧入口保持兼容，不因项目创建迁移旧任务。 */
  @Transactional(readOnly = true)
  public Page list(int page, int size, String status, String projectId) {
    if (page < 1 || page > 10000 || size < 1 || size > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码为 1～10000，每页条数为 1～50");
    if (status != null
        && !Set.of("ALL", "PENDING", "RUNNING", "FAILED", "SUCCESS", "CANCELLED").contains(status))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的任务状态");
    boolean filtered = status != null && !status.equals("ALL");
    String where =
        filtered ? " WHERE j.job_kind='FEEDBACK' AND j.status=?" : " WHERE j.job_kind='FEEDBACK'";
    List<Object> args = new ArrayList<>();
    if (filtered) args.add(status);
    if (projectId != null) {
      projects.get(projectId);
      where +=
          " AND EXISTS(SELECT 1 FROM project_snapshot ps JOIN business_project p ON"
              + " p.id=ps.project_id WHERE ps.snapshot_id=j.snapshot_id AND p.id=? AND"
              + " p.workspace_scope=?)";
      args.add(projectId);
      args.add(projects.workspace());
    }
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM analysis_job j" + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var rows =
        db.query(
            """
            SELECT j.id,j.snapshot_id,
                   JSON_UNQUOTE(JSON_EXTRACT(s.document_json,'$.content.title')) AS title,
                   c.source_type,j.status,j.attempts,
                   DATE_FORMAT(j.created_at,'%Y-%m-%d %H:%i:%s') AS created_at,
                   r.id AS report_id
              FROM analysis_job j
              JOIN data_snapshot s ON s.id=j.snapshot_id
              JOIN content_item c ON c.id=s.content_id
              LEFT JOIN analysis_report r ON r.job_id=j.id
            """
                + where
                + " ORDER BY j.created_at DESC,j.id DESC LIMIT ? OFFSET ?",
            (r, n) ->
                new Item(
                    r.getString("id"),
                    r.getString("snapshot_id"),
                    r.getString("title"),
                    r.getString("source_type"),
                    r.getString("status"),
                    r.getInt("attempts"),
                    r.getString("created_at"),
                    r.getString("report_id")),
            args.toArray());
    return new Page(List.copyOf(rows), total, page, size, (total + size - 1) / size);
  }
}
