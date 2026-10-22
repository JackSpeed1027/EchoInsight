/* 文件职责：以合成材料检验封闭JSON、哈希、原文完整性、版本图和字幕绑定，不访问网络或用户资料。 */
package io.echoinsight.archive;

import static io.echoinsight.archive.MaterialArchiveProtocol.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.content.ContentMaterialParser;
import io.echoinsight.ingestion.*;
import io.echoinsight.material.SubtitleParser;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;

/** 验证输入协议，不把工程合成数据当成真实平台来源或独立语义质量样本。 */
class MaterialArchiveProtocolTest {
  private static final jakarta.validation.ValidatorFactory FACTORY =
      Validation.buildDefaultValidatorFactory();
  private final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final ImportValidator imports = new ImportValidator(FACTORY.getValidator());
  private final SubtitleParser subtitles = new SubtitleParser();
  private final ContentMaterialParser content = new ContentMaterialParser(subtitles);
  private final MaterialArchiveProtocol protocol =
      new MaterialArchiveProtocol(json, imports, content, subtitles);

  /** 测试结束释放校验器资源，不清理任何持久化资料。 */
  @AfterAll
  static void close() {
    FACTORY.close();
  }

  /** 空项目也能携带项目目标；封包确定性不依赖生成时间。 */
  @Test
  void deterministicEnvelopeAndEmptyProject() {
    var data = data(List.of(), List.of(), List.of(), List.of());
    assertThat(protocol.seal(data)).isEqualTo(protocol.seal(data));
    assertThat(protocol.check(protocol.seal(data)).preview().snapshots()).isZero();
  }

  /** 包内正文被改而沿用旧哈希时必须拒绝。 */
  @Test
  void changedPayloadCannotReuseOldHash() {
    var a = protocol.seal(data(List.of(), List.of(), List.of(), List.of()));
    var changed =
        new Data(
            VERSION, new ProjectInfo("被修改", "合成目标"), List.of(), List.of(), List.of(), List.of());
    assertThatThrownBy(() -> protocol.check(new Archive(changed, a.sha256())))
        .hasMessageContaining(HASH);
  }

  /** 即使重算外包哈希，也不能遮蔽某条资料的原始哈希错误。 */
  @Test
  void innerHashIsCheckedSeparately() {
    var reference = new Reference(id(), "参考", "自行编写", "原文", "0".repeat(64));
    assertThatThrownBy(
            () ->
                protocol.check(
                    protocol.seal(data(List.of(), List.of(), List.of(reference), List.of()))))
        .hasMessageContaining(HASH);
  }

  /** 严格读取拒绝未知字段、重复键、后缀JSON与标量类型强转。 */
  @Test
  void strictReaderRejectsAmbiguousOrUnknownInput() {
    String value =
        protocol.encode(restore(protocol.seal(data(List.of(), List.of(), List.of(), List.of()))));
    for (String bad :
        List.of(
            value.substring(0, value.length() - 1) + ",\"sql\":\"ignored\"}",
            value + " {}",
            value.replace("\"name\":\"新项目\"", "\"name\":8"),
            value.replace("\"name\":\"新项目\"", "\"name\":\"新项目\",\"name\":\"另一个\"")))
      assertThatThrownBy(() -> protocol.read(stream(bad))).hasMessageContaining(INVALID);
    assertThat(protocol.read(stream(value)).name()).isEqualTo("新项目");
  }

  /** 确认时原包哈希改变则409，不以过期预检替代校验。 */
  @Test
  void expectedHashBindsPreviewToRestore() {
    var a = protocol.seal(data(List.of(), List.of(), List.of(), List.of()));
    assertThatThrownBy(() -> protocol.check(new Restore("新项目", "新目标", "0".repeat(64), a)))
        .hasMessageContaining(HASH);
  }

  /** 乱序版本可恢复，但父版必先于子版；原文/分段哈希保持原解析规则。 */
  @Test
  void reversedVersionsAreTopologicallyOrdered() {
    var a = text(id(), null, "\ufeff第一段😀\r\n原始换行");
    var b = text(id(), a.id(), "追加修订");
    var checked =
        protocol.check(protocol.seal(data(List.of(), List.of(b, a), List.of(), List.of())));
    assertThat(checked.orderedContents()).containsExactly(a, b);
  }

  /** 缺父、环与跨类型重复身份均整包拒绝，不静默删除异常节点。 */
  @Test
  void missingParentsCyclesAndDuplicateIdsAreRejected() {
    String a = id(), b = id();
    assertThatThrownBy(
            () ->
                protocol.check(
                    protocol.seal(data(List.of(), List.of(text(a, b, "甲")), List.of(), List.of()))))
        .hasMessageContaining(GRAPH);
    assertThatThrownBy(
            () ->
                protocol.check(
                    protocol.seal(
                        data(
                            List.of(),
                            List.of(text(a, b, "甲"), text(b, a, "乙")),
                            List.of(),
                            List.of()))))
        .hasMessageContaining(GRAPH);
    var r = new Reference(a, "参考", "声明", "原文", hash("原文"));
    assertThatThrownBy(
            () ->
                protocol.check(
                    protocol.seal(
                        data(List.of(), List.of(text(a, null, "甲")), List.of(r), List.of()))))
        .hasMessageContaining(GRAPH);
  }

