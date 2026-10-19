/* 文件职责：提供当前工程能力与离线演示目录。 */
package io.echoinsight.api;

import io.echoinsight.infrastructure.persistence.DemoDatasetMapper;
import io.echoinsight.ingestion.DemoDataset;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
/** 只展示已经实现的功能，保留仍待开发能力的说明。 */
public class SystemController {
  private final DemoDatasetMapper demoMapper;
  private final io.echoinsight.model.ConfiguredModelPlan plans;

  /** 注入示例目录查询接口。 */
  public SystemController(
      DemoDatasetMapper demoMapper, io.echoinsight.model.ConfiguredModelPlan plans) {
    this.plans = plans;
    this.demoMapper = demoMapper;
  }

  @GetMapping("/system/info")
  /** 返回当前演示模式及后续开发范围。 */
  public SystemInfo info() {
    return new SystemInfo(
        "EchoInsight",
        "0.1.0-SNAPSHOT",
        "CONTEXT_ANALYSIS_READY",
        plans.current().reportMode(),
        List.of("独立语义质量验证", "跨作品语义归并", "弹幕等材料角色扩展", "自然语言业务规划"));
  }

  @GetMapping("/demos")
  /** 读取示例目录，不触发分析任务。 */
  public List<DemoDataset> demos() {
    return demoMapper.findAll();
  }

  /** 给页面展示的工程信息。 */
  public record SystemInfo(
      String name, String version, String stage, String aiMode, List<String> pendingFeatures) {}
}
