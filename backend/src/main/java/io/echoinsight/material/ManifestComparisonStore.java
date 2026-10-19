/* 文件职责：校验两份冻结集合的原文指纹，持久保存确定性差异与增量失效依据。 */
package io.echoinsight.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.project.ProjectStore;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 比较只读不可变快照，输出追加保存；不删除缺失数据，不自动复用模型结论。 */
@Service
public class ManifestComparisonStore {
  public static final String VERSION = "manifest-diff-v1";
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final InputManifestStore manifests;
  private final SnapshotDiffer differ;
  private final ObjectMapper json;

  /** 共用项目范围和快照，不用聊天历史或前端缓存判断增量。 */
  public ManifestComparisonStore(
      JdbcTemplate db,
      ProjectStore projects,
      InputManifestStore manifests,
      SnapshotDiffer differ,
      ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.manifests = manifests;
    this.differ = differ;
    this.json = json;
  }

  /** 每个来源作品独立统计；新增/缺失作品不冒充同作品评论差分。 */
  public record Work(
      String sourceType,
      String sourceContentId,
      String title,
      String status,
      String baseSnapshotId,
      String targetSnapshotId,
      boolean metadataChanged,
      boolean collectionScopeChanged,
      SnapshotDiffer.Difference comments) {}

  /** 结果绑定算法版本，候选复用数仍需同时满足目标、配方、模型和规则版本。 */
  public record Result(
      String algorithmVersion,
      int baseWorkCount,
      int targetWorkCount,
      List<Work> works,
      List<String> limitations) {}

  /** 比较身份与结果长期保存，后续重发或查看不会重新调用模型。 */
  public record Comparison(
      String id,
      String projectId,
      String baseManifestId,
      String targetManifestId,
      String createdAt,
      Result result) {}

  /** 1、验证项目与有界清单；2、核对存量幂等；3、逐作品全量差分；4、原子保存结果。 */
  @Transactional
  public Comparison create(String projectId, String key, String baseId, String targetId) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效比较请求键");
    // 比较和其他项目写入保持同一锁顺序；先取得锁再建立读取快照。
    projects.lock(projectId);
    var base = manifests.get(projectId, baseId);
    var target = manifests.get(projectId, targetId);
    if (base.items().stream().mapToInt(InputManifestStore.Item::commentCount).sum() > 20000
        || target.items().stream().mapToInt(InputManifestStore.Item::commentCount).sum() > 20000)
      throw bad("单次比较每侧最多20000条评论，请缩小集合");
    var existing =
        db.queryForList(
            "SELECT id,base_manifest_id,target_manifest_id FROM manifest_comparison WHERE"
                + " project_id=? AND request_key=?",
            projectId,
            key);
    if (!existing.isEmpty()) {
      var saved = existing.getFirst();
      if (!baseId.equals(saved.get("base_manifest_id"))
          || !targetId.equals(saved.get("target_manifest_id")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求键已用于其他集合比较");
      return get(projectId, (String) saved.get("id"));
    }
    var oldItems = index(base.items());
    var newItems = index(target.items());
    Set<String> identities = new TreeSet<>(oldItems.keySet());
    identities.addAll(newItems.keySet());
    List<Work> changes = new ArrayList<>();
    for (String identity : identities) {
      var oldItem = oldItems.get(identity);
      var newItem = newItems.get(identity);
      var selected = newItem == null ? oldItem : newItem;
      if (oldItem == null || newItem == null) {
        changes.add(
            new Work(
                selected.sourceType(),
                selected.sourceContentId(),
                selected.title(),
                oldItem == null ? "ADDED_WORK" : "UNKNOWN_MISSING_WORK",
                oldItem == null ? null : oldItem.snapshotId(),
                newItem == null ? null : newItem.snapshotId(),
                false,
                false,
                null));
        continue;
      }
      ImportDocument before = document(oldItem);
      ImportDocument after = document(newItem);
      boolean metadata =
          !before.content().equals(after.content())
              || !before.transcriptSegments().equals(after.transcriptSegments());
      boolean scope =
          !Objects.equals(oldItem.collectionId(), newItem.collectionId())
              || !Objects.equals(oldItem.sourceCompleteness(), newItem.sourceCompleteness());
      var difference = differ.compare(before, after);
      String status =
          oldItem.snapshotId().equals(newItem.snapshotId()) && !scope
              ? "UNCHANGED"
              : "REVISED_WORK";
      changes.add(
          new Work(
              selected.sourceType(),
              selected.sourceContentId(),
              selected.title(),
              status,
              oldItem.snapshotId(),
              newItem.snapshotId(),
              metadata,
              scope,
              difference));
    }
    var result =
        new Result(
            VERSION,
            base.items().size(),
            target.items().size(),
            List.copyOf(changes),
            List.of(
                "UNKNOWN_MISSING表示目标集合或采集结果中未出现，不证明平台删除；旧资料保留。",
                "上下文稳定数只是局部抽取的候选，不是实际复用量或节省；目标、模型、提示、规则和资料角色仍需一致。",
                "新增回复也可能改变整串议题、观点合并和评议，跨评论及集合产物需要重新计算。",
                "来源ID样本每类最多100条；计数使用全部选中输入。本次不调用模型。"));
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " manifest_comparison(id,project_id,request_key,base_manifest_id,target_manifest_id,algorithm_version,result_json)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        projectId,
        key,
        baseId,
        targetId,
        VERSION,
        encode(result));
    return get(projectId, id);
  }

