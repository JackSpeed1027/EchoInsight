/* 文件职责：将 MediaCrawler 的作品/评论 JSON 映射为标准导入，不联网、不接触采集登录态。 */
package io.echoinsight.ingestion.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportFailure;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** 平台字段适配器：只白名单读取本轮核对的 MediaCrawler JSON 数组字段。 原始文件可能包含多个作品，调用者必须明确来源 ID；排除数返回给用户，不假装全部采集。 */
@Component
public class MediaCrawlerAdapter {
  public static final String VERSION = "mediacrawler-json-v1";
  public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
  private final ObjectMapper parser;

  /** 为平台输入建立独立严格解析器；不影响其他控制器，也不回显原始解析异常。 */
  public MediaCrawlerAdapter(ObjectMapper json) {
    parser =
        json.copy()
            .enable(
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    parser
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxStringLength(20000)
                .maxNumberLength(128)
                .build());
  }

  /** 映射结果不保存原始附加字段；哈希供核对本次上传，不等于已建立持久采集档案。 */
  public record Converted(
      ImportDocument document,
      String platform,
      String sourceContentId,
      int contentRows,
      int commentRows,
      int selectedCommentRows,
      int excludedContentRows,
      int excludedCommentRows,
      String contentFileSha256,
      String commentsFileSha256) {}

  /** 平台规则明确时间单位；绝不按数字长度猜测，或用采集时间代替发布时间。 */
  private enum Platform {
    BILIBILI("video_id", "create_time", "mc-bili-", false),
    XHS("note_id", "time", "mc-xhs-", true);
    final String contentField;
    final String publishedField;
    final String prefix;
    final boolean milliseconds;

    /** 将平台差异集中，避免控制器和前端各自猜测字段。 */
    Platform(String contentField, String publishedField, String prefix, boolean milliseconds) {
      this.contentField = contentField;
      this.publishedField = publishedField;
      this.prefix = prefix;
      this.milliseconds = milliseconds;
    }
  }

  /**
   * 1、核对平台/文件；2、选择唯一作品；3、映射其全部评论行；4、返回可交给 ImportService 的材料。 本方法无副作用，后续父评论、重复冲突和长度等统一由既有导入校验器处理。
   */
  public Converted convert(
      String platformName, String contentId, byte[] contentBytes, byte[] commentBytes) {
    Platform platform;
    try {
      platform = Platform.valueOf(platformName == null ? "" : platformName);
    } catch (IllegalArgumentException ignored) {
      throw failure("platform", "平台仅支持 BILIBILI 或 XHS");
    }
    if (contentId == null || !contentId.matches("[A-Za-z0-9_-]{1,96}")) {
      throw failure("sourceContentId", "请输入导出文件里的作品 ID，不能填写链接");
    }
    // 1、文件仅支持严格 UTF-8 JSON 数组，限制原始体积、行数和嵌套，拒绝 JSONL 和错误根对象。
    JsonNode contents = array(contentBytes, "contentsFile");
    JsonNode comments = array(commentBytes, "commentsFile");
    // 2、选择唯一作品；不能凭第一行猜作品，也不能静默从重复作品记录中挑最新。
    JsonNode selected = null;
    for (int index = 0; index < contents.size(); index++) {
      var row = contents.get(index);
      String path = "contents[" + index + "]";
      String id = identity(row, platform.contentField, path);
      if (contentId.equals(id)) {
        if (selected != null) throw failure(path, "同一作品出现多条元数据，请先明确唯一版本");
        selected = row;
      }
    }
    if (selected == null) throw failure("sourceContentId", "作品文件没有指定作品，请核对来源 ID 和平台");
    String creator = text(selected, "creator_hash", false, "content");
    if (creator == null) creator = identity(selected, "user_id", "content");
    var content =
        new ImportDocument.Content(
            platform.prefix + contentId,
            platform.name(),
            contentId,
            creator,
            text(selected, "title", true, "content"),
            timestamp(selected, platform.publishedField, platform, "content"));

    // 3、只映射选中作品；其余作品计入排除数，选中作品没有评论时明确报错。
    List<ImportDocument.Comment> mapped = new ArrayList<>();
    for (int index = 0; index < comments.size(); index++) {
      var row = comments.get(index);
      String path = "comments[" + index + "]";
      String owner = identity(row, platform.contentField, path);
      if (!contentId.equals(owner)) continue;
      String id = identity(row, "comment_id", path);
      String parent = optionalParent(row, path);
      mapped.add(
          new ImportDocument.Comment(
              platform.prefix + id,
              id,
              parent == null ? null : platform.prefix + parent,
              text(row, "content", true, path),
              timestamp(row, "create_time", platform, path)));
    }
    if (mapped.isEmpty()) throw failure("commentsFile", "指定作品没有评论，无法建立当前分析快照");
    // 4、不把点赞数、用户昵称、Cookie、链接 token 等附加字段传入标准快照或模型。
    var document = new ImportDocument("1.0", content, List.copyOf(mapped), List.of());
    return new Converted(
        document,
        platform.name(),
        contentId,
        contents.size(),
        comments.size(),
        mapped.size(),
        contents.size() - 1,
        comments.size() - mapped.size(),
        sha256(contentBytes),
        sha256(commentBytes));
  }

