/* 文件职责：单次Spring AI内容响应转换；严格解析JSON并提取真实用量，不保存任务或自动重试。 */
package io.echoinsight.model.springai;

import io.echoinsight.content.ContentFullContract;
import io.echoinsight.content.ContentModelContract;
import io.echoinsight.model.ModelContract;
import java.time.Duration;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

/** 复用讨论适配器的严格JSON规则，内容语义协议单独固定版本，旧评论提示不变。 */
public final class SpringAiContentProvider implements ContentModelContract.Provider {
  private static final com.fasterxml.jackson.databind.ObjectReader OUTPUT =
      SpringAiDiscussionProvider.strictOutputReader(ContentModelContract.Payload.class);
  private final ChatModel model;
  private final String modelVersion, responseModel;

  /** 客户端由原固定DeepSeek工厂构造，不接收用户端点或凭据。 */
  public SpringAiContentProvider(ChatModel model, String modelVersion, String responseModel) {
    this.model = Objects.requireNonNull(model);
    this.modelVersion = Objects.requireNonNull(modelVersion);
    this.responseModel = Objects.requireNonNull(responseModel);
  }

  /** 与原计划统一供应商身份，注册表按业务协议隔离。 */
  @Override
  public String id() {
    return "SPRING_AI";
  }

  /** 对同一个冻结计划精确匹配请求模型。 */
  @Override
  public String modelVersion() {
    return modelVersion;
  }

  /** 恢复不能换内容提示版本、输出体系或响应模型别名。 */
  @Override
  public void validatePlan(ModelContract.Plan plan) {
    if (!id().equals(plan.providerId())
        || !modelVersion.equals(plan.modelVersion())
        || !responseModel.equals(plan.expectedResponseModel())
        || !(ContentModelContract.PROMPT.equals(plan.promptVersion())
                && ContentModelContract.TAXONOMY.equals(plan.taxonomyVersion())
            || ContentFullContract.PROMPT.equals(plan.promptVersion())
                && ContentFullContract.TAXONOMY.equals(plan.taxonomyVersion())))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
  }

  /** 1、执行一次；2、验证单响应/完成状态/身份；3、验证原始用量；4、解析正文，字段与证据由网关再核对。 */
  @Override
  public ContentModelContract.Response analyze(
      ContentModelContract.Request request, Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Invalid timeout");
    return extract(model.call(ContentPrompt.build(request, modelVersion)));
  }

  /** 完整内容每片/汇总仍只出站一次，超时及发送状态由原网关负责。 */
  @Override
  public ContentModelContract.Response analyzeFull(
      ContentFullContract.Request request, Duration timeout) {
    if (timeout == null || timeout.isNegative() || timeout.isZero())
      throw new IllegalArgumentException("Invalid timeout");
    return extract(model.call(ContentFullPrompt.build(request, modelVersion)));
  }

  /** 共用同一响应元数据/严格JSON验证，不复制另一套收费或供应商错误处理。 */
  private ContentModelContract.Response extract(
      org.springframework.ai.chat.model.ChatResponse response) {
    if (response == null || response.getResults().size() != 1 || response.hasToolCalls())
      throw failure("MODEL_RESPONSE_ENVELOPE");
    var generation = response.getResult();
    if (generation == null
        || generation.getOutput() == null
        || !"stop".equalsIgnoreCase(generation.getMetadata().getFinishReason()))
      throw failure("MODEL_INCOMPLETE_RESPONSE");
    Object refusal = generation.getOutput().getMetadata().get("refusal");
    if (refusal instanceof String reason && !reason.isBlank())
      throw failure("MODEL_INCOMPLETE_RESPONSE");
    var metadata = response.getMetadata();
    if (!responseModel.equals(metadata.getModel())) throw failure("MODEL_ID_MISMATCH");
    if (!(metadata.getUsage().getNativeUsage() instanceof OpenAiApi.Usage usage)
        || usage.promptTokens() == null
        || usage.completionTokens() == null
        || usage.totalTokens() == null) throw failure("MODEL_USAGE_MISSING");
    if (usage.promptTokens() < 0
        || usage.completionTokens() < 0
        || usage.totalTokens() < 0
        || (long) usage.promptTokens() + usage.completionTokens() != usage.totalTokens())
      throw failure("MODEL_INVALID_USAGE");
    String body = generation.getOutput().getText();
    if (body == null || body.isBlank()) throw failure("MODEL_RESPONSE_EMPTY");
    if (body.length() > 131072) throw failure("MODEL_RESPONSE_TOO_LARGE");
    ContentModelContract.Payload payload;
    try {
      payload = OUTPUT.readValue(body);
    } catch (com.fasterxml.jackson.core.JsonParseException error) {
      throw failure("MODEL_RESPONSE_JSON");
    } catch (Exception error) {
      throw failure("MODEL_RESPONSE_SCHEMA");
    }
    if (payload == null) throw failure("MODEL_RESPONSE_SCHEMA");
    String requestId = metadata.getId();
    if (requestId != null && requestId.isBlank()) requestId = null;
    return new ContentModelContract.Response(
        payload.findings(),
        payload.uncertainties(),
        usage.promptTokens(),
        usage.completionTokens(),
        requestId);
  }

  /** 只暴露受控诊断，不回显供应商响应或用户正文。 */
  private ModelContract.Failure failure(String code) {
    return new ModelContract.Failure(code);
  }
}
