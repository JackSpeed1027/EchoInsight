/* 文件职责：提供核验目录与追加式核验提交，当前仅面向本机单用户。 */
package io.echoinsight.api;

import io.echoinsight.review.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 核验使用报告与稳定结论 ID，不接受浏览器自报的用户身份。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportReviewController {
  private final ReviewService reviews;

  /** 注入持久化核验服务。 */
  public ReportReviewController(ReviewService reviews) {
    this.reviews = reviews;
  }

  /** 每条结论最多返回最近 1～100 条历史；全部事件仍保存在数据库中。 */
  @GetMapping("/{id}/reviews")
  public ReviewService.View list(
      @PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须为 1～100");
    return reviews.list(id, limit);
  }

  /** 新事件返回 201，同键同载荷重放返回 200；版本过期或请求键冲突为 409。 */
  @PostMapping("/{id}/findings/{findingId}/reviews")
  public ResponseEntity<ReviewService.Saved> save(
      @PathVariable String id,
      @PathVariable String findingId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ReviewService.Request request) {
    if (!key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求键格式错误");
    var saved = reviews.save(id, findingId, key, request);
    return ResponseEntity.status(saved.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
  }
}
