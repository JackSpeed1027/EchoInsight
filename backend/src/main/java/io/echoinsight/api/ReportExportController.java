/* 文件职责：提供报告只读附件下载，限制格式并设置安全响应头。 */
package io.echoinsight.api;

import io.echoinsight.report.ReportExportService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller 只处理下载协议；范围、原文和核验的一致性由导出服务负责。 */
@RestController
public class ReportExportController {
  private final ReportExportService exports;

  /** 注入只读导出服务；不接收任意文件路径、文件名或供应商配置。 */
  public ReportExportController(ReportExportService exports) {
    this.exports = exports;
  }

  /** 下载已发布报告；格式非法 400，报告不存在 404，成功为 200 附件。 默认包含最新核验；no-store 减少原文在共享缓存中留存，nosniff 禁止浏览器猜测文件类型。 */
  @GetMapping("/api/v1/reports/{id}/export")
  public ResponseEntity<byte[]> export(
      @PathVariable String id,
      @RequestParam(defaultValue = "markdown") String format,
      @RequestParam(defaultValue = "true") boolean includeReviews) {
    var download = exports.export(id, format, includeReviews);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.contentType()))
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(download.bytes());
  }
}
