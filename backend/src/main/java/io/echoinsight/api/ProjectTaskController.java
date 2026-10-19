/* 文件职责：提供同项目的分析/采集任务中心；操作均落在原任务，不新增执行系统。 */
package io.echoinsight.api;

import io.echoinsight.orchestration.ProjectTaskQuery;
import io.echoinsight.orchestration.TaskControlService;
import org.springframework.web.bind.annotation.*;

/** URL中的kind为ANALYSIS或COLLECTION；服务端重新核对项目、工作空间、执行范围。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class ProjectTaskController {
  private final ProjectTaskQuery query;
  private final TaskControlService control;

  /** 读写入口分开，查询不触发派工或额外模型调用。 */
  public ProjectTaskController(ProjectTaskQuery query, TaskControlService control) {
    this.query = query;
    this.control = control;
  }

  /** 分页读取两种原任务；Counts始终是全项目计数，ACTIVE仅匹配等待/运行。 */
  @GetMapping
  public ProjectTaskQuery.Page list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "ALL") String kind,
      @RequestParam(defaultValue = "ALL") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return query.list(projectId, kind, status, page, size);
  }

  /** 详情只含安全摘要、有限调用/控制历史及检查点数量，不返回供应商或采集正文。 */
  @GetMapping("/{kind}/{taskId}")
  public ProjectTaskQuery.Detail detail(
      @PathVariable String projectId, @PathVariable String kind, @PathVariable String taskId) {
    return query.detail(projectId, kind, taskId);
  }

  /** 200表示逻辑取消已经提交；不承诺供应商物理请求立即终止或不计费。 */
  @PostMapping("/{kind}/{taskId}/cancel")
  public ProjectTaskQuery.Detail cancel(
      @PathVariable String projectId, @PathVariable String kind, @PathVariable String taskId) {
    return control.cancel(projectId, kind, taskId);
  }

  /** 继续原已取消任务，返回排队事实；未决费用409，未修改原预算或成功检查点。 */
  @PostMapping("/{kind}/{taskId}/resume")
  public ProjectTaskQuery.Detail resume(
      @PathVariable String projectId, @PathVariable String kind, @PathVariable String taskId) {
    return control.resume(projectId, kind, taskId);
  }

  /** 失败的任务才走原安全重试；与取消后的继续分开，避免用户误以为重新生成任务。 */
  @PostMapping("/{kind}/{taskId}/retry")
  public ProjectTaskQuery.Detail retry(
      @PathVariable String projectId, @PathVariable String kind, @PathVariable String taskId) {
    return control.retry(projectId, kind, taskId);
  }
}
