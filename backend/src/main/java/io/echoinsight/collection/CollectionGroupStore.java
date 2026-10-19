/* 文件职责：把明确选择的1～5个候选原子展开为现有采集任务，保留部分成功并显式冻结分析范围。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.material.InputManifestStore;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 组合表只记录成员关系；进度从子批次派生，不另建采集Worker或第二份事实源。 */
@Service
public class CollectionGroupStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final CreatorDiscoveryStore discoveries;
  private final CollectionStore batches;
  private final BilibiliUrlParser urls;
  private final InputManifestStore manifests;
  private final ObjectMapper json;

  /** 使用原有采集和冻结服务，数据库事务覆盖全部子批次的接受过程。 */
  public CollectionGroupStore(
      JdbcTemplate db,
      ProjectStore projects,
      CreatorDiscoveryStore discoveries,
      CollectionStore batches,
      BilibiliUrlParser urls,
      InputManifestStore manifests,
      ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.discoveries = discoveries;
    this.batches = batches;
    this.urls = urls;
    this.manifests = manifests;
    this.json = json;
  }

  /** 每项保留候选身份、标题及原批次状态，错误不掩盖其他成功结果。 */
  public record Item(String bvid, String title, CollectionStore.Batch batch) {}

  /** pending与failed都按真实子状态统计；成功不等于平台全量。 */
  public record Group(
      String id,
      String discoveryId,
      String status,
      int rootLimit,
      int succeeded,
      int failed,
      int pending,
      int cancelled,
      List<Item> items,
      List<String> limitations) {}

  /** 1、核对候选成功和选择；2、锁项目核对幂等；3、同事务创建所有子批次；4、返回派生状态。 */
  @Transactional
  public Group create(
      String projectId, String key, String discoveryId, List<String> bvids, int rootLimit) {
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || bvids == null
        || bvids.isEmpty()
        || bvids.size() > 5
        || bvids.stream().anyMatch(Objects::isNull)
        || new HashSet<>(bvids).size() != bvids.size()
        || rootLimit < 1
        || rootLimit > 200) throw bad("一次选择1～5个不重复作品，每作品根评论上限1～200");
    // 先锁项目再读取成员和幂等记录，保证并发重发采用锁后的同一读取时点。
    projects.lock(projectId);
    var discovery = discoveries.get(projectId, discoveryId);
    if (!"SUCCESS".equals(discovery.status())) throw conflict("候选发现尚未成功");
    Map<String, CreatorDiscovery.Candidate> candidates = new HashMap<>();
    discovery.result().candidates().forEach(c -> candidates.put(c.bvid(), c));
    if (!candidates.keySet().containsAll(bvids)) throw bad("只能选择当前检索结果中的作品");
    List<String> selected = bvids.stream().sorted().toList();
    String encoded = encode(selected);
    var old =
        db.queryForList(
            "SELECT id,discovery_id,selection_json,root_limit FROM collection_group WHERE"
                + " project_id=? AND request_key=?",
            projectId,
            key);
    if (!old.isEmpty()) {
      var row = old.getFirst();
      if (!discoveryId.equals(row.get("discovery_id"))
          || !encoded.equals(row.get("selection_json"))
          || rootLimit != ((Number) row.get("root_limit")).intValue())
        throw conflict("请求键已用于其他批量采集范围");
      return get(projectId, (String) row.get("id"));
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " collection_group(id,project_id,discovery_id,request_key,selection_json,root_limit)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        projectId,
        discoveryId,
        key,
        encoded,
        rootLimit);
    int ordinal = 0;
    for (String bvid : selected) {
      var batch =
          batches.create(
              projectId,
              "group:" + id + ":" + ordinal,
              urls.parse(candidates.get(bvid).url()),
              rootLimit);
      db.update(
          "INSERT INTO collection_group_item(group_id,ordinal_no,bvid,collection_id)"
              + " VALUES(?,?,?,?)",
          id,
          ordinal++,
          bvid,
          batch.id());
    }
    return get(projectId, id);
  }

  /** 同一只读事务读取全部子状态，未完成和部分失败不能误报全成功。 */
  @Transactional(readOnly = true)
  public Group get(String projectId, String id) {
    projects.get(projectId);
    var rows =
        db.queryForList(
            "SELECT discovery_id,root_limit FROM collection_group WHERE id=? AND project_id=?",
            id,
            projectId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批量采集不存在");
    String discoveryId = (String) rows.getFirst().get("discovery_id");
    var discovery = discoveries.get(projectId, discoveryId);
    Map<String, String> titles = new HashMap<>();
    discovery.result().candidates().forEach(c -> titles.put(c.bvid(), c.title()));
    var items =
        db.query(
            "SELECT bvid,collection_id FROM collection_group_item WHERE group_id=? ORDER BY"
                + " ordinal_no",
            (r, n) ->
                new Item(
                    r.getString("bvid"),
                    titles.get(r.getString("bvid")),
                    batches.get(projectId, r.getString("collection_id"))),
            id);
    if (items.isEmpty()) throw new IllegalStateException("Empty stored collection group");
    int succeeded = (int) items.stream().filter(i -> "SUCCESS".equals(i.batch().status())).count(),
        failed = (int) items.stream().filter(i -> "FAILED".equals(i.batch().status())).count(),
        cancelled =
            (int) items.stream().filter(i -> "CANCELLED".equals(i.batch().status())).count(),
        pending = items.size() - succeeded - failed - cancelled;
    String status =
        succeeded == items.size()
            ? "SUCCESS"
            : pending == 0
                ? (cancelled == items.size()
                    ? "CANCELLED"
                    : succeeded > 0 || cancelled > 0 ? "PARTIAL_FAILED" : "FAILED")
                : items.stream().allMatch(i -> "PENDING".equals(i.batch().status()))
                    ? "PENDING"
                    : "RUNNING";
    return new Group(
        id,
        discoveryId,
        status,
        ((Number) rows.getFirst().get("root_limit")).intValue(),
        succeeded,
        failed,
        pending,
        cancelled,
        List.copyOf(items),
        List.of(
            "仅采集所选作品，来源来自一次冻结的候选检索；并非该UP主全部视频。",
            "每个作品分别限制根评论数量并保留回复完整性，批次SUCCESS不能证明评论全量。",
            "全部子批次结束后，可显式选取成功作品冻结为分析集合；失败作品不会被静默纳入。"));
  }

  /** 历史只列当前项目最近的小页运行，其他工作空间不能访问。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Group> list(String projectId, int page, int size) {
    projects.get(projectId);
    if (page < 1 || page > 10000 || size < 1 || size > 20) throw bad("每页1～20个批次");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM collection_group WHERE project_id=?", Long.class, projectId);
    var ids =
        db.queryForList(
            "SELECT id FROM collection_group WHERE project_id=? ORDER BY created_at DESC,id DESC"
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

  /** 全部结束后只冻结用户明确选择的成功子批次；不自动触发分析或付费调用。 */
  @Transactional
  public InputManifestStore.Manifest freeze(
      String projectId, String id, String key, String name, List<String> collectionIds) {
    if (key == null
        || !key.matches("[A-Za-z0-9._:-]{1,128}")
        || collectionIds == null
        || collectionIds.isEmpty()
        || collectionIds.size() > 5
        || collectionIds.stream().anyMatch(Objects::isNull)
        || new HashSet<>(collectionIds).size() != collectionIds.size()) throw bad("需要明确选择成功的采集批次");
    projects.lock(projectId);
    var group = get(projectId, id);
    if (group.pending() > 0) throw conflict("请等所有采集结束后确认冻结范围");
    Map<String, String> eligible = new HashMap<>();
    for (var item : group.items())
      if ("SUCCESS".equals(item.batch().status()))
        eligible.put(item.batch().id(), item.batch().snapshotId());
    if (!eligible.keySet().containsAll(collectionIds)) throw bad("只能冻结本组内成功的子批次");
    Map<String, String> sources = new HashMap<>();
    collectionIds.forEach(collection -> sources.put(eligible.get(collection), collection));
    if (sources.size() != collectionIds.size()) throw bad("同一快照不能重复冻结多个来源");
    return manifests.createFromCollections(
        projectId, "group-freeze:" + hash(id + "\n" + key), name, sources);
  }

  /** 选择顺序不改变逻辑请求，排序后的JSON保证网络重发稳定。 */
  private String encode(List<String> values) {
    try {
      return json.writeValueAsString(values);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 压缩内部幂等键长度，避免用户128字键叠加业务前缀越界。 */
  private String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 输入不符合业务范围时返回400，保留原数据以便用户修正后重新提交。 */
  private ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  /** 已接受范围发生冲突或阶段未就绪时返回409，不静默改变已有业务记录。 */
  private ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }
}
