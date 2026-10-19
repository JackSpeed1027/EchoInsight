/* 文件职责：只读组合历史报告、绑定快照的原文和可选最新核验，生成可下载副本。 */
package io.echoinsight.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.review.ReviewService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 导出是已有业务结果的只读视图，不创建分析任务、不调用模型、不追加核验。 原报告与最新意见分开保存；原文始终来自报告快照，不能联接到后续修订。 */
@Service
public class ReportExportService {
  private final JobStore jobs;
  private final ImportMapper imports;
  private final ReviewService reviews;
  private final ObjectMapper json;
  private final ReportExportRenderer renderer;
  private final TransactionTemplate read;

  /** 为一次导出建立只读一致性事务；所有查询使用同一快照，防止不同结论混入不同时间的意见。 序列化和 Markdown 排版在事务结束后完成，不长时间占用数据库连接。 */
  public ReportExportService(
      JobStore jobs,
      ImportMapper imports,
      ReviewService reviews,
      ObjectMapper json,
      ReportExportRenderer renderer,
      PlatformTransactionManager manager) {
    this.jobs = jobs;
    this.imports = imports;
    this.reviews = reviews;
    this.json = json;
    this.renderer = renderer;
    read = new TransactionTemplate(manager);
    read.setReadOnly(true);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /** 一条原文保留快照内 ID 与来源 ID；两者不能混作核验结论 ID。 */
  public record Evidence(String commentId, String sourceCommentId, String text) {}

  /** 仅附上最新核验和总修订数；findingIndex 关联原报告数组位置，不替代稳定 findingId。 latestReview 为空表示尚未核验，不冒充全部历史。 */
  public record ReviewSummary(
      int findingIndex,
      String findingId,
      int reviewCount,
      ReviewService.Review latestReview,
      List<Evidence> latestCorrectedEvidence) {}

  /**
   * 导出封套有独立版本；report 保持原 JSON 结构，不能当作核验后的修订报告。 exportedAt 表示这份副本生成时间；reviewsIncluded 解释是否有意省略人工附录。
   */
  public record Bundle(
      String exportVersion,
      String exportedAt,
      JsonNode report,
      List<Evidence> evidence,
      boolean reviewsIncluded,
      List<ReviewSummary> reviewSummary) {}

  /** 已编码附件；文件名只由服务器固定字符串构成，不使用用户标题或原文。 */
  public record Download(String filename, String contentType, byte[] bytes) {}

  /**
   * 1、验证格式；2、短事务读取报告与附录；3、事务外渲染；4、返回 UTF-8 附件。
   *
   * @param reportId 已发布报告身份，必须来自成功任务；不存在时返回 404。
   * @param format 仅 markdown / json，其他值在查询数据库前返回 400。
   * @param includeReviews 为真时附上导出时最新意见，不覆盖原报告。
   * @return 文件名、媒体类型和附件字节；不保存副本到服务器或改动任何业务记录。
   */
  public Download export(String reportId, String format, boolean includeReviews) {
    if (!List.of("markdown", "json").contains(format)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format 仅支持 markdown 或 json");
    }
    Bundle bundle = read.execute(status -> load(reportId, includeReviews));
    if ("markdown".equals(format)) {
      return new Download(
          "echoinsight-report.md",
          "text/markdown;charset=UTF-8",
          renderer.markdown(bundle).getBytes(StandardCharsets.UTF_8));
    }
    try {
      return new Download(
          "echoinsight-report.json",
          "application/json",
          json.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
    } catch (JsonProcessingException ignored) {
      throw new IllegalStateException("Report export serialization failed");
    }
  }

  /** 在同一只读事务内固定所有材料。1、核对报告身份；2、索引旧快照；3、展开引用；4、读取最新意见。 存储中缺失原文属于数据完整性错误，不能静默导出空证据或从新快照补齐。 */
  private Bundle load(String reportId, boolean includeReviews) {
    var stored = jobs.report(reportId);
    JsonNode report = parse(stored.json());
    if (!stored.id().equals(report.path("reportId").asText())
        || !stored.snapshotId().equals(report.path("snapshotId").asText())) {
      throw new IllegalStateException("Stored report identity mismatch");
    }
    var snapshot = imports.snapshot(stored.snapshotId());
    if (snapshot == null) throw new IllegalStateException("Stored report snapshot missing");
    Map<String, Evidence> comments = new LinkedHashMap<>();
    parse(snapshot.documentJson())
        .path("comments")
        .forEach(
            comment -> {
              String id = comment.path("id").asText();
              comments.put(
                  id,
                  new Evidence(
                      id, comment.path("sourceCommentId").asText(), comment.path("text").asText()));
            });
    var references = new LinkedHashSet<String>();
    report
        .path("findings")
        .forEach(finding -> finding.path("evidenceIds").forEach(id -> references.add(id.asText())));
    List<Evidence> evidence = expand(List.copyOf(references), comments);
    List<ReviewSummary> summary = new ArrayList<>();
    if (includeReviews) {
      var view = reviews.list(reportId, 1);
      if (!stored.snapshotId().equals(view.snapshotId())) {
        throw new IllegalStateException("Review snapshot mismatch");
      }
      int findingIndex = 0;
      for (var finding : view.findings()) {
        var latest = finding.history().isEmpty() ? null : finding.history().getFirst();
        summary.add(
            new ReviewSummary(
                findingIndex++,
                finding.findingId(),
                finding.reviewCount(),
                latest,
                latest == null ? List.of() : expand(latest.correctedEvidenceIds(), comments)));
      }
    }
    return new Bundle(
        "1.0", Instant.now().toString(), report, evidence, includeReviews, List.copyOf(summary));
  }

  /** 只展开已经引用的原文，保留顺序；不输出完整快照中无关评论。 */
  private List<Evidence> expand(List<String> ids, Map<String, Evidence> comments) {
    return ids.stream()
        .map(
            id -> {
              Evidence evidence = comments.get(id);
              if (evidence == null)
                throw new IllegalStateException("Export evidence outside report snapshot");
              return evidence;
            })
        .toList();
  }

  /** 持久文档解析错误只暴露安全异常，不把原始 JSON 或评论放进错误消息。 */
  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (JsonProcessingException ignored) {
      throw new IllegalStateException("Invalid stored export document");
    }
  }
}
