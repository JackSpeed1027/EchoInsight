/* 文件职责：离线验证平台字段、时间单位、身份精度、选定范围与严格文件解析。 */
package io.echoinsight.ingestion.platform;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportFailure;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 数据按上游字段人工构造，测试通过不代表真实平台采集成功。 */
class MediaCrawlerAdapterTest {
  private final MediaCrawlerAdapter adapter = new MediaCrawlerAdapter(new ObjectMapper());

  /** 默认元数据是 B站秒时间戳，附带多余字段以确认白名单映射。 */
  private String contents() {
    return """
    [{"video_id":"42","title":"合成作品","creator_hash":"creator-hash","create_time":1760000000,"xsec_token":"SECRET_SENTINEL"}]
    """;
  }

  /** 评论父子身份与字符必须保留，未采集的时间不允许补造。 */
  private String comments() {
    return """
    [{"video_id":"42","comment_id":"11","parent_comment_id":"0","content":" 原始正文<script>字面</script> ","create_time":1760000001},
     {"video_id":"42","comment_id":"12","parent_comment_id":"11","content":"回复","create_time":1760000002}]
    """;
  }

  /** 用严格 UTF-8 模拟上传文件，不访问网络或用户平台账号。 */
  private MediaCrawlerAdapter.Converted convert(
      String platform, String id, String content, String comments) {
    return adapter.convert(
        platform,
        id,
        content.getBytes(StandardCharsets.UTF_8),
        comments.getBytes(StandardCharsets.UTF_8));
  }

