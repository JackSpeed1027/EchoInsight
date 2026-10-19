/* 文件职责：用受控HTTP响应验证固定URL、白名单正文、平台拒绝和响应体边界，不访问真实平台。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** 传输替身只返回内存字节，不启动网络服务器或读取凭据。 */
class BilibiliPageClientTest {
  private final ObjectMapper json = new ObjectMapper();

  /** 获取元数据时只请求固定域/路径，返回不包含昵称等非必要字段。 */
  @Test
  void validatesIdentityAndWhitelistsMetadata() throws Exception {
    HttpClient transport =
        transport(
            200,
            "{\"code\":0,\"data\":{\"aid\":123,\"bvid\":\"BV1ntuF64EhF\",\"owner\":{\"mid\":1,\"name\":\"不保存昵称\"},\"title\":\"合成\",\"pubdate\":1785810000,\"stat\":{\"reply\":3}}}");
    var client = new BilibiliPageClient(json, transport);
    var page =
        client.metadata(
            new BilibiliUrlParser().parse("https://www.bilibili.com/video/BV1ntuF64EhF"));
    assertThat(page.reported()).isEqualTo(3);
    assertThat(page.records().toString()).doesNotContain("不保存昵称", "owner\"");
    verify(transport)
        .sendAsync(
            argThat(
                r ->
                    r.uri().getHost().equals("api.bilibili.com")
                        && r.uri().getPath().equals("/x/web-interface/view")
                        && r.headers()
                            .firstValue("Cookie")
                            .orElse("")
                            .equals("buvid3=anonymous-3; buvid4=anonymous-4")),
            any(HttpResponse.BodyHandler.class));
  }

  /** 平台风险拒绝只发一次业务请求（另有一次匿名握手），不解释为空评论，也不自动换身份重试。 */
  @Test
  void riskRefusalIsNotEmptySuccessOrAutomaticRetry() {
    HttpClient transport = transport(200, "{\"code\":-352,\"message\":\"refused\"}");
    var client = new BilibiliPageClient(json, transport);
    assertThatThrownBy(() -> client.replies("123", "10", "1"))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("PLATFORM_RISK_CONTROL");
    verify(transport, times(2)).sendAsync(any(), any(HttpResponse.BodyHandler.class));
  }

  /** 返回中途空页却仍声明后续数据时拒绝，防止误判遍历完成。 */
  @Test
  void anomalousEmptyReplyPageIsRejected() {
    var client =
        new BilibiliPageClient(
            json,
            transport(
                200,
                "{\"code\":0,\"data\":{\"page\":{\"count\":30,\"num\":1,\"size\":20},\"replies\":[]}}"));
    assertThatThrownBy(() -> client.replies("123", "10", "1")).hasMessage("INVALID_PAGE_CURSOR");
  }

  /** 来源root不同的回复不能混入当前楼，原parent仍需保留由整体闭包校验。 */
  @Test
  void crossThreadResponseIsRejected() {
    var client =
        new BilibiliPageClient(
            json,
            transport(
                200,
                "{\"code\":0,\"data\":{\"page\":{\"count\":1,\"num\":1,\"size\":20},\"replies\":[{\"root\":99}]}}"));
    assertThatThrownBy(() -> client.replies("123", "10", "1")).hasMessage("SOURCE_ID_MISMATCH");
  }

  /** 响应体超过5MiB立即取消订阅；未完整结束的响应不成为成功字节。 */
  @Test
  void boundedSubscriberCancelsOversizedBody() {
    var body = new BilibiliPageClient.LimitedBody();
    Flow.Subscription subscription = mock(Flow.Subscription.class);
    body.onSubscribe(subscription);
    body.onNext(List.of(ByteBuffer.wrap(new byte[BilibiliCliAdapter.MAX_JSON_BYTES + 1])));
    verify(subscription).cancel();
    assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
        .hasCauseInstanceOf(CollectionFailure.class);
  }

  /** 正常分块按原顺序拼接，网络半包异常保持失败语义。 */
  @Test
  void subscriberPreservesChunksAndRejectsIncompleteNetworkBody() {
    var body = new BilibiliPageClient.LimitedBody();
    body.onSubscribe(mock(Flow.Subscription.class));
    body.onNext(
        List.of(
            ByteBuffer.wrap("[".getBytes(StandardCharsets.UTF_8)),
            ByteBuffer.wrap("]".getBytes(StandardCharsets.UTF_8))));
    assertThat(body.getBody().toCompletableFuture().isDone()).isFalse();
    body.onComplete();
    assertThat(new String(body.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8))
        .isEqualTo("[]");
    var failed = new BilibiliPageClient.LimitedBody();
    failed.onSubscribe(mock(Flow.Subscription.class));
    failed.onError(new java.io.IOException("synthetic"));
    assertThatThrownBy(() -> failed.getBody().toCompletableFuture().join())
        .hasCauseInstanceOf(java.io.IOException.class);
  }

  /** 匿名握手被拒绝时直接停止，不继续请求业务页，也不读取本机用户Cookie。 */
  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void anonymousHandshakeRefusalStopsBeforeBusinessPage() {
    HttpClient transport = mock(HttpClient.class);
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(412);
    when(response.body()).thenReturn(new byte[0]);
    when(transport.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    var client = new BilibiliPageClient(json, transport);
    assertThatThrownBy(() -> client.replies("123", "10", "1")).hasMessage("PLATFORM_RISK_CONTROL");
    verify(transport, times(1))
        .sendAsync(
            argThat(
                r ->
                    r.uri().getPath().equals("/x/frontend/finger/spi")
                        && r.headers().firstValue("Cookie").isEmpty()),
            any(HttpResponse.BodyHandler.class));
  }

  /** Mockito HTTP边界返回完整内存响应，保持业务解析与协议检查真实执行。 */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private HttpClient transport(int status, String body) {
    HttpClient transport = mock(HttpClient.class);
    HttpResponse<byte[]> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
    when(transport.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            call -> {
              HttpRequest request = call.getArgument(0);
              if (request.uri().getPath().equals("/x/frontend/finger/spi")) {
                HttpResponse<byte[]> anonymous = mock(HttpResponse.class);
                when(anonymous.statusCode()).thenReturn(200);
                when(anonymous.body())
                    .thenReturn(
                        "{\"code\":0,\"data\":{\"b_3\":\"anonymous-3\",\"b_4\":\"anonymous-4\"}}"
                            .getBytes(StandardCharsets.UTF_8));
                return CompletableFuture.completedFuture(anonymous);
              }
              return CompletableFuture.completedFuture(response);
            });
    return transport;
  }
}
