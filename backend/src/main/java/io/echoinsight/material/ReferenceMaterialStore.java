/* 文件职责：保存项目内不可变参考文本及其来源说明，不把背景材料混入观众评论或验证为外部事实。 */
package io.echoinsight.material;

import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 文本与标题创建后不更新；修订资料时新增一份，已接受分析仍保留原版本。 */
@Service
public class ReferenceMaterialStore {
  public static final int MAX_TEXT_CHARS = 12000;
  private final JdbcTemplate db;
  private final ProjectStore projects;

  /** 只复用项目资格及同一本机数据库，不新增文件解析器或外部网络访问。 */
  public ReferenceMaterialStore(JdbcTemplate db, ProjectStore projects) {
    this.db = db;
    this.projects = projects;
  }

  /** 列表不携带正文，来源说明仅是用户声明，不是已经核实的权威来源。 */
  public record Summary(
      String id,
      String title,
      String sourceDescription,
      int textChars,
      String bodySha256,
      String createdAt) {}

  /** 详情保留服务器实际存储的原文，作为后续冻结上下文的依据。 */
  public record Material(Summary summary, String text) {}

  /** 1、校验标题、正文和来源说明；2、锁项目核对幂等；3、追加原文和摘要；4、返回不可变版本。 */
  @Transactional
  public Material create(
      String projectId, String key, String title, String text, String sourceDescription) {
    String source = sourceDescription == null ? "" : sourceDescription.strip();
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || title == null
        || title.isBlank()
        || title.strip().length() > 120
        || text == null
        || text.isBlank()
        || text.length() > MAX_TEXT_CHARS
        || source.length() > 200
        || text.indexOf('\0') >= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题1～120字、正文1～12000字、来源说明最多200字");
    }
    projects.lock(projectId);
    String cleanTitle = title.strip();
    var old =
        db.queryForList(
            "SELECT id FROM project_reference_material WHERE project_id=? AND request_key=?",
            String.class,
            projectId,
            key);
    if (!old.isEmpty()) {
      var saved = get(projectId, old.getFirst());
      if (!cleanTitle.equals(saved.summary().title())
          || !source.equals(saved.summary().sourceDescription())
          || !text.equals(saved.text())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已用于其他参考资料");
      }
      return saved;
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " project_reference_material(id,project_id,request_key,title,source_description,body_text,body_sha256)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        projectId,
        key,
        cleanTitle,
        source,
        text,
        hash(text));
    return get(projectId, id);
  }

  /** 返回同项目原文并核对哈希；损坏不能静默变成另一份背景。 */
  @Transactional(readOnly = true)
  public Material get(String projectId, String id) {
    projects.get(projectId);
    var rows =
        db.query(
            select() + " WHERE project_id=? AND id=?",
            (r, n) -> new Material(summary(r), r.getString("body_text")),
            projectId,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "参考资料不存在");
    var material = rows.getFirst();
    if (!hash(material.text()).equals(material.summary().bodySha256()))
      throw new IllegalStateException("Reference material hash mismatch");
    return material;
  }

  /** 按页读取摘要，避免浏览列表时下载全部参考原文。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String projectId, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 50)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每页1～50份参考资料");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM project_reference_material WHERE project_id=?",
            Long.class,
            projectId);
    var items =
        db.query(
            select() + " WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (r, n) -> summary(r),
            projectId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(items, total, page, size, (total + size - 1) / size);
  }

  /** 接口textChars使用Java长度，与请求及模型输入的硬限口径一致。 */
  private Summary summary(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Summary(
        r.getString("id"),
        r.getString("title"),
        r.getString("source_description"),
        r.getString("body_text").length(),
        r.getString("body_sha256"),
        r.getString("created_time"));
  }

  /** 所有读取保持同一时间格式，正文只在服务器用于哈希核对或详情返回。 */
  private String select() {
    return "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
        + " project_reference_material";
  }

  /** 哈希证明文本身份，不给来源真实性或语义质量背书。 */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
