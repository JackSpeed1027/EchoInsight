/* 文件职责：在接受分析请求时冻结有界字幕与显式参考文本，让历史任务不随项目资料追加而漂移。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 单次请求已经是冻结边界，因此只增加一份请求上下文，不另造会话、任务或通用清单状态机。 */
@Service
public class AnalysisContextStore {
  public static final String VERSION = "discussion-context-v1";
  public static final int MAX_REFERENCE_CHARS = 12000;
  public static final int MAX_TRANSCRIPT_CHARS = 8000;
  public static final int MAX_SEGMENTS = 40;
  public static final String FOLLOWUP_VERSION = "discussion-context-v3-followup";
  public static final String RETRIEVAL_VERSION = "discussion-context-v2-search";
  public static final String CONTENT_VERSION = "discussion-context-v4-content";
  private final io.echoinsight.content.ContentMaterialStore contentMaterials;
  private final io.echoinsight.material.TranscriptMaterialStore transcripts;
  private final io.echoinsight.material.TranscriptRetriever retriever;
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ReferenceMaterialStore references;
  private final ObjectMapper json;

  /** 资料资格和原文复用已有项目与快照，不从客户端接受正文位置或外部抓取地址。 */
  public AnalysisContextStore(
      JdbcTemplate db,
      ProjectStore projects,
      ReferenceMaterialStore references,
      ObjectMapper json,
      io.echoinsight.material.TranscriptMaterialStore transcripts,
      io.echoinsight.material.TranscriptRetriever retriever,
      io.echoinsight.content.ContentMaterialStore contentMaterials) {
    this.contentMaterials = contentMaterials;
    this.transcripts = transcripts;
    this.retriever = retriever;
    this.db = db;
    this.projects = projects;
    this.references = references;
    this.json = json;
  }

  /** 角色由服务器固定：参考文本是用户提供背景，字幕是当前作品原文，都不是观众评论。 */
  public enum Role {
    REFERENCE_TEXT,
    TRANSCRIPT,
    CONTENT_TEXT
  }

  /** 模型输入的独立命名空间，保留资料身份、原始字幕段身份和时间，不与评论ID相撞。 */
  public record Source(
      String id,
      Role role,
      String materialId,
      String title,
      String sourceDescription,
      String sourceSegmentId,
      Long startMs,
      Long endMs,
      String text,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Integer startChar,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Integer endChar) {
    /** 旧字幕/参考记录没有字符字段，原JSON及检查点编码保持不变。 */
    public Source(
        String id,
        Role role,
        String materialId,
        String title,
        String sourceDescription,
        String sourceSegmentId,
        Long startMs,
        Long endMs,
        String text) {
      this(
          id,
          role,
          materialId,
          title,
          sourceDescription,
          sourceSegmentId,
          startMs,
          endMs,
          text,
          null,
          null);
    }
  }

  /** 所选独立内容的原版本、范围、词法策略与缺口，不能把显式关联当作者认证。 */
  public record ContentBinding(
      String materialId,
      String title,
      String rawSha256,
      String segmentsSha256,
      Long fromMs,
      Long toMs,
      String strategy,
      String querySha256,
      int totalSegments,
      int eligibleSegments,
      int matchedSegments,
      int selectedSegments,
      int selectedChars,
      int oversizedMatches,
      List<String> segmentIds,
      List<String> notes) {}

  /** 每个选中参考版本都有内容指纹；上下文总字符也明确保存。 */
  public record Reference(String id, String title, String bodySha256) {}

  /** 截断只作用于按时间排序的完整字幕段前缀；参考文本必须完整且合计不超限。 */
  public record Selection(
      String version,
      String snapshotId,
      boolean transcriptRequested,
      int totalTranscriptSegments,
      int selectedTranscriptSegments,
      boolean transcriptTruncated,
      List<Reference> references,
      List<Source> sources,
      int textChars,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Retrieval retrieval,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          History history,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ContentBinding content) {
    /** v3追问记录缺少独立内容绑定时不补字段，兼容原冻结哈希。 */
    public Selection(
        String version,
        String snapshotId,
        boolean transcriptRequested,
        int totalTranscriptSegments,
        int selectedTranscriptSegments,
        boolean transcriptTruncated,
        List<Reference> references,
        List<Source> sources,
        int textChars,
        Retrieval retrieval,
        History history) {
      this(
          version,
          snapshotId,
          transcriptRequested,
          totalTranscriptSegments,
          selectedTranscriptSegments,
          transcriptTruncated,
          references,
          sources,
          textChars,
          retrieval,
          history,
          null);
    }

    /** 保持第48轮十字段构造可用，旧JSON无history字段。 */
    public Selection(
        String version,
        String snapshotId,
        boolean transcriptRequested,
        int totalTranscriptSegments,
        int selectedTranscriptSegments,
        boolean transcriptTruncated,
        List<Reference> references,
        List<Source> sources,
        int textChars,
        Retrieval retrieval) {
      this(
          version,
          snapshotId,
          transcriptRequested,
          totalTranscriptSegments,
          selectedTranscriptSegments,
          transcriptTruncated,
          references,
          sources,
          textChars,
          retrieval,
          null);
    }

    /** 旧v1构造与JSON不新增字段，避免改变历史背景指纹。 */
    public Selection(
        String version,
        String snapshotId,
        boolean transcriptRequested,
        int totalTranscriptSegments,
        int selectedTranscriptSegments,
        boolean transcriptTruncated,
        List<Reference> references,
        List<Source> sources,
        int textChars) {
      this(
          version,
          snapshotId,
          transcriptRequested,
          totalTranscriptSegments,
          selectedTranscriptSegments,
          transcriptTruncated,
          references,
          sources,
          textChars,
          null);
    }
  }

