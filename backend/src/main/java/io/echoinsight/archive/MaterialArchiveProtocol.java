/* 文件职责：定义资料归档的封闭协议、完整性校验及恢复预检，不接受SQL、路径或可执行指令。 */
package io.echoinsight.archive;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.echoinsight.content.ContentMaterialParser;
import io.echoinsight.ingestion.*;
import io.echoinsight.material.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 只归档原始资料及内容版本关系；报告、任务、费用、人工意见和凭据均不属于恢复协议。 */
@Component
public class MaterialArchiveProtocol {
  public static final String VERSION = "echoinsight-materials-v1";
  public static final int MAX_BYTES = 16 * 1024 * 1024;
  public static final int ARCHIVE_MAX_BYTES = 14 * 1024 * 1024;
  public static final String INVALID = "资料归档格式或身份无效，请选择本系统导出的资料JSON";
  public static final String HASH = "资料归档校验不一致，请保留原文件并重新导出，不能强制恢复";
  public static final String LIMIT = "资料归档最多14MiB、60份评论快照及各100份文本、参考、字幕，不会静默截取";
  public static final String GRAPH = "归档版本链或字幕绑定缺失、重复或成环，不能恢复为完整资料";
  public static final String META = "恢复项目名称1～120字、目标1～1000字，来源说明最多200字";
  private final ObjectMapper json;
  private final ImportValidator imports;
  private final ContentMaterialParser content;
  private final SubtitleParser subtitles;

  /** 独立严格JSON读取器仅用于归档，不改变旧接口对未知字段的兼容行为。 */
  public MaterialArchiveProtocol(
      ObjectMapper mapper,
      ImportValidator imports,
      ContentMaterialParser content,
      SubtitleParser subtitles) {
    json =
        mapper
            .copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    json.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    json.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    this.imports = imports;
    this.content = content;
    this.subtitles = subtitles;
  }

  /** 外层哈希覆盖整个类型化data；哈希只证明一致性，不认证文件作者或平台覆盖。 */
  public record Archive(Data data, String sha256) {}

  /** 来源项目摘要，不携带工作空间或身份权限。 */
  public record ProjectInfo(String name, String analysisGoal) {}

  /** 完整评论导入原文与规范哈希，id仅为包内恢复映射。 */
  public record Snapshot(String id, String inputSha256, String documentJson) {}

  /** 独立内容原文及修订父链，定位由原解析器核对。 */
  public record Content(
      String id,
      String previousId,
      String title,
      String sourceDescription,
      String format,
      String rawText,
      String rawSha256,
      String segmentsSha256) {}

  /** 参考原文与用户来源说明，不认证外部事实。 */
  public record Reference(
      String id, String title, String sourceDescription, String text, String sha256) {}

  /** 保留字幕原文/格式以及包内快照绑定，不包含媒体文件。 */
  public record Subtitle(
      String id,
      String snapshotId,
      String title,
      String sourceDescription,
      String format,
      String rawText,
      String rawSha256,
      String segmentsSha256) {}

  /** 固定四类原文集合，空项目合法，成员必须全部满足边界。 */
  public record Data(
      String version,
      ProjectInfo project,
      List<Snapshot> snapshots,
      List<Content> contents,
      List<Reference> references,
      List<Subtitle> subtitles) {}

  /** 同一archive随预检/恢复显式提交；不以浏览器上次预检结果代替服务端重新核验。 */
  public record Restore(String name, String analysisGoal, String expectedSha256, Archive archive) {}

  public record Preview(
      String archiveSha256,
      String sourceName,
      int snapshots,
      int comments,
      int contents,
      int references,
      int subtitles,
      int encodedBytes,
      List<String> notes) {}

  /** 已规范化的评论和拓扑排序内容仅存在当前调用栈，不建立第二套持久任务。 */
  public record Checked(
      Archive archive,
      Preview preview,
      Map<String, ImportDocument> documents,
      List<Content> orderedContents) {}

