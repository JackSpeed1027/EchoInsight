/* 文件职责：将项目内明确快照与采集范围冻结为不可变清单，为多作品分析和增量比较提供同一输入依据。 */
package io.echoinsight.material;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 清单引用已有正文而不复制文本；名称、成员、顺序、指纹与采集诊断创建后不再改变。 */
@Service
public class InputManifestStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ObjectMapper json;

  /** 项目范围与既有资料查询共用，清单不是另一套资料库或用户权限体系。 */
  public InputManifestStore(JdbcTemplate db, ProjectStore projects, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.json = json;
  }

  /** 列表只含范围摘要，读取它不会触发采集或模型。 */
  public record Summary(
      String id, String projectId, String name, int itemCount, String createdAt) {}

  /** 单项冻结来源、快照、原文指纹和当时的采集诊断；未知完整性保留null。 */
  public record Item(
      int ordinal,
      String snapshotId,
      String collectionId,
      String sourceType,
      String sourceContentId,
      String title,
      int commentCount,
      String sourceCompleteness,
      Integer sourceReportedComments,
      String documentSha256) {}

  /** 不可变详情；请求指纹用于重发冲突校验，不是语义结果或质量分数。 */
  public record Manifest(Summary summary, String requestFingerprint, List<Item> items) {}

  /** 1、规范请求；2、锁项目并创建幂等清单；3、验证每个明确版本；4、冻结成员和来源。 */
  @Transactional
  public Manifest create(String projectId, String key, String name, List<String> snapshots) {
    return createInternal(projectId, key, name, snapshots, Map.of());
  }

  /** 批量采集冻结时指定确切采集来源，避免相同正文复用快照后意外绑定另一轮采集诊断。 */
  @Transactional
  public Manifest createFromCollections(
      String projectId, String key, String name, Map<String, String> collections) {
    if (collections == null || collections.isEmpty()) throw bad("需要明确采集来源");
    return createInternal(projectId, key, name, new ArrayList<>(collections.keySet()), collections);
  }

  /** 旧入口保持原指纹；明确采集来源的新入口把来源身份也纳入幂等核对，历史记录不变。 */
  private Manifest createInternal(
      String projectId,
      String key,
      String name,
      List<String> snapshots,
      Map<String, String> collections) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效的清单请求键");
    if (name == null || name.isBlank() || name.strip().length() > 120) throw bad("集合名称为1～120字");
    if (snapshots == null
        || snapshots.isEmpty()
        || snapshots.size() > 20
        || snapshots.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f-]{36}")))
      throw bad("每份集合选择1～20个明确资料版本");
    var selected = snapshots.stream().distinct().sorted().toList();
    if (selected.size() != snapshots.size()) throw bad("不能重复选择同一资料版本");
    String cleanName = name.strip();
    String fingerprint =
        hash(
            encode(
                collections.isEmpty()
                    ? List.of(cleanName, selected)
                    : List.of(cleanName, selected, new TreeMap<>(collections))));
    projects.lock(projectId);
    String candidate = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO input_manifest(id,project_id,request_key,request_fingerprint,name)"
            + " VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
        candidate,
        projectId,
        key,
        fingerprint,
        cleanName);
    var stored =
        db.queryForMap(
            "SELECT id,request_fingerprint FROM input_manifest WHERE project_id=? AND request_key=?"
                + " FOR UPDATE",
            projectId,
            key);
    if (!fingerprint.equals(stored.get("request_fingerprint")))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已用于不同名称或资料范围");
    String id = (String) stored.get("id");
    if (candidate.equals(id)) {
      Set<String> works = new HashSet<>();
      int ordinal = 0;
      for (String snapshot : selected) {
        var member = projects.snapshot(projectId, snapshot);
        String collectionId = member.collectionId();
        String completeness = member.sourceCompleteness();
        Integer reported = member.sourceReportedComments();
        if (collections.containsKey(snapshot)) {
          var source =
              db.queryForList(
                  "SELECT id,completeness,source_reported_comments FROM source_collection_batch"
                      + " WHERE id=? AND project_id=? AND workspace_scope=? AND snapshot_id=? AND"
                      + " status='SUCCESS'",
                  collections.get(snapshot),
                  projectId,
                  projects.workspace(),
                  snapshot);
          if (source.size() != 1) throw bad("明确采集来源不属于当前项目的成功快照");
          collectionId = (String) source.getFirst().get("id");
          completeness = (String) source.getFirst().get("completeness");
          reported = (Integer) source.getFirst().get("source_reported_comments");
        }
        String identity = member.sourceType() + ":" + member.sourceContentId();
        if (!works.add(identity)) throw bad("同一作品只能选择一个版本；版本对比请建立两份集合");
        String digest =
            db.queryForObject(
                "SELECT SHA2(document_json,256) FROM data_snapshot WHERE id=?",
                String.class,
                snapshot);
        db.update(
            """
            INSERT INTO input_manifest_item(manifest_id,ordinal_no,snapshot_id,collection_id,source_type,
              source_content_id,title,comment_count,source_completeness,source_reported_comments,document_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,
            id,
            ++ordinal,
            snapshot,
            collectionId,
            member.sourceType(),
            member.sourceContentId(),
            member.title(),
            member.commentCount(),
            completeness,
            reported,
            digest);
      }
    }
    return get(projectId, id);
  }

  /** 详情只读取清单中已保存的字段，不回查全局最新采集以改写历史范围。 */
  @Transactional(readOnly = true)
  public Manifest get(String projectId, String id) {
    projects.get(projectId);
    var rows =
        db.query(
            summarySql() + " WHERE m.project_id=? AND m.id=?", this::summaryRow, projectId, id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资料集合不存在");
    String fingerprint =
        db.queryForObject(
            "SELECT request_fingerprint FROM input_manifest WHERE id=?", String.class, id);
    var items =
        db.query(
            "SELECT * FROM input_manifest_item WHERE manifest_id=? ORDER BY ordinal_no",
            (r, n) ->
                new Item(
                    r.getInt("ordinal_no"),
                    r.getString("snapshot_id"),
                    r.getString("collection_id"),
                    r.getString("source_type"),
                    r.getString("source_content_id"),
                    r.getString("title"),
                    r.getInt("comment_count"),
                    r.getString("source_completeness"),
                    (Integer) r.getObject("source_reported_comments"),
                    r.getString("document_sha256")),
            id);
    return new Manifest(rows.getFirst(), fingerprint, List.copyOf(items));
  }

  /** 稳定分页只限当前项目；首页列表不一次加载所有成员正文。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String projectId, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 50) throw bad("页码1～10000，每页1～50条");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM input_manifest WHERE project_id=?", Long.class, projectId);
    var items =
        db.query(
            summarySql()
                + " WHERE m.project_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT ? OFFSET ?",
            this::summaryRow,
            projectId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        List.copyOf(items), total, page, size, (total + size - 1) / size);
  }

  /** SQL返回实际成员数量，避免把作品数和评论数混成一个指标。 */
  private String summarySql() {
    return "SELECT m.id,m.project_id,m.name,DATE_FORMAT(m.created_at,'%Y-%m-%d %H:%i:%s')"
        + " created_time, (SELECT COUNT(*) FROM input_manifest_item i WHERE"
        + " i.manifest_id=m.id) item_count FROM input_manifest m";
  }

  /** 只映射安全业务字段，不向调用方暴露连接或请求键。 */
  private Summary summaryRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Summary(
        r.getString("id"),
        r.getString("project_id"),
        r.getString("name"),
        r.getInt("item_count"),
        r.getString("created_time"));
  }

  /** 编码只用于稳定请求指纹；错误在服务端终止，不泄漏输入正文。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode manifest request", e);
    }
  }

  /** 原始字节指纹使后续对账能确认输入身份；它本身不证明语义相同。 */
  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** 请求边界统一返回400；不存在和幂等冲突分别由对应分支返回404/409。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
