/* 文件职责：仅向本机回环 HTTP Stub 发送类型化请求，用于慢响应与坏输出实验。 */
package io.echoinsight.model;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 不注册为生产 Bean、不接受浏览器 URL、不携带密钥，不能访问外部付费服务。 */
public class LocalStubHttpProvider implements ModelContract.Provider {
  private final URI endpoint;
  private final HttpClient client;
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

  /** 只允许明确的 IP 回环地址，关闭重定向，避免 Stub 逃逸到外部地址。 */
  public LocalStubHttpProvider(URI endpoint) {
    if (!"http".equals(endpoint.getScheme())
        || !("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost()))
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("Only loopback HTTP stubs are supported");
    this.endpoint = endpoint;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** 本机故障供应商名称。 */
  @Override
  public String id() {
    return "LOCAL_HTTP_STUB";
  }

  /** 固定实验模型版本，不冒充任何真实模型。 */
  @Override
  public String modelVersion() {
    return "local-http-stub-v1";
  }

  /** 1、发送 JSON；2、限制响应体；3、严格解析；语义完整性由网关统一检查。 */
  @Override
  public ModelContract.Response classify(ModelContract.Request request, Duration timeout)
      throws Exception {
    var outbound =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    ModelContract.encode(request), StandardCharsets.UTF_8))
            .build();
    var response = client.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      byte[] bytes = body.readNBytes(1024 * 1024 + 1);
      if (response.statusCode() != 200 || bytes.length > 1024 * 1024)
        throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
      try {
        return json.readValue(bytes, ModelContract.Response.class);
      } catch (Exception e) {
        throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
      }
    }
  }
}
