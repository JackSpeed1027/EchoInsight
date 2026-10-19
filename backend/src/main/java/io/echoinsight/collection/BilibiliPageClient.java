/* 文件职责：按固定 B站公开接口读取单页，返回不含昵称等个人资料的统一评论；不登录、不自动重试。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** 单页客户端与持久分页编排解耦，测试可替换网络边界；沿用已核对的 bilibili-cli v0.3.0 接口契约。 */
@Component
public class BilibiliPageClient {
  private static final String BASE = "https://api.bilibili.com";
  private static final int[] MIX = {
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29,
    28, 14, 39, 12, 38, 41, 13
  };
  private final ObjectMapper json;
  private final HttpClient http;
  private long nextRequestNanos;
  private String anonymousCookie;
  private String mixin;
  private Instant keyFetched;

  /** 页记录只保存白名单正文和分页事实，sourceHash 用于追溯返回身份，不将响应头或 Cookie 放进业务数据。 */
  public record Page(
      JsonNode records, String next, boolean terminal, int reported, String sourceHash) {}

  /** 使用独立严格解析器，不改变模型、旧导入或指纹的 JSON 行为。 */
  @org.springframework.beans.factory.annotation.Autowired
  public BilibiliPageClient(ObjectMapper mapper) {
    this(
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
  }

  /** 测试注入受控传输；生产固定禁止重定向，不从 API 接受地址或 HTTP 客户端。 */
  BilibiliPageClient(ObjectMapper mapper, HttpClient http) {
    this.http = http;
    json =
        mapper
            .copy()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    json.getFactory()
        .setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(20000)
                .build());
  }

  /** 获取作品身份、总评论声明和发布时间；BV/av 已由 URL 白名单验证。 */
  public Page metadata(BilibiliUrlParser.Target target) {
    String id = target.sourceId();
    JsonNode raw =
        request(
            "/x/web-interface/view",
            Map.of(
                id.startsWith("av") ? "aid" : "bvid", id.startsWith("av") ? id.substring(2) : id),
            false);
    JsonNode data = raw.path("data");
    ObjectNode video = json.createObjectNode();
    copy(data, video, "aid", "bvid", "title", "pubdate");
    if (!id.equals(data.path("bvid").asText()) && !id.equals("av" + number(data.path("aid"))))
      throw new CollectionFailure("SOURCE_ID_MISMATCH");
    video.set("owner_mid", data.path("owner").path("mid"));
    video.set("reply_count", data.path("stat").path("reply"));
    ArrayNode records = json.createArrayNode().add(video);
    return new Page(records, "", true, count(video.path("reply_count")), hash(raw));
  }

  /** 1、固定按时间遍历根评论；2、保留置顶；3、要求平台明确提供终止标记与下一游标。 */
  public Page roots(String aid, String cursor) {
    numeric(aid);
    numeric(cursor);
    JsonNode raw =
        request(
            "/x/v2/reply/wbi/main",
            Map.of("oid", aid, "type", "1", "mode", "2", "next", cursor),
            true);
    JsonNode data = raw.path("data");
    JsonNode state = data.path("cursor");
    if (!state.path("is_end").isBoolean()) throw new CollectionFailure("INVALID_PAGE_CURSOR");
    ArrayNode records = json.createArrayNode();
    JsonNode top = data.path("top").path("upper");
    if ("0".equals(cursor) && top.isObject() && top.path("rpid").asLong() != 0)
      records.add(comment(top, aid));
    for (JsonNode node : array(data.path("replies"))) records.add(comment(node, aid));
    boolean terminal = state.path("is_end").booleanValue();
    String next = terminal ? "" : number(state.path("next"));
    if (!terminal && (next.equals(cursor) || records.isEmpty()))
      throw new CollectionFailure("INVALID_PAGE_CURSOR");
    return new Page(records, next, terminal, 0, hash(raw));
  }

