/* 文件职责：以追加方式保存本机人工核验，校验快照、并发版本和请求幂等。 */
package io.echoinsight.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.orchestration.JobStore;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 当前身份固定为 local，既有报告及先前核验均不更新或删除。 */
@Service
public class ReviewService {
  private final JdbcTemplate db;
  private final JobStore jobs;
  private final ImportMapper imports;
  private final ObjectMapper json;
  private final FindingCatalog catalog;
  private final TransactionTemplate write;
  private final TransactionTemplate read;
  private static final String COLUMNS =
      "id,report_id,finding_id,revision,verdict,reason,corrected_evidence_json,"
          + "DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ') created_time";

  /** 为写操作使用已提交读，并在报告行锁下串行校验修订号。 */
  public ReviewService(
      JdbcTemplate db,
      JobStore jobs,
      ImportMapper imports,
      ObjectMapper json,
      FindingCatalog catalog,
      PlatformTransactionManager manager) {
    this.db = db;
    this.jobs = jobs;
    this.imports = imports;
    this.json = json;
    this.catalog = catalog;
    write = new TransactionTemplate(manager);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(manager);
    read.setReadOnly(true);
  }

  /** 表达人工判断，不把部分支持或不确定算成充分支持。 */
  public enum Verdict {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNCERTAIN
  }

  /** expectedRevision 为当前已知版本，零表示尚未核验。 */
  public record Request(
      @NotNull Verdict verdict,
      @NotBlank @Size(max = 1000) String reason,
      @NotNull @Size(max = 10) List<@NotBlank @Size(max = 128) String> correctedEvidenceIds,
      @NotNull @Min(0) @Max(1000000000) Integer expectedRevision) {}

  /** 每次提交都是不可变事件，correction 列表为空表示未提出替代证据。 */
  public record Review(
      String id,
      String reportId,
      String findingId,
      int revision,
      String verdict,
      String reason,
      List<String> correctedEvidenceIds,
      String reviewerId,
      String createdAt) {}

  /** 原文来自报告绑定快照，而不是后续导入的最新数据。 */
  public record Quote(String commentId, String text) {}

  /** history 按最新在前排列，reviewCount 表示全部修订数量。 */
  public record FindingView(
      String findingId,
      String claim,
      List<String> evidenceIds,
      List<Quote> evidence,
      List<Quote> latestCorrectedEvidence,
      int reviewCount,
      List<Review> history) {}

  /** 一个报告的核验目录，明确当前仅支持本机身份。 */
  public record View(
      String reportId,
      String snapshotId,
      String reviewerId,
      int historyLimit,
      List<FindingView> findings) {}

  /** replayed 为真时返回原事件，不产生新修订。 */
  public record Saved(Review review, boolean replayed) {}

  /** 将数据库行映射为核验事件，时间字段由 SQL 明确格式为 UTC。 */
  private Review row(ResultSet r, int index) throws SQLException {
    return new Review(
        r.getString("id"),
        r.getString("report_id"),
        r.getString("finding_id"),
        r.getInt("revision"),
        r.getString("verdict"),
        r.getString("reason"),
        decodeIds(r.getString("corrected_evidence_json")),
        "local",
        r.getString("created_time"));
  }

