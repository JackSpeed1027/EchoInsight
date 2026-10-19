/* 文件职责：集中定义任务控制的状态边界，不依赖模型判断是否可取消或继续。 */
package io.echoinsight.orchestration;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 状态与费用分开：已取消也可能费用未决；可继续不意味着失败原因已修复。 */
public final class TaskPolicy {
  private static final Set<String> ACTIVE = Set.of("PENDING", "RUNNING");

  private TaskPolicy() {}

  /** 只接受两种原任务类型，拒绝自由表名、Agent会话或其他伪造类型。 */
  public static String kind(String value) {
    if (!Set.of("ANALYSIS", "COLLECTION").contains(value == null ? "" : value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务类型只能是 ANALYSIS 或 COLLECTION");
    return value;
  }

  /** 等待和运行可取消，终态不会被取消操作改写。 */
  public static boolean canCancel(String status) {
    return ACTIVE.contains(status);
  }

  /** 只有已取消且没有已发出未决调用的任务能显式继续。 */
  public static boolean canResume(String status, boolean feeUncertain) {
    return "CANCELLED".equals(status) && !feeUncertain;
  }

  /** 安全重试沿用原FAILED语义，取消和失败不能混成同一个状态。 */
  public static boolean canRetry(String status, boolean feeUncertain) {
    return "FAILED".equals(status) && !feeUncertain;
  }
}
