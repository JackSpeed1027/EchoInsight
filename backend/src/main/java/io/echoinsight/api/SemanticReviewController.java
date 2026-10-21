/* 文件职责：提供同一分析请求内的追加语义核验与分页历史入口。 */
package io.echoinsight.api;

import io.echoinsight.intelligence.SemanticReviewStore;
import io.echoinsight.project.ProjectStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 原文引用由服务器校验；意见是人工阅读辅助，不触发LLM，也不修改分析产物。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-requests/{requestId}/semantic-reviews")
public class SemanticReviewController {
  private final SemanticReviewStore store;

  /** 接口不直接访问数据库，通过业务层检查项目、请求和产物归属。 */
  public SemanticReviewController(SemanticReviewStore store) {
    this.store = store;
  }

  /** 同键同意见重发复用原记录；新意见使用新键保留历史。 */
  @PostMapping
  public ResponseEntity<SemanticReviewStore.Review> create(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody SemanticReviewStore.Input input) {
    return ResponseEntity.status(201).body(store.create(projectId, requestId, key, input));
  }

  /** 阅读意见覆盖及分歧，不是模型质量评分。 */
  @GetMapping("/summary")
  public SemanticReviewStore.Summary summary(
      @PathVariable String projectId, @PathVariable String requestId) {
    return store.summary(projectId, requestId);
  }

  /** 历史仅查询明确请求，不产生模型费用。 */
  @GetMapping
  public ProjectStore.Page<SemanticReviewStore.Review> list(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return store.list(projectId, requestId, page, size);
  }
}
