/* 文件职责：为受控供应商复用有界响应、禁跳转和直连传输，防止 SDK 输出敏感原文。 */
package io.echoinsight.model.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.ModelContract;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 只提供传输，不决定端点白名单；调用方必须固定或严格校验目标地址。 */
final class BoundedModelHttp {
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  /** 静态工具不持有模型凭据。 */
  private BoundedModelHttp() {}

  /** 以独立超时、无代理和无跳转构造客户端；所有响应在交给 SDK 前限制为 1 MiB。 */
  static RestClient.Builder create(Duration timeout) {
    // 1、不继承系统代理；禁止通过 3xx 将密钥或输入转发到其他地址。
    var client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(
                new ProxySelector() {
                  /** 显式直连工厂确定的目标，不继承操作系统代理。 */
                  @Override
                  public List<java.net.Proxy> select(URI uri) {
                    return List.of(java.net.Proxy.NO_PROXY);
                  }

                  @Override
                  public void connectFailed(URI uri, SocketAddress address, IOException error) {
                    // 不记录请求材料或代理细节，连接失败由上层账本统一处理。
                  }
                })
            .build();
    var requests = new JdkClientHttpRequestFactory(client);
    requests.setReadTimeout(timeout);
    return RestClient.builder()
        .requestFactory(requests)
        .requestInterceptor(
            (request, body, execution) -> new LimitedResponse(execution.execute(request, body)));
  }

  /** 保留响应状态与头，只给解码器提供已经有界检查的正文，避免错误服务无限返回正文。 */
  private static final class LimitedResponse implements ClientHttpResponse {
    private final ClientHttpResponse delegate;
    private final InputStream body;

    /** 立即包装正文；超过上限时抛 IOException，由外层标记调用结果未知。 */
    private LimitedResponse(ClientHttpResponse delegate) throws IOException {
      this.delegate = delegate;
      // 先有界读取并核对外壳，防止 SDK 在缺少 choices 时将完整 Prompt 写入警告日志。
      byte[] bytes;
      try {
        bytes = delegate.getBody().readNBytes(MAX_BODY_BYTES + 1);
      } catch (IOException error) {
        delegate.close();
        throw error;
      }
      if (bytes.length > MAX_BODY_BYTES) {
        delegate.close();
        throw new ModelContract.Failure("MODEL_RESPONSE_TOO_LARGE");
      }
      if (!delegate.getStatusCode().is2xxSuccessful()) {
        // 供应商错误正文可能回显用户材料；在 SDK 错误处理器记录正文之前转为安全错误码。
        delegate.close();
        throw new ModelContract.Failure("MODEL_HTTP_ERROR");
      }
      if (delegate.getStatusCode().is2xxSuccessful()) {
        try {
          var envelope = new ObjectMapper().readTree(bytes);
          if (envelope == null
              || !envelope.path("choices").isArray()
              || envelope.path("choices").size() != 1
              || !envelope.path("choices").get(0).path("message").isObject()) {
            throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
          }
        } catch (IOException error) {
          delegate.close();
          throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
        } catch (RuntimeException error) {
          delegate.close();
          throw error;
        }
      }
      this.body = new ByteArrayInputStream(bytes);
    }

    /** 状态码供 SDK 原有错误处理使用，不改写为成功。 */
    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return delegate.getStatusCode();
    }

    /** 状态描述仅在框架内部使用，应用不输出响应正文。 */
    @Override
    public String getStatusText() throws IOException {
      return delegate.getStatusText();
    }

    /** 透传框架需要的响应头，不向业务 API 暴露供应商头。 */
    @Override
    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    /** 返回同一有界流，避免重复包装后重置计数。 */
    @Override
    public InputStream getBody() {
      return body;
    }

    /** 仅关闭本次响应资源，不管理应用服务或用户文件。 */
    @Override
    public void close() {
      delegate.close();
    }
  }
}