  /** 数据库中非法的内部 JSON 属于服务错误，不能静默丢弃审核引用。 */
  private List<String> decodeIds(String value) {
    try {
      return json.readValue(value, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored review", e);
    }
  }

  /** 读取内部不可变 JSON；不在此修改已经发布的报告。 */
  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored document", e);
    }
  }

  /** 建立快照原文索引，供引用校验及核验页面展开。 */
  private Map<String, String> comments(String snapshotId) {
    Map<String, String> result = new HashMap<>();
    parse(imports.snapshot(snapshotId).documentJson())
        .path("comments")
        .forEach(c -> result.put(c.path("id").asText(), c.path("text").asText()));
    return result;
  }

  /** 按调用者指定的顺序展开已经确认存在的原文。 */
  private List<Quote> quotes(List<String> ids, Map<String, String> comments) {
    return ids.stream().map(id -> new Quote(id, comments.get(id))).toList();
  }

  /** 1、读取旧报告与快照；2、生成稳定目录；3、在只读事务中读取有限历史。 */
  public View list(String reportId, int limit) {
    return read.execute(
        status -> {
          var report = jobs.report(reportId);
          var comments = comments(report.snapshotId());
          List<FindingView> findings = new ArrayList<>();
          for (var finding : catalog.list(reportId, parse(report.json()))) {
            var history =
                db.query(
                    "SELECT "
                        + COLUMNS
                        + " FROM report_review WHERE report_id=? AND finding_id=?"
                        + " AND reviewer_id='local' ORDER BY revision DESC LIMIT ?",
                    this::row,
                    reportId,
                    finding.findingId(),
                    limit);
            var latest =
                history.isEmpty() ? List.<String>of() : history.getFirst().correctedEvidenceIds();
            findings.add(
                new FindingView(
                    finding.findingId(),
                    finding.claim(),
                    finding.evidenceIds(),
                    quotes(finding.evidenceIds(), comments),
                    quotes(latest, comments),
                    history.isEmpty() ? 0 : history.getFirst().revision(),
                    history));
          }
          return new View(reportId, report.snapshotId(), "local", limit, List.copyOf(findings));
        });
  }

  /** 1、规范化输入；2、锁定报告；3、处理重放与冲突；4、校验版本和引用后追加事件。 */
  public Saved save(String reportId, String findingId, String key, Request request) {
    if (new HashSet<>(request.correctedEvidenceIds()).size()
        != request.correctedEvidenceIds().size())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "替代证据 ID 不得重复");
    var ids = request.correctedEvidenceIds().stream().sorted().toList();
    String reason = request.reason().strip();
    if (reason.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写核验理由");
    var payload =
        json.createArrayNode()
            .add(reportId)
            .add(findingId)
            .add(request.verdict().name())
            .add(reason)
            .add(request.expectedRevision());
    var evidenceArray = payload.addArray();
    ids.forEach(evidenceArray::add);
    String hash = FindingCatalog.hash(payload.toString());
    return write.execute(
        status -> {
          // 报告行只用于加锁，不执行 UPDATE，不改变原始报告字节。
          if (db.queryForList(
                  "SELECT id FROM analysis_report WHERE id=? FOR UPDATE", String.class, reportId)
              .isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在");
          var report = jobs.report(reportId);
          if (catalog.list(reportId, parse(report.json())).stream()
              .noneMatch(f -> f.findingId().equals(findingId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "结论不存在");
          var repeated =
              db.query(
                  "SELECT " + COLUMNS + " FROM report_review WHERE request_key=?", this::row, key);
          if (!repeated.isEmpty()) return replay(key, hash, repeated.getFirst());
          int current =
              db.queryForObject(
                  "SELECT COALESCE(MAX(revision),0) FROM report_review"
                      + " WHERE report_id=? AND finding_id=? AND reviewer_id='local'",
                  Integer.class,
                  reportId,
                  findingId);
          if (current != request.expectedRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "核验记录已更新，请刷新后再提交");
          if (!comments(report.snapshotId()).keySet().containsAll(ids))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "替代证据必须属于报告绑定快照");
          String candidate = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " report_review(id,report_id,finding_id,reviewer_id,revision,verdict,reason,"
                  + "corrected_evidence_json,request_key,payload_hash,created_at)"
                  + " VALUES(?,?,?,'local',?,?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE"
                  + " id=id",
              candidate,
              reportId,
              findingId,
              current + 1,
              request.verdict().name(),
              reason,
              evidenceArray.toString(),
              key,
              hash);
          var saved =
              db.queryForObject(
                  "SELECT " + COLUMNS + " FROM report_review WHERE request_key=?", this::row, key);
          return candidate.equals(saved.id()) ? new Saved(saved, false) : replay(key, hash, saved);
        });
  }

  /** 比较整个逻辑请求，旧版本的同键重放仍返回原结果；异载荷返回冲突。 */
  private Saved replay(String key, String hash, Review review) {
    var stored =
        db.queryForObject(
            "SELECT payload_hash FROM report_review WHERE request_key=?", String.class, key);
    if (!hash.equals(stored))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求键不能用于不同核验内容");
    return new Saved(review, true);
  }
}
