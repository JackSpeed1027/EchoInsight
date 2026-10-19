/* 文件职责：持久化候选发现的幂等输入、短租约及原始字节指纹，与评论采集共享有界执行器。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 发现成功后结果冻结；没有隐式重试平台拒绝，过期租约最多接管两次。 */
@Service
public class CreatorDiscoveryStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final BilibiliCollectionSettings settings;
  private final CreatorDiscovery adapter;
  private final ObjectMapper json;

  /** 工具与DB分阶段协作，所有网络调用放在Worker事务之外。 */
  public CreatorDiscoveryStore(
      JdbcTemplate db,
      ProjectStore projects,
      BilibiliCollectionSettings settings,
      CreatorDiscovery adapter,
      ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.settings = settings;
    this.adapter = adapter;
    this.json = json;
  }

  /** 不暴露租约或原始数据，页面通过同一项目路径读取白名单候选。 */
  public record Run(
      String id,
      String projectId,
      String ownerMid,
      String keyword,
      int candidateLimit,
      String status,
      int attempts,
      String lastErrorCode,
      String rawSha256,
      CreatorDiscovery.Result result,
      String createdAt) {}

  /** 仅内部工作者可使用的执行资格。 */
  public record Lease(Run run, String token) {}

  /** 1、解析官方URL和上限；2、锁项目核对重发；3、创建待执行记录；4、返回轮询身份。 */
  @Transactional
  public Run create(String projectId, String key, String url, String keyword, int limit) {
    // 在首次一致性读取前取得项目锁，避免REPEATABLE READ看不到刚提交的同键记录。
    projects.lock(projectId);
    if (!settings.enabled())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "B站在线采集尚未启用");
    String mid = adapter.owner(url), clean = keyword == null ? "" : keyword.strip();
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || clean.length() > 80
        || clean.chars().anyMatch(Character::isISOControl)
        || limit < 1
        || limit > 20)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键词最多80字，候选上限1～20");
    var old =
        db.queryForList(
            "SELECT id FROM creator_discovery WHERE project_id=? AND request_key=?",
            String.class,
            projectId,
            key);
    if (!old.isEmpty()) {
      var run = get(projectId, old.getFirst());
      if (!mid.equals(run.ownerMid())
          || !clean.equals(run.keyword())
          || limit != run.candidateLimit())
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已用于其他发现范围");
      return run;
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO creator_discovery(id,project_id,request_key,owner_mid,keyword,candidate_limit)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        projectId,
        key,
        mid,
        clean,
        limit);
    return get(projectId, id);
  }

  /** 读取必须验证服务器工作空间，不接受客户端提供workspace。 */
  @Transactional(readOnly = true)
  public Run get(String projectId, String id) {
    projects.get(projectId);
    return internal(projectId, id);
  }

  /** 只加载最近20次检索；用户可带页码继续，避免一次加载全部元数据。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Run> list(String projectId, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 20)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每页1～20次检索");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM creator_discovery WHERE project_id=?", Long.class, projectId);
    var ids =
        db.queryForList(
            "SELECT id FROM creator_discovery WHERE project_id=? ORDER BY created_at DESC,id DESC"
                + " LIMIT ? OFFSET ?",
            String.class,
            projectId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        ids.stream().map(id -> internal(projectId, id)).toList(),
        total,
        page,
        size,
        (total + size - 1) / size);
  }

  /** 同执行范围只发现小批量；最终过期状态也在扫描时明确收敛为失败。 */
  @Transactional
  public List<String> candidates() {
    db.update(
        "UPDATE creator_discovery d JOIN business_project p ON p.id=d.project_id SET"
            + " d.status='FAILED',d.last_error_code='DISCOVERY_LEASE_EXHAUSTED',d.owner_token=NULL,d.lease_until=NULL"
            + " WHERE p.workspace_scope=? AND d.status='RUNNING' AND d.lease_until<UTC_TIMESTAMP(6)"
            + " AND d.attempts>=3",
        projects.workspace());
    return db.queryForList(
        "SELECT d.id FROM creator_discovery d JOIN business_project p ON p.id=d.project_id WHERE"
            + " p.workspace_scope=? AND (d.status='PENDING' OR (d.status='RUNNING' AND"
            + " d.lease_until<UTC_TIMESTAMP(6) AND d.attempts<3)) ORDER BY d.created_at,d.id LIMIT"
            + " 2",
        String.class,
        projects.workspace());
  }

  /** 条件更新发放唯一token；并发工作者和其他工作空间均不能重复取得资格。 */
  @Transactional
  public Lease claim(String id) {
    String token = UUID.randomUUID().toString();
    int changed =
        db.update(
            "UPDATE creator_discovery d JOIN business_project p ON p.id=d.project_id SET"
                + " d.status='RUNNING',d.owner_token=?,d.lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL"
                + " ? SECOND),d.attempts=d.attempts+1 WHERE d.id=? AND p.workspace_scope=? AND"
                + " (d.status='PENDING' OR (d.status='RUNNING' AND d.lease_until<UTC_TIMESTAMP(6)"
                + " AND d.attempts<3))",
            token,
            settings.leaseSeconds(),
            id,
            projects.workspace());
    if (changed == 0) return null;
    String project =
        db.queryForObject("SELECT project_id FROM creator_discovery WHERE id=?", String.class, id);
    return new Lease(internal(project, id), token);
  }

  /** 结果和原始字节指纹同事务绑定；旧token只会返回false，不覆盖接管者状态。 */
  @Transactional
  public boolean succeed(Lease lease, byte[] raw, CreatorDiscovery.Result result) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
      return db.update(
              "UPDATE creator_discovery SET"
                  + " status='SUCCESS',raw_json=?,raw_sha256=?,result_json=?,owner_token=NULL,lease_until=NULL,last_error_code=NULL"
                  + " WHERE id=? AND status='RUNNING' AND owner_token=? AND"
                  + " lease_until>=UTC_TIMESTAMP(6)",
              new String(raw, StandardCharsets.UTF_8),
              hash,
              json.writeValueAsString(result),
              lease.run().id(),
              lease.token())
          == 1;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot publish discovery", e);
    }
  }

  /** 保存安全错误码，stderr、内部路径和凭据不进入接口。 */
  public void fail(Lease lease, String code) {
    db.update(
        "UPDATE creator_discovery SET"
            + " status='FAILED',last_error_code=?,owner_token=NULL,lease_until=NULL WHERE id=? AND"
            + " status='RUNNING' AND owner_token=? AND lease_until>=UTC_TIMESTAMP(6)",
        code,
        lease.run().id(),
        lease.token());
  }

  /** 存储JSON损坏直接报错，不伪装成空候选列表。 */
  private Run internal(String projectId, String id) {
    var rows =
        db.query(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " creator_discovery WHERE project_id=? AND id=?",
            (r, n) -> {
              try {
                return new Run(
                    r.getString("id"),
                    r.getString("project_id"),
                    r.getString("owner_mid"),
                    r.getString("keyword"),
                    r.getInt("candidate_limit"),
                    r.getString("status"),
                    r.getInt("attempts"),
                    r.getString("last_error_code"),
                    r.getString("raw_sha256"),
                    r.getString("result_json") == null
                        ? null
                        : json.readValue(r.getString("result_json"), CreatorDiscovery.Result.class),
                    r.getString("created_time"));
              } catch (Exception e) {
                throw new IllegalStateException("Invalid stored discovery", e);
              }
            },
            projectId,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "候选检索不存在");
    return rows.getFirst();
  }
}
