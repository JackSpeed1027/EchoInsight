/* 文件职责：从报告旧快照与已保存分类读取逐条明细，验证一致性后进行有界筛选和分页。 */
package io.echoinsight.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.echoinsight.analysis.FixtureClassifier.Label;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.review.FindingCatalog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 逐条查看服务于质量抽查：读取历史事实，不按当前供应商、核验意见或 Mock 重新计算标签。 当前输入上限为 5000 条；筛选与分页在有界内存完成，不能把它当成大规模全文检索。 */
@Service
public class ReportCommentQuery {
  private static final List<String> LABELS =
      List.of("ALL", "POSITIVE", "NEGATIVE", "NEUTRAL", "UNKNOWN");
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ObjectReader storedTree;
  private final FindingCatalog catalog;

  /** 注入单次读取及固定身份工具；本类不依赖模型网关或任务执行器。 */
  public ReportCommentQuery(JdbcTemplate db, ObjectMapper json, FindingCatalog catalog) {
    this.db = db;
    this.json = json;
    // 只为当前读取创建严格 Reader，不改全局 Mapper；拒绝重复键和尾随 JSON。
    this.storedTree =
        json.reader()
            .with(
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    this.catalog = catalog;
  }

  /** 单次 SQL 的内部材料，不通过 HTTP 返回整份快照或完整步骤 JSON。 */
  private record Stored(
      String reportId,
      String snapshotId,
      String jobId,
      String reportJson,
      String snapshotJson,
      String classificationsJson,
      String classifyStatus,
      String jobStatus) {}

  /** 只展开同快照直接父评论；不声称取得完整对话或视频章节。 */
  public record Parent(String commentId, String sourceCommentId, String text) {}

  /** 原始分类加原文；findingIds 关联结论核验目录，空数组表示未被选作报告引用。 */
  public record CommentView(
      String commentId,
      String sourceCommentId,
      String text,
      String occurredAt,
      Label label,
      Parent parent,
      List<String> findingIds) {}

  /** 全快照原始标签数，始终不受筛选或分页影响，不代表人工认定正确的数量。 */
  public record LabelCounts(int positive, int negative, int neutral, int unknown) {}

  /** total 是筛选匹配数，snapshotCommentCount 是全快照数，两者不能混成统计分母。 */
  public record Page(
      String reportId,
      String snapshotId,
      String jobId,
      String sourceMode,
      int snapshotCommentCount,
      LabelCounts labelCounts,
      String label,
      String query,
      int total,
      int page,
      int size,
      int totalPages,
      List<CommentView> items) {}

  /**
   * 读取逐条原文和标签。1、先验参数；2、一次 SQL 读取；3、核对覆盖与计数；4、按原顺序筛选分页。
   *
   * @param reportId 已发布报告 ID，误传任务 ID 或报告不存在时返回 404。
   * @param page 从 1 开始，最多 10000；越界页返回空 items 并保留真实 total。
   * @param size 每页 1～100，限制响应体；内部仍受单快照最多 5000 条约束。
   * @param label ALL 或原始四类标签，区分大小写，UNKNOWN 不是失败状态。
   * @param query 当前评论正文的字面关键词，最多 100 个 Unicode 码点，不搜索父评论。
   * @return 已保存事实的只读分页，不启动任务、不添加核验，也不写任何业务记录。
   */
  public Page list(String reportId, int page, int size, String label, String query) {
    // 1、先检查范围，阻止非法参数进入数据库读取。
    String keyword = validate(page, size, label, query);
    // 2、一次 SQL 取得报告、快照和分类，避免跨多次查询拼接不同状态。
    Stored stored = load(reportId);
    if (!"SUCCEEDED".equals(stored.classifyStatus()) || !"SUCCESS".equals(stored.jobStatus())) {
      throw invalid();
    }
    // 3、核对身份、逐条覆盖、父评论和计数；材料不完整就拒绝展示。
    JsonNode report = parseReport(stored);
    ImportDocument input = parseInput(stored.snapshotJson());
    Map<String, ImportDocument.Comment> comments = indexComments(input);
    Map<String, Label> labels = indexLabels(stored.classificationsJson(), comments);
    LabelCounts counts = count(labels);
    validateCounts(report, counts, comments.size());
    Map<String, List<String>> references = references(stored.reportId(), report, comments);

    // 4、按保存的规范化快照顺序分页（导入阶段按 sourceCommentId 排序），不按分类响应顺序。
    String needle = keyword.toLowerCase(Locale.ROOT);
    List<ImportDocument.Comment> matches =
        input.comments().stream()
            .filter(comment -> "ALL".equals(label) || labels.get(comment.id()).name().equals(label))
            .filter(comment -> comment.text().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    int offset = Math.min((page - 1) * size, matches.size());
    var items =
        matches.subList(offset, Math.min(offset + size, matches.size())).stream()
            .map(
                comment -> {
                  var parent = comment.parentId() == null ? null : comments.get(comment.parentId());
                  return new CommentView(
                      comment.id(),
                      comment.sourceCommentId(),
                      comment.text(),
                      comment.occurredAt().toString(),
                      labels.get(comment.id()),
                      parent == null
                          ? null
                          : new Parent(parent.id(), parent.sourceCommentId(), parent.text()),
                      List.copyOf(references.getOrDefault(comment.id(), List.of())));
                })
            .toList();
    return new Page(
        stored.reportId(),
        stored.snapshotId(),
        stored.jobId(),
        report.path("provenance").path("mode").asText(),
        comments.size(),
        counts,
        label,
        keyword,
        matches.size(),
        page,
        size,
        (matches.size() + size - 1) / size,
        items);
  }

  /** 参数在访问数据库前检查；关键词只去首尾空白，不作为 SQL、通配符或正则表达式执行。 */
  private String validate(int page, int size, String label, String query) {
    if (page < 1
        || page > 10000
        || size < 1
        || size > 100
        || label == null
        || !LABELS.contains(label)
        || query == null
        || query.codePointCount(0, query.length()) > 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "page 为 1～10000，size 为 1～100；label 必须为规定标签，q 最多 100 个字符");
    }
    return query.strip();
  }

  /**
   * 单个参数化 SELECT 保证同一读取视图；只使用报告 ID 作为参数，关键词不会进入 SQL。 LEFT JOIN 让“报告存在但产物丢失”与“报告不存在”能分别处理，不能静默漏评论。
   */
  private Stored load(String reportId) {
    return db
        .query(
            "SELECT r.id,r.snapshot_id,r.job_id,r.report_json,d.document_json,s.result_json,"
                + "s.status classify_status,j.status job_status FROM analysis_report r "
                + "LEFT JOIN data_snapshot d ON d.id=r.snapshot_id "
                + "LEFT JOIN job_step s ON s.job_id=r.job_id AND s.step_key='CLASSIFY' "
                + "LEFT JOIN analysis_job j ON j.id=r.job_id WHERE r.id=?",
            (row, index) ->
                new Stored(
                    row.getString("id"),
                    row.getString("snapshot_id"),
                    row.getString("job_id"),
                    row.getString("report_json"),
                    row.getString("document_json"),
                    row.getString("result_json"),
                    row.getString("classify_status"),
                    row.getString("job_status")),
            reportId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在"));
  }

  /** 报告 JSON 的身份必须与数据库外键一致，不从正文中接受另一份快照。 */
  private JsonNode parseReport(Stored stored) {
    if (stored.reportJson() == null) throw invalid();
    try {
      JsonNode report = json.readTree(stored.reportJson());
      if (report == null
          || !stored.reportId().equals(report.path("reportId").asText())
          || !stored.snapshotId().equals(report.path("snapshotId").asText())
          || !report.path("findings").isArray()) throw invalid();
      return report;
    } catch (JsonProcessingException ignored) {
      throw invalid();
    }
  }

  /** 解析保存的旧快照，任何错误都使用安全异常，不回显评论或完整 JSON。 */
  private ImportDocument parseInput(String value) {
    if (value == null) throw invalid();
    try {
      return json.readValue(value, ImportDocument.class);
    } catch (JsonProcessingException ignored) {
      throw invalid();
    }
  }

  /** 先建立完整索引再检查父评论；响应只展开当前页需要的直接上下文。 */
  private Map<String, ImportDocument.Comment> indexComments(ImportDocument input) {
    if (input == null
        || input.comments() == null
        || input.comments().isEmpty()
        || input.comments().size() > 5000) throw invalid();
    Map<String, ImportDocument.Comment> comments = new LinkedHashMap<>();
    for (var comment : input.comments()) {
      if (comment == null
          || comment.id() == null
          || comment.sourceCommentId() == null
          || comment.text() == null
          || comment.occurredAt() == null
          || comments.put(comment.id(), comment) != null) throw invalid();
    }
    for (var comment : comments.values()) {
      if (comment.parentId() != null && !comments.containsKey(comment.parentId())) throw invalid();
    }
    return comments;
  }

  /** 逐条检查分类唯一性、标签和全快照覆盖，拒绝用 UNKNOWN 补填丢失的分类产物。 */
  private Map<String, Label> indexLabels(
      String value, Map<String, ImportDocument.Comment> comments) {
    if (value == null) throw invalid();
    JsonNode classes;
    try {
      classes = storedTree.readTree(value);
    } catch (JsonProcessingException ignored) {
      throw invalid();
    }
    if (classes == null || !classes.isArray() || classes.size() != comments.size()) throw invalid();
    Map<String, Label> labels = new HashMap<>();
    for (var classification : classes) {
      if (!classification.isObject()
          || !classification.path("commentId").isTextual()
          || !classification.path("label").isTextual()) throw invalid();
      String id = classification.path("commentId").textValue();
      Label label;
      try {
        label = Label.valueOf(classification.path("label").textValue());
      } catch (IllegalArgumentException ignored) {
        throw invalid();
      }
      if (!comments.containsKey(id) || labels.put(id, label) != null) throw invalid();
    }
    return labels;
  }

  /** 只统计原始分类一次；筛选不能改变全快照标签计数或报告分母。 */
  private LabelCounts count(Map<String, Label> labels) {
    var counts = new EnumMap<Label, Integer>(Label.class);
    labels.values().forEach(label -> counts.merge(label, 1, Integer::sum));
    return new LabelCounts(
        counts.getOrDefault(Label.POSITIVE, 0),
        counts.getOrDefault(Label.NEGATIVE, 0),
        counts.getOrDefault(Label.NEUTRAL, 0),
        counts.getOrDefault(Label.UNKNOWN, 0));
  }

  /** 原报告与已保存分类必须相互印证；不展示看似完整但计数互相矛盾的明细。 */
  private void validateCounts(JsonNode report, LabelCounts counts, int total) {
    var metrics = report.path("metrics");
    var coverage = report.path("coverage");
    if (integer(metrics, "positive") != counts.positive()
        || integer(metrics, "negative") != counts.negative()
        || integer(metrics, "neutral") != counts.neutral()
        || integer(coverage, "unknown") != counts.unknown()
        || integer(coverage, "eligibleComments") != total
        || integer(coverage, "classifiedComments") != total - counts.unknown()) throw invalid();
  }

  /** 计数字段必须是非负整数，不能让 asInt 将缺失或错误类型静默转成 0。 */
  private int integer(JsonNode node, String field) {
    var value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) throw invalid();
    return value.asInt();
  }

  /** 复用原报告的稳定核验身份；未被引用的评论仍可查看，不把“引用”当成准确性认证。 */
  private Map<String, List<String>> references(
      String reportId, JsonNode report, Map<String, ImportDocument.Comment> comments) {
    Map<String, List<String>> result = new HashMap<>();
    for (var finding : catalog.list(reportId, report)) {
      for (String id : finding.evidenceIds()) {
        if (!comments.containsKey(id)) throw invalid();
        result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(finding.findingId());
      }
    }
    return result;
  }

  /** 安全的存储一致性错误；不自动重分析、修复历史数据或暴露私有正文。 */
  private IllegalStateException invalid() {
    return new IllegalStateException("Stored report comments are incomplete or inconsistent");
  }
}
