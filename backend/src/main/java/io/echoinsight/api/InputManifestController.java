/* 文件职责：提供项目内不可变多作品资料集合的创建、详情和分页接口。 */
package io.echoinsight.api;

import io.echoinsight.material.InputManifestStore;
import io.echoinsight.project.ProjectStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 选择快照是业务范围决定；控制器不创建模型请求，也不把“最新资料”当成固定输入。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/manifests")
public class InputManifestController {
  private final InputManifestStore store;

  /** 复用项目范围与清单事务，接口不直接操作成员表。 */
  public InputManifestController(InputManifestStore store) {
    this.store = store;
  }

  /** 请求只含集合名称与明确版本列表；每个作品只能选择一个版本。 */
  public record CreateRequest(
      @NotBlank @Size(max = 120) String name,
      @NotEmpty @Size(max = 20) List<@NotBlank String> snapshotIds) {}

  /** 规范请求指纹与幂等键一起避免网络重发重复冻结。 */
  @PostMapping
  public ResponseEntity<InputManifestStore.Manifest> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreateRequest input) {
    var result = store.create(projectId, key, input.name(), input.snapshotIds());
    return ResponseEntity.created(
            URI.create("/api/v1/projects/" + projectId + "/manifests/" + result.summary().id()))
        .body(result);
  }

  /** 所有字段读取冻结值，之后采集不改写这份集合。 */
  @GetMapping("/{manifestId}")
  public InputManifestStore.Manifest get(
      @PathVariable String projectId, @PathVariable String manifestId) {
    return store.get(projectId, manifestId);
  }

  /** 只返回集合摘要，用户按需打开具体成员。 */
  @GetMapping
  public ProjectStore.Page<InputManifestStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return store.list(projectId, page, size);
  }
}
