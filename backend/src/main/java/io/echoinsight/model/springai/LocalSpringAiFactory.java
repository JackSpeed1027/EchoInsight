/* 文件职责：只为本机 HTTP 故障试验构造单次尝试的真实 Spring AI 模型，禁止外部端点。 */
package io.echoinsight.model.springai;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

/**
 * 验证框架真实 HTTP 行为的隔离工厂，不是正式供应商配置，也不注册为 Spring Bean。
 *
 * <p>只接受带端口的字面回环地址，使用无效的测试占位密钥；禁代理和跳转，最多读取 1 MiB。 SDK 自动重试和内部工具执行关闭；正式供应商接入需要独立配置与验收。
 */
public final class LocalSpringAiFactory {

  /** 工厂不持有应用状态，避免被自动扫描成可联网供应商。 */
  private LocalSpringAiFactory() {}

  /**
   * 创建固定模型及 HTTP 超时的本机试验实例，构造本身不发送请求。
   *
   * @param endpoint 本机根地址，不接受路径、查询、片段、凭据或域名
   * @param modelVersion 测试模型身份，响应必须原样声明
   * @param timeout 50 ms～5 分钟，控制底层 HTTP；任务总等待仍由网关按固定计划限制
   * @return 关闭 SDK 自动重试及工具执行的适配器
   */
  public static SpringAiClassifierProvider create(
      URI endpoint, String modelVersion, Duration timeout) {
    if (endpoint == null
        || !"http".equals(endpoint.getScheme())
        || !List.of("127.0.0.1", "[::1]").contains(endpoint.getHost())
        || endpoint.getPort() < 1
        || endpoint.getPort() > 65535
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || !(endpoint.getRawPath().isEmpty() || "/".equals(endpoint.getRawPath()))) {
      throw new IllegalArgumentException("Only literal loopback HTTP roots are allowed");
    }
    if (timeout == null || timeout.toMillis() < 50 || timeout.toMillis() > 300000) {
      throw new IllegalArgumentException("Invalid HTTP timeout");
    }
    var rest = BoundedModelHttp.create(timeout);
    // 2、使用普通模型模块手动装配；不引入需要真实密钥的自动配置 starter。
    var api =
        OpenAiApi.builder()
            .baseUrl(endpoint.toString().replaceAll("/$", ""))
            .apiKey("local-stub-unused")
            .restClientBuilder(rest)
            .build();
    var model =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(modelVersion)
                    .internalToolExecutionEnabled(false)
                    .build())
            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
            .build();
    return new SpringAiClassifierProvider(model, modelVersion);
  }
}
