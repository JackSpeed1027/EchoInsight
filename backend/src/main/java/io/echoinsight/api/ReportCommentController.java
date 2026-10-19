/* 文件职责：提供单报告评论明细的只读入口，参数校验和历史一致性由查询服务完成。 */
package io.echoinsight.api;

import io.echoinsight.report.ReportCommentQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 页面能检查全部已保存标签，接口只读且不依赖当前模型开关或供应商可用性。 */
@RestController
public class ReportCommentController {
  private final ReportCommentQuery comments;

  /** 注入已有报告的只读查询，不把任务执行器或模型网关带入查询链路。 */
  public ReportCommentController(ReportCommentQuery comments) {
    this.comments = comments;
  }

  /** 返回分页明细；合法请求 200、参数非法 400、报告不存在 404。 q 只搜索当前评论原文；响应含评论内容，使用 no-store 减少共享缓存留存。 */
  @GetMapping("/api/v1/reports/{id}/comments")
  public ResponseEntity<ReportCommentQuery.Page> list(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "ALL") String label,
      @RequestParam(name = "q", defaultValue = "") String query) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .body(comments.list(id, page, size, label, query));
  }
}
