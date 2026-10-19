/* 文件职责：提供同一集合运行的跨作品证据对照和可下载JSON，所有内容来自已发布产物。 */
package io.echoinsight.api;

import io.echoinsight.material.ManifestEvidenceReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 终态对照是只读能力，打开或下载不会触发新的模型调用。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/manifest-analyses/{runId}")
public class ManifestEvidenceController {
  private final ManifestEvidenceReport reports;

  /** 控制器复用唯一报告构造器，屏幕与导出采用同一口径。 */
  public ManifestEvidenceController(ManifestEvidenceReport reports) {
    this.reports = reports;
  }

  /** 返回严格绑定冻结范围和逐作品产物的对照，未结束时返回409。 */
  @GetMapping("/evidence-report")
  public ManifestEvidenceReport.Report report(
      @PathVariable String projectId, @PathVariable String runId) {
    return reports.get(projectId, runId);
  }

  /** 使用固定文件名，不把客户端文本写入响应头。 */
  @GetMapping("/evidence-report/export")
  public ResponseEntity<ManifestEvidenceReport.Report> export(
      @PathVariable String projectId, @PathVariable String runId) {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=echoinsight-evidence-report.json")
        .header("Cache-Control", "no-store")
        .body(reports.get(projectId, runId));
  }
}
