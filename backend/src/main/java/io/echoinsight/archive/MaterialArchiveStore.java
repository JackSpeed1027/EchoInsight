/* 文件职责：有界导出项目原始资料，并以单事务追加恢复到全新项目，保留原项目及运行历史。 */
package io.echoinsight.archive;

import static io.echoinsight.archive.MaterialArchiveProtocol.*;

import io.echoinsight.content.ContentMaterialStore;
import io.echoinsight.material.*;
import io.echoinsight.project.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 复用原导入/内容/字幕存储与项目行锁；不复制任务状态机、费用或采集完成证据。 */
@Service
public class MaterialArchiveStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ProjectImportService imports;
  private final ContentMaterialStore contents;
  private final ReferenceMaterialStore references;
  private final TranscriptMaterialStore subtitles;
  private final MaterialArchiveProtocol protocol;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  /** 依赖均为本机确定性业务；没有模型、平台网关或文件路径执行入口。 */
  public MaterialArchiveStore(
      JdbcTemplate db,
      ProjectStore projects,
      ProjectImportService imports,
      ContentMaterialStore contents,
      ReferenceMaterialStore references,
      TranscriptMaterialStore subtitles,
      MaterialArchiveProtocol protocol,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.imports = imports;
    this.contents = contents;
    this.references = references;
    this.subtitles = subtitles;
    this.protocol = protocol;
    this.json = json;
  }

  /** rawBytes为正文存储字节下界，不是JSON下载实际字节数；原文转义后还会再次限制。 */
  public record Overview(
      int snapshots,
      int contents,
      int references,
      int subtitles,
      long rawBytes,
      boolean withinBounds,
      List<String> notes) {}

  /** 映射明确旧身份和新身份；相同快照可能复用本机既有内容，材料版本总是追加。 */
  public record Receipt(
      String id,
      String projectId,
      String archiveSha256,
      Map<String, String> snapshots,
      Map<String, String> contents,
      Map<String, String> references,
      Map<String, String> subtitles,
      List<String> notes) {}

  public record Restored(Receipt receipt, boolean created) {}

  /** 不加载正文的容量预检，超界明确拒绝，不能把前60份伪称完整备份。 */
  @Transactional(readOnly = true)
  public Overview overview(String projectId) {
    projects.get(projectId);
    int sn = count("project_snapshot", projectId),
        cm = count("content_material", projectId),
        rm = count("project_reference_material", projectId),
        tm = count("project_transcript_material", projectId);
    long bytes =
        db.queryForObject(
                "SELECT COALESCE(SUM(OCTET_LENGTH(s.document_json)),0) FROM project_snapshot ps"
                    + " JOIN data_snapshot s ON s.id=ps.snapshot_id WHERE ps.project_id=?",
                Long.class,
                projectId)
            + rawBytes("content_material", "raw_text", projectId)
            + rawBytes("project_reference_material", "body_text", projectId)
            + rawBytes("project_transcript_material", "raw_text", projectId);
    return new Overview(
        sn,
        cm,
        rm,
        tm,
        bytes,
        sn <= 60 && cm <= 100 && rm <= 100 && tm <= 100 && bytes <= ARCHIVE_MAX_BYTES,
        notes());
  }

  /** 1、同一RR只读快照核对数量；2、逐条验证原持久哈希；3、封闭协议再验证；4、有界返回。 */
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Archive export(String projectId) {
    var project = projects.get(projectId);
    if (!overview(projectId).withinBounds()) throw bad(HttpStatus.PAYLOAD_TOO_LARGE, LIMIT);
    var snapshots =
        db.query(
            "SELECT s.id,s.input_hash,s.document_json FROM project_snapshot ps JOIN data_snapshot s"
                + " ON s.id=ps.snapshot_id WHERE ps.project_id=? ORDER BY ps.id",
            (r, n) -> new Snapshot(r.getString(1), r.getString(2), r.getString(3)),
            projectId);
    var contentRows =
        db.query(
            "SELECT"
                + " id,previous_id,title,source_description,format_name,raw_text,raw_sha256,segments_sha256"
                + " FROM content_material WHERE project_id=? ORDER BY created_at,id",
            (r, n) ->
                new Content(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7),
                    r.getString(8)),
            projectId);
    for (var c : contentRows) contents.get(projectId, c.id());
    var referenceRows =
        db.query(
            "SELECT id,title,source_description,body_text,body_sha256 FROM"
                + " project_reference_material WHERE project_id=? ORDER BY created_at,id",
            (r, n) ->
                new Reference(
                    r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
            projectId);
    for (var r : referenceRows) references.get(projectId, r.id());
    var subtitleRows =
        db.query(
            "SELECT"
                + " id,snapshot_id,title,source_description,format_name,raw_text,raw_sha256,segments_sha256"
                + " FROM project_transcript_material WHERE project_id=? ORDER BY created_at,id",
            (r, n) ->
                new Subtitle(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7),
                    r.getString(8)),
            projectId);
    for (var s : subtitleRows) subtitles.get(projectId, s.id());
    var archive =
        protocol.seal(
            new Data(
                VERSION,
                new ProjectInfo(project.name(), project.analysisGoal()),
                snapshots,
                contentRows,
                referenceRows,
                subtitleRows));
    try {
      protocol.check(archive);
    } catch (org.springframework.web.server.ResponseStatusException error) {
      // 用户上传文件错误属于400；服务端自身持久数据损坏则必须按500失败关闭。
      if (error.getStatusCode().value() == 413) throw error;
      throw new IllegalStateException("Stored project materials failed archive validation", error);
    }
    return archive;
  }

  /** 预检只做解析、完整性与关系校验，不创建项目、导入回执或模型任务。 */
  public Preview preview(Restore body) {
    return protocol.check(body).preview();
  }

  /** 1、写入前完整预检；2、稳定键定位并锁新项目；3、原服务追加全部资料；4、同事务记回执。 */
  @Transactional
  public Restored restore(String key, Restore body) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw bad(HttpStatus.BAD_REQUEST, "需要有效的资料恢复请求键");
    var checked = protocol.check(body);
    String signature = hash(protocol.encode(body));
    // 项目创建的唯一键/行锁串行化相同恢复键，不在默认RR中先读再锁依赖旧快照。
    var created =
        projects.create(
            "materials-restore:" + hash(projects.workspace() + "\n" + key),
            body.name(),
            body.analysisGoal());
    String projectId = created.project().id();
    var old =
        db.queryForList(
            "SELECT id,project_id,archive_sha256,request_fingerprint,receipt_json,receipt_sha256"
                + " FROM project_material_restore WHERE workspace_scope=? AND request_key=? FOR"
                + " UPDATE",
            projects.workspace(),
            key);
    if (!old.isEmpty()) {
      if (!signature.equals(old.getFirst().get("request_fingerprint")))
        throw bad(HttpStatus.CONFLICT, "恢复请求键已用于其他归档或项目要求，请核对原恢复结果");
      return new Restored(readReceipt(old.getFirst()), false);
    }
    if (!created.created()) throw bad(HttpStatus.CONFLICT, "恢复项目身份冲突，已保留原项目，请使用新的请求键");
    Map<String, String> snapshotMap = new LinkedHashMap<>(),
        contentMap = new LinkedHashMap<>(),
        referenceMap = new LinkedHashMap<>(),
        subtitleMap = new LinkedHashMap<>();
    // 各类资料稳定排序，多个项目同时恢复相同作品时保持内容行锁次序一致。
    var snapshotEntries = new ArrayList<>(checked.archive().data().snapshots());
    snapshotEntries.sort(
        Comparator.comparing(
            s -> {
              var c = checked.documents().get(s.id()).content();
              return c.sourceType() + "\n" + c.sourceContentId() + "\n" + s.inputSha256();
            }));
    for (var s : snapshotEntries) {
      String restoredId = imports.ingest(projectId, checked.documents().get(s.id())).snapshotId();
      String actual =
          db.queryForObject(
              "SELECT document_json FROM data_snapshot WHERE id=?", String.class, restoredId);
      if (!hash(actual).equals(s.inputSha256()))
        throw new IllegalStateException("Existing material snapshot corrupted");
      snapshotMap.put(s.id(), restoredId);
    }
    for (var c : checked.orderedContents()) {
      var value =
          contents.create(
              projectId,
              "archive:" + c.id(),
              c.title(),
              c.sourceDescription(),
              c.previousId() == null ? null : contentMap.get(c.previousId()),
              c.format(),
              c.rawText().getBytes(StandardCharsets.UTF_8));
      contentMap.put(c.id(), value.summary().id());
    }
    for (var r : checked.archive().data().references()) {
      var value =
          references.create(
              projectId, "archive:" + r.id(), r.title(), r.text(), r.sourceDescription());
      referenceMap.put(r.id(), value.summary().id());
    }
    for (var s : checked.archive().data().subtitles()) {
      var value =
          subtitles.create(
              projectId,
              "archive:" + s.id(),
              snapshotMap.get(s.snapshotId()),
              s.title(),
              s.sourceDescription(),
              s.format(),
              s.rawText().getBytes(StandardCharsets.UTF_8));
      subtitleMap.put(s.id(), value.summary().id());
    }
    var receipt =
        new Receipt(
            UUID.randomUUID().toString(),
            projectId,
            checked.archive().sha256(),
            Map.copyOf(snapshotMap),
            Map.copyOf(contentMap),
            Map.copyOf(referenceMap),
            Map.copyOf(subtitleMap),
            notes());
    db.update(
        "INSERT INTO"
            + " project_material_restore(id,workspace_scope,request_key,request_fingerprint,project_id,archive_sha256,receipt_json,receipt_sha256)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        receipt.id(),
        projects.workspace(),
        key,
        signature,
        projectId,
        receipt.archiveSha256(),
        protocol.encode(receipt),
        hash(protocol.encode(receipt)));
    return new Restored(receipt, true);
  }

  /** 网络重发只返回已提交回执；读取时校验哈希，不重建或覆盖任何恢复身份。 */
  private Receipt readReceipt(Map<String, Object> row) {
    String encoded = (String) row.get("receipt_json");
    if (!hash(encoded).equals(row.get("receipt_sha256")))
      throw new IllegalStateException("Material restore receipt corrupted");
    try {
      var receipt = json.readValue(encoded, Receipt.class);
      if (!receipt.id().equals(row.get("id"))
          || !receipt.projectId().equals(row.get("project_id"))
          || !receipt.archiveSha256().equals(row.get("archive_sha256")))
        throw new IllegalStateException("Material restore receipt identity mismatch");
      projects.get(receipt.projectId());
      return receipt;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Material restore receipt invalid", error);
    }
  }

  /** 表/列参数只来自本类固定常量，不接受请求中的SQL片段。 */
  private int count(String table, String projectId) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE project_id=?", Integer.class, projectId);
  }

  /** 容量先聚合数据库字节数，不为预检把所有大正文读进Java内存。 */
  private long rawBytes(String table, String column, String projectId) {
    return db.queryForObject(
        "SELECT COALESCE(SUM(OCTET_LENGTH("
            + column
            + ")),0) FROM "
            + table
            + " WHERE project_id=?",
        Long.class,
        projectId);
  }
}