  /** 楼中楼分页独立于根评论；空页但声明仍有后续时拒绝，不把异常空页当成结束。 */
  public Page replies(String aid, String root, String page) {
    numeric(aid);
    numeric(root);
    numeric(page);
    JsonNode raw =
        request(
            "/x/v2/reply/reply",
            Map.of("oid", aid, "type", "1", "root", root, "pn", page, "ps", "20"),
            false);
    JsonNode data = raw.path("data");
    JsonNode state = data.path("page");
    int reported = count(state.path("count")),
        size = count(state.path("size")),
        current = count(state.path("num"));
    if (size < 1 || current != Integer.parseInt(page))
      throw new CollectionFailure("INVALID_PAGE_CURSOR");
    ArrayNode records = json.createArrayNode();
    for (JsonNode node : array(data.path("replies"))) {
      if (!root.equals(number(node.path("root"))))
        throw new CollectionFailure("SOURCE_ID_MISMATCH");
      records.add(comment(node, aid));
    }
    boolean terminal = (long) current * size >= reported;
    if (!terminal && records.isEmpty()) throw new CollectionFailure("INVALID_PAGE_CURSOR");
    return new Page(
        records, terminal ? "" : Integer.toString(current + 1), terminal, reported, hash(raw));
  }

  /** 不嵌入预览回复，避免同一楼的预览和分页正文重复入库；原 parent/root 原样保留。 */
  private ObjectNode comment(JsonNode raw, String aid) {
    if (!aid.equals(number(raw.path("oid"))) || count(raw.path("type")) != 1)
      throw new CollectionFailure("SOURCE_ID_MISMATCH");
    ObjectNode node = json.createObjectNode();
    copy(raw, node, "rpid", "oid", "type", "parent", "root", "ctime");
    node.set("content", raw.path("content").path("message"));
    node.set("reply_count", raw.path("rcount"));
    return node;
  }

