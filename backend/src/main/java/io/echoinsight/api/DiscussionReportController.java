/* 文件职责：提供统一讨论报告首屏、命题分页及下载，复用原请求权限与不可变产物。 */
package io.echoinsight.api;

import io.echoinsight.intelligence.*;
import io.echoinsight.report.DiscussionReportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 只读报告投影，不重跑模型、不修改历史结果；下载名由服务器固定。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-requests/{requestId}")
public class DiscussionReportController {
  private final DiscussionReportService reports;
  private final AnalysisRequestStore requests;
  private final SourceCoverageStore coverage;

  /** 接口共享原请求资格与来源诊断。 */
  public DiscussionReportController(
      DiscussionReportService reports,
      AnalysisRequestStore requests,
      SourceCoverageStore coverage) {
    this.reports = reports;
    this.requests = requests;
    this.coverage = coverage;
  }

  /** 首屏普通讨论带证据；命题列表和原文按页加载，统计仍为全局。 */
  @GetMapping("/report")
  public DiscussionReportService.Overview report(
      @PathVariable String projectId, @PathVariable String requestId) {
    return reports.overview(projectId, requestId);
  }

  /** 分页回看普通完整讨论节点及原文，不触发调用或重写结果。 */
  @GetMapping("/synthesis-nodes")
  public DiscussionReportService.NodePage nodes(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "3") int size) {
    return reports.nodes(projectId, requestId, page, size);
  }

  /** 无模型调用的业务过滤，关键词最多80字。 */
  @GetMapping("/decisions")
  public DiscussionReportService.DecisionPage decisions(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "ALL") String filter,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return reports.decisions(projectId, requestId, filter, query, page, size);
  }

  /** 包含原文的下载不缓存，不把用户标题放进响应头。 */
  @GetMapping("/report/export")
  public ResponseEntity<String> export(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "markdown") String format) {
    String body = reports.export(projectId, requestId, format);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"echoinsight-discussion."
                + (!format.equals("markdown") ? "json" : "md")
                + "\"")
        .contentType(
            MediaType.parseMediaType(
                !format.equals("markdown")
                    ? "application/json;charset=UTF-8"
                    : "text/markdown;charset=UTF-8"))
        .body(body);
  }

  /** 未成功请求也可查看来源事实，故障排查不依赖产物存在。 */
  @GetMapping("/source-coverage")
  public SourceCoverageStore.View coverage(
      @PathVariable String projectId, @PathVariable String requestId) {
    var request = requests.get(projectId, requestId);
    return coverage.get(projectId, requestId, request.snapshotId());
  }
}
