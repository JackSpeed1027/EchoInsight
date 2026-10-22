/* 文件职责：提供本机项目资料归档、只读预检和明确恢复到新项目的HTTP入口。 */
package io.echoinsight.api;

import io.echoinsight.archive.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** JSON流在协议层限制16MiB，不提高原上传接口上限，不接受服务器文件路径或远程URL。 */
@RestController
@RequestMapping("/api/v1")
public class MaterialArchiveController {
  private final MaterialArchiveStore store;
  private final MaterialArchiveProtocol protocol;

  /** 注入确定性协议与事务存储，控制器不持有文件或运行任务状态。 */
  public MaterialArchiveController(MaterialArchiveStore store, MaterialArchiveProtocol protocol) {
    this.store = store;
    this.protocol = protocol;
  }

  /** 无正文容量预检，不生成备份文件或任务。 */
  @GetMapping("/projects/{projectId}/material-archive/overview")
  public MaterialArchiveStore.Overview overview(@PathVariable String projectId) {
    return store.overview(projectId);
  }

  /** 明确下载原文JSON，固定文件名及no-store避免本机标题/正文变成响应头。 */
  @GetMapping("/projects/{projectId}/material-archive")
  public ResponseEntity<String> export(@PathVariable String projectId) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"echoinsight-materials.json\"")
        .body(protocol.encode(store.export(projectId)));
  }

  /** 上传预检与恢复使用同一受限协议；该请求不写数据库。 */
  @PostMapping(value = "/material-archives/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  public MaterialArchiveProtocol.Preview preview(HttpServletRequest request) throws IOException {
    return store.preview(protocol.read(request.getInputStream()));
  }

  /** 用户确认后才创建新项目，首次201、相同请求键重发200；失败整事务回滚。 */
  @PostMapping(value = "/material-archives/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<MaterialArchiveStore.Receipt> restore(
      @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) throws IOException {
    var value = store.restore(key, protocol.read(request.getInputStream()));
    return ResponseEntity.status(value.created() ? 201 : 200).body(value.receipt());
  }
}
