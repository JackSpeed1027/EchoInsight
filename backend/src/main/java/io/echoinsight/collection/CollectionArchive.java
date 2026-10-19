/* 文件职责：追加保存每次成功采集的原始工具输出与校验清单，为复现和审计保留证据。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/** 存档目录由服务器配置决定；批次和尝试号均来自数据库，HTTP 请求不能指定路径。 */
@Component
public class CollectionArchive {
  private final BilibiliCollectionSettings settings;
  private final ObjectMapper json;

  public CollectionArchive(BilibiliCollectionSettings settings, ObjectMapper json) {
    this.settings = settings;
    this.json = json;
  }

  /** 1、创建唯一尝试目录；2、以 CREATE_NEW 写原文；3、写哈希清单；4、返回相对存储键。 */
  public String save(
      String batchId,
      int attempt,
      BilibiliUrlParser.Target target,
      BilibiliCliCollector.Payload payload) {
    return saveInternal(batchId, attempt, target, payload, null);
  }

  /** 公开分页来源与CLI分开标识，避免把新连接器的结果伪装成旧工具执行输出。 */
  public String savePaged(
      String batchId,
      int attempt,
      BilibiliUrlParser.Target target,
      BilibiliCliCollector.Payload payload,
      int completedPages) {
    if (completedPages < 1 || completedPages > PagedBilibiliCollection.MAX_PAGES)
      throw new CollectionFailure("STORAGE_BOUNDARY_ERROR");
    return saveInternal(batchId, attempt, target, payload, completedPages);
  }

  /** 只接受服务器选择的来源模式，保持旧存档格式和哈希字段兼容。 */
  private String saveInternal(
      String batchId,
      int attempt,
      BilibiliUrlParser.Target target,
      BilibiliCliCollector.Payload payload,
      Integer completedPages) {
    String key = batchId + "/attempt-" + attempt;
    if (!batchId.matches("[0-9a-f-]{36}") || attempt < 1)
      throw new CollectionFailure("STORAGE_BOUNDARY_ERROR");
    var directory = settings.storage().resolve(batchId).resolve("attempt-" + attempt).normalize();
    if (!directory.startsWith(settings.storage()))
      throw new CollectionFailure("STORAGE_BOUNDARY_ERROR");
    try {
      Files.createDirectories(directory.getParent());
      Files.createDirectory(directory);
      Files.write(directory.resolve("video.json"), payload.video(), StandardOpenOption.CREATE_NEW);
      Files.write(
          directory.resolve("comments.json"), payload.comments(), StandardOpenOption.CREATE_NEW);
      var manifest = new LinkedHashMap<String, Object>();
      manifest.put(
          "schemaVersion",
          completedPages == null ? "collection-archive-v1" : "collection-archive-v2-pages");
      manifest.put("batchId", batchId);
      manifest.put("attempt", attempt);
      manifest.put("capturedAt", java.time.Instant.now().toString());
      manifest.put("toolName", completedPages == null ? "bilibili-cli" : "bilibili-public-pages");
      manifest.put("toolVersion", completedPages == null ? "0.3.0" : "visible-pages-v1");
      if (completedPages != null) {
        manifest.put("completedPages", completedPages);
        manifest.put("checkpointAuthority", "source_collection_page");
      }
      manifest.put("adapterVersion", BilibiliCliAdapter.VERSION);
      manifest.put("platform", "BILIBILI");
      manifest.put("canonicalUrl", target.canonicalUrl());
      manifest.put("sourceId", target.sourceId());
      manifest.put("videoSha256", sha256(payload.video()));
      manifest.put("commentsSha256", sha256(payload.comments()));
      Files.writeString(
          directory.resolve("manifest.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest)
              + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW);
      return key;
    } catch (IOException e) {
      throw new CollectionFailure("ARCHIVE_WRITE_FAILED", e);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