  /** 1、先限制流字节；2、严格字段/类型/重复键；3、固定错误不回显原文片段。 */
  public Restore read(InputStream stream) {
    try {
      byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, LIMIT);
      return json.readValue(bytes, Restore.class);
    } catch (ResponseStatusException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      throw bad(HttpStatus.BAD_REQUEST, INVALID);
    }
  }

  /** 导出使用确定字段和固定版本；相同材料再次导出不因生成时间改变哈希。 */
  public Archive seal(Data data) {
    return new Archive(data, hash(encode(data)));
  }

  /** 1、校验完整包；2、验证目的项目字段；3、绑定用户预检确认的哈希，不能偷换文件。 */
  public Checked check(Restore restore) {
    if (restore == null) throw bad(HttpStatus.BAD_REQUEST, INVALID);
    project(restore.name(), restore.analysisGoal());
    var checked = check(restore.archive());
    if (!Objects.equals(restore.expectedSha256(), checked.archive().sha256()))
      throw bad(HttpStatus.CONFLICT, HASH);
    return checked;
  }

  /** 所有解析、图与哈希检查在写入项目之前完成；任何条目失败拒绝整个包。 */
  public Checked check(Archive archive) {
    if (archive == null || archive.data() == null) throw bad(HttpStatus.BAD_REQUEST, INVALID);
    var d = archive.data();
    if (!VERSION.equals(d.version()) || d.project() == null)
      throw bad(HttpStatus.BAD_REQUEST, INVALID);
    project(d.project().name(), d.project().analysisGoal());
    bounded(d.snapshots(), 60);
    bounded(d.contents(), 100);
    bounded(d.references(), 100);
    bounded(d.subtitles(), 100);
    String encoded = encode(archive);
    int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > ARCHIVE_MAX_BYTES) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, LIMIT);
    if (!hash(encode(d)).equals(archive.sha256())) throw bad(HttpStatus.BAD_REQUEST, HASH);
    Set<String> ids = new HashSet<>();
    Map<String, ImportDocument> documents = new LinkedHashMap<>();
    Set<String> snapshotHashes = new HashSet<>();
    int comments = 0;
    for (var s : d.snapshots()) {
      identity(s == null ? null : s.id(), ids);
      plain(s.documentJson(), 5 * 1024 * 1024, false);
      if (!hash(s.documentJson()).equals(s.inputSha256())) throw bad(HttpStatus.BAD_REQUEST, HASH);
      ImportDocument doc;
      try {
        doc = imports.normalize(json.readValue(s.documentJson(), ImportDocument.class));
      } catch (IOException | RuntimeException error) {
        throw bad(HttpStatus.BAD_REQUEST, INVALID);
      }
      if (!hash(encode(doc)).equals(s.inputSha256())) throw bad(HttpStatus.BAD_REQUEST, HASH);
      // 同一资料不能伪造多个来源身份增加恢复分母；新库仍可能复用已有相同快照。
      if (!snapshotHashes.add(s.inputSha256())) throw bad(HttpStatus.BAD_REQUEST, GRAPH);
      documents.put(s.id(), doc);
      comments += doc.comments().size();
    }
    Map<String, Content> byId = new LinkedHashMap<>();
    for (var c : d.contents()) {
      identity(c == null ? null : c.id(), ids);
      metadata(c.title(), c.sourceDescription());
      plain(c.rawText(), SubtitleParser.MAX_BYTES, false);
      var parsed = content.parse(c.format(), c.rawText().getBytes(StandardCharsets.UTF_8));
      if (!hash(parsed.raw()).equals(c.rawSha256())
          || !hash(encode(parsed.segments())).equals(c.segmentsSha256()))
        throw bad(HttpStatus.BAD_REQUEST, HASH);
      byId.put(c.id(), c);
    }
    List<Content> ordered = order(byId);
    for (var r : d.references()) {
      identity(r == null ? null : r.id(), ids);
      metadata(r.title(), r.sourceDescription());
      plain(r.text(), ReferenceMaterialStore.MAX_TEXT_CHARS, false);
      if (!hash(r.text()).equals(r.sha256())) throw bad(HttpStatus.BAD_REQUEST, HASH);
    }
    for (var s : d.subtitles()) {
      identity(s == null ? null : s.id(), ids);
      metadata(s.title(), s.sourceDescription());
      if (!documents.containsKey(s.snapshotId())) throw bad(HttpStatus.BAD_REQUEST, GRAPH);
      plain(s.rawText(), SubtitleParser.MAX_BYTES, false);
      var parsed = subtitles.parse(s.format(), s.rawText().getBytes(StandardCharsets.UTF_8));
      if (!hash(parsed.raw()).equals(s.rawSha256())
          || !hash(encode(parsed.segments())).equals(s.segmentsSha256()))
        throw bad(HttpStatus.BAD_REQUEST, HASH);
    }
    var preview =
        new Preview(
            archive.sha256(),
            d.project().name(),
            documents.size(),
            comments,
            d.contents().size(),
            d.references().size(),
            d.subtitles().size(),
            bytes,
            notes());
    return new Checked(archive, preview, Map.copyOf(documents), List.copyOf(ordered));
  }

  /** 拓扑恢复父版先于子版；乱序输入可接受，缺父/环/超过100层失败，不丢弃版本分支。 */
  private List<Content> order(Map<String, Content> byId) {
    List<Content> ordered = new ArrayList<>();
    Set<String> done = new HashSet<>();
    while (done.size() < byId.size()) {
      boolean progress = false;
      for (var c : byId.values()) {
        if (done.contains(c.id())) continue;
        if (c.previousId() != null && !byId.containsKey(c.previousId()))
          throw bad(HttpStatus.BAD_REQUEST, GRAPH);
        if (c.previousId() == null || done.contains(c.previousId())) {
          ordered.add(c);
          done.add(c.id());
          progress = true;
        }
      }
      if (!progress) throw bad(HttpStatus.BAD_REQUEST, GRAPH);
    }
    return ordered;
  }

  /** 恢复明确排除运行真相；旧分析、人工意见仍在原项目，不能伪造为新项目成绩。 */
  public static List<String> notes() {
    return List.of(
        "恢复到新项目，原项目、旧资料及历史记录不改不删。",
        "只包含评论快照、独立内容版本、参考文本和关联字幕；不含任务、报告、费用、人工意见、集合或采集检查点。",
        "不包含凭据或本机配置；哈希一致不代表来源可信、平台完整或语义正确。",
        "恢复后采集覆盖为未知，不把归档中的原文条数当作已抓取全部平台评论。",
        "恢复不自动采集或分析；如需新分析，仍需明确范围、费用及授权。",
        "本功能是资料可携带性，不是整个MySQL数据库或整机灾备。");
  }

  /** 对外编码只输出已定义类型，未知字段不能夹带SQL、路径或费用执行配置。 */
  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot encode material archive", error);
    }
  }

  /** 与原文存储共用UTF8 SHA算法，避免另建不同的指纹口径。 */
  public static String hash(String text) {
    return ReferenceMaterialStore.hash(text);
  }

  /** 成员总数拒绝而非截断，null数组不是空资料。 */
  private static void bounded(List<?> values, int max) {
    if (values == null) throw bad(HttpStatus.BAD_REQUEST, INVALID);
    if (values.size() > max) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, LIMIT);
  }

  /** 版本和绑定仅接受唯一UUID，不能借混合身份让引用指向另一种资料。 */
  private static void identity(String id, Set<String> ids) {
    if (id == null
        || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        || !ids.add(id)) throw bad(HttpStatus.BAD_REQUEST, GRAPH);
  }

  /** 保持已有资料的标题与来源说明上限，来源必须明确为空或文本。 */
  private static void metadata(String title, String source) {
    plain(title, 120, false);
    plain(source, 200, true);
  }

  /** 来源摘要和目的项目均需要真实可读的名称与研究目标。 */
  private static void project(String name, String goal) {
    plain(name, 120, false);
    plain(goal, 1000, false);
  }

  /** UTF16字符限制与旧业务一致，同时拒绝NUL及未配对代理，防止编码替换悄悄改变原文。 */
  private static void plain(String text, int max, boolean empty) {
    if (text == null
        || text.length() > max
        || (!empty && text.isBlank())
        || text.indexOf('\0') >= 0) throw bad(HttpStatus.BAD_REQUEST, META);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
          throw bad(HttpStatus.BAD_REQUEST, INVALID);
      } else if (Character.isLowSurrogate(c)) throw bad(HttpStatus.BAD_REQUEST, INVALID);
    }
  }

  /** 固定错误原因交给原HTTP安全白名单，不回显私有归档正文。 */
  public static ResponseStatusException bad(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }
}
