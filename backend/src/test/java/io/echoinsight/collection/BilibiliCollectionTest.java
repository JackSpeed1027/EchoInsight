/* 文件职责：用合成工具输出与本机 Java 子进程验证 URL、来源、完整性、进程和存档边界。 */
package io.echoinsight.collection;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/** 不访问平台、不调用模型；进程测试执行本测试类内的固定辅助程序。 */
class BilibiliCollectionTest {
  @TempDir Path temporary;
  private final BilibiliUrlParser urls = new BilibiliUrlParser();
  private final ObjectMapper json = new ObjectMapper();
  private final BilibiliCliAdapter adapter = new BilibiliCliAdapter(json);
  private static final String BV = "BV1ntuF64EhF";

  /** 查询参数不改变规范身份；仿冒域、内网、userinfo、端口、编码路径和短链均拒绝。 */
  @Test
  void normalizesOfficialUrlAndRejectsArbitraryTargets() {
    assertThat(urls.parse("https://www.bilibili.com/video/" + BV + "/?spm_id=abc").canonicalUrl())
        .isEqualTo("https://www.bilibili.com/video/" + BV);
    assertThat(urls.parse("https://bilibili.com/video/av123").sourceId()).isEqualTo("av123");
    for (String value :
        List.of(
            "http://127.0.0.1/video/" + BV,
            "https://www.bilibili.com.evil.test/video/" + BV,
            "https://x@www.bilibili.com/video/" + BV,
            "https://www.bilibili.com:443/video/" + BV,
            "https://www.bilibili.com/video/%42V1ntuF64EhF",
            "https://b23.tv/test",
            "https://www.bilibili.com/video/" + BV + "/extra")) {
      assertThatThrownBy(() -> urls.parse(value)).isInstanceOf(ResponseStatusException.class);
    }
  }

  /** 根评论与总评论分开计数，稳定来源身份兼容既有文件导入，个人资料字段被丢弃。 */
  @Test
  void mapsSourceIdsAndSeparatesCollectionCompleteness() {
    var target = urls.parse("https://www.bilibili.com/video/" + BV);
    var result = adapter.convert(target, 50, video("123"), comments("123", 1));
    assertThat(result.document().content().id()).isEqualTo("mc-bili-123");
    assertThat(result.document().content().creatorId()).hasSize(64);
    assertThat(result.document().comments()).hasSize(2);
    assertThat(result.document().comments().get(1).parentId()).isEqualTo("mc-bili-10");
    assertThat(result.rootCount()).isEqualTo(1);
    assertThat(result.completeness()).isEqualTo("NOT_PROVEN");
    assertThat(adapter.convert(target, 1, video("123"), comments("123", 1)).completeness())
        .isEqualTo("TRUNCATED");
    assertThat(adapter.convert(target, 50, video("123"), comments("123", 9)).completeness())
        .isEqualTo("PARTIAL_FAILED");
  }

