/* 文件职责：把一次 Spring AI 讨论响应严格解析为类型化协议，并保留供应商原始用量。 */
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
import io.echoinsight.model.DiscussionModelContract;
import io.echoinsight.model.ModelContract;
import java.time.Duration;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * DeepSeek 的讨论语义适配器；只负责一次请求及响应转换，不负责任务编排或结论发布。
 *
 * <p>JSON 模式只能保证语法倾向，字段、枚举、引用闭包和用量仍由本地代码独立验证。
 */
public final class SpringAiDiscussionProvider implements DiscussionModelContract.Provider {
  public static final String ID = "SPRING_AI";
  private static final int MAX_TEXT_CHARS = 131072;
  private static final ObjectReader OUTPUT = strictOutputReader(DiscussionPrompt.Payload.class);
  private static final ObjectReader STANCE_OUTPUT =
      strictOutputReader(io.echoinsight.model.StanceProtocol.Payload.class);

  /** 创建拒绝未知字段、重复键、强制类型转换和尾随内容的解析器。 */
  static ObjectReader strictOutputReader(Class<?> type) {
    var mapper =
        JsonMapper.builder(
                JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                        StreamReadConstraints.builder()
                            .maxNestingDepth(24)
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
    return mapper.readerFor(type);
  }

  private final ChatModel model;
  private final String modelVersion;
  private final String responseModel;

  /** 请求名和响应名相同的简化构造器，主要用于本机响应适配测试。 */
  public SpringAiDiscussionProvider(ChatModel model, String modelVersion) {
    this(model, modelVersion, modelVersion);
  }

  /** 固定请求模型与期望响应模型；实例生命周期内不得漂移。 */
  public SpringAiDiscussionProvider(ChatModel model, String modelVersion, String responseModel) {
    this.model = Objects.requireNonNull(model);
    this.responseModel = Objects.requireNonNull(responseModel);
    if (modelVersion == null || modelVersion.isBlank() || modelVersion.length() > 128) {
      throw new IllegalArgumentException("Invalid model version");
    }
    this.modelVersion = modelVersion;
  }

  /** 返回网关白名单使用的供应商身份。 */
  @Override
  public String id() {
    return ID;
  }

  /** 返回任务计划中固定的请求模型名。 */
  @Override
  public String modelVersion() {
    return modelVersion;
  }

  /** 讨论提示或分类体系发生版本变化时拒绝恢复旧任务，避免同任务口径漂移。 */
  @Override
  public void validatePlan(ModelContract.Plan plan) {
    if (!ID.equals(plan.providerId())
        || !modelVersion.equals(plan.modelVersion())
        || !responseModel.equals(plan.expectedResponseModel())
        || !(DiscussionPrompt.CONTENT_VERSION.equals(plan.promptVersion())
                && DiscussionPrompt.CONTENT_TAXONOMY.equals(plan.taxonomyVersion())
            || io.echoinsight.intelligence.DiscussionDigestPlan.enabled(plan)
            || DiscussionPrompt.VERSION.equals(plan.promptVersion())
                && DiscussionPrompt.TAXONOMY.equals(plan.taxonomyVersion())
            || DiscussionPrompt.CONTEXT_VERSION.equals(plan.promptVersion())
                && DiscussionPrompt.CONTEXT_TAXONOMY.equals(plan.taxonomyVersion())
            || io.echoinsight.model.StanceProtocol.VERSION.equals(plan.promptVersion())
                && io.echoinsight.model.StanceProtocol.TAXONOMY.equals(plan.taxonomyVersion())
            || io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(plan.promptVersion())
                && io.echoinsight.intelligence.StanceChunkPlanner.TAXONOMY.equals(
                    plan.taxonomyVersion()))) {
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
    }
  }

  /**
   * 调用一次模型并提取类型化正文。
   *
   * <p>1、构造固定提示；2、拒绝多结果、工具调用、截断和拒答；3、精确核对响应模型与原始用量；4、只接受纯 JSON 正文。
   */
  @Override
  public DiscussionModelContract.Response analyze(
      DiscussionModelContract.Request request, Duration timeout) {
    return call(DiscussionPrompt.build(request, modelVersion), request.promptVersion(), timeout);
  }

  /** 分层归并沿用同一严格解析器、原生用量与单次出站，无工具或自动重试。 */
  @Override
  public DiscussionModelContract.Response digest(
      io.echoinsight.model.DiscussionDigestContract.Request request, Duration timeout) {
    return call(
        DiscussionDigestPrompt.build(request, modelVersion), request.promptVersion(), timeout);
  }

  /** 共享响应信封校验，旧v3～v6与新v7保持相同收费及错误语义。 */
  private DiscussionModelContract.Response call(
      org.springframework.ai.chat.prompt.Prompt prompt, String promptVersion, Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Timeout must be positive");
    }
    ChatResponse response = model.call(prompt);
    if (response == null || response.getResults().size() != 1 || response.hasToolCalls()) {
      throw new ModelContract.Failure("MODEL_RESPONSE_ENVELOPE");
    }
    var generation = response.getResult();
    if (generation == null
        || generation.getOutput() == null
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
    if (content == null || content.isBlank())
      throw new ModelContract.Failure("MODEL_RESPONSE_EMPTY");
    if (content.length() > MAX_TEXT_CHARS)
      throw new ModelContract.Failure("MODEL_RESPONSE_TOO_LARGE");
    String requestId = metadata.getId();
    if (requestId != null && requestId.isBlank()) requestId = null;
    if (io.echoinsight.model.StanceProtocol.VERSION.equals(promptVersion)
        || io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(promptVersion)) {
      try {
        io.echoinsight.model.StanceProtocol.Payload stance = STANCE_OUTPUT.readValue(content);
        if (stance == null || stance.decisions() == null)
          throw new ModelContract.Failure("MODEL_RESPONSE_SCHEMA");
        return new DiscussionModelContract.Response(
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            usage.promptTokens(),
            usage.completionTokens(),
            requestId,
            stance.decisions());
      } catch (com.fasterxml.jackson.core.JsonParseException error) {
        throw new ModelContract.Failure("MODEL_RESPONSE_JSON");
      } catch (ModelContract.Failure error) {
        throw error;
      } catch (Exception error) {
        throw new ModelContract.Failure("MODEL_RESPONSE_SCHEMA");
      }
    }
    DiscussionPrompt.Payload payload;
    try {
      payload = OUTPUT.readValue(content);
    } catch (com.fasterxml.jackson.core.JsonParseException ignored) {
      throw new ModelContract.Failure("MODEL_RESPONSE_JSON");
    } catch (Exception ignored) {
      throw new ModelContract.Failure("MODEL_RESPONSE_SCHEMA");
    }
    if (payload == null
        || payload.issues() == null
        || payload.claims() == null
        || payload.relations() == null
        || payload.assessments() == null) {
      throw new ModelContract.Failure("MODEL_RESPONSE_SCHEMA");
    }
    return new DiscussionModelContract.Response(
        payload.issues(),
        payload.claims(),
        payload.relations(),
        payload.assessments(),
        usage.promptTokens(),
        usage.completionTokens(),
        requestId);
  }
}