  /** 追问只携带明确评论与上一份产物身份；历史模型判断作为待复核背景，不能认证事实。 */
  public record History(
      String parentRequestId,
      String rootRequestId,
      String anchorCommentId,
      int depth,
      String parentArtifactSha256,
      String historySha256) {}

  /** 1、保留原背景段；2、移除上一轮导航摘录避免累积；3、校验总量；4、追加本轮可追溯历史。 */
  public View withHistory(String snapshotId, View parent, History history, String text) {
    var previous = parent.selection();
    var sources = new ArrayList<Source>();
    if (previous != null) {
      previous.sources().stream()
          .filter(s -> !s.id().equals("context-history"))
          .forEach(sources::add);
    }
    long referenceCount =
        sources.stream().filter(source -> source.role() == Role.REFERENCE_TEXT).count();
    int referenceChars =
        sources.stream()
            .filter(source -> source.role() == Role.REFERENCE_TEXT)
            .mapToInt(source -> source.text().length())
            .sum();
    if (referenceCount >= 3 || referenceChars + text.length() > 12000) {
      throw bad("追问需要一个背景位置：原参考最多2份，参考与历史合计12000字，请缩小背景后新建分析");
    }
    if (sources.stream().anyMatch(source -> source.id().equals("context-history"))) {
      throw new IllegalStateException("History source was not removed");
    }
    int inherited = sources.stream().mapToInt(s -> s.text().length()).sum();
    if (text.length() > 4000 || inherited + text.length() > 20000) {
      throw bad("追问背景超过4000字或总背景20000字，请新建独立分析并缩小资料范围");
    }
    sources.add(
        new Source(
            "context-history",
            Role.REFERENCE_TEXT,
            history.parentRequestId(),
            "上一轮问题与所选评论的判断摘录",
            "系统保存的历史模型输出或结构演示，未经事实认证；只供复核，不是创作者声明",
            null,
            null,
            null,
            text));
    var selection =
        new Selection(
            FOLLOWUP_VERSION,
            snapshotId,
            previous != null && previous.transcriptRequested(),
            previous == null ? 0 : previous.totalTranscriptSegments(),
            previous == null ? 0 : previous.selectedTranscriptSegments(),
            previous != null && previous.transcriptTruncated(),
            previous == null ? List.of() : previous.references(),
            List.copyOf(sources),
            inherited + text.length(),
            previous == null ? null : previous.retrieval(),
            history,
            previous == null ? null : previous.content());
    return new View(ReferenceMaterialStore.hash(encode(selection)), selection);
  }

  /** 只记录可验证的来源/查询身份及检索事实，不存模型推断的“相关度”。 */
  public record Retrieval(
      String materialId,
      String segmentsSha256,
      String strategy,
      String querySha256,
      int matchedSegments) {}

  /** 新检索选择版本不改变v4/v5/v6模型输出协议；旧版本继续按原规则执行。 */
  public static boolean supported(String version) {
    return VERSION.equals(version)
        || RETRIEVAL_VERSION.equals(version)
        || FOLLOWUP_VERSION.equals(version)
        || CONTENT_VERSION.equals(version);
  }

  /** null上下文专门表示历史/纯评论请求，不伪造其背景处理状态。 */
  public record View(String fingerprint, Selection selection) {
    public static View none() {
      return new View(null, null);
    }
  }

