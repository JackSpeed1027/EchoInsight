/* 文件职责：验证UP主URL边界、关键词候选身份、严格JSON以及工具输出不能扩大范围。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 所有输入均为合成字节，不访问B站。 */
class CreatorDiscoveryTest {
  private final CreatorDiscovery adapter = new CreatorDiscovery(new ObjectMapper());

  /** 允许官方空间查询参数，不允许编码路径、凭据、非HTTPS或伪造主机。 */
  @Test
  void acceptsOnlyCanonicalCreatorIdentity() {
    assertThat(adapter.owner("https://space.bilibili.com/123/video?foo=bar")).isEqualTo("123");
    for (String url :
        new String[] {
          "http://space.bilibili.com/123",
          "https://space.bilibili.com.evil/123",
          "https://x@space.bilibili.com/123",
          "https://space.bilibili.com:443/123",
          "https://space.bilibili.com/%31%32%33",
          "https://space.bilibili.com/123/other",
          "https://space.bilibili.com/0"
        })
      assertThatThrownBy(() -> adapter.owner(url))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 返回上限与无结果分别给出截断/未知，不作全量承诺。 */
  @Test
  void distinguishesCapFromUnknownCoverage() {
    assertThat(adapter.parse(rows("123"), "123", 1).completeness()).isEqualTo("TRUNCATED");
    assertThat(adapter.parse("[]".getBytes(StandardCharsets.UTF_8), "123", 20).completeness())
        .isEqualTo("NOT_PROVEN");
    assertThat(adapter.parse(rows("123"), "123", 2).candidates().getFirst().url())
        .isEqualTo("https://www.bilibili.com/video/BV1ntuF64EhF/");
  }

  /** 他人作品、重复作品和超出上限均拒绝，不能让工具结果扩大用户指定范围。 */
  @Test
  void rejectsForeignDuplicateAndOverLimitResults() {
    assertThatThrownBy(() -> adapter.parse(rows("456"), "123", 2))
        .isInstanceOf(CollectionFailure.class);
    String row = new String(rows("123"), StandardCharsets.UTF_8);
    String duplicate =
        "[" + row.substring(1, row.length() - 1) + "," + row.substring(1, row.length() - 1) + "]";
    assertThatThrownBy(() -> adapter.parse(duplicate.getBytes(StandardCharsets.UTF_8), "123", 2))
        .isInstanceOf(CollectionFailure.class);
    assertThatThrownBy(() -> adapter.parse(duplicate.getBytes(StandardCharsets.UTF_8), "123", 1))
        .isInstanceOf(CollectionFailure.class);
  }

  /** 重复键、尾随对象和非法UTF8均不能作为正常候选发布。 */
  @Test
  void refusesAmbiguousOrInvalidEncoding() {
    assertThatThrownBy(
            () ->
                adapter.parse(
                    "[{\"bvid\":\"x\",\"bvid\":\"y\"}]".getBytes(StandardCharsets.UTF_8), "123", 2))
        .isInstanceOf(CollectionFailure.class);
    assertThatThrownBy(() -> adapter.parse("[] {}".getBytes(StandardCharsets.UTF_8), "123", 2))
        .isInstanceOf(CollectionFailure.class);
    assertThatThrownBy(() -> adapter.parse(new byte[] {(byte) 0xc3, 0x28}, "123", 2))
        .isInstanceOf(CollectionFailure.class);
  }

  /** 输出格式匹配已核实的CLI v0.3.0 UserVideos记录，不含用户昵称等额外字段。 */
  static byte[] rows(String mid) {
    return ("[{\"bvid\":\"BV1ntuF64EhF\",\"aid\":123,\"owner_mid\":\""
            + mid
            + "\",\"title\":\"合成作品\",\"pubdate\":123}]")
        .getBytes(StandardCharsets.UTF_8);
  }
}
