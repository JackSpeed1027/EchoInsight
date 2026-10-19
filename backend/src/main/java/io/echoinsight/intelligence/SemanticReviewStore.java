/* 文件职责：记录可追溯的语义核验意见；核对引用身份，但不以程序替代人工判断证据是否支持结论。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
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

/** 核验是追加意见，原产物不变；本机署名不等于登录认证，更不代表独立双盲标注。 */
@Service
public class SemanticReviewStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final AnalysisRequestStore requests;
  private final ObjectMapper json;

  /** 统一复用项目和请求资格，避免核验接口绕过原有工作空间边界。 */
  public SemanticReviewStore(
      JdbcTemplate db, ProjectStore projects, AnalysisRequestStore requests, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.requests = requests;
    this.json = json;
  }

  /** targetKind取ISSUE/CLAIM/RELATION/ASSESSMENT；关系使用从0开始的产物数组序号作为targetId。 */
  public record Input(
      String targetKind,
      String targetId,
      String verdict,
      String reviewer,
      String rationale,
      List<String> evidenceIds) {}

  /** 每条记录绑定不可变产物；createdAt仅用于历史排序，不参加内容指纹。 */
  public record Review(
      String id, String requestId, String artifactId, Input input, String createdAt) {}

  /** 1、检查署名和理由；2、核对目标及原文引用；3、锁项目处理幂等；4、只追加新意见。 */
  @Transactional
  public Review create(String projectId, String requestId, String key, Input input) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}") || input == null)
      throw bad("需要有效核验请求键");
    if (input.reviewer() == null
        || input.reviewer().isBlank()
        || input.reviewer().length() > 80
        || input.rationale() == null
        || input.rationale().isBlank()
        || input.rationale().length() > 2000
        || !Set.of("SUPPORTED", "UNSUPPORTED", "UNCERTAIN")
            .contains(Objects.toString(input.verdict(), ""))) throw bad("署名、理由和核验判断不能为空或超限");
    // 原文读取也会建立一致性快照，必须在项目串行化边界之后进行。
    projects.lock(projectId);
    JsonNode artifact = requests.artifact(projectId, requestId);
    String field =
        switch (Objects.toString(input.targetKind(), "")) {
          case "ISSUE" -> "issues";
          case "CLAIM" -> "claims";
          case "RELATION" -> "relations";
          case "ASSESSMENT" -> "assessments";
          default -> throw bad("不支持的核验目标");
        };
    JsonNode target = null;
    for (int n = 0; n < artifact.path(field).size(); n++) {
      var row = artifact.path(field).get(n);
      String identity =
          field.equals("relations")
              ? Integer.toString(n)
              : row.path(field.equals("assessments") ? "issueId" : "id").asText();
      if (identity.equals(input.targetId())) target = row;
    }
    if (target == null) throw bad("核验目标不属于该产物");
    if (input.evidenceIds() == null
        || input.evidenceIds().size() > 20
        || input.evidenceIds().stream().anyMatch(Objects::isNull)
        || new HashSet<>(input.evidenceIds()).size() != input.evidenceIds().size())
      throw bad("引用为不重复的0～20条当前产物证据");
    Set<String> evidence = new HashSet<>();
    artifact.path("evidence").forEach(e -> evidence.add(e.path("id").asText()));
    if (!evidence.containsAll(input.evidenceIds())) throw bad("引用不存在于当前产物");
    Input clean =
        new Input(
            input.targetKind(),
            input.targetId(),
            input.verdict(),
            input.reviewer().strip(),
            input.rationale().strip(),
            input.evidenceIds().stream().sorted().toList());
    String encoded = encode(clean),
        fingerprint =
            hash(requestId + "\n" + artifact.path("artifactId").asText() + "\n" + encoded);
    var old =
        db.queryForList(
            "SELECT id,fingerprint FROM discussion_semantic_review WHERE project_id=? AND"
                + " request_key=?",
            projectId,
            key);
    if (!old.isEmpty()) {
      if (!fingerprint.equals(old.getFirst().get("fingerprint")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已用于另一条核验意见");
      return get((String) old.getFirst().get("id"));
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " discussion_semantic_review(id,project_id,request_id,artifact_id,request_key,fingerprint,review_json)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        projectId,
        requestId,
        artifact.path("artifactId").asText(),
        key,
        fingerprint,
        encoded);
    return get(id);
  }

  /** 按明确请求分页读历史，不跨作品汇总支持率，不把重复署名当作独立评审。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Review> list(String projectId, String requestId, int page, int size) {
    requests.get(projectId, requestId);
    if (page < 1 || page > 10000 || size < 1 || size > 50) throw bad("每页1～50条核验记录");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM discussion_semantic_review WHERE request_id=? AND project_id=?",
            Long.class,
            requestId,
            projectId);
    var ids =
        db.queryForList(
            "SELECT id FROM discussion_semantic_review WHERE request_id=? AND project_id=? ORDER BY"
                + " created_at DESC,id DESC LIMIT ? OFFSET ?",
            String.class,
            requestId,
            projectId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        ids.stream().map(this::get).toList(), total, page, size, (total + size - 1) / size);
  }

  /** 内部身份已由调用者验证，不作为可直接访问的跨项目接口。 */
  private Review get(String id) {
    return db.queryForObject(
        "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
            + " discussion_semantic_review WHERE id=?",
        (r, n) -> {
          try {
            return new Review(
                r.getString("id"),
                r.getString("request_id"),
                r.getString("artifact_id"),
                json.readValue(r.getString("review_json"), Input.class),
                r.getString("created_time"));
          } catch (Exception e) {
            throw new IllegalStateException("Invalid stored review", e);
          }
        },
        id);
  }

  /** 固定结构JSON用于幂等指纹，编码失败表示服务端对象损坏。 */
  private String encode(Input input) {
    try {
      return json.writeValueAsString(input);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** SHA256只证明内容身份，不是语义质量分数。 */
  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 输入不符合业务范围时返回400，保留原数据以便用户修正后重新提交。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
