/* 文件职责：提供只读、脱敏的模型执行模式，供创建任务前说明用途与潜在费用。 */
package io.echoinsight.api;

import io.echoinsight.model.ConfiguredModelPlan;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 页面只查询安全摘要，不能通过这个接口开启供应商或改变价格。 */
@RestController
public class ModelStatusController {
  private final ConfiguredModelPlan plans;

  /** 注入启动时已经校验的计划，不读取配置文件或环境变量。 */
  public ModelStatusController(ConfiguredModelPlan plans) {
    this.plans = plans;
  }

  /** 返回新任务将采用的模式；历史任务仍以自身账本及报告为准，不触发模型请求。 */
  @GetMapping("/api/v1/model/status")
  public ConfiguredModelPlan.Status status() {
    return plans.status();
  }
}
