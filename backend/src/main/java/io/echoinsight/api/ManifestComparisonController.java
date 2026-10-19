/* 文件职责：接受项目内前后两份冻结集合，返回已持久化的确定性差异结果。 */
package io.echoinsight.api;

import io.echoinsight.material.ManifestComparisonStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 比较不触发新采集、付费模型或数据删除，前后顺序由用户显式指定。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/manifest-comparisons")
public class ManifestComparisonController {
  private final ManifestComparisonStore store;

  /** 接口复用冻结资料和项目范围检查。 */
  public ManifestComparisonController(ManifestComparisonStore store) {
    this.store = store;
  }

  /** base为以前，target为现在；不自动采用最新集合。 */
  public record CreateRequest(@NotBlank String baseManifestId, @NotBlank String targetManifestId) {}

  /** 计算并追加保存；网络重发复用同一比较身份。 */
  @PostMapping
  public ResponseEntity<ManifestComparisonStore.Comparison> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateRequest input) {
    var result = store.create(projectId, key, input.baseManifestId(), input.targetManifestId());
    return ResponseEntity.created(
            java.net.URI.create(
                "/api/v1/projects/" + projectId + "/manifest-comparisons/" + result.id()))
        .body(result);
  }

  /** 固定地址可回看原版本结果，不因当前资料更新重新解释。 */
  @GetMapping("/{comparisonId}")
  public ManifestComparisonStore.Comparison get(
      @PathVariable String projectId, @PathVariable String comparisonId) {
    return store.get(projectId, comparisonId);
  }

  /** 历史结果不重新运行比较或模型，保持算法版本和范围。 */
  @GetMapping
  public io.echoinsight.project.ProjectStore.Page<ManifestComparisonStore.Comparison> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "5") int size) {
    return store.list(projectId, page, size);
  }
}