  /** 历史详情按项目校验；读取已存JSON而非用新算法改写旧结果。 */
  @Transactional(readOnly = true)
  public Comparison get(String projectId, String id) {
    projects.get(projectId);
    return db
        .query(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " manifest_comparison WHERE id=? AND project_id=?",
            (r, n) ->
                new Comparison(
                    r.getString("id"),
                    projectId,
                    r.getString("base_manifest_id"),
                    r.getString("target_manifest_id"),
                    r.getString("created_time"),
                    decode(r.getString("result_json"))),
            id,
            projectId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "集合比较不存在"));
  }

  /** 分页回看比较记录，页面刷新后仍有入口；每页最多10个有界结果。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Comparison> list(String projectId, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 10) throw bad("每页1～10份比较");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM manifest_comparison WHERE project_id=?", Long.class, projectId);
    var ids =
        db.queryForList(
            "SELECT id FROM manifest_comparison WHERE project_id=? ORDER BY created_at DESC,id DESC"
                + " LIMIT ? OFFSET ?",
            String.class,
            projectId,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(
        ids.stream().map(id -> get(projectId, id)).toList(),
        total,
        page,
        size,
        (total + size - 1) / size);
  }

  /** 指纹先核对再解析，防止被破坏的存储内容冒充原冻结输入。 */
  private ImportDocument document(InputManifestStore.Item item) {
    String raw =
        db.queryForObject(
            "SELECT document_json FROM data_snapshot WHERE id=?", String.class, item.snapshotId());
    try {
      String actual =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      if (!actual.equals(item.documentSha256()))
        throw new IllegalStateException("Frozen document digest mismatch");
      return json.readValue(raw, ImportDocument.class);
    } catch (java.security.NoSuchAlgorithmException
        | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid frozen document", e);
    }
  }

  /** 平台与来源ID联合定位，避免B站/小红书同数字被错误关联。 */
  private Map<String, InputManifestStore.Item> index(List<InputManifestStore.Item> items) {
    Map<String, InputManifestStore.Item> result = new TreeMap<>();
    for (var item : items) result.put(item.sourceType() + ":" + item.sourceContentId(), item);
    return result;
  }

  /** 保存的是有版本的计算结果，不包含数据库连接和模型凭据。 */
  private String encode(Result result) {
    try {
      return json.writeValueAsString(result);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Cannot encode comparison", e);
    }
  }

  /** 当前算法对应的严格DTO读取，结构不合法时不能静默返回空结果。 */
  private Result decode(String raw) {
    try {
      return json.readValue(raw, Result.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Invalid stored comparison", e);
    }
  }

  /** 不满足输入上限的请求终止，不截断后假装全量对账。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
