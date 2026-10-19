/* 文件职责：提供项目参考文本的追加/分页/详情及已接受请求的冻结背景查询。 */
package io.echoinsight.api;

import io.echoinsight.intelligence.AnalysisContextStore;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.project.ProjectStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 背景只作为业务资料，接口不取远程网页、执行命令或调用付费模型。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class ReferenceMaterialController {
  private final ReferenceMaterialStore materials;
  private final AnalysisContextStore contexts;

  /** 使用服务端资格校验和不可变输入服务，不在Controller直接写数据库。 */
  public ReferenceMaterialController(
      ReferenceMaterialStore materials, AnalysisContextStore contexts) {
    this.materials = materials;
    this.contexts = contexts;
  }

  /** text为原文而非待抓取地址；sourceDescription只说明用户提供的出处。 */
  public record Create(String title, String text, String sourceDescription) {}

  /** 同键重发返回同一版本201，不覆盖已经用于分析的资料。 */
  @PostMapping("/reference-materials")
  public ResponseEntity<ReferenceMaterialStore.Material> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Create input) {
    return ResponseEntity.status(201)
        .body(
            materials.create(
                projectId, key, input.title(), input.text(), input.sourceDescription()));
  }

  /** 列表只给摘要，可分页读取较早资料。 */
  @GetMapping("/reference-materials")
  public ProjectStore.Page<ReferenceMaterialStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return materials.list(projectId, page, size);
  }

  /** 正文按需读取，不能使用另一个项目路径借用资料。 */
  @GetMapping("/reference-materials/{materialId}")
  public ReferenceMaterialStore.Material get(
      @PathVariable String projectId, @PathVariable String materialId) {
    return materials.get(projectId, materialId);
  }

  /** 返回原请求实际冻结的背景；纯评论/历史请求明确返回null，不自动补新资料。 */
  @GetMapping("/analysis-requests/{requestId}/context")
  public AnalysisContextStore.View context(
      @PathVariable String projectId, @PathVariable String requestId) {
    return contexts.get(projectId, requestId);
  }
}