  /** 1、全局串行限速；2、固定域与路径出站；3、有界读取；4、拒绝平台错误，禁止自动重试或改身份。 */
  private synchronized JsonNode request(String path, Map<String, String> params, boolean signed) {
    if (!path.equals("/x/frontend/finger/spi") && anonymousCookie == null) loadAnonymousSession();
    if (signed && (mixin == null || Duration.between(keyFetched, Instant.now()).toHours() >= 6))
      loadKeys();
    TreeMap<String, String> query = new TreeMap<>(params);
    if (signed) {
      query.put("wts", Long.toString(Instant.now().getEpochSecond()));
      query.put("w_rid", digest("MD5", encode(query) + mixin));
    }
    try {
      long wait = nextRequestNanos - System.nanoTime();
      if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
      nextRequestNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder(URI.create(BASE + path + "?" + encode(query)))
              .timeout(Duration.ofSeconds(15))
              .header(
                  "User-Agent",
                  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                      + " Chrome/124.0.0.0 Safari/537.36")
              .header("Referer", "https://www.bilibili.com/")
              .GET();
      if (anonymousCookie != null) requestBuilder.header("Cookie", anonymousCookie);
      HttpRequest request = requestBuilder.build();
      CompletableFuture<HttpResponse<byte[]>> flight =
          http.sendAsync(request, info -> new LimitedBody());
      HttpResponse<byte[]> response;
      try {
        response = flight.get(20, java.util.concurrent.TimeUnit.SECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        flight.cancel(true);
        throw new CollectionFailure("TOOL_TIMEOUT", e);
      } catch (InterruptedException e) {
        flight.cancel(true);
        throw e;
      } catch (java.util.concurrent.ExecutionException e) {
        if (e.getCause() instanceof CollectionFailure failure) throw failure;
        throw new CollectionFailure("NETWORK_FAILURE", e);
      }
      byte[] bytes = response.body();
      if (response.statusCode() == 429) throw new CollectionFailure("RATE_LIMITED");
      if (response.statusCode() == 403 || response.statusCode() == 412)
        throw new CollectionFailure("PLATFORM_RISK_CONTROL");
      if (response.statusCode() != 200) throw new CollectionFailure("NETWORK_FAILURE");
      JsonNode envelope = json.readTree(bytes);
      if (envelope == null
          || !envelope.path("code").isIntegralNumber()
          || !envelope.path("code").canConvertToInt())
        throw new CollectionFailure("INVALID_TOOL_OUTPUT");
      int code = envelope.path("code").intValue();
      if (code != 0
          && !(path.equals("/x/web-interface/nav")
              && code == -101
              && envelope.path("data").path("wbi_img").isObject())) {
        throw new CollectionFailure(
            switch (code) {
              case -352, -412, -403 -> "PLATFORM_RISK_CONTROL";
              case -509 -> "RATE_LIMITED";
              case -404, 62002 -> "SOURCE_NOT_FOUND";
              default -> "PLATFORM_SILENT_REFUSAL";
            });
      }
      if (!envelope.path("data").isObject()) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
      return envelope;
    } catch (CollectionFailure e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CollectionFailure("COLLECTION_INTERRUPTED", e);
    } catch (IOException e) {
      throw new CollectionFailure("NETWORK_FAILURE", e);
    }
  }

  /** 普通公开匿名握手，与固定版本CLI一致；只接受平台返回的buvid令牌，不访问本机浏览器或登录凭据。 */
  private void loadAnonymousSession() {
    JsonNode session = request("/x/frontend/finger/spi", Map.of(), false).path("data");
    String first = session.path("b_3").asText(), second = session.path("b_4").asText();
    if (!first.matches("[A-Za-z0-9_-]{1,256}") || !second.matches("[A-Za-z0-9_-]{1,256}"))
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    anonymousCookie = "buvid3=" + first + "; buvid4=" + second;
  }

  /** 从平台公开 nav 获取临时签名键；不持久保存用户登录状态，不伪造设备指纹。 */
  private void loadKeys() {
    JsonNode keys = request("/x/web-interface/nav", Map.of(), false).path("data").path("wbi_img");
    String raw = stem(keys.path("img_url").asText()) + stem(keys.path("sub_url").asText());
    if (raw.length() != 64 || !raw.matches("[a-fA-F0-9]{64}"))
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    StringBuilder mixed = new StringBuilder();
    for (int position : MIX) mixed.append(raw.charAt(position));
    mixin = mixed.toString();
    keyFetched = Instant.now();
  }

  /** 签名只涉及受限数值参数；排序与 URL 编码对应固定版本上游算法。 */
  static String encode(Map<String, String> values) {
    return new TreeMap<>(values)
        .entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)
                            .replace("+", "%20"))
            .collect(java.util.stream.Collectors.joining("&"));
  }

  /** 提取键文件名，不请求键图片。 */
  private String stem(String value) {
    int slash = value.lastIndexOf('/'), dot = value.lastIndexOf('.');
    return dot > slash ? value.substring(slash + 1, dot) : "";
  }

  /** 只接受非负无损整数，杜绝游标或作品身份变为任意查询。 */
  private String number(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return node.asText();
  }

  /** 计数需要适配数据库及分页容量，拒绝负数和截断。 */
  private int count(JsonNode node) {
    number(node);
    if (!node.canConvertToInt()) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return node.intValue();
  }

  /** 已保存游标仍需验证，不能信任数据库外来的任意字符串。 */
  private void numeric(String value) {
    if (value == null || !value.matches("[0-9]{1,19}"))
      throw new CollectionFailure("INVALID_PAGE_CURSOR");
  }

  /** 空 replies 允许表示无记录；其他形状拒绝。 */
  private JsonNode array(JsonNode value) {
    if (value.isNull()) return json.createArrayNode();
    if (!value.isArray()) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return value;
  }

  /** 只复制来源白名单字段。 */
  private void copy(JsonNode from, ObjectNode to, String... fields) {
    for (String field : fields) to.set(field, from.path(field));
  }

  /** 记录来源响应的确定性身份，不将整份含个人资料的响应写入业务表。 */
  private String hash(JsonNode value) {
    return digest("SHA-256", value.toString());
  }

  /** MD5 仅供平台签名，SHA-256 供页校验，均不是密码存储。 */
  private static String digest(String algorithm, String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 在网络层逐块限流内存；超5MiB立即取消订阅，sendAsync的总期限覆盖响应体而不只响应头。 */
  static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> done = new CompletableFuture<>();
    private Flow.Subscription subscription;

    /** 结果仅在响应完整结束时可用，异常不能成为成功页。 */
    public CompletionStage<byte[]> getBody() {
      return done;
    }

    /** 每次只请求一个块列表，消费完成再取下一块。 */
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    /** 拒绝超大响应，取消后不再保留新字节。 */
    public void onNext(List<ByteBuffer> blocks) {
      for (ByteBuffer block : blocks) {
        if (done.isDone()) return;
        if (block.remaining() > BilibiliCliAdapter.MAX_JSON_BYTES - kept.size()) {
          subscription.cancel();
          done.completeExceptionally(new CollectionFailure("TOOL_OUTPUT_LIMIT"));
          return;
        }
        byte[] bytes = new byte[block.remaining()];
        block.get(bytes);
        kept.writeBytes(bytes);
      }
      subscription.request(1);
    }

    /** 网络异常保留失败语义，不返回已读取的半份JSON。 */
    public void onError(Throwable failure) {
      done.completeExceptionally(failure);
    }

    /** 正常收尾后交付完整且有界的字节数组。 */
    public void onComplete() {
      done.complete(kept.toByteArray());
    }
  }
}