  /** 字幕必须绑定本包快照，不接受另一项目的悬空快照ID。 */
  @Test
  void subtitleCannotReferToMissingSnapshot() {
    String raw = "1\n00:00:00,000 --> 00:00:01,000\n合成字幕\n";
    var parsed = subtitles.parse("SRT", raw.getBytes(StandardCharsets.UTF_8));
    var s =
        new Subtitle(
            id(),
            id(),
            "字幕",
            "自写",
            "SRT",
            raw,
            hash(raw),
            hash(protocol.encode(parsed.segments())));
    assertThatThrownBy(
            () -> protocol.check(protocol.seal(data(List.of(), List.of(), List.of(), List.of(s)))))
        .hasMessageContaining(GRAPH);
  }

  /** 原评论缺父声明原样保留，不能伪造父评论或重新挂接。 */
  @Test
  void explicitCommentGapsRoundTrip() {
    var time = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    var doc =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("raw-content", "SYNTHETIC", "work-1", "creator", "原文", time),
            List.of(
                new ImportDocument.Comment("child", "source-child", "missing", "无法获得父评论", time)),
            List.of(),
            List.of(new ImportDocument.MissingParent("child", "missing")));
    var encoded = protocol.encode(imports.normalize(doc));
    String sid = id();
    var a =
        protocol.seal(
            data(
                List.of(new Snapshot(sid, hash(encoded), encoded)),
                List.of(),
                List.of(),
                List.of()));
    var restored = protocol.check(a).documents().get(sid);
    assertThat(restored.comments().getFirst().parentId()).isEqualTo("missing");
    assertThat(restored.contextGaps()).hasSize(1);
  }

  /** JSON合法但评论父图违法时仍拒绝；外包哈希一致不能越过原导入规则。 */
  @Test
  void invalidCommentTreeIsNotTrusted() {
    var time = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    var doc =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("raw-content", "SYNTHETIC", "work-2", "creator", "原文", time),
            List.of(new ImportDocument.Comment("a", "source-a", "a", "自循环", time)),
            List.of());
    String encoded = protocol.encode(doc);
    assertThatThrownBy(
            () ->
                protocol.check(
                    protocol.seal(
                        data(
                            List.of(new Snapshot(id(), hash(encoded), encoded)),
                            List.of(),
                            List.of(),
                            List.of()))))
        .hasMessageContaining(INVALID);
  }

  /** 流上限在JSON解析前生效，条数越界不截取前缀。 */
  @Test
  void byteAndEntryLimitsFailClosed() {
    assertThatThrownBy(() -> protocol.read(new ByteArrayInputStream(new byte[MAX_BYTES + 1])))
        .hasMessageContaining(LIMIT);
    var references = new ArrayList<Reference>();
    for (int n = 0; n < 101; n++) references.add(new Reference(id(), "参考", "自写", "正文", hash("正文")));
    assertThatThrownBy(
            () -> protocol.check(protocol.seal(data(List.of(), List.of(), references, List.of()))))
        .hasMessageContaining(LIMIT);
  }

  /** 原文中的未配对代理及NUL不可被UTF8替换成其他正文后恢复。 */
  @Test
  void malformedUnicodeIsRejectedBeforeEncoding() {
    var c = text(id(), null, "正常");
    for (String bad : List.of("坏\ud800", "含\0内容")) {
      var broken =
          new Content(
              c.id(),
              null,
              c.title(),
              c.sourceDescription(),
              c.format(),
              bad,
              hash(bad),
              c.segmentsSha256());
      assertThatThrownBy(
              () ->
                  protocol.check(
                      protocol.seal(data(List.of(), List.of(broken), List.of(), List.of()))))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
  }

  /** 来源与目标限制保持业务含义，不把无名恢复写进项目库。 */
  @Test
  void destinationFieldsMustBeExplicit() {
    var a = protocol.seal(data(List.of(), List.of(), List.of(), List.of()));
    assertThatThrownBy(() -> protocol.check(new Restore(" ", "目标", a.sha256(), a)))
        .hasMessageContaining(META);
  }

  /** 构造固定版本的空或有材料包，所有输入由本测试生成。 */
  private Data data(List<Snapshot> s, List<Content> c, List<Reference> r, List<Subtitle> t) {
    return new Data(VERSION, new ProjectInfo("合成归档", "验证恢复，不代表平台实测"), s, c, r, t);
  }

  private Restore restore(Archive a) {
    return new Restore("新项目", "新目标", a.sha256(), a);
  }

  private ByteArrayInputStream stream(String s) {
    return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
  }

  private String id() {
    return UUID.randomUUID().toString();
  }

  private Content text(String id, String previous, String raw) {
    var parsed = content.parse("TXT", raw.getBytes(StandardCharsets.UTF_8));
    return new Content(
        id,
        previous,
        "文本",
        "自行编写",
        "TXT",
        raw,
        hash(raw),
        hash(protocol.encode(parsed.segments())));
  }
}
