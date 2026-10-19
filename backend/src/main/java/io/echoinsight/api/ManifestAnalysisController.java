/* 文件职责：为冻结集合的一句话分作品分析提供创建、详情及历史接口。 */
package io.echoinsight.api;

import io.echoinsight.material.ManifestAnalysisStore;
import io.echoinsight.project.ProjectStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 只提交业务范围和目标；模型计划、价格和任务资格仍由服务器控制。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/manifest-analyses")
public class ManifestAnalysisController {
  private final ManifestAnalysisStore store;

  /** 组合层复用单资料执行链路，接口不运行模型循环。 */
  public ManifestAnalysisController(ManifestAnalysisStore store) {
    this.store = store;
  }

  /** manifestId指定已经冻结的范围，instruction对每个作品独立执行。 */
  public record CreateRequest(
      @NotBlank String manifestId, @NotBlank @Size(max = 1000) String instruction) {}

  /** 全部子请求同事务创建，成功接受后由既有分析Worker执行。 */
  @PostMapping
  public ResponseEntity<ManifestAnalysisStore.Run> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateRequest input) {
    var result = store.create(projectId, key, input.manifestId(), input.instruction());
    return ResponseEntity.accepted()
        .location(
            java.net.URI.create(
                "/api/v1/projects/" + projectId + "/manifest-analyses/" + result.id()))
        .body(result);
  }

  /** 汇总状态由子任务计算，失败不会隐藏成功的作品结果。 */
  @GetMapping("/{runId}")
  public ManifestAnalysisStore.Run get(@PathVariable String projectId, @PathVariable String runId) {
    return store.get(projectId, runId);
  }

  /** 历史按明确清单过滤，不能自动改用新版本。 */
  @GetMapping
  public ProjectStore.Page<ManifestAnalysisStore.Run> list(
      @PathVariable String projectId,
      @RequestParam String manifestId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "5") int size) {
    return store.list(projectId, manifestId, page, size);
  }
}
