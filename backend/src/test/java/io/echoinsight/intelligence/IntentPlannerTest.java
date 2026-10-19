/* 文件职责：验证自然语言路由只接受当前已实现的讨论目标，并正确识别可选评议。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 路由测试不调用数据库或模型，只验证可解释的业务入口规则。 */
class IntentPlannerTest {
  private final IntentPlanner planner = new IntentPlanner();

  /** 普通讨论总结不会自动升级为胜负评议。 */
  @Test
  void routesDiscussionSummaryWithoutAssessment() {
    var result = planner.plan("总结这个视频下面用户讨论问题的逻辑和主要观点");
    assertThat(result.intent()).isEqualTo(IntentPlanner.DISCUSSION);
    assertThat(result.includeAssessment()).isFalse();
  }

  /** 明确比较理由时才生成评议占位，后续仍允许返回证据不足。 */
  @Test
  void recognizesExplicitAssessmentRequest() {
    var result = planner.plan("整理双方观点，并判断一下谁更有道理");
    assertThat(result.includeAssessment()).isTrue();
  }

  /** 当前未注册的目标明确422，不用相似词硬猜执行路径。 */
  @Test
  void rejectsUnsupportedOrEmptyInstructions() {
    assertThatThrownBy(() -> planner.plan("帮我做个封面"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(422));
    assertThatThrownBy(() -> planner.plan(" "))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
  }
}
