/* 文件职责：定义供应商无关的分类协议、任务执行计划和明确的失败原因。 */
package io.echoinsight.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.analysis.FixtureClassifier.Classification;
import java.time.Duration;
import java.util.List;

/** 免费 Mock 为默认；付费供应商只能经服务器显式配置注册，页面不能指定端点或价格。 */
public final class ModelContract {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 工具类不允许实例化。 */
  private ModelContract() {}

  /**
   * 任务创建时固定的执行计划；所有批次及恢复过程使用同一份计划。
   *
   * <p>providerId/modelVersion 标识具体实现；promptVersion/taxonomyVersion 固定解释规则。 batchSize
   * 限制单批条数，timeoutMs 限制本地等待，maxOutputTokens 限制输出上界。
   * unit/priceVersion/inputUnitRate/outputUnitRate/budgetLimit 用于解释预留和结算。 SIMULATED_UNITS
   * 是模拟单位；USD_NANOS 是十亿分之一美元的本地估算，并非供应商账单。 新增可空字段只对新计划写入；旧计划保持原 JSON 和请求指纹，不能在读取时补默认值。
   */
  public record Plan(
      String providerId,
      String modelVersion,
      String promptVersion,
      String taxonomyVersion,
      int batchSize,
      long timeoutMs,
      int maxOutputTokens,
      String unit,
      String priceVersion,
      long inputUnitRate,
      long outputUnitRate,
      long budgetLimit,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer inputTokenCeiling,
      @JsonInclude(JsonInclude.Include.NON_NULL) String responseModel,
      @JsonInclude(JsonInclude.Include.NON_NULL) String executionMode) {
    /** 兼容已保存的 13 字段计划与既有调用点；不改变历史 JSON 或检查点摘要。 */
    public Plan(
        String providerId,
        String modelVersion,
        String promptVersion,
        String taxonomyVersion,
        int batchSize,
        long timeoutMs,
        int maxOutputTokens,
        String unit,
        String priceVersion,
        long inputUnitRate,
        long outputUnitRate,
        long budgetLimit) {
      this(
          providerId,
          modelVersion,
          promptVersion,
          taxonomyVersion,
          batchSize,
          timeoutMs,
          maxOutputTokens,
          unit,
          priceVersion,
          inputUnitRate,
          outputUnitRate,
          budgetLimit,
          null,
          null,
          null);
    }

    /** 返回执行模式；老 Mock 仍为演示，其他历史替身不能冒充真实模型。 */
    public String reportMode() {
      return executionMode != null
          ? executionMode
          : "MOCK".equals(providerId) ? "MOCK" : "LOCAL_STUB";
    }

    /** 请求别名与响应身份可以不同，实际响应仍须逐批精确匹配。 */
    public String expectedResponseModel() {
      return responseModel == null ? modelVersion : responseModel;
    }

    /** 约束批次、超时、费用和来源字段；真实币种必须有独立输入上界与响应身份。 */
    public Plan {
      if (providerId == null
          || modelVersion == null
          || promptVersion == null
          || taxonomyVersion == null
          || priceVersion == null
          || !("SIMULATED_UNITS".equals(unit) || "USD_NANOS".equals(unit))
          || batchSize < 1
          || batchSize > 100
          || timeoutMs < 50
          || timeoutMs > 300000
          || maxOutputTokens < 1
          || maxOutputTokens > 4096
          || inputUnitRate < 0
          || inputUnitRate > 1000000
          || outputUnitRate < 0
          || outputUnitRate > 1000000
          || budgetLimit < 0
          || budgetLimit > 1000000000000L)
        throw new IllegalArgumentException("Invalid model execution plan");
      if ((inputTokenCeiling != null && (inputTokenCeiling < 1 || inputTokenCeiling > 2000000))
          || (responseModel != null && !responseModel.matches("[A-Za-z0-9._:-]{1,128}"))
          || (executionMode != null
              && !List.of("MOCK", "LOCAL_STUB", "MODEL").contains(executionMode))
          || ("USD_NANOS".equals(unit)
              && (inputTokenCeiling == null
                  || responseModel == null
                  || !List.of("MODEL", "LOCAL_STUB")
                      .contains(executionMode == null ? "" : executionMode)))
          || ("MODEL".equals(executionMode)
              && (!"SPRING_AI".equals(providerId) || !"USD_NANOS".equals(unit)))) {
        throw new IllegalArgumentException("Invalid model execution boundary");
      }
    }

    /** 默认计划保持既有演示标签和零费用，不需要密钥或网络。 */
    public static Plan mock() {
      return new Plan(
          "MOCK",
          "synthetic-fixture-v1",
          "no-prompt-v1",
          "sentiment-v1",
          50,
          3000,
          512,
          "SIMULATED_UNITS",
          "mock-zero-v1",
          0,
          0,
          0);
    }
  }

  /** 供应商只看到当前批次必需的文本，不读取审核标签或评测答案。 */
  public record Text(String commentId, String text, String parentCommentId, String parentText) {}

  /** 批次身份、输入上界和输出上界都由服务端决定。 */
  public record Request(
      String snapshotId,
      int batchIndex,
      String sourceType,
      List<Text> comments,
      int maxInputTokens,
      int maxOutputTokens) {}

  /** 用量是供应商返回的事实，不从评论条数推导真实账单。 */
  public record Response(
      List<Classification> classifications, int inputTokens, int outputTokens, String requestId) {}

  /** 新供应商须显式实现协议，模型身份与已保存计划一致后才能调用。 */
  public interface Provider {
    /** 返回唯一供应商名称，用于白名单解析。 */
    String id();

    /** 返回不可静默变化的模型版本。 */
    String modelVersion();

    /** 在创建调用账本前核对实现与固定计划，避免记录版本与实际提示模板不一致。 既有 Mock/HTTP Stub 保持原有检查；有固定提示规则的实现应覆盖本方法。 */
    default void validatePlan(Plan plan) {}

    /** 执行单批请求；网关负责超时、预算、输出校验和结果提交。 */
    Response classify(Request request, Duration timeout) throws Exception;
  }

  /** 错误码用于任务诊断，不把模型错误消息或私有原文写入日志。 */
  public static class Failure extends RuntimeException {
    private final String code;

    /** 保存受控错误类型。 */
    public Failure(String code) {
      super(code);
      this.code = code;
    }

    /** 读取适合对外展示的错误码。 */
    public String code() {
      return code;
    }
  }

  /** 对计划和结果采用固定 DTO 编码。 */
  public static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 历史任务缺少计划时按原有零费用 Mock 规则兼容读取。 */
  public static Plan plan(String value) {
    if (value == null) return Plan.mock();
    try {
      return JSON.readValue(value, Plan.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored model plan", e);
    }
  }
}
