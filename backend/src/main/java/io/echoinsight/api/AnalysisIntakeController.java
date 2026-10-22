/* 文件职责：暴露链接加原话的固定业务入口，分别确认采集、资料与分析，复用原任务。 */
package io.echoinsight.api;

import io.echoinsight.intake.*;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.project.ProjectStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 没有通用工具调用或自动收费；所有资格、版本与事务在入口业务服务处理。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-intakes")
public class AnalysisIntakeController {
  private final AnalysisIntakeStore store;

  /** 注入原业务编排器，控制器不保留运行状态。 */
  public AnalysisIntakeController(AnalysisIntakeStore store) {
    this.store = store;
  }

  /** 返回候选和缺失命题问题，不创建入口或子任务。 */
  @PostMapping("/preview")
  public AnalysisIntakeStore.Planning preview(
      @PathVariable String projectId, @RequestBody AnalysisIntakeProtocol.Draft body) {
    return store.preview(projectId, body);
  }

  /** 保存原话和明确范围，首次201、幂等重发200，尚未开始采集/分析。 */
  @PostMapping
  public ResponseEntity<AnalysisIntakeStore.View> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody AnalysisIntakeProtocol.Draft body) {
    var r = store.create(projectId, key, body);
    return ResponseEntity.status(r.created() ? 201 : 200).body(r.value());
  }

  /** 有界历史仅元数据，恢复按明确ID进行。 */
  @GetMapping
  public ProjectStore.Page<AnalysisIntakeStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return store.list(projectId, page, size);
  }

  /** 只读阶段投影，查询不产生后续业务动作。 */
  @GetMapping("/{intakeId}")
  public AnalysisIntakeStore.View get(
      @PathVariable String projectId, @PathVariable String intakeId) {
    return store.get(projectId, intakeId);
  }

  /** 显式选择已有版本，服务端按链接身份和同项目资格过滤。 */
  @GetMapping("/{intakeId}/candidates")
  public ProjectStore.Page<ProjectStore.Snapshot> candidates(
      @PathVariable String projectId,
      @PathVariable String intakeId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return store.candidates(projectId, intakeId, page, size);
  }

  /** 仅创建/返回原采集批次，失败/取消沿用原任务中心操作。 */
  @PostMapping("/{intakeId}/collection")
  public AnalysisIntakeStore.View collect(
      @PathVariable String projectId,
      @PathVariable String intakeId,
      @RequestBody AnalysisIntakeProtocol.Collect body) {
    return store.collect(projectId, intakeId, body);
  }

  /** 空snapshotId表示接受本次采集实际生成的快照，不能代换其他批次结果。 */
  public record Bind(String snapshotId) {}

  /** 明确绑定一次资料与哈希，不改变原话，也不自动分析。 */
  @PostMapping("/{intakeId}/source")
  public AnalysisIntakeStore.View bind(
      @PathVariable String projectId, @PathVariable String intakeId, @RequestBody Bind body) {
    return store.bind(projectId, intakeId, body == null ? null : body.snapshotId());
  }

  /** 只读原预检并返回本次确认指纹，无写入、模型或平台调用。 */
  @PostMapping("/{intakeId}/analysis-preview")
  public AnalysisIntakeStore.Quote quote(
      @PathVariable String projectId, @PathVariable String intakeId) {
    return store.quote(projectId, intakeId);
  }

  /** 分析接受才调用原请求服务；一次入口仅一个原任务，重发200不重新派工。 */
  @PostMapping("/{intakeId}/analysis")
  public ResponseEntity<AnalysisRequestStore.RequestRow> accept(
      @PathVariable String projectId,
      @PathVariable String intakeId,
      @RequestBody AnalysisIntakeProtocol.Accept body) {
    var r = store.accept(projectId, intakeId, body);
    return ResponseEntity.status(r.created() ? 201 : 200).body(r.request());
  }
}
