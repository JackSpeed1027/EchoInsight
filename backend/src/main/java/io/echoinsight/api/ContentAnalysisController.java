/* 文件职责：项目内独立内容自然语言预检、接受与历史，不通过评论接口伪装无评论材料。 */
package io.echoinsight.api;

import io.echoinsight.content.ContentAnalysisStore;
import io.echoinsight.project.ProjectStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** jobId复用原任务身份；取消/继续仍走项目任务中心TC3/TC4，无第二套控制接口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/content-analyses")
public class ContentAnalysisController {
  private final ContentAnalysisStore store;

  /** 所有边界由业务服务重新核验，不信任浏览器预检结果。 */
  public ContentAnalysisController(ContentAnalysisStore store) {
    this.store = store;
  }

  /** 用户只给问题/材料/可选业务时间范围，未同意裁剪不能静默处理一部分。 */
  public record Create(
      String materialId,
      String instruction,
      Long fromMs,
      Long toMs,
      Boolean allowPartialInput,
      String coverageMode) {}

  /** POST预检只因范围/正文参数需要请求体，不创建任务或预留费用。 */
  @PostMapping("/preview")
  public ContentAnalysisStore.Preview preview(
      @PathVariable String projectId, @RequestBody Create body) {
    return store.preview(
        projectId,
        body.materialId(),
        body.instruction(),
        body.fromMs(),
        body.toMs(),
        body.coverageMode());
  }

  /** 原任务与冻结输入原子接受，首次201、同键重发200；更改版本或问题409。 */
  @PostMapping
  public ResponseEntity<ContentAnalysisStore.View> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Create body) {
    var created =
        store.create(
            projectId,
            key,
            body.materialId(),
            body.instruction(),
            body.fromMs(),
            body.toMs(),
            Boolean.TRUE.equals(body.allowPartialInput()),
            body.coverageMode());
    return ResponseEntity.status(created.created() ? 201 : 200).body(created.analysis());
  }

  /** 成功后含不可变内容报告，其他状态返回冻结输入、状态和受控错误码。 */
  @GetMapping("/{jobId}")
  public ContentAnalysisStore.View get(@PathVariable String projectId, @PathVariable String jobId) {
    return store.get(projectId, jobId);
  }

  /** 独立内容历史与评论请求历史分开，但统一出现在原项目任务中心。 */
  @GetMapping
  public ProjectStore.Page<ContentAnalysisStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int size) {
    return store.list(projectId, page, size);
  }
}
