/* 文件职责：启动时校验服务器收费边界，为新任务生成不包含密钥的固定计划。 */
package io.echoinsight.model;

import io.echoinsight.model.springai.DiscussionPrompt;
import io.echoinsight.model.springai.SentimentPrompt;
import java.math.BigDecimal;

/** 固定一家供应商和请求别名，不提供任意 URL。关闭时保持免费 Mock；启用但配置不完整则拒绝启动。 价格为用户核实后填写的保守单价，预算只约束本系统单个任务的本地记账。 */
public final class ConfiguredModelPlan {
  public static final String MODEL = "deepseek-flash";
  // 官方当前声明 1M 上下文；分类计划按完整上限预留，不使用未经验证的字符分词估算。
  public static final int INPUT_CEILING = 1000000;
  // 讨论任务在本地先限制为12.8万输入 Token，控制一次长评论分析的最大费用。
  public static final int DISCUSSION_INPUT_CEILING = 128000;
  private final ModelContract.Plan plan;

  /** 1、关闭时不读取凭据；2、校验明确授权及报价；3、精确转换美元；4、固化无密钥计划。 */
  public ConfiguredModelPlan(ModelSettings settings) {
    if (!settings.enabled()) {
      plan = ModelContract.Plan.mock();
      return;
    }
    if (!settings.allowPaidCalls()
        || settings.apiKey() == null
        || settings.apiKey().isBlank()
        || settings.apiKey().contains("\n")
        || settings.apiKey().contains("\r")
        || settings.expectedResponseModel() == null
        || settings.priceVersion() == null
        || !settings.priceVersion().matches("[A-Za-z0-9._:-]{1,64}")
        || settings.inputNanosPerToken() == null
        || settings.inputNanosPerToken() <= 0
        || settings.outputNanosPerToken() == null
        || settings.outputNanosPerToken() <= 0
        || settings.taskBudgetUsd() == null
        || settings.taskBudgetUsd().signum() <= 0) {
      throw new IllegalArgumentException(
          "Model configuration incomplete; inspect local configuration without logging secrets");
    }
    long budget;
    try {
      budget = settings.taskBudgetUsd().movePointRight(9).longValueExact();
    } catch (ArithmeticException ignored) {
      throw new IllegalArgumentException("Model budget must be exact USD nanos");
    }
    plan =
        new ModelContract.Plan(
            "SPRING_AI",
            MODEL,
            SentimentPrompt.VERSION,
            SentimentPrompt.TAXONOMY,
            20,
            60000,
            4096,
            "USD_NANOS",
            settings.priceVersion(),
            settings.inputNanosPerToken(),
            settings.outputNanosPerToken(),
            budget,
            INPUT_CEILING,
            settings.expectedResponseModel(),
            "MODEL");
    long reservation =
        Math.addExact(
            Math.multiplyExact((long) INPUT_CEILING, plan.inputUnitRate()),
            Math.multiplyExact((long) plan.maxOutputTokens(), plan.outputUnitRate()));
    if (reservation > budget) {
      throw new IllegalArgumentException(
          "Task budget cannot cover one conservative batch reservation");
    }
  }

  /** 新任务保存这份计划；历史任务读取自己的计划，不跟随配置漂移。 */
  public ModelContract.Plan current() {
    return plan;
  }

  /**
   * 讨论分析关闭模型时使用结构配方；显式开启后复用同一供应商、报价和任务预算。
   *
   * <p>提示版本和输入上限独立固化，使分类与讨论可以分别演进，同时继续经过同一调用账本。
   */
  public ModelContract.Plan discussion() {
    if ("MODEL".equals(plan.reportMode())) {
      return new ModelContract.Plan(
          plan.providerId(),
          plan.modelVersion(),
          DiscussionPrompt.VERSION,
          DiscussionPrompt.TAXONOMY,
          1,
          plan.timeoutMs(),
          4096,
          plan.unit(),
          plan.priceVersion(),
          plan.inputUnitRate(),
          plan.outputUnitRate(),
          plan.budgetLimit(),
          DISCUSSION_INPUT_CEILING,
          plan.expectedResponseModel(),
          "MODEL");
    }
    return new ModelContract.Plan(
        "MOCK",
        "discussion-structure-v1",
        "discussion-structure-v1",
        "argument-map-v1",
        50,
        3000,
        1024,
        "SIMULATED_UNITS",
        "mock-zero-v1",
        0,
        0,
        0,
        null,
        null,
        "MOCK");
  }

