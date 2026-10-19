/* 文件职责：提供两份报告的只读版本比较与评测候选下载，所有状态和模型调用仍由原请求管理。 */
package io.echoinsight.api;

import io.echoinsight.report.EvaluationPackageService;
import io.echoinsight.report.RevisionComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 响应文件名固定，不将用户问题或作品名放入响应头。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-requests/{requestId}")
public class RevisionComparisonController {
  private final RevisionComparisonService comparisons;
  private final EvaluationPackageService evaluations;

  /** 注入只读业务投影。 */
  public RevisionComparisonController(
      RevisionComparisonService comparisons, EvaluationPackageService evaluations) {
    this.comparisons = comparisons;
    this.evaluations = evaluations;
  }

  /** 路径requestId是after，beforeRequestId由用户明确选定。 */
  @GetMapping("/comparison")
  public RevisionComparisonService.Page compare(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam String beforeRequestId,
      @RequestParam(defaultValue = "ALL") String filter,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return comparisons.compare(projectId, beforeRequestId, requestId, filter, page, size);
  }

  /** 下载全部差异行，页面筛选不改变完整导出；不会重跑模型。 */
  @GetMapping("/comparison/export")
  public ResponseEntity<String> export(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam String beforeRequestId) {
    return download(
        comparisons.export(projectId, beforeRequestId, requestId),
        "echoinsight-revision-comparison.json");
  }

  /** 显式下载原文候选与原预测，保持未裁决/未授权状态，不写入公开数据集。 */
  @GetMapping("/evaluation-package")
  public ResponseEntity<String> evaluation(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "dev") String split,
      @RequestParam(defaultValue = "50") int limit) {
    return download(
        evaluations.export(projectId, requestId, split, limit),
        "echoinsight-evaluation-candidate.json");
  }

  /** 包含原文的附件禁止缓存与类型嗅探，不读取本机路径。 */
  private ResponseEntity<String> download(String body, String filename) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
        .header("Content-Type", "application/json;charset=UTF-8")
        .body(body);
  }
}
