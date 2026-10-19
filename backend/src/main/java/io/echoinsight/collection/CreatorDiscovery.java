/* 文件职责：把官方UP主URL和受限CLI输出变成可选择的作品目录，不把关键词检索等同语义分类。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 只接收space.bilibili.com下的数字身份；工具输出不能决定后续网络地址或命令。 */
@Component
public class CreatorDiscovery {
  private final ObjectMapper parser;

  /** 外部数据独立严格解析，拒绝重复键、尾随JSON、非法UTF8和过深结构。 */
  public CreatorDiscovery(ObjectMapper json) {
    parser =
        json.copy()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    parser
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(20000).build());
  }

  /** 候选身份由BV号和aid双重记录，URL由服务器重新构造。 */
  public record Candidate(String bvid, String aid, String title, long publishedAt, String url) {}

  /** 原始候选数与完整性分开；空结果不表示用户没有其他作品。 */
  public record Result(
      List<Candidate> candidates,
      String completeness,
      String selectionMethod,
      List<String> limitations) {}

  /** 允许空间首页和投稿页；查询参数可舍弃，端口、用户信息及其他路径拒绝。 */
  public String owner(String url) {
    try {
      if (url == null || url.length() > 500) throw new IllegalArgumentException();
      URI uri = URI.create(url.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || !"space.bilibili.com".equalsIgnoreCase(uri.getHost())
          || uri.getPort() != -1
          || uri.getUserInfo() != null
          || !uri.getRawPath().matches("/[1-9][0-9]{0,18}(/video)?/?"))
        throw new IllegalArgumentException();
      return uri.getPath().split("/")[1];
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入B站UP主空间的HTTPS官方链接");
    }
  }

  /** 1、检查输出上限；2、严格解析；3、核对作者和唯一作品；4、明确关键词与完整性边界。 */
  public Result parse(byte[] raw, String mid, int limit) {
    try {
      if (raw.length > BilibiliCliAdapter.MAX_JSON_BYTES)
        throw new CollectionFailure("TOOL_OUTPUT_LIMIT");
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(raw))
              .toString();
      JsonNode rows = parser.readTree(text);
      if (rows == null || !rows.isArray() || rows.size() > limit)
        throw new CollectionFailure("INVALID_DISCOVERY_OUTPUT");
      List<Candidate> candidates = new ArrayList<>();
      Set<String> bvIds = new HashSet<>(), aids = new HashSet<>();
      for (var row : rows) {
        String bvid = row.path("bvid").asText(),
            aid = row.path("aid").asText(),
            title = row.path("title").asText();
        if (!bvid.matches("BV[0-9A-Za-z]{10}")
            || !aid.matches("[1-9][0-9]{0,18}")
            || !mid.equals(row.path("owner_mid").asText())
            || title.isBlank()
            || title.length() > 1000
            || !row.path("pubdate").canConvertToLong()
            || row.path("pubdate").asLong() < 0
            || !bvIds.add(bvid)
            || !aids.add(aid)) throw new CollectionFailure("DISCOVERY_IDENTITY_MISMATCH");
        candidates.add(
            new Candidate(
                bvid,
                aid,
                title,
                row.path("pubdate").asLong(),
                "https://www.bilibili.com/video/" + bvid + "/"));
      }
      return new Result(
          List.copyOf(candidates),
          candidates.size() == limit ? "TRUNCATED" : "NOT_PROVEN",
          "PLATFORM_KEYWORD",
          List.of(
              "仅按平台关键词检索最新投稿，不代表模型已经判定主题，也不表示全空间已检索。",
              "固定从第1页取最多20个候选，CLI页大小30；没有采集评论、弹幕或字幕。",
              "平台拒绝和超时会保留失败，不能解释成没有符合条件的作品。"));
    } catch (CollectionFailure e) {
      throw e;
    } catch (Exception e) {
      throw new CollectionFailure("INVALID_DISCOVERY_OUTPUT", e);
    }
  }
}
