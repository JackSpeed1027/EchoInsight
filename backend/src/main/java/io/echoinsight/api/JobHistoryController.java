/* 文件职责：为任务历史页提供分页和状态筛选接口。 */
package io.echoinsight.api;

import io.echoinsight.orchestration.JobHistoryQuery;
import org.springframework.web.bind.annotation.*;

/** 本机单用户的历史入口；不提供删除、清空或重建历史的操作。 */
@RestController
@RequestMapping("/api/v1/analysis-jobs")
public class JobHistoryController {
  private final JobHistoryQuery history;

  /** 注入只读查询服务，避免控制器直接拼接数据库条件。 */
  public JobHistoryController(JobHistoryQuery history) {
    this.history = history;
  }

  /** 按最新优先返回一页任务；非法参数交给查询服务统一拒绝。 */
  @GetMapping
  public JobHistoryQuery.Page list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String projectId) {
    return history.list(page, size, status, projectId);
  }
}