  /** 1、核对参考资格；2、读取固定快照字幕；3、按角色和硬限选取；4、对整个选取正文求指纹。 */
  @Transactional(readOnly = true)
  public View prepare(
      String projectId, String snapshotId, boolean includeTranscript, List<String> referenceIds) {
    projects.snapshot(projectId, snapshotId);
    List<String> ids = referenceIds == null ? List.of() : referenceIds;
    if (ids.size() > 3
        || ids.stream().anyMatch(Objects::isNull)
        || new HashSet<>(ids).size() != ids.size()) throw bad("最多选择3份不重复参考资料");
    if (!includeTranscript && ids.isEmpty()) return View.none();
    List<Reference> chosen = new ArrayList<>();
    List<Source> sources = new ArrayList<>();
    int referenceChars = 0;
    for (String id : ids.stream().sorted().toList()) {
      var material = references.get(projectId, id);
      referenceChars = Math.addExact(referenceChars, material.text().length());
      if (referenceChars > MAX_REFERENCE_CHARS) throw bad("本次参考文本合计最多12000字，请缩小范围");
      chosen.add(new Reference(id, material.summary().title(), material.summary().bodySha256()));
      sources.add(
          new Source(
              "context-reference-" + chosen.size(),
              Role.REFERENCE_TEXT,
              id,
              material.summary().title(),
              material.summary().sourceDescription(),
              null,
              null,
              null,
              material.text()));
    }
    ImportDocument document;
    try {
      document =
          json.readValue(
              db.queryForObject(
                  "SELECT document_json FROM data_snapshot WHERE id=?", String.class, snapshotId),
              ImportDocument.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid context snapshot", e);
    }
    var segments =
        document.transcriptSegments().stream()
            .sorted(
                Comparator.comparing(ImportDocument.Segment::startMs)
                    .thenComparing(ImportDocument.Segment::id))
            .toList();
    int transcriptChars = 0, selected = 0;
    if (includeTranscript)
      for (var segment : segments) {
        if (selected == MAX_SEGMENTS
            || transcriptChars + segment.text().length() > MAX_TRANSCRIPT_CHARS) break;
        selected++;
        transcriptChars += segment.text().length();
        sources.add(
            new Source(
                "context-transcript-" + selected,
                Role.TRANSCRIPT,
                snapshotId,
                document.content().title(),
                "当前快照自带字幕，未做外部真实性校验",
                segment.id(),
                segment.startMs(),
                segment.endMs(),
                segment.text()));
      }
    var selection =
        new Selection(
            VERSION,
            snapshotId,
            includeTranscript,
            segments.size(),
            selected,
            includeTranscript && selected < segments.size(),
            List.copyOf(chosen),
            List.copyOf(sources),
            referenceChars + transcriptChars);
    return new View(ReferenceMaterialStore.hash(encode(selection)), selection);
  }

  /** 1、核对上传字幕的作品身份；2、按本次问题检索完整段及邻域；3、并入参考；4、冻结来源/策略/原文。 */
  @Transactional(readOnly = true)
  public View prepareWithMaterial(
      String projectId,
      String snapshotId,
      boolean includeTranscript,
      List<String> referenceIds,
      String transcriptMaterialId,
      String instruction) {
    if (transcriptMaterialId == null || transcriptMaterialId.isBlank())
      return prepare(projectId, snapshotId, includeTranscript, referenceIds);
    if (includeTranscript) throw bad("独立字幕与快照自带字幕不能同时选择");
    var base = prepare(projectId, snapshotId, false, referenceIds);
    var material = transcripts.forSnapshot(projectId, transcriptMaterialId, snapshotId);
    var result = retriever.select(material.segments(), instruction);
    var sources = new ArrayList<Source>();
    var references = List.<Reference>of();
    int referenceChars = 0;
    if (base.selection() != null) {
      sources.addAll(base.selection().sources());
      references = base.selection().references();
      referenceChars = base.selection().textChars();
    }
    int selected = 0, chars = 0;
    for (var segment : result.segments()) {
      selected++;
      chars += segment.text().length();
      sources.add(
          new Source(
              "context-transcript-" + selected,
              Role.TRANSCRIPT,
              transcriptMaterialId,
              material.summary().title(),
              "上传字幕，来源未自动认证；" + material.summary().sourceDescription(),
              segment.id(),
              segment.startMs(),
              segment.endMs(),
              segment.text()));
    }
    var selection =
        new Selection(
            RETRIEVAL_VERSION,
            snapshotId,
            true,
            material.segments().size(),
            selected,
            selected < material.segments().size(),
            references,
            List.copyOf(sources),
            referenceChars + chars,
            new Retrieval(
                transcriptMaterialId,
                material.summary().segmentsSha256(),
                result.strategy(),
                result.querySha256(),
                result.matchedSegments()));
    return new View(ReferenceMaterialStore.hash(encode(selection)), selection);
  }

  /** 1、先冻结旧背景；2、核对明确内容版本同项目；3、取有界原文；4、合并并按总限额拒绝，不截断来源。 */
  @Transactional(readOnly = true)
  public View prepareAll(
      String projectId,
      String snapshotId,
      boolean includeTranscript,
      List<String> referenceIds,
      String transcriptMaterialId,
      String instruction,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs) {
    var base =
        prepareWithMaterial(
            projectId,
            snapshotId,
            includeTranscript,
            referenceIds,
            transcriptMaterialId,
            instruction);
    if (contentMaterialId == null || contentMaterialId.isBlank()) {
      if (contentFromMs != null || contentToMs != null) throw bad("内容时间范围需要先选择独立材料");
      return base;
    }
    var material = contentMaterials.get(projectId, contentMaterialId);
    var chosen =
        io.echoinsight.content.ContentEvidenceSelector.select(
            material, instruction, contentFromMs, contentToMs);
    var sources = new ArrayList<Source>();
    if (base.selection() != null) sources.addAll(base.selection().sources());
    int inherited = base.selection() == null ? 0 : base.selection().textChars();
    if (inherited + chosen.selectedChars() > 20000) throw bad("本次全部背景合计最多20000字，请减少参考资料或缩小内容范围");
    for (var segment : chosen.segments())
      sources.add(
          new Source(
              "context-content-"
                  + (sources.stream().filter(x -> x.role() == Role.CONTENT_TEXT).count() + 1),
              Role.CONTENT_TEXT,
              material.summary().id(),
              material.summary().title(),
              material.summary().sourceDescription(),
              segment.id(),
              segment.startMs(),
              segment.endMs(),
              segment.text(),
              segment.startChar(),
              segment.endChar()));
    var binding =
        new ContentBinding(
            material.summary().id(),
            material.summary().title(),
            material.summary().rawSha256(),
            material.summary().segmentsSha256(),
            contentFromMs,
            contentToMs,
            chosen.strategy(),
            chosen.querySha256(),
            chosen.totalSegments(),
            chosen.eligibleSegments(),
            chosen.matchedSegments(),
            chosen.segments().size(),
            chosen.selectedChars(),
            chosen.oversizedMatches(),
            chosen.segments().stream()
                .map(io.echoinsight.content.ContentMaterialParser.Segment::id)
                .toList(),
            chosen.notes());
    var old = base.selection();
    var selection =
        new Selection(
            CONTENT_VERSION,
            snapshotId,
            old != null && old.transcriptRequested(),
            old == null ? 0 : old.totalTranscriptSegments(),
            old == null ? 0 : old.selectedTranscriptSegments(),
            old != null && old.transcriptTruncated(),
            old == null ? List.of() : old.references(),
            List.copyOf(sources),
            inherited + chosen.selectedChars(),
            old == null ? null : old.retrieval(),
            null,
            binding);
    return new View(ReferenceMaterialStore.hash(encode(selection)), selection);
  }

  /** 由AnalysisRequestStore同事务调用；请求和冻结背景必须同时可见，不能留下半份输入。 */
  public void save(String requestId, View view) {
    if (view.selection() != null)
      db.update(
          "INSERT INTO analysis_request_context(request_id,context_fingerprint,context_json)"
              + " VALUES(?,?,?)",
          requestId,
          view.fingerprint(),
          encode(view.selection()));
  }

  /** 读取前先核对请求项目；原文/指纹损坏直接失败，不退回读取项目最新材料。 */
  @Transactional(readOnly = true)
  public View get(String projectId, String requestId) {
    projects.get(projectId);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM analysis_request WHERE id=? AND project_id=?",
            Integer.class,
            requestId,
            projectId)
        == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析请求不存在");
    var rows =
        db.queryForList(
            "SELECT context_fingerprint,context_json FROM analysis_request_context WHERE"
                + " request_id=?",
            requestId);
    if (rows.isEmpty()) return View.none();
    String encoded = (String) rows.getFirst().get("context_json"),
        fingerprint = (String) rows.getFirst().get("context_fingerprint");
    if (!ReferenceMaterialStore.hash(encoded).equals(fingerprint))
      throw new IllegalStateException("Frozen analysis context hash mismatch");
    try {
      return new View(fingerprint, json.readValue(encoded, Selection.class));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored analysis context", e);
    }
  }

  /** JSON只由固定DTO生成，编码失败不能降级为空背景。 */
  private String encode(Selection selection) {
    try {
      return json.writeValueAsString(selection);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 超限必须明确拒绝，避免悄悄少读用户勾选的参考文。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