  /** 错误作品、非法 UTF-8、重复键、重复评论和断裂父关系都不能成为可信资料。 */
  @Test
  void rejectsSourceMismatchMalformedBytesAndDuplicateComments() {
    var target = urls.parse("https://www.bilibili.com/video/" + BV);
    assertThatThrownBy(() -> adapter.convert(target, 50, video("123"), comments("456", 1)))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("SOURCE_ID_MISMATCH");
    assertThatThrownBy(
            () -> adapter.convert(target, 50, new byte[] {(byte) 0xc3, 0x28}, comments("123", 1)))
        .isInstanceOf(CollectionFailure.class);
    String repeated =
        new String(comments("123", 1), StandardCharsets.UTF_8)
            .replace("\"rpid\":11", "\"rpid\":10");
    assertThatThrownBy(
            () ->
                adapter.convert(
                    target, 50, video("123"), repeated.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("DUPLICATE_COMMENT_ID");
    assertThatThrownBy(
            () ->
                adapter.convert(
                    target,
                    50,
                    "[{\"aid\":1,\"aid\":2}]".getBytes(StandardCharsets.UTF_8),
                    comments("123", 1)))
        .isInstanceOf(CollectionFailure.class);
  }

  /** 原文存档只追加；同一尝试不能覆盖，清单哈希可供之后独立核对。 */
  @Test
  void writesHashManifestAndRefusesArchiveOverwrite() throws Exception {
    var settings = settings();
    var archive = new CollectionArchive(settings, json);
    var payload = new BilibiliCliCollector.Payload(video("123"), comments("123", 1));
    var target = urls.parse("https://www.bilibili.com/video/" + BV);
    String batch = java.util.UUID.randomUUID().toString();
    assertThat(archive.save(batch, 1, target, payload)).isEqualTo(batch + "/attempt-1");
    var manifest =
        json.readTree(Files.readString(temporary.resolve(batch + "/attempt-1/manifest.json")));
    assertThat(manifest.path("commentsSha256").asText()).hasSize(64);
    assertThatThrownBy(() -> archive.save(batch, 1, target, payload))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("ARCHIVE_WRITE_FAILED");
    assertThat(Files.readAllBytes(temporary.resolve(batch + "/attempt-1/comments.json")))
        .isEqualTo(payload.comments());
  }

  /** 同时消费 stdout/stderr，拒绝超限输出，并保持平台退出码的明确语义。 */
  @Test
  void boundsRealChildProcessOutputAndMapsRefusal() {
    var collector = new BilibiliCliCollector(settings());
    assertThat(new String(collector.run(child("ok")), StandardCharsets.UTF_8)).isEqualTo("[]");
    assertThatThrownBy(() -> collector.run(child("overflow")))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("TOOL_OUTPUT_LIMIT");
    assertThatThrownBy(() -> collector.run(child("refused")))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("PLATFORM_RISK_CONTROL");
    assertThat(collector.exitCode(3)).isEqualTo("SOURCE_EMPTY");
    assertThat(collector.exitCode(6)).isEqualTo("RATE_LIMITED");
    assertThat(collector.exitCode(7)).isEqualTo("SOURCE_NOT_FOUND");
  }

  /** 超过总时长的子进程被终止，工作线程可以返回而非永久等待管道。 */
  @Test
  void terminatesTimedOutChildProcess() {
    long started = System.nanoTime();
    assertThatThrownBy(() -> new BilibiliCliCollector(settings()).run(child("sleep")))
        .isInstanceOf(CollectionFailure.class)
        .hasMessage("TOOL_TIMEOUT");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds()).isLessThan(20);
  }

  private BilibiliCollectionSettings settings() {
    return new BilibiliCollectionSettings(
        new MockEnvironment()
            .withProperty("echoinsight.collection.bilibili.enabled", "true")
            .withProperty(
                "echoinsight.collection.bilibili.binary",
                Path.of(
                        System.getProperty("java.home"),
                        "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                    .toString())
            .withProperty("echoinsight.collection.bilibili.storage-dir", temporary.toString())
            .withProperty("echoinsight.collection.bilibili.timeout-seconds", "10")
            .withProperty("echoinsight.collection.bilibili.lease-seconds", "60"));
  }

  private List<String> child(String mode) {
    return List.of("-cp", System.getProperty("java.class.path"), Child.class.getName(), mode);
  }

  /** 仅单元测试使用的固定子进程入口，忽略后面的 CLI 公共参数。 */
  public static class Child {
    public static void main(String[] arguments) throws Exception {
      switch (arguments[0]) {
        case "ok" -> {
          System.err.print("diagnostic");
          System.out.print("[]");
        }
        case "overflow" -> System.out.write(new byte[BilibiliCliAdapter.MAX_JSON_BYTES + 8192]);
        case "refused" -> System.exit(2);
        case "sleep" -> Thread.sleep(30_000);
        default -> System.exit(1);
      }
    }
  }

  /** 合成内容结构刻意保持最小，不能用作真实讨论的质量成绩。 */
  static byte[] video(String aid) {
    return ("[{\"aid\":"
            + aid
            + ",\"bvid\":\""
            + BV
            + "\",\"owner_mid\":1,\"title\":\"合成采集验收\",\"pubdate\":1785810000,\"reply_count\":9}]")
        .getBytes(StandardCharsets.UTF_8);
  }

  static byte[] comments(String aid, int replies) {
    return ("[{\"rpid\":10,\"oid\":"
            + aid
            + ",\"type\":1,\"parent\":0,\"content\":\"合成问题\",\"ctime\":1785814540,\"reply_count\":"
            + replies
            + ",\"replies\":[{\"rpid\":11,\"oid\":"
            + aid
            + ",\"type\":1,\"parent\":10,\"content\":\"合成回复\",\"ctime\":1785814541,\"reply_count\":0}]}]")
        .getBytes(StandardCharsets.UTF_8);
  }
}
