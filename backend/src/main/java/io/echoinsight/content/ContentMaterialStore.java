/* 文件职责：项目内追加独立内容版本，保留原文、父版本及定位哈希，不伪造评论。 */
package io.echoinsight.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 新版本只追加；previousId为用户明确选择，版本深度不是全局序号，允许保留分支。 */
@Service
public class ContentMaterialStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ContentMaterialParser parser;
  private final ObjectMapper json;

  /** 同一本机库、项目锁与解析器，无远程媒体服务或第二套存储。 */
  public ContentMaterialStore(
      JdbcTemplate db, ProjectStore projects, ContentMaterialParser parser, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.parser = parser;
    this.json = json;
  }

  /** 只读列表摘要不包含正文。来源说明属于用户声明。 */
  public record Summary(
      String id,
      String projectId,
      String previousId,
      int versionDepth,
      String title,
      String sourceDescription,
      String format,
      int segmentCount,
      int textChars,
      String rawSha256,
      String segmentsSha256,
      String createdAt) {}

  /** 不可变原文段，用于独立分析冻结及证据回看。 */
  public record Material(Summary summary, List<ContentMaterialParser.Segment> segments) {}

  /** 1、严格解析；2、锁项目；3、同键比较内容/父版本；4、追加后返回，不覆盖原材料。 */
  @Transactional
  public Material create(
      String p,
      String key,
      String title,
      String source,
      String previous,
      String format,
      byte[] bytes) {
    validateKey(key);
    if (title == null
        || title.isBlank()
        || title.strip().length() > 120
        || (source != null && source.strip().length() > 200)) throw bad("标题1～120字，来源说明最多200字");
    var parsed = parser.parse(format, bytes);
    projects.lock(p);
    String cleanSource = source == null ? "" : source.strip();
    String rawHash = ReferenceMaterialStore.hash(parsed.raw());
    var old =
        db.queryForList(
            "SELECT id FROM content_material WHERE project_id=? AND request_key=?",
            String.class,
            p,
            key);
    if (!old.isEmpty()) {
      var saved = get(p, old.getFirst());
      var meta = saved.summary();
      if (!Objects.equals(previous, meta.previousId())
          || !title.strip().equals(meta.title())
          || !cleanSource.equals(meta.sourceDescription())
          || !format.equals(meta.format())
          || !rawHash.equals(meta.rawSha256())) throw conflict("请求键已用于另一份内容材料");
      return saved;
    }
    int depth = previous == null ? 1 : get(p, previous).summary().versionDepth() + 1;
    if (depth > 100) throw bad("单条材料版本分支最多100层，请另建材料");
    String id = UUID.randomUUID().toString(), encoded = encode(parsed.segments());
    db.update(
        """
        INSERT INTO content_material(id,project_id,previous_id,version_depth,request_key,title,source_description,
         format_name,raw_text,raw_sha256,segments_json,segments_sha256,segment_count,text_chars)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        id,
        p,
        previous,
        depth,
        key,
        title.strip(),
        cleanSource,
        format,
        parsed.raw(),
        rawHash,
        encoded,
        ReferenceMaterialStore.hash(encoded),
        parsed.segments().size(),
        parsed.segments().stream().mapToInt(x -> x.text().length()).sum());
    return get(p, id);
  }

  /** 每次使用重新核对原文/分段哈希；损坏时拒绝，不能换成“最新版本”继续分析。 */
  @Transactional(readOnly = true)
  public Material get(String p, String id) {
    projects.get(p);
    var result =
        db.query(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " content_material WHERE project_id=? AND id=?",
            (r, n) -> {
              if (!ReferenceMaterialStore.hash(r.getString("raw_text"))
                      .equals(r.getString("raw_sha256"))
                  || !ReferenceMaterialStore.hash(r.getString("segments_json"))
                      .equals(r.getString("segments_sha256")))
                throw new IllegalStateException("Content material hash mismatch");
              try {
                return new Material(
                    summary(r),
                    json.readValue(
                        r.getString("segments_json"),
                        new TypeReference<List<ContentMaterialParser.Segment>>() {}));
              } catch (java.io.IOException e) {
                throw new IllegalStateException("Invalid content segments", e);
              }
            },
            p,
            id);
    if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容材料不存在");
    return result.getFirst();
  }

  /** 同一只读事务计数与取页；上传新版本不隐藏旧版本，也不修改已有报告。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String p, int page, int size) {
    projects.get(p);
    validatePage(page, size);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM content_material WHERE project_id=?", Long.class, p);
    var rows =
        db.query(
            "SELECT"
                + " id,project_id,previous_id,version_depth,title,source_description,format_name,segment_count,text_chars,raw_sha256,segments_sha256,DATE_FORMAT(created_at,'%Y-%m-%d"
                + " %H:%i:%s') created_time FROM content_material WHERE project_id=? ORDER BY"
                + " created_at DESC,id DESC LIMIT ? OFFSET ?",
            (r, n) -> summary(r), p, size, (page - 1) * size);
    return new ProjectStore.Page<>(List.copyOf(rows), total, page, size, (total + size - 1) / size);
  }

  /** 服务端分页原文段，可按字幕区间或归一化子串检索；此查询不创建任务或计费。 */
  public ProjectStore.Page<ContentMaterialParser.Segment> segments(
      String p, String id, String query, Long from, Long to, int page, int size) {
    validatePage(page, size);
    if (query != null && query.length() > 80) throw bad("原文搜索最多80字");
    var material = get(p, id);
    var selected = ContentSelection.candidates(material, from, to);
    String term =
        query == null
            ? ""
            : java.text.Normalizer.normalize(query.strip(), java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    var matches =
        selected.stream()
            .filter(
                x ->
                    java.text.Normalizer.normalize(x.text(), java.text.Normalizer.Form.NFKC)
                        .toLowerCase(Locale.ROOT)
                        .contains(term))
            .toList();
    int start = Math.min((page - 1) * size, matches.size()),
        end = Math.min(start + size, matches.size());
    return new ProjectStore.Page<>(
        matches.subList(start, end),
        matches.size(),
        page,
        size,
        (matches.size() + size - 1) / size);
  }

  /** 固定列读取，避免列表通过正文长度隐式载入整份原文。 */
  private Summary summary(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Summary(
        r.getString("id"),
        r.getString("project_id"),
        r.getString("previous_id"),
        r.getInt("version_depth"),
        r.getString("title"),
        r.getString("source_description"),
        r.getString("format_name"),
        r.getInt("segment_count"),
        r.getInt("text_chars"),
        r.getString("raw_sha256"),
        r.getString("segments_sha256"),
        r.getString("created_time"));
  }

  /** 统一有界参数，不接受任意文件路径。 */
  public static void validatePage(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 50) throw bad("页码1～10000，每页1～50条");
  }

  /** 业务幂等键不会复用为供应商请求身份。 */
  public static void validateKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效请求键");
  }

  /** 固定DTO序列化，用于冻结及哈希，不输出对象的任意toString。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 字段错误统一400。 */
  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 同键改变内容统一409，不覆盖既有材料。 */
  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