  /** 有界严格解析，安全错误只返回文件名/行路径，不包含昵称、token 或原文片段。 */
  private JsonNode array(byte[] bytes, String path) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
      throw failure(path, "文件不能为空且不能超过 2 MiB");
    }
    try {
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (value.startsWith("\uFEFF")) value = value.substring(1);
      JsonNode result = parser.readTree(value);
      if (result == null || !result.isArray() || result.isEmpty() || result.size() > 5000) {
        throw failure(path, "需要包含 1～5000 行的 JSON 数组；本轮不支持 JSONL/CSV");
      }
      for (var row : result) {
        if (!row.isObject()) throw failure(path, "数组中的每一行必须是对象");
      }
      return result;
    } catch (CharacterCodingException ignored) {
      throw failure(path, "文件必须为 UTF-8 编码");
    } catch (JsonProcessingException ignored) {
      throw failure(path, "JSON 结构错误，或存在重复键/多根对象/超限字段");
    }
  }

  /** 来源 ID 允许字符串或无损整数；禁止浮点数、布尔值以及宽松隐式转换。 */
  private String identity(JsonNode row, String field, String path) {
    JsonNode value = row.path(field);
    if (!value.isTextual() && !value.isIntegralNumber())
      throw failure(path + "." + field, "缺少有效来源 ID");
    String result = value.asText();
    if (!result.matches("[A-Za-z0-9_-]{1,96}")) throw failure(path + "." + field, "来源 ID 格式或长度错误");
    return result;
  }

  /** 上游根评论可用缺失/null/空串/0；其他父身份保留，缺失父行由统一校验拒绝。 */
  private String optionalParent(JsonNode row, String path) {
    var value = row.path("parent_comment_id");
    if (value.isMissingNode()
        || value.isNull()
        || (value.isTextual() && value.textValue().isEmpty())
        || ((value.isTextual() || value.isIntegralNumber()) && "0".equals(value.asText()))) {
      return null;
    }
    return identity(row, "parent_comment_id", path);
  }

  /** 正文和标题必须是真正字符串；保留正文空白，长度与非空由标准导入协议统一约束。 */
  private String text(JsonNode row, String field, boolean required, String path) {
    var value = row.path(field);
    if (!required
        && (value.isMissingNode()
            || value.isNull()
            || (value.isTextual() && value.textValue().isBlank()))) return null;
    if (!value.isTextual()) throw failure(path + "." + field, "字段必须为字符串");
    return value.textValue();
  }

  /** 显式秒/毫秒转 UTC，限定 2000～2100 年，拒绝把错误单位转换为看似合理的时间。 */
  private OffsetDateTime timestamp(JsonNode row, String field, Platform platform, String path) {
    var value = row.path(field);
    if ((!value.isTextual() && !value.isIntegralNumber())
        || !value.asText().matches("[0-9]{1,16}")) {
      throw failure(path + "." + field, "时间必须为整数时间戳，B站单位秒，小红书单位毫秒");
    }
    try {
      long epoch = Long.parseLong(value.asText());
      var result =
          (platform.milliseconds ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch))
              .atOffset(ZoneOffset.UTC);
      if (result.getYear() < 2000 || result.getYear() > 2100) throw new DateTimeException("range");
      return result;
    } catch (DateTimeException | NumberFormatException ignored) {
      throw failure(path + "." + field, "时间超出 2000～2100 年，请核对秒/毫秒单位");
    }
  }

  /** 原始文件指纹只用于本次上传核对，不代替正文快照，也不写入日志中的敏感内容。 */
  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  /** 汇总到既有导入错误格式，确保平台分支不绕过统一错误处理。 */
  private ImportFailure failure(String path, String message) {
    return new ImportFailure(path, message);
  }
}
