/* 文件职责：独立追加作品字幕版本，不修改评论快照，不伪造评论来满足旧导入契约。 */
package io.echoinsight.material;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 项目和作品身份决定引用资格；修订创建新版本，旧分析只读取原请求冻结的字幕段。 */
@Service
public class TranscriptMaterialStore {
  public static final String INVALID = "字幕需要有效请求键、1～120字标题和最多200字来源说明";
  public static final String MISMATCH = "字幕与本次作品不匹配，请选择该作品的字幕版本";
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final SubtitleParser parser;
  private final ObjectMapper json;

  /** 与已有资料存储共用项目锁和MySQL，不引入第二份任务/媒体运行时。 */
  public TranscriptMaterialStore(
      JdbcTemplate db, ProjectStore projects, SubtitleParser parser, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.parser = parser;
    this.json = json;
  }

  /** 列表只返回摘要；sourceDescription始终是用户声明。 */
  public record Summary(
      String id,
      String snapshotId,
      String contentId,
      String title,
      String sourceDescription,
      String format,
      int segmentCount,
      long durationMs,
      String rawSha256,
      String segmentsSha256,
      String createdAt) {}

  /** 详情保留每个来源段的完整原文/时间，背景检索另有有界选择。 */
  public record Material(Summary summary, List<ImportDocument.Segment> segments) {}

  /** 1、解析文件；2、锁项目/核对作品；3、同键核对全部身份；4、追加原文和规范段后提交。 */
  @Transactional
  public Material create(
      String projectId,
      String key,
      String snapshotId,
      String title,
      String sourceDescription,
      String format,
      byte[] bytes) {
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || title == null
        || title.isBlank()
        || title.strip().length() > 120
        || (sourceDescription != null && sourceDescription.strip().length() > 200))
      throw bad(INVALID);
    var parsed = parser.parse(format, bytes);
    projects.lock(projectId);
    var snapshot = projects.snapshot(projectId, snapshotId);
    String encoded = encode(parsed.segments()),
        rawHash = ReferenceMaterialStore.hash(parsed.raw()),
        segmentHash = ReferenceMaterialStore.hash(encoded),
        source = sourceDescription == null ? "" : sourceDescription.strip();
    var old =
        db.queryForList(
            "SELECT id FROM project_transcript_material WHERE project_id=? AND request_key=?",
            String.class,
            projectId,
            key);
    if (!old.isEmpty()) {
      var saved = get(projectId, old.getFirst());
      var s = saved.summary();
      if (!s.snapshotId().equals(snapshotId)
          || !s.title().equals(title.strip())
          || !s.sourceDescription().equals(source)
          || !s.format().equals(format)
          || !s.rawSha256().equals(rawHash))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已用于其他字幕资料");
      return saved;
    }
    String id = UUID.randomUUID().toString();
    long duration =
        parsed.segments().stream().mapToLong(ImportDocument.Segment::endMs).max().orElse(0);
    db.update(
        "INSERT INTO"
            + " project_transcript_material(id,project_id,snapshot_id,content_id,request_key,title,source_description,format_name,raw_text,raw_sha256,segments_json,segments_sha256,segment_count,duration_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        projectId,
        snapshotId,
        snapshot.contentId(),
        key,
        title.strip(),
        source,
        format,
        parsed.raw(),
        rawHash,
        encoded,
        segmentHash,
        parsed.segments().size(),
        duration);
    return get(projectId, id);
  }

  /** 每次详情核对原文与规范段两个哈希；损坏不能退回读取项目的新版字幕。 */
  @Transactional(readOnly = true)
  public Material get(String projectId, String id) {
    projects.get(projectId);
    var rows =
        db.query(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " project_transcript_material WHERE project_id=? AND id=?",
            (r, n) -> {
              String raw = r.getString("raw_text"), encoded = r.getString("segments_json");
              if (!ReferenceMaterialStore.hash(raw).equals(r.getString("raw_sha256"))
                  || !ReferenceMaterialStore.hash(encoded).equals(r.getString("segments_sha256")))
                throw new IllegalStateException("Transcript hash mismatch");
              try {
                return new Material(
                    summary(r),
                    json.readValue(encoded, new TypeReference<List<ImportDocument.Segment>>() {}));
              } catch (java.io.IOException e) {
                throw new IllegalStateException("Invalid transcript segments", e);
              }
            },
            projectId,
            id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "字幕资料不存在");
    return rows.getFirst();
  }

  /** 同项目其他作品的字幕也不能混用；同一作品新增评论版本仍可明确选择原字幕。 */
  public Material forSnapshot(String projectId, String id, String snapshotId) {
    var member = projects.snapshot(projectId, snapshotId);
    var material = get(projectId, id);
    if (!member.contentId().equals(material.summary().contentId())) throw bad(MISMATCH);
    return material;
  }

  /** 按明确快照的作品身份筛选摘要，不加载全部字幕正文。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String projectId, String snapshotId, int page, int size) {
    var member = projects.snapshot(projectId, snapshotId);
    if (page < 1 || page > 10000 || size < 1 || size > 50) throw bad("页码1～10000，每页1～50条");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM project_transcript_material WHERE project_id=? AND content_id=?",
            Long.class,
            projectId,
            member.contentId());
    var rows =
        db.query(
            "SELECT"
                + " id,snapshot_id,content_id,title,source_description,format_name,segment_count,duration_ms,raw_sha256,segments_sha256,DATE_FORMAT(created_at,'%Y-%m-%d"
                + " %H:%i:%s') created_time FROM project_transcript_material WHERE project_id=? AND"
                + " content_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (r, n) -> summary(r), projectId, member.contentId(), size, (page - 1) * size);
    return new ProjectStore.Page<>(rows, total, page, size, (total + size - 1) / size);
  }

  /** 摘要字段含两个指纹，证明内容身份，不证明字幕由UP本人或平台认证。 */
  private Summary summary(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Summary(
        r.getString("id"),
        r.getString("snapshot_id"),
        r.getString("content_id"),
        r.getString("title"),
        r.getString("source_description"),
        r.getString("format_name"),
        r.getInt("segment_count"),
        r.getLong("duration_ms"),
        r.getString("raw_sha256"),
        r.getString("segments_sha256"),
        r.getString("created_time"));
  }

  /** 规范段只由固定DTO序列化，时间仍是毫秒整数。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 固定业务错误不回显上传正文。 */
  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