  /** B站保留来源身份、父子关系和字面正文，不把 token 等字段带入标准对象。 */
  @Test
  void mapsBilibiliWithoutExtraFieldsOrTranscriptGuessing() throws Exception {
    var value = convert("BILIBILI", "42", contents(), comments());
    assertThat(value.document().content().sourceType()).isEqualTo("BILIBILI");
    assertThat(value.document().content().sourceContentId()).isEqualTo("42");
    assertThat(value.document().comments().get(0).text()).isEqualTo(" 原始正文<script>字面</script> ");
    assertThat(value.document().comments().get(1).parentId()).isEqualTo("mc-bili-11");
    assertThat(value.document().comments().get(0).occurredAt().toEpochSecond())
        .isEqualTo(1760000001L);
    assertThat(value.document().transcriptSegments()).isEmpty();
    assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(value.document()))
        .doesNotContain("SECRET_SENTINEL");
    assertThat(value.contentFileSha256()).hasSize(64);
  }

  /** 小红书的毫秒保留子秒精度，不能误按 B站秒解析。 */
  @Test
  void mapsXiaohongshuMillisecondsAndBlankRootParent() {
    var value =
        convert(
            "XHS",
            "note1",
            """
            [{"note_id":"note1","title":"合成笔记","creator_hash":"hash","time":1760000000123}]\
            """,
            """
            [{"note_id":"note1","comment_id":"c1","content":"原文","parent_comment_id":"","create_time":"1760000000456"}]\
            """);
    assertThat(value.document().comments().getFirst().occurredAt().toInstant().toEpochMilli())
        .isEqualTo(1760000000456L);
    assertThat(value.document().comments().getFirst().parentId()).isNull();
    assertThat(value.document().content().sourceType()).isEqualTo("XHS");
  }

  /** 时间单位错、字段缺失、零值和浮点数均拒绝，不使用现在作为默认值。 */
  @Test
  void rejectsInvalidOrMissingTimes() {
    for (String time : new String[] {"1760000000000", "0", "1.5", "null", "\"not-time\""}) {
      assertThatThrownBy(
              () -> convert("BILIBILI", "42", contents().replace("1760000000", time), comments()))
          .isInstanceOf(ImportFailure.class);
    }
    assertThatThrownBy(
            () ->
                convert(
                    "XHS",
                    "note1",
                    """
                    [{"note_id":"note1","title":"笔记","creator_hash":"hash","time":1760000000}]\
                    """,
                    """
                    [{"note_id":"note1","comment_id":"c1","content":"文字","create_time":1760000000000}]\
                    """))
        .isInstanceOf(ImportFailure.class);
  }

  /** 重复键、多个根、JSONL、对象根和无效 UTF-8 都不能宽松接受或回显秘密值。 */
  @Test
  void rejectsMalformedDocumentsSafely() {
    for (String broken :
        new String[] {
          "{}",
          "[]",
          comments() + "[]",
          comments().replace("\"11\"", "\"11\",\"comment_id\":\"SECRET_SENTINEL\""),
          "[1]"
        }) {
      assertThatThrownBy(() -> convert("BILIBILI", "42", contents(), broken))
          .isInstanceOf(ImportFailure.class)
          .hasMessageNotContaining("SECRET_SENTINEL");
    }
    assertThatThrownBy(
            () ->
                adapter.convert(
                    "BILIBILI",
                    "42",
                    contents().getBytes(StandardCharsets.UTF_8),
                    new byte[] {(byte) 0xc3, (byte) 0x28}))
        .isInstanceOf(ImportFailure.class);
  }

  /** 多份同作品元数据不自动挑选；选择不到作品时也不能拿第一条替代。 */
  @Test
  void requiresUniqueSelectedContentAndKnownPlatform() {
    assertThatThrownBy(() -> convert("BILIBILI", "absent", contents(), comments()))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(
            () ->
                convert(
                    "BILIBILI",
                    "42",
                    contents().trim().replace("}]", "}," + contents().trim().substring(1)),
                    comments()))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(() -> convert("bilibili", "42", contents(), comments()))
        .isInstanceOf(ImportFailure.class);
    assertThatThrownBy(() -> convert("BILIBILI", "https://example.test", contents(), comments()))
        .isInstanceOf(ImportFailure.class);
  }

  /** 大整数不经过浮点数转换；布尔或小数不能作为来源 ID。 */
  @Test
  void preservesLargeIntegerIdentityWithoutCoercion() {
    var mapped =
        convert("BILIBILI", "42", contents(), comments().replace("\"11\"", "9007199254740993"));
    assertThat(mapped.document().comments().getFirst().sourceCommentId())
        .isEqualTo("9007199254740993");
    for (String invalid : new String[] {"true", "1.5", "null"}) {
      assertThatThrownBy(
              () -> convert("BILIBILI", "42", contents(), comments().replace("\"12\"", invalid)))
          .isInstanceOf(ImportFailure.class);
    }
  }

  /** 多作品混合文件必须显示实际排除数；没有匹配评论时不能建立空快照。 */
  @Test
  void explicitlyCountsOtherContentAndCommentRows() {
    String content =
        contents()
            .trim()
            .replace("}]", "}," + contents().trim().substring(1).replace("\"42\"", "\"other\""));
    String comments =
        comments()
            .trim()
            .replace(
                "}]", "}," + this.comments().trim().substring(1).replace("\"42\"", "\"other\""));
    var value = convert("BILIBILI", "42", content, comments);
    assertThat(value.contentRows()).isEqualTo(2);
    assertThat(value.commentRows()).isEqualTo(4);
    assertThat(value.excludedContentRows()).isEqualTo(1);
    assertThat(value.excludedCommentRows()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                convert(
                    "BILIBILI", "42", contents(), this.comments().replace("\"42\"", "\"other\"")))
        .isInstanceOf(ImportFailure.class);
  }

  /** 旧导出允许使用作品 user_id；新字段优先，缺失创作者不捏造身份。 */
  @Test
  void supportsExplicitLegacyCreatorFieldWithoutInventingIt() {
    var value =
        convert(
            "BILIBILI",
            "42",
            contents().replace("\"creator_hash\":\"creator-hash\"", "\"user_id\":123"),
            comments());
    assertThat(value.document().content().creatorId()).isEqualTo("123");
    assertThatThrownBy(
            () ->
                convert(
                    "BILIBILI",
                    "42",
                    contents().replace("\"creator_hash\":\"creator-hash\",", ""),
                    comments()))
        .isInstanceOf(ImportFailure.class);
  }
}
