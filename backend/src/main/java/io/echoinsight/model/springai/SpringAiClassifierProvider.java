/* 文件职责：将一次 Spring AI ChatResponse 转换为现有分类协议，保留真实响应中的原始用量。 */
package io.echoinsight.model.springai;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.echoinsight.model.ModelContract;
import java.time.Duration;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Spring AI 的 OpenAI 兼容响应适配器，由受控工厂显式装配，不能自行发现端点。
 *
 * <p>本机测试与 DeepSeek 配置复用严格解析逻辑；费用、输入上界和来源由不可变任务计划约束。
 */
public final class SpringAiClassifierProvider implements ModelContract.Provider {
  public static final String ID = "SPRING_AI";
  private static final int MAX_TEXT_CHARS = 65536;
  private static final ObjectReader OUTPUT = strictOutputReader();

  /** 严格 Mapper 不能仅关闭通用强转开关；文本类型仍须单独拒绝数字和布尔值。 */
  private static ObjectReader strictOutputReader() {
    var mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(20)
                            .maxStringLength(MAX_TEXT_CHARS)
                            .build())
                    .build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    return mapper.readerFor(SentimentPrompt.Payload.class);
  }

  private final ChatModel model;
  private final String modelVersion;
  private final String responseModel;

  /**
   * 绑定已经关闭自动重试、工具执行及敏感日志的模型实例。
   *
   * @param model 一次 call 只允许一次 SDK 尝试；回环工厂提供本轮验证过的构造方式
   * @param modelVersion 非空且最长 128 字符，需与任务计划及响应模型身份一致
   */
  public SpringAiClassifierProvider(ChatModel model, String modelVersion) {
    this(model, modelVersion, modelVersion);
  }

  /** 请求可以使用别名，响应身份独立固定；逐批验证，不能自动接受别名变化。 */
  public SpringAiClassifierProvider(ChatModel model, String modelVersion, String responseModel) {
    this.responseModel = Objects.requireNonNull(responseModel);
    this.model = Objects.requireNonNull(model);
    if (modelVersion == null || modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("Invalid model version");
    }
    this.modelVersion = modelVersion;
  }

  /** 返回固定协议身份，网关据此解析显式注册的实现，不接受页面指定类名。 */
  @Override
  public String id() {
    return ID;
  }

  /** 返回创建实例时固定的模型身份；不能在恢复时悄悄换成其他模型。 */
  @Override
  public String modelVersion() {
    return modelVersion;
  }

  /** 固定模板与分类规则必须同任务计划相符；不一致时在预留或出站前拒绝。 */
  @Override
  public void validatePlan(ModelContract.Plan plan) {
    if (!ID.equals(plan.providerId())
        || !modelVersion.equals(plan.modelVersion())
        || !responseModel.equals(plan.expectedResponseModel())
        || !SentimentPrompt.VERSION.equals(plan.promptVersion())
        || !SentimentPrompt.TAXONOMY.equals(plan.taxonomyVersion())) {
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
    }
  }

  /**
   * 只执行一次 SDK 调用；不在这里重试、发布报告或结算费用。
   *
   * <p>1、构造独立系统规则和批次数据；2、调用模型一次；3、检查结束原因、来源和原始用量； 4、严格解析标签。逐条身份和覆盖校验由 ModelGateway 再次执行。
   *
   * @param timeout 外层网关的等待预算，实际中断由网关和底层 HTTP 超时共同负责
   * @return 有明确原始用量的类型化响应；缺失用量不能用默认 0 冒充
   * @throws ModelContract.Failure 响应缺失、截断、工具调用、无效标签或用量不完整
   */
  @Override
  public ModelContract.Response classify(ModelContract.Request request, Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Timeout must be positive");
    }
    ChatResponse response = model.call(SentimentPrompt.build(request, modelVersion));
    if (response == null || response.getResults().size() != 1 || response.hasToolCalls()) {
      throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    }
    var generation = response.getResult();
    if (generation.getOutput() == null
        || !"stop".equalsIgnoreCase(generation.getMetadata().getFinishReason())) {
      throw new ModelContract.Failure("MODEL_INCOMPLETE_RESPONSE");
    }
    Object refusal = generation.getOutput().getMetadata().get("refusal");
    if (refusal instanceof String reason && !reason.isBlank()) {
      throw new ModelContract.Failure("MODEL_INCOMPLETE_RESPONSE");
    }
    var metadata = response.getMetadata();
    if (!responseModel.equals(metadata.getModel())) {
      throw new ModelContract.Failure("MODEL_ID_MISMATCH");
    }
    // Spring AI 可能将缺失计数规范化；必须回看 OpenAI 兼容响应的原始字段。
    if (!(metadata.getUsage().getNativeUsage() instanceof OpenAiApi.Usage usage)
        || usage.promptTokens() == null
        || usage.completionTokens() == null
        || usage.totalTokens() == null) {
      throw new ModelContract.Failure("MODEL_USAGE_MISSING");
    }
    if (usage.promptTokens() < 0
        || usage.completionTokens() < 0
        || usage.totalTokens() < 0
        || (long) usage.promptTokens() + usage.completionTokens() != usage.totalTokens()) {
      throw new ModelContract.Failure("MODEL_INVALID_USAGE");
    }
    String content = generation.getOutput().getText();
    if (content == null || content.length() > MAX_TEXT_CHARS) {
      throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    }
    SentimentPrompt.Payload payload;
    try {
      // 不使用 converter.convert：它会清理代码块，解析失败时还可能记录原始响应。
      // 本契约只接受纯 JSON；BeanOutputConverter 只用于生成固定 Schema 提示。
      payload = OUTPUT.readValue(content);
    } catch (Exception ignored) {
      throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    }
    if (payload == null
        || payload.classifications() == null
        || payload.classifications().size() > 100) {
      throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    }
    String requestId = metadata.getId();
    if (requestId != null && requestId.isBlank()) {
      requestId = null;
    }
    return new ModelContract.Response(
        payload.classifications(), usage.promptTokens(), usage.completionTokens(), requestId);
  }
}
