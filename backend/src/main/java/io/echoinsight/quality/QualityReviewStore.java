/* 文件职责：在项目事务内冻结无预测样本，追加人工草稿/提交，并保留每版输入与意见哈希。 */
package io.echoinsight.quality;

import static io.echoinsight.quality.QualityReviewProtocol.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.*;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.ModelContract;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 不派工、不调用模型；本机署名与隐藏预测的页面行为不是团队认证或独立盲标证明。 */
@Service
public class QualityReviewStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ProjectStore projects;
  private final AnalysisRequestStore requests;
  private final AnalysisScopeStore scopes;
  private final ImportMapper imports;
  private final JobStore jobs;
  private final ThreadContextSelector selector;

  /** 复用原项目、请求、不可变资料和执行范围，避免创建第二套研究任务。 */
  public QualityReviewStore(
      JdbcTemplate db,
      ObjectMapper json,
      ProjectStore projects,
      AnalysisRequestStore requests,
      AnalysisScopeStore scopes,
      ImportMapper imports,
      JobStore jobs,
      ThreadContextSelector selector) {
    this.db = db;
    this.json = json;
    this.projects = projects;
    this.requests = requests;
    this.scopes = scopes;
    this.imports = imports;
    this.jobs = jobs;
    this.selector = selector;
  }

  /** 创建结果区分新建和网络重发，HTTP沿用201/200约定。 */
  public record Created<T>(T value, boolean created) {}

  /** 输入只带人工阅读依据，不能包含缓存响应、最终报告或旧追问摘要。 */
  public record View(String id, String sha256, Input input, String createdAt) {}

  /** 列表仅含摘要，不把所有正文与模型输出放进首屏。 */
  public record Summary(
      String id,
      String requestId,
      String instruction,
      String title,
      int targetCount,
      String createdAt) {}

  /** 保存的人工快照，引用输入哈希；从未将任何字段当作已裁决金标。 */
  public record Saved(
      String id,
      String inputId,
      String inputSha256,
      RecordBody body,
      String sha256,
      String createdAt) {}

  /** 意见列表不包含判断正文，需明确选定署名与记录才读取。 */
  public record RecordSummary(
      String id, String reviewer, String kind, int labeledTargets, String createdAt) {}

  /** 1、锁项目并处理幂等；2、核原任务资格；3、从真实输入抽样；4、冻结原文与版本。 */
  @Transactional
  public Created<View> create(String projectId, String key, Create body) {
    key(key);
    if (body == null || body.requestId() == null) throw bad("请选择原分析请求");
    projects.lock(projectId);
    String fingerprint = hash(encode(body));
    var old =
        db.queryForList(
            "SELECT id,request_fingerprint FROM quality_review_input WHERE project_id=? AND"
                + " request_key=?",
            projectId,
            key);
    if (!old.isEmpty()) {
      if (!fingerprint.equals(old.getFirst().get("request_fingerprint")))
        throw conflict("请求键已经用于其他人工核对输入");
      return new Created<>(get(projectId, (String) old.getFirst().get("id")), false);
    }
    var request = qualified(projectId, body.requestId());
    var source = imports.snapshot(request.snapshotId());
    ImportDocument doc = decode(source.documentJson(), ImportDocument.class);
    var context = requests.context(projectId, request.id());
    if (context.selection() != null && context.selection().history() != null)
      throw bad("追问包含历史生成内容，请从原始分析请求准备无预测核对输入");
    var plan = jobs.plan(request.jobId());
    var spec = requests.specification(projectId, request.id());
    var scope = requests.scope(projectId, request.id());
    Set<String> eligible = new LinkedHashSet<>();
    if (scope != null) scope.chunks().forEach(c -> eligible.addAll(c.targetIds()));
    else
      selector
          .select(doc, spec == null ? 120 : 12, spec == null ? 80000 : 12000)
          .comments()
          .forEach(c -> eligible.add(c.id()));
    var comments = sample(doc, eligible, body.count(), body.seed());
    var targets = comments.stream().filter(Comment::target).map(Comment::id).toList();
    if (context.selection() != null) {
      Set<String> commentIds = new HashSet<>();
      comments.forEach(c -> commentIds.add(c.id()));
      if (context.selection().sources().stream().anyMatch(s -> commentIds.contains(s.id())))
        throw bad("核对原文与背景身份冲突，请选择无冲突的原始分析请求");
    }
    Input input =
        new Input(
            VERSION,
            projectId,
            request.id(),
            request.jobId(),
            request.snapshotId(),
            hash(source.documentJson()),
            hash(ModelContract.encode(plan)),
            request.instruction(),
            doc.content().title(),
            doc.content().sourceType(),
            doc.content().sourceContentId(),
            spec == null ? "DISCUSSION" : "STANCE",
            spec == null ? null : spec.proposition(),
            body.seed(),
            body.count(),
            eligible.size(),
            targets,
            comments,
            context.selection() == null ? List.of() : context.selection().sources(),
            List.of(
                "抽样基于固定标记与来源ID，不按模型引用挑选；没有读取或返回模型预测。",
                "只判断所列目标；祖先及背景供理解，不重复计为标注目标。",
                "含祖先最多120条/80000正文字符；超界整目标不选，不截断。实际样本可少于请求数，固定标记抽样不是总体代表性证明。",
                "样本不代表整个平台；来源关联与评审身份未认证，人工初审不是独立裁决金标准。",
                "如之前已看过报告，请如实选择已看过或不确定；本机页面隐藏不能证明盲标独立性。"));
    String id = UUID.randomUUID().toString(), encoded = encode(input);
    db.update(
        "INSERT INTO"
            + " quality_review_input(id,project_id,request_id,request_key,request_fingerprint,input_json,input_sha256)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        projectId,
        request.id(),
        key,
        fingerprint,
        encoded,
        hash(encoded));
    return new Created<>(get(projectId, id), true);
  }

  /** 读取时验证冻结正文哈希和项目/原任务身份，不追随之后修改的配置或资料。 */
  @Transactional(readOnly = true)
  public View get(String projectId, String inputId) {
    projects.get(projectId);
    var rows =
        db.queryForList(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " quality_review_input WHERE id=? AND project_id=?",
            inputId, projectId);
    if (rows.isEmpty()) throw missing();
    var row = rows.getFirst();
    String raw = (String) row.get("input_json"), sha = (String) row.get("input_sha256");
    if (!hash(raw).equals(sha)) throw new IllegalStateException("Quality input hash mismatch");
    Input input = decode(raw, Input.class);
    if (!VERSION.equals(input.version())
        || !projectId.equals(input.projectId())
        || !row.get("request_id").equals(input.requestId()))
      throw new IllegalStateException("Quality input identity mismatch");
    var request = qualified(projectId, input.requestId());
    if (!request.snapshotId().equals(input.snapshotId()) || !request.jobId().equals(input.jobId()))
      throw new IllegalStateException("Quality request identity mismatch");
    return new View(inputId, sha, input, (String) row.get("created_time"));
  }

  /** 按项目与服务端执行范围分页，摘要不含模型输出或人工判断。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String projectId, int page, int size) {
    projects.get(projectId);
    pages(page, size);
    String where =
        " FROM quality_review_input q JOIN analysis_request r ON r.id=q.request_id JOIN"
            + " analysis_job j ON j.id=r.job_id WHERE q.project_id=? AND j.execution_scope=?";
    long total =
        db.queryForObject(
            "SELECT COUNT(*)" + where, Long.class, projectId, scopes.executionScope());
    var ids =
        db.queryForList(
            "SELECT q.id" + where + " ORDER BY q.created_at DESC,q.id DESC LIMIT ? OFFSET ?",
            String.class,
            projectId,
            scopes.executionScope(),
            size,
            (page - 1) * size);
    var items =
        ids.stream()
            .map(
                id -> {
                  var v = get(projectId, id);
                  return new Summary(
                      id,
                      v.input().requestId(),
                      v.input().instruction(),
                      v.input().title(),
                      v.input().targetIds().size(),
                      v.createdAt());
                })
            .toList();
    return new ProjectStore.Page<>(items, total, page, size, (total + size - 1) / size);
  }

  /** 草稿和提交均只追加；先核同键重发，再检查previousId，防止多标签页丢失进度。 */
  @Transactional
  public Created<Saved> save(String projectId, String inputId, String key, RecordBody body) {
    key(key);
    projects.lock(projectId);
    var input = get(projectId, inputId);
    RecordBody clean = validate(input.input(), body);
    String raw = encode(clean), fingerprint = hash(input.sha256() + "\n" + raw);
    var old =
        db.queryForList(
            "SELECT id,fingerprint FROM quality_review_record WHERE input_id=? AND request_key=?",
            inputId,
            key);
    if (!old.isEmpty()) {
      if (!fingerprint.equals(old.getFirst().get("fingerprint"))) throw conflict("请求键已经用于其他人工核对意见");
      return new Created<>(record(projectId, inputId, (String) old.getFirst().get("id")), false);
    }
    var latest =
        db.queryForList(
            "SELECT id FROM quality_review_record WHERE input_id=? AND BINARY reviewer=BINARY ?"
                + " ORDER BY created_at DESC,id DESC LIMIT 1",
            String.class,
            inputId,
            clean.reviewer());
    String previous = latest.isEmpty() ? null : latest.getFirst();
    if (!Objects.equals(previous, clean.previousId())) throw conflict("核对草稿已有更新，请先载入该署名的最新记录");
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " quality_review_record(id,input_id,request_key,reviewer,record_kind,previous_id,record_json,fingerprint)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        inputId,
        key,
        clean.reviewer(),
        clean.kind(),
        previous,
        raw,
        fingerprint);
    return new Created<>(record(projectId, inputId, id), true);
  }

  /** 明确读取某一版人工快照；不以最新编辑覆盖用于对照的已提交版本。 */
  @Transactional(readOnly = true)
  public Saved record(String projectId, String inputId, String recordId) {
    var input = get(projectId, inputId);
    var rows =
        db.queryForList(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " quality_review_record WHERE id=? AND input_id=?",
            recordId, inputId);
    if (rows.isEmpty()) throw missing();
    var row = rows.getFirst();
    String raw = (String) row.get("record_json");
    if (!hash(input.sha256() + "\n" + raw).equals(row.get("fingerprint")))
      throw new IllegalStateException("Quality record hash mismatch");
    var body = validate(input.input(), decode(raw, RecordBody.class));
    if (!body.reviewer().equals(row.get("reviewer"))
        || !body.kind().equals(row.get("record_kind"))
        || !Objects.equals(body.previousId(), row.get("previous_id")))
      throw new IllegalStateException("Quality record identity mismatch");
    return new Saved(
        recordId,
        inputId,
        input.sha256(),
        body,
        (String) row.get("fingerprint"),
        (String) row.get("created_time"));
  }

  /** 只列所选署名历史的元数据；不自动带出他人标签影响当前判断。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<RecordSummary> records(
      String projectId, String inputId, String reviewer, int page, int size) {
    get(projectId, inputId);
    pages(page, size);
    if (reviewer == null || reviewer.isBlank() || reviewer.length() > 80)
      throw bad("请填写1～80字署名后读取自己的核对记录");
    String reader = reviewer.strip();
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM quality_review_record WHERE input_id=? AND BINARY reviewer=BINARY"
                + " ?",
            Long.class,
            inputId,
            reader);
    var ids =
        db.queryForList(
            "SELECT id FROM quality_review_record WHERE input_id=? AND BINARY reviewer=BINARY ?"
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            String.class,
            inputId,
            reader,
            size,
            (page - 1) * size);
    var items =
        ids.stream()
            .map(
                id -> {
                  var r = record(projectId, inputId, id);
                  return new RecordSummary(
                      id,
                      r.body().reviewer(),
                      r.body().kind(),
                      r.body().labels().size(),
                      r.createdAt());
                })
            .toList();
    return new ProjectStore.Page<>(items, total, page, size, (total + size - 1) / size);
  }

  /** 项目与execution-scope分别核对；均不是团队账号授权的替代。 */
  private AnalysisRequestStore.RequestRow qualified(String projectId, String requestId) {
    var request = requests.get(projectId, requestId);
    Integer count =
        db.queryForObject(
            "SELECT COUNT(*) FROM analysis_job WHERE id=? AND execution_scope=?",
            Integer.class,
            request.jobId(),
            scopes.executionScope());
    if (count == null || count != 1) throw missing();
    return request;
  }

  /** 固定分页上限，避免一次读出无限意见历史。 */
  private void pages(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 20) throw bad("核对页码1～10000，每页1～20条");
  }

  /** 请求键只用于幂等，不作为凭据或评审人身份。 */
  private void key(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效的人工核对请求键");
  }

  /** 序列化只使用本项目统一Jackson，不产生带正文的异常。 */
  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Quality JSON encoding failed", e);
    }
  }

  /** 冻结对象损坏时失败关闭，不自动重建覆盖历史。 */
  private <T> T decode(String raw, Class<T> type) {
    try {
      return json.readValue(raw, type);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Quality JSON decoding failed", e);
    }
  }

  /** 哈希用于内容完整性检查，不声称能防止数据库管理员同时改写正文与哈希。 */
  private String hash(String value) {
    return ReferenceMaterialStore.hash(value);
  }

  private ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "人工核对输入或记录不存在");
  }

  private ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }
}
