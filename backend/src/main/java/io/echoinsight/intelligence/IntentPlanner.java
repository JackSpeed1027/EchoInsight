/* 文件职责：把用户原话路由到已注册的分析配方；不调用模型，也不接受前端指定内部工具。 */
package io.echoinsight.intelligence;

import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 自然语言入口的确定性路由器。
 *
 * <p>当前只开放单资料讨论理解。固定规则适合可解释的首版入口；以后增加配方时仍由服务端注册，不能让模型任意执行 SQL、网络或文件命令。
 */
@Component
public class IntentPlanner {
  /** 已实现配方的稳定身份，保存后不会因关键词规则升级而改变历史任务。 */
  public static final String DISCUSSION = "DISCUSSION_V1";

  /** 路由结果只记录业务意图和是否需要评议，不把模型或工具暴露给页面。 */
  public record Spec(String intent, boolean includeAssessment) {}

  /**
   * 识别当前可完成的讨论类目标。
   *
   * <p>1、清理首尾空白并检查长度；2、识别总结讨论、观点或理由的表达；3、单独识别比较与“谁更有道理”的评议要求；4、不能可靠路由时明确拒绝，避免生成答非所问的产物。
   */
  public Spec plan(String instruction) {
    if (instruction == null || instruction.isBlank() || instruction.strip().length() > 1000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析要求不能为空且最多1000字");
    }
    String text = instruction.strip().toLowerCase(Locale.ROOT);
    boolean discussion =
        containsAny(text, "讨论", "观点", "争论", "逻辑", "理由", "论点", "总结", "有道理", "说法");
    if (!discussion) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "当前先支持总结讨论、整理观点和比较理由，请把目标写得更明确");
    }
    boolean assessment =
        containsAny(text, "谁更有道理", "更有道理", "判断", "评议", "比较理由", "更合理", "站得住脚");
    return new Spec(DISCUSSION, assessment);
  }

  /** 任一业务词出现即命中；列表由服务端版本管理，用户输入不能修改规则。 */
  private boolean containsAny(String text, String... words) {
    for (String word : words) if (text.contains(word)) return true;
    return false;
  }
}
