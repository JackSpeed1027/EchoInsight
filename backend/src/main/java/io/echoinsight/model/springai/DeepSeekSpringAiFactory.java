/* 文件职责：构造唯一白名单 DeepSeek HTTPS 客户端，关闭推理、自动重试和工具执行。 */
package io.echoinsight.model.springai;

import io.echoinsight.model.ModelContract;
import java.time.Duration;
import java.util.Map;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.retry.support.RetryTemplate;

/** 固定官方根地址及兼容路径，不读取任意 URL；构造本身不发送网络请求。 */
public final class DeepSeekSpringAiFactory {
  /** 静态工厂不保存凭据副本。 */
  private DeepSeekSpringAiFactory() {}

  /** 1、采用已校验计划的超时；2、固定 JSON 和非思考模式；3、SDK 最多尝试一次。 密钥只交给 SDK 请求头，不能进入计划、报告、接口或日志。 */
  public static SpringAiClassifierProvider create(String apiKey, ModelContract.Plan plan) {
    var model = model(apiKey, plan);
    return new SpringAiClassifierProvider(model, plan.modelVersion(), plan.expectedResponseModel());
  }

  /** 为讨论语义协议创建同样受限的客户端；它仍由共享网关控制并发、预算和恢复。 */
  public static SpringAiDiscussionProvider createDiscussion(
      String apiKey, ModelContract.Plan plan) {
    var model = model(apiKey, plan);
    return new SpringAiDiscussionProvider(model, plan.modelVersion(), plan.expectedResponseModel());
  }

  /** 集中构造一次 OpenAI 兼容模型实例，两个业务协议不会复制网络安全配置。 */
  private static OpenAiChatModel model(String apiKey, ModelContract.Plan plan) {
    var api =
        OpenAiApi.builder()
            .baseUrl("https://api.deepseek.com")
            .completionsPath("/chat/completions")
            .apiKey(apiKey)
            .restClientBuilder(BoundedModelHttp.create(Duration.ofMillis(plan.timeoutMs())))
            .build();
    return OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(options(plan.modelVersion()))
        .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
        .build();
  }

  /** 原生 JSON 模式仍需本地严格校验；禁思考避免额外推理用量混入输出预算。 */
  static OpenAiChatOptions options(String model) {
    return OpenAiChatOptions.builder()
        .model(model)
        .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, (String) null))
        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
        .internalToolExecutionEnabled(false)
        .build();
  }
}
