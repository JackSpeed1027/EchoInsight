/* 文件职责：核对终态与费用的组合，避免取消被误当失败或已退款。 */
package io.echoinsight.orchestration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 纯规则测试只覆盖有业务差异的分支，真实租约/行锁由MySQL集成验证。 */
class TaskPolicyTest {
  /** 取消只改变活动任务。 */
  @Test
  void cancellationOnlyAcceptsActiveStates() {
    assertThat(TaskPolicy.canCancel("PENDING")).isTrue();
    assertThat(TaskPolicy.canCancel("RUNNING")).isTrue();
    for (String state : new String[] {"FAILED", "SUCCESS", "CANCELLED"})
      assertThat(TaskPolicy.canCancel(state)).isFalse();
  }

  /** 未决费用优先于继续资格，取消也不例外。 */
  @Test
  void uncertaintyBlocksResuming() {
    assertThat(TaskPolicy.canResume("CANCELLED", false)).isTrue();
    assertThat(TaskPolicy.canResume("CANCELLED", true)).isFalse();
    assertThat(TaskPolicy.canResume("FAILED", false)).isFalse();
  }

  /** 继续与重试是不同操作。 */
  @Test
  void retryDoesNotReviveCancelledOrSuccessfulTasks() {
    assertThat(TaskPolicy.canRetry("FAILED", false)).isTrue();
    assertThat(TaskPolicy.canRetry("FAILED", true)).isFalse();
    assertThat(TaskPolicy.canRetry("CANCELLED", false)).isFalse();
  }

  /** 类型白名单不能成为SQL表名入口。 */
  @Test
  void kindIsStrictlyEnumerated() {
    assertThat(TaskPolicy.kind("ANALYSIS")).isEqualTo("ANALYSIS");
    assertThatThrownBy(() -> TaskPolicy.kind("analysis_job")).hasMessageContaining("任务类型");
    assertThatThrownBy(() -> TaskPolicy.kind(null)).hasMessageContaining("任务类型");
  }
}
