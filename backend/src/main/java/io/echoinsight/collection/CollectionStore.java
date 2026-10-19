/* 文件职责：持久化采集批次、幂等键、执行租约和范围诊断，所有状态转换都由数据库条件保护。 */
package io.echoinsight.collection;

import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 批次状态可跨进程重启恢复；外部网络等待不发生在数据库事务中。 */
@Repository
public class CollectionStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final BilibiliCollectionSettings settings;

  public CollectionStore(
      JdbcTemplate db, ProjectStore projects, BilibiliCollectionSettings settings) {
    this.db = db;
    this.projects = projects;
    this.settings = settings;
  }

  /** API 返回不包含本机路径和工具 stderr；计数为空表示尚未产生结果。 */
  public record Batch(
      String id,
      String projectId,
      String platform,
      String canonicalUrl,
      String requestedSourceId,
      String resolvedBvid,
      String resolvedAid,
      int requestedRootLimit,
      String status,
      String completeness,
      Integer sourceReportedComments,
      Integer collectedRootCount,
      Integer collectedCommentCount,
      Integer partialThreadCount,
      String snapshotId,
      String importId,
      String toolName,
      String toolVersion,
      int attempts,
      int recoveries,
      String lastErrorCode,
      String createdAt,
      String startedAt,
      String finishedAt,
      boolean collectAll) {}

  /** 工作者专用租约包含 token 和尝试号；token 永不返回浏览器。 */
  public record Lease(Batch batch, String token) {}

  /** 1、锁定项目；2、按请求键插入；3、核对指纹；4、返回新建或已有批次。 */
  @Transactional
  public Batch create(
      String projectId, String requestKey, BilibiliUrlParser.Target target, int rootLimit) {
    return createWithMode(projectId, requestKey, target, rootLimit, false);
  }

  /** 新公开遍历模式独立冻结，旧指纹和1～200根范围保持兼容。 */
  @Transactional
  public Batch createWithMode(
      String projectId,
      String requestKey,
      BilibiliUrlParser.Target target,
      int rootLimit,
      boolean collectAll) {
    if (!settings.enabled())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "B站在线采集尚未在本机启用");
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效的采集请求键");
    if (!collectAll && (rootLimit < 1 || rootLimit > BilibiliCollectionSettings.MAX_ROOT_LIMIT))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "根评论数量需要在 1～200 之间");
    if (collectAll && settings.leaseSeconds() < 90)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "公开分页采集需要至少90秒租约，请检查服务器配置");
    projects.lock(projectId);
    int frozenLimit = collectAll ? PagedBilibiliCollection.MAX_COMMENTS : rootLimit;
    String fingerprint =
        sha256(
            target.canonicalUrl()
                + "\n"
                + frozenLimit
                + "\ntrue"
                + (collectAll ? "\nVISIBLE_V1" : ""));
    String candidate = UUID.randomUUID().toString();
    db.update(
        """
        INSERT INTO source_collection_batch(
          id,workspace_scope,project_id,request_key,request_fingerprint,platform,canonical_url,
          requested_source_id,requested_root_limit,include_replies,status,tool_name,tool_version,collection_mode)
        VALUES(?,?,?,?,?,'BILIBILI',?,?,?,1,'PENDING',?,?,?)
        ON DUPLICATE KEY UPDATE id=id
        """,
        candidate,
        projects.workspace(),
        projectId,
        requestKey,
        fingerprint,
        target.canonicalUrl(),
        target.sourceId(),
        frozenLimit,
        collectAll ? "bilibili-public-pages" : "bilibili-cli",
        collectAll ? "visible-pages-v1" : "0.3.0",
        collectAll ? "VISIBLE_V1" : "LIMITED");
    var saved =
        db.queryForMap(
            "SELECT id,request_fingerprint FROM source_collection_batch"
                + " WHERE workspace_scope=? AND project_id=? AND request_key=? FOR UPDATE",
            projects.workspace(),
            projectId,
            requestKey);
    if (!fingerprint.equals(saved.get("request_fingerprint")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已用于不同链接或采集范围");
    return get(projectId, (String) saved.get("id"));
  }

  /** 项目内分页查看采集历史，便于页面刷新后继续观察状态。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Batch> list(String projectId, int page, int size) {
    if (page < 1 || page > 10_000 || size < 1 || size > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码1～10000，每页1～50条");
    projects.get(projectId);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM source_collection_batch WHERE project_id=? AND workspace_scope=?",
            Long.class,
            projectId,
            projects.workspace());
    var items =
        db.query(
            select()
                + " WHERE project_id=? AND workspace_scope=? ORDER BY created_at DESC,id DESC LIMIT"
                + " ? OFFSET ?",
            this::row,
            projectId,
            projects.workspace(),
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        List.copyOf(items), total, page, size, (total + size - 1) / size);
  }

  /** 批次必须同时属于当前项目和本机范围，避免通过 ID 穿透项目边界。 */
  @Transactional(readOnly = true)
  public Batch get(String projectId, String id) {
    projects.get(projectId);
    return db
        .query(
            select() + " WHERE id=? AND project_id=? AND workspace_scope=?",
            this::row,
            id,
            projectId,
            projects.workspace())
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "采集批次不存在"));
  }

  /** 定时器只发现待执行或租约过期批次；最多返回队列可容纳的小批量 ID。 */
  public List<String> candidates() {
    return db.queryForList(
        """
        SELECT id FROM source_collection_batch
        WHERE workspace_scope=? AND (status='PENDING'
           OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND recoveries<3))
        ORDER BY created_at,id LIMIT 4
        """,
        String.class,
        projects.workspace());
  }

  /** 1、锁行；2、认领待执行或过期任务；3、发放新 token；4、增加尝试/恢复次数。 */
  @Transactional
  public Lease claim(String id) {
    var rows =
        db.query(
            "SELECT status,(lease_until<UTC_TIMESTAMP(6)) expired,recoveries FROM"
                + " source_collection_batch WHERE id=? AND workspace_scope=? FOR UPDATE",
            (r, n) -> new Object[] {r.getString(1), r.getBoolean(2), r.getInt(3)},
            id,
            projects.workspace());
    if (rows.isEmpty()) return null;
    Object[] state = rows.get(0);
    String status = (String) state[0];
    boolean recovery = "RUNNING".equals(status) && (Boolean) state[1];
    if (!"PENDING".equals(status) && !recovery) return null;
    if (recovery && (Integer) state[2] >= 3) return null;
    String token = UUID.randomUUID().toString();
    int updated =
        db.update(
            "UPDATE source_collection_batch SET status='RUNNING',attempts=attempts+1,"
                + " recoveries=recoveries+?,owner_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL"
                + " ? SECOND),"
                + " started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),last_error_code=NULL WHERE"
                + " id=?",
            recovery ? 1 : 0,
            token,
            settings.leaseSeconds(),
            id);
    if (updated != 1) return null;
    return new Lease(internal(id), token);
  }

  /** 在每个网络步骤前核对当前租约；取消后旧执行者不得主动开始下一次请求。 */
  public void check(Lease lease) {
    int count =
        db.queryForObject(
            "SELECT COUNT(*) FROM source_collection_batch WHERE id=? AND workspace_scope=? AND"
                + " status='RUNNING' AND owner_token=? AND lease_until>=UTC_TIMESTAMP(6)",
            Integer.class,
            lease.batch().id(),
            projects.workspace(),
            lease.token());
    if (count != 1) throw new LostLease();
  }

  /** 成功发布由外层事务调用；只有仍持有租约的工作者能绑定快照和诊断。 */
  public void succeed(
      String id,
      String token,
      BilibiliCliAdapter.Converted converted,
      String snapshotId,
      String importId,
      String storageKey) {
    int changed =
        db.update(
            """
            UPDATE source_collection_batch SET status='SUCCESS',completeness=?,resolved_bvid=?,resolved_aid=?,
              source_reported_comments=?,collected_root_count=?,collected_comment_count=?,partial_thread_count=?,
              snapshot_id=?,import_id=?,storage_key=?,owner_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(6)
            WHERE id=? AND status='RUNNING' AND owner_token=? AND lease_until>=UTC_TIMESTAMP(6)
            """,
            converted.completeness(),
            converted.bvid(),
            converted.aid(),
            converted.sourceReportedComments(),
            converted.rootCount(),
            converted.commentCount(),
            converted.partialThreadCount(),
            snapshotId,
            importId,
            storageKey,
            id,
            token);
    if (changed != 1) throw new LostLease();
  }

  /** 失败只保存稳定错误码；已经失去租约的旧工作者不能覆盖新尝试。 */
  @Transactional
  public void fail(String id, String token, String code) {
    db.update(
        "UPDATE source_collection_batch SET status='FAILED',last_error_code=?,owner_token=NULL,"
            + " lease_until=NULL,finished_at=UTC_TIMESTAMP(6) WHERE id=? AND status='RUNNING' AND"
            + " owner_token=?",
        safeCode(code),
        id,
        token);
  }

  /** 用户可显式重试失败批次；历史原始存档和尝试计数继续保留。 */
  @Transactional
  public Batch retry(String projectId, String id) {
    get(projectId, id);
    if (db.update(
            "UPDATE source_collection_batch SET"
                + " status='PENDING',recoveries=0,last_error_code=NULL,finished_at=NULL WHERE id=?"
                + " AND project_id=? AND status='FAILED'",
            id,
            projectId)
        != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败的采集批次可以重试");
    return get(projectId, id);
  }

  /** 工作者内部读取不经过 HTTP 项目参数，但仍按服务器工作空间约束。 */
  private Batch internal(String id) {
    return db
        .query(select() + " WHERE id=? AND workspace_scope=?", this::row, id, projects.workspace())
        .stream()
        .findFirst()
        .orElseThrow();
  }

  private String select() {
    return """
    SELECT id,project_id,platform,canonical_url,requested_source_id,resolved_bvid,resolved_aid,
     requested_root_limit,status,completeness,source_reported_comments,collected_root_count,
     collected_comment_count,partial_thread_count,snapshot_id,import_id,tool_name,tool_version,
     attempts,recoveries,last_error_code,collection_mode,
     DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_at,
       DATE_FORMAT(TIMESTAMPADD(SECOND,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP()),started_at),'%Y-%m-%d %H:%i:%s') started_at,
       DATE_FORMAT(TIMESTAMPADD(SECOND,TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP()),finished_at),'%Y-%m-%d %H:%i:%s') finished_at
    FROM source_collection_batch
    """;
  }

  private Batch row(ResultSet r, int ignored) throws SQLException {
    return new Batch(
        r.getString("id"),
        r.getString("project_id"),
        r.getString("platform"),
        r.getString("canonical_url"),
        r.getString("requested_source_id"),
        r.getString("resolved_bvid"),
        r.getString("resolved_aid"),
        r.getInt("requested_root_limit"),
        r.getString("status"),
        r.getString("completeness"),
        integer(r, "source_reported_comments"),
        integer(r, "collected_root_count"),
        integer(r, "collected_comment_count"),
        integer(r, "partial_thread_count"),
        r.getString("snapshot_id"),
        r.getString("import_id"),
        r.getString("tool_name"),
        r.getString("tool_version"),
        r.getInt("attempts"),
        r.getInt("recoveries"),
        r.getString("last_error_code"),
        r.getString("created_at"),
        r.getString("started_at"),
        r.getString("finished_at"),
        "VISIBLE_V1".equals(r.getString("collection_mode")));
  }

  private Integer integer(ResultSet r, String name) throws SQLException {
    int value = r.getInt(name);
    return r.wasNull() ? null : value;
  }

  private String safeCode(String code) {
    return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : "COLLECTION_FAILED";
  }

  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  public static class LostLease extends RuntimeException {}

  /** 三次恢复后仍然失联的任务转为明确失败，避免永远挂在运行中；用户可显式重试。 */
  public void expireExhausted() {
    db.update(
        "UPDATE source_collection_batch SET status='FAILED',last_error_code='RECOVERY_EXHAUSTED',"
            + " owner_token=NULL,lease_until=NULL,finished_at=UTC_TIMESTAMP(6) WHERE"
            + " workspace_scope=? AND status='RUNNING' AND lease_until<UTC_TIMESTAMP(6) AND"
            + " recoveries>=3",
        projects.workspace());
  }
}
