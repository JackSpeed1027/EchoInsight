/* 文件职责：严格解析 bilibili-cli 的视频与评论 JSON，并转换为系统统一、可版本化的导入文档。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 适配器只读取白名单字段；昵称、头像、Cookie、来源 envelope 和其他附加字段不会进入快照。 */
@Component
public class BilibiliCliAdapter {
  public static final String VERSION = "bilibili-cli-json-v2-context";
  public static final int MAX_JSON_BYTES = 5 * 1024 * 1024;
  private final ObjectMapper parser;

  /** 为外部工具输出使用独立严格解析器，避免改变其他接口的 Jackson 行为。 */
  public BilibiliCliAdapter(ObjectMapper objectMapper) {
    parser =
        objectMapper
            .copy()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    parser
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(20_000)
                .maxNumberLength(64)
                .build());
  }

  /** 转换结果同时携带范围诊断；完整性不能证明时明确返回 NOT_PROVEN。 */
  public record Converted(
      ImportDocument document,
      String bvid,
      String aid,
      int sourceReportedComments,
      int rootCount,
      int commentCount,
      int partialThreadCount,
      String completeness) {}

  /** 1、验证视频身份；2、递归展开评论树；3、核对父关系；4、建立稳定来源 ID 和范围声明。 */
  public Converted convert(
      BilibiliUrlParser.Target target, int rootLimit, byte[] videoBytes, byte[] commentBytes) {
    JsonNode videos = array(videoBytes, "video");
    JsonNode roots = array(commentBytes, "comments");
    if (roots.size() > rootLimit) throw new CollectionFailure("TOOL_SCOPE_EXCEEDED");
    if (videos.size() != 1) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    JsonNode video = videos.get(0);
    String aid = identity(video, "aid");
    String bvid = identity(video, "bvid");
    if (!target.sourceId().equals(bvid) && !target.sourceId().equals("av" + aid))
      throw new CollectionFailure("SOURCE_ID_MISMATCH");

    int sourceReported = nonNegativeInt(video, "reply_count");
    List<ImportDocument.Comment> comments = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    int partialThreads = 0;
    for (JsonNode root : roots) {
      if (optionalIdentity(root, "parent") != null)
        throw new CollectionFailure("INVALID_COMMENT_TREE");
      int before = comments.size();
      flatten(root, aid, comments, ids, 0);
      int returnedReplies = comments.size() - before - 1;
      if (nonNegativeInt(root, "reply_count") > returnedReplies) partialThreads++;
    }
    if (comments.isEmpty()) throw new CollectionFailure("NO_COMMENTS");
    Set<String> internalIds = new HashSet<>();
    for (var comment : comments) internalIds.add(comment.id());
    // 保留来源给出的parent，不把遗漏的父评论改接到外层根评论；缺口单独声明。
    List<ImportDocument.MissingParent> gaps = new ArrayList<>();
    for (var comment : comments) {
      if (comment.parentId() != null && !internalIds.contains(comment.parentId()))
        gaps.add(new ImportDocument.MissingParent(comment.id(), comment.parentId()));
    }

    String creator = sha256("bilibili-owner:" + identity(video, "owner_mid"));
    var content =
        new ImportDocument.Content(
            "mc-bili-" + aid,
            "BILIBILI",
            aid,
            creator,
            text(video, "title", 200),
            epochSeconds(video, "pubdate"));
    String completeness =
        partialThreads > 0 || !gaps.isEmpty()
            ? "PARTIAL_FAILED"
            : roots.size() >= rootLimit ? "TRUNCATED" : "NOT_PROVEN";
    return new Converted(
        new ImportDocument(
            "1.0",
            content,
            List.copyOf(comments),
            List.of(),
            gaps.isEmpty() ? null : List.copyOf(gaps)),
        bvid,
        aid,
        sourceReported,
        roots.size(),
        comments.size(),
        partialThreads,
        completeness);
  }

  /** 深度、总量和来源作品均在递归时验证，防止畸形树耗尽内存或串入其他作品。 */
  private void flatten(
      JsonNode node, String aid, List<ImportDocument.Comment> output, Set<String> ids, int depth) {
    if (!node.isObject()) throw new CollectionFailure("INVALID_COMMENT_TREE");
    if (depth > 16) throw new CollectionFailure("COMMENT_DEPTH_LIMIT");
    if (output.size() >= 5000) throw new CollectionFailure("COMMENT_COUNT_LIMIT");
    if (!aid.equals(identity(node, "oid")) || nonNegativeInt(node, "type") != 1)
      throw new CollectionFailure("SOURCE_ID_MISMATCH");
    String sourceId = identity(node, "rpid");
    if (!ids.add(sourceId)) throw new CollectionFailure("DUPLICATE_COMMENT_ID");
    String parent = optionalIdentity(node, "parent");
    output.add(
        new ImportDocument.Comment(
            "mc-bili-" + sourceId,
            sourceId,
            parent == null ? null : "mc-bili-" + parent,
            text(node, "content", 10_000),
            epochSeconds(node, "ctime")));
    JsonNode replies = node.path("replies");
    if (!replies.isMissingNode() && !replies.isNull()) {
      if (!replies.isArray()) throw new CollectionFailure("INVALID_COMMENT_TREE");
      for (JsonNode reply : replies) flatten(reply, aid, output, ids, depth + 1);
    }
  }

  /** 工具输出必须是 UTF-8 JSON 数组；空评论数组由上层给出更明确错误。 */
  private JsonNode array(byte[] bytes, String kind) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_JSON_BYTES)
      throw new CollectionFailure("TOOL_OUTPUT_LIMIT");
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      JsonNode node = parser.readTree(decoded);
      if (node == null || !node.isArray()) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
      return node;
    } catch (JsonProcessingException | CharacterCodingException ignored) {
      throw new CollectionFailure("INVALID_TOOL_OUTPUT_" + kind.toUpperCase());
    }
  }

  /** 来源身份只接受无损整数或受限字符串，禁止浮点数和宽松类型转换。 */
  private String identity(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if ((!value.isTextual() && !value.isIntegralNumber())
        || !value.asText().matches("[A-Za-z0-9_-]{1,96}"))
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return value.asText();
  }

  /** parent=0 表示根评论；其余身份必须在同一批评论树中出现。 */
  private String optionalIdentity(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if ((value.isIntegralNumber() || value.isTextual()) && "0".equals(value.asText())) return null;
    return identity(node, field);
  }

  /** 数值计数必须在 Java int 非负范围内，避免截断和溢出。 */
  private int nonNegativeInt(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return value.intValue();
  }

  /** 标题和正文保留原文，但先执行明确非空与长度边界。 */
  private String text(JsonNode node, String field, int max) {
    JsonNode value = node.path(field);
    if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    return value.textValue();
  }

  /** B站 CLI 契约使用秒级 Unix 时间；限定合理年份，不能用抓取时间冒充发布时间。 */
  private java.time.OffsetDateTime epochSeconds(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (!value.isIntegralNumber() || !value.canConvertToLong())
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    try {
      var result = Instant.ofEpochSecond(value.longValue()).atOffset(ZoneOffset.UTC);
      if (result.getYear() < 2000 || result.getYear() > 2100) throw new DateTimeException("range");
      return result;
    } catch (DateTimeException ignored) {
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    }
  }

  /** 创作者身份与既有 MediaCrawler 转换脚本保持一致，避免同一作品产生无意义元数据修订。 */
  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
