/* 文件职责：提供项目内可选的无预测人工核对、追加意见、提交后对照和本机导出入口。 */
package io.echoinsight.api;

import io.echoinsight.quality.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 不创建分析任务，不调用模型；原有日常分析无需经过本控制器。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/quality-review-inputs")
public class QualityReviewController {
  private final QualityReviewStore reviews;
  private final QualityReviewComparison comparisons;

  /** 控制器只处理HTTP语义，资格、事务与意见版本由服务负责。 */
  public QualityReviewController(QualityReviewStore reviews, QualityReviewComparison comparisons) {
    this.reviews = reviews;
    this.comparisons = comparisons;
  }

  /** 只冻结原文样本，首次201、同键重发200，不读取任何预测。 */
  @PostMapping
  public ResponseEntity<QualityReviewStore.View> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody QualityReviewProtocol.Create body) {
    var result = reviews.create(projectId, key, body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.value());
  }

  /** 输入列表只带摘要，保持本机项目及执行范围。 */
  @GetMapping
  public io.echoinsight.project.ProjectStore.Page<QualityReviewStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return reviews.list(projectId, page, size);
  }

  /** 正文没有预测/人工意见，不会因某人已提交而改变这个响应。 */
  @GetMapping("/{inputId}")
  public QualityReviewStore.View get(@PathVariable String projectId, @PathVariable String inputId) {
    return reviews.get(projectId, inputId);
  }

  /** 每次保存追加不可变快照，SUBMISSION必须完整，previousId检测多页面冲突。 */
  @PostMapping("/{inputId}/records")
  public ResponseEntity<QualityReviewStore.Saved> save(
      @PathVariable String projectId,
      @PathVariable String inputId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody QualityReviewProtocol.RecordBody body) {
    var result = reviews.save(projectId, inputId, key, body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.value());
  }

  /** 明确署名后才列出其历史元数据，本机署名不是账号身份。 */
  @GetMapping("/{inputId}/records")
  public io.echoinsight.project.ProjectStore.Page<QualityReviewStore.RecordSummary> records(
      @PathVariable String projectId,
      @PathVariable String inputId,
      @RequestParam String reviewer,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return reviews.records(projectId, inputId, reviewer, page, size);
  }

  /** 读取某一版草稿或提交，不自动载入其他署名的最新判断。 */
  @GetMapping("/{inputId}/records/{recordId}")
  public QualityReviewStore.Saved record(
      @PathVariable String projectId, @PathVariable String inputId, @PathVariable String recordId) {
    return reviews.record(projectId, inputId, recordId);
  }

  /** 只有完整提交能读取预测对照，不派工、不自动裁决谁正确。 */
  @GetMapping("/{inputId}/records/{recordId}/comparison")
  public QualityReviewComparison.View compare(
      @PathVariable String projectId, @PathVariable String inputId, @PathVariable String recordId) {
    return comparisons.compare(projectId, inputId, recordId);
  }

  /** 下载内容由明确format决定；input不含意见和预测，record/comparison绑定不可变记录。 */
  @GetMapping("/{inputId}/export")
  public ResponseEntity<String> export(
      @PathVariable String projectId,
      @PathVariable String inputId,
      @RequestParam(defaultValue = "input") String format,
      @RequestParam(required = false) String recordId) {
    Object value =
        switch (format) {
          case "input" -> reviews.get(projectId, inputId);
          case "record" -> reviews.record(projectId, inputId, recordId);
          case "comparison" -> comparisons.compare(projectId, inputId, recordId);
          default -> throw QualityReviewProtocol.bad("核对导出格式只能为input、record或comparison");
        };
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"echoinsight-quality-review.json\"")
        .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
        .body(reviews.encode(value));
  }
}
