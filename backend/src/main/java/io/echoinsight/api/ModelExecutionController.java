/* 文件职责：只读展示任务分批、模拟或美元估算预算及调用尝试，帮助解释耗时和失败。 */
package io.echoinsight.api;

import io.echoinsight.model.ModelLedger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 不在此提供模型地址、密钥、价格配置或付费调用入口。 */
@RestController
@RequestMapping("/api/v1/analysis-jobs")
public class ModelExecutionController {
  private final ModelLedger ledger;

  /** 注入只读执行视图服务。 */
  public ModelExecutionController(ModelLedger ledger) {
    this.ledger = ledger;
  }

  /** 分页展示批次，每批最多三条最近调用；参数错误明确拒绝。 */
  @GetMapping("/{id}/execution")
  public ModelLedger.View get(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page 为 1～10000，size 为 1～100");
    return ledger.view(id, page, size);
  }
}
