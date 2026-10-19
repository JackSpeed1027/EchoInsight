/* 文件职责：提供最小项目、明确资料版本挂载与只读查询，不提供破坏性清理。 */
package io.echoinsight.api;

import io.echoinsight.project.ProjectStore;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 页面从项目进入资料与分析，实际范围由服务器注入。 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
  private final ProjectStore projects;

  /** 控制器不直接拼 SQL 或调用模型。 */
  public ProjectController(ProjectStore projects) {
    this.projects = projects;
  }

  /** 项目输入是名称和复盘目标，请求键单独从头部提供。 */
  public record Create(String name, String analysisGoal) {}

  /** 只挂载既有快照，不接收任意文件路径或正文。 */
  public record Attach(String snapshotId) {}

  /** 首次201、相同重发200、同键不同内容409。 */
  @PostMapping
  public ResponseEntity<ProjectStore.Project> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Create body) {
    var result = projects.create(key, body.name(), body.analysisGoal());
    return ResponseEntity.status(result.created() ? 201 : 200)
        .location(URI.create("/api/v1/projects/" + result.project().id()))
        .body(result.project());
  }

  /** 首页仅加载项目卡片，不默认拉取全部历史。 */
  @GetMapping
  public ProjectStore.Page<ProjectStore.Project> list(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
    return projects.list(page, size);
  }

  /** 返回项目目标、作品/版本/分析数量。 */
  @GetMapping("/{id}")
  public ProjectStore.Project get(@PathVariable String id) {
    return projects.get(id);
  }

  /** 挂载幂等返回200，不改旧快照和报告。 */
  @PostMapping("/{id}/snapshots")
  public ProjectStore.Snapshot attach(@PathVariable String id, @RequestBody Attach body) {
    return projects.attach(id, body.snapshotId());
  }

  /** 逐页列项目明确挂载的版本。 */
  @GetMapping("/{id}/snapshots")
  public ProjectStore.Page<ProjectStore.Snapshot> snapshots(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return projects.snapshots(id, page, size);
  }

  /** 分析页恢复指定资料，成员不存在返回404。 */
  @GetMapping("/{id}/snapshots/{snapshotId}")
  public ProjectStore.Snapshot snapshot(@PathVariable String id, @PathVariable String snapshotId) {
    return projects.snapshot(id, snapshotId);
  }
}