  /** 独立内容使用新提示版本，报价、预算、供应商沿用原服务器计划，不回写历史评论任务。 */
  public ModelContract.Plan content() {
    var base = discussion();
    return new ModelContract.Plan(
        base.providerId(),
        base.modelVersion(),
        io.echoinsight.content.ContentModelContract.PROMPT,
        io.echoinsight.content.ContentModelContract.TAXONOMY,
        1,
        base.timeoutMs(),
        4096,
        base.unit(),
        base.priceVersion(),
        base.inputUnitRate(),
        base.outputUnitRate(),
        base.budgetLimit(),
        base.inputTokenCeiling(),
        base.responseModel(),
        base.executionMode());
  }

  /** 仅显式选择背景的新请求采用v4；纯评论和既有任务继续保留v3计划与检查点。 */
  public ModelContract.Plan discussionWithContext() {
    var base = discussion();
    if (!"MODEL".equals(base.reportMode())) return base;
    return new ModelContract.Plan(
        base.providerId(),
        base.modelVersion(),
        DiscussionPrompt.CONTEXT_VERSION,
        DiscussionPrompt.CONTEXT_TAXONOMY,
        base.batchSize(),
        base.timeoutMs(),
        base.maxOutputTokens(),
        base.unit(),
        base.priceVersion(),
        base.inputUnitRate(),
        base.outputUnitRate(),
        base.budgetLimit(),
        base.inputTokenCeiling(),
        base.responseModel(),
        base.executionMode());
  }

  /** 命题协议单独冻结版本；模型关闭时仍明确结构演示，不生成虚假立场。 */
  public ModelContract.Plan discussionWithProposition() {
    var base = discussion();
    if (!"MODEL".equals(base.reportMode())) return base;
    return new ModelContract.Plan(
        base.providerId(),
        base.modelVersion(),
        StanceProtocol.VERSION,
        StanceProtocol.TAXONOMY,
        base.batchSize(),
        base.timeoutMs(),
        base.maxOutputTokens(),
        base.unit(),
        base.priceVersion(),
        base.inputUnitRate(),
        base.outputUnitRate(),
        base.budgetLimit(),
        base.inputTokenCeiling(),
        base.responseModel(),
        base.executionMode());
  }

  /** 完整命题使用独立分片协议；所有调用仍由原任务预算和账本约束。 */
  public ModelContract.Plan discussionFull() {
    var base = discussionWithProposition();
    if (!"MODEL".equals(base.reportMode())) return base;
    return new ModelContract.Plan(
        base.providerId(),
        base.modelVersion(),
        io.echoinsight.intelligence.StanceChunkPlanner.PROMPT,
        io.echoinsight.intelligence.StanceChunkPlanner.TAXONOMY,
        12,
        base.timeoutMs(),
        base.maxOutputTokens(),
        base.unit(),
        base.priceVersion(),
        base.inputUnitRate(),
        base.outputUnitRate(),
        base.budgetLimit(),
        base.inputTokenCeiling(),
        base.responseModel(),
        base.executionMode());
  }

  /** 只返回页面需要的非敏感状态，不暴露密钥、端点或完整本机配置。 */
  public Status status() {
    return new Status(
        plan.reportMode(),
        plan.providerId(),
        plan.modelVersion(),
        plan.unit(),
        plan.budgetLimit(),
        "USD_NANOS".equals(plan.unit())
            ? BigDecimal.valueOf(plan.budgetLimit(), 9).toPlainString()
            : "0");
  }

  /** 预算字段说明一次新任务的本地上限，不表示供应商账户余额。 */
  public record Status(
      String mode,
      String providerId,
      String modelVersion,
      String unit,
      long taskBudgetUnits,
      String taskBudgetUsd) {}
}
