/* 文件职责：以一页一检查点遍历 B站可见根评论与楼中楼，完成后统一校验、存档和原子发布快照。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 固定分页工作流足够完成采集；不让 LLM 控制游标、重试、平台访问或完整性判定。 */
@Service
public class PagedBilibiliCollection {
  public static final int MAX_COMMENTS = 5000;
  public static final int MAX_PAGES = 512;
  private final BilibiliPageClient client;
  private final CollectionPageStore pages;
  private final BilibiliCliAdapter adapter;
  private final CollectionArchive archive;
  private final CollectionPublicationService publication;
  private final ObjectMapper json;

  /** 分页状态复用原批次；页表仅保存事实，不复制任务/租约/错误状态机。 */
  public PagedBilibiliCollection(
      BilibiliPageClient client,
      CollectionPageStore pages,
      BilibiliCliAdapter adapter,
      CollectionArchive archive,
      CollectionPublicationService publication,
      ObjectMapper json) {
    this.client = client;
    this.pages = pages;
    this.adapter = adapter;
    this.archive = archive;
    this.publication = publication;
    this.json = json;
  }

  /** 用户可读进度不估算虚假百分比；声明数量是采集开始时的平台口径。 */
  public record Progress(
      int completedPages,
      int storedComments,
      int roots,
      Integer sourceReportedComments,
      boolean rootTraversalEnded,
      String nextPhase) {}

  /** 1、从页事实还原下一步；2、事务外取一页；3、租约保护落库；4、遍历结束才发布，不发布半份快照。 */
  public void step(CollectionStore.Lease lease, BilibiliUrlParser.Target target) {
    List<CollectionPageStore.Saved> saved = pages.read(lease.batch().id());
    State state = state(saved);
    if (state.nextKey() != null) {
      if (saved.size() >= MAX_PAGES) throw new CollectionFailure("COLLECTION_PAGE_LIMIT");
      pages.check(lease);
      BilibiliPageClient.Page page;
      String key = state.nextKey();
      if (key.equals("META")) page = client.metadata(target);
      else if (key.startsWith("ROOT:")) page = client.roots(state.aid(), key.substring(5));
      else {
        String[] parts = key.split(":");
        page = client.replies(state.aid(), parts[1], parts[2]);
      }
      if (page == null
          || page.records() == null
          || !page.records().isArray()
          || page.sourceHash() == null
          || !page.sourceHash().matches("[a-f0-9]{64}"))
        throw new CollectionFailure("INVALID_TOOL_OUTPUT");
      List<CollectionPageStore.Saved> candidate = new ArrayList<>(saved);
      candidate.add(new CollectionPageStore.Saved(key, page));
      state(candidate); // 先验证新游标、重复身份与总量，再提交检查点；坏页不污染后续恢复。
      pages.append(lease, key, page);
      return;
    }
    var payload =
        new BilibiliCliCollector.Payload(
            state.video().toString().getBytes(StandardCharsets.UTF_8),
            joined(state).toString().getBytes(StandardCharsets.UTF_8));
    var converted = adapter.convert(target, MAX_COMMENTS, payload.video(), payload.comments());
    // 只有所有分页有明确结束、楼回复数量/父引用及作品总数对齐，才给出“当前可见遍历完成”。
    String completeness =
        converted.partialThreadCount() > 0
                || !converted.document().contextGaps().isEmpty()
                || converted.commentCount() != converted.sourceReportedComments()
            ? "PARTIAL_FAILED"
            : "VISIBLE_EXHAUSTED";
    converted =
        new BilibiliCliAdapter.Converted(
            converted.document(),
            converted.bvid(),
            converted.aid(),
            converted.sourceReportedComments(),
            converted.rootCount(),
            converted.commentCount(),
            converted.partialThreadCount(),
            completeness);
    String key =
        archive.savePaged(
            lease.batch().id(), lease.batch().attempts(), target, payload, saved.size());
    publication.publish(lease, converted, key);
  }

  /** 进度读取不触发网络或修改状态；旧限量任务没有页进度。 */
  public Progress progress(CollectionStore.Batch batch) {
    if (!batch.collectAll()) return null;
    List<CollectionPageStore.Saved> saved = pages.read(batch.id());
    State state = state(saved);
    return new Progress(
        saved.size(),
        state.comments(),
        state.roots().size(),
        state.video() == null ? null : state.video().get(0).path("reply_count").asInt(),
        state.rootEnded(),
        state.nextKey() == null ? "PUBLISH" : state.nextKey().split(":")[0]);
  }

  /** 不可变页还原规则：元数据→根游标链→逐根回复页链；重复置顶只计一次，冲突正文或循环游标拒绝。 */
  private State state(List<CollectionPageStore.Saved> saved) {
    if (saved.isEmpty())
      return new State(null, null, new LinkedHashMap<>(), new LinkedHashMap<>(), false, "META", 0);
    var keys = new LinkedHashMap<String, BilibiliPageClient.Page>();
    for (var row : saved)
      if (keys.putIfAbsent(row.key(), row.page()) != null)
        throw new CollectionFailure("PAGE_CHECKPOINT_INVALID");
    var meta = keys.get("META");
    if (meta == null || !meta.records().isArray() || meta.records().size() != 1)
      throw new CollectionFailure("PAGE_CHECKPOINT_INVALID");
    String aid = meta.records().get(0).path("aid").asText();
    if (!aid.matches("[1-9][0-9]{0,18}")) throw new CollectionFailure("SOURCE_ID_MISMATCH");
    Map<String, JsonNode> roots = new LinkedHashMap<>();
    Map<String, Map<String, JsonNode>> replies = new LinkedHashMap<>();
    Map<String, JsonNode> unique = new LinkedHashMap<>();
    String cursor = "0";
    boolean ended = false;
    java.util.Set<String> visited = new java.util.HashSet<>();
    String next = null;
    while (true) {
      String key = "ROOT:" + cursor;
      if (!visited.add(key)) throw new CollectionFailure("INVALID_PAGE_CURSOR");
      var page = keys.get(key);
      if (page == null) {
        next = key;
        break;
      }
      for (JsonNode node : page.records()) {
        if (!"0".equals(node.path("parent").asText()))
          throw new CollectionFailure("INVALID_COMMENT_TREE");
        add(roots, node);
        add(unique, node);
      }
      if (page.terminal()) {
        ended = true;
        break;
      }
      cursor = cursor(page.next());
    }
    if (ended)
      for (var root : roots.entrySet()) {
        JsonNode count = root.getValue().path("reply_count");
        if (!count.isIntegralNumber() || !count.canConvertToInt() || count.intValue() < 0)
          throw new CollectionFailure("INVALID_TOOL_OUTPUT");
        if (count.intValue() == 0) continue;
        Map<String, JsonNode> thread = new LinkedHashMap<>();
        replies.put(root.getKey(), thread);
        String number = "1";
        int observedReplies = count.intValue();
        while (true) {
          String key = "REPLY:" + root.getKey() + ":" + number;
          if (!visited.add(key)) throw new CollectionFailure("INVALID_PAGE_CURSOR");
          var page = keys.get(key);
          if (page == null) {
            next = key;
            break;
          }
          if (page.reported() < 0) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
          observedReplies = Math.max(observedReplies, page.reported());
          for (JsonNode node : page.records()) {
            if (!root.getKey().equals(node.path("root").asText())
                || "0".equals(node.path("parent").asText()))
              throw new CollectionFailure("INVALID_COMMENT_TREE");
            add(thread, node);
            add(unique, node);
          }
          if (page.terminal()) break;
          String candidate = cursor(page.next());
          if (!candidate.equals(Long.toString(Long.parseLong(number) + 1)))
            throw new CollectionFailure("INVALID_PAGE_CURSOR");
          number = candidate;
        }
        ObjectNode projectedRoot = root.getValue().deepCopy();
        projectedRoot.put("reply_count", observedReplies);
        root.setValue(projectedRoot);
        if (next != null) break;
      }
    if (unique.size() > MAX_COMMENTS) throw new CollectionFailure("COMMENT_COUNT_LIMIT");
    if (next != null && keys.containsKey(next)) throw new CollectionFailure("INVALID_PAGE_CURSOR");
    if (!visited.containsAll(keys.keySet().stream().filter(key -> !key.equals("META")).toList()))
      throw new CollectionFailure("PAGE_CHECKPOINT_INVALID");
    return new State(meta.records(), aid, roots, replies, ended, next, unique.size());
  }

  /** 每个来源 ID 保留一份；相同置顶可跨页重复，内容/父关系变化需新采集，禁止静默覆盖。 */
  private void add(Map<String, JsonNode> target, JsonNode node) {
    String id = node.path("rpid").asText();
    if (!id.matches("[1-9][0-9]{0,18}")) throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    for (String field : List.of("oid", "type", "parent", "root", "ctime", "reply_count")) {
      JsonNode value = node.path(field);
      if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
        throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    }
    JsonNode text = node.path("content");
    if (!text.isTextual() || text.textValue().isBlank() || text.textValue().length() > 10000)
      throw new CollectionFailure("INVALID_TOOL_OUTPUT");
    JsonNode previous = target.putIfAbsent(id, node);
    if (previous != null) {
      for (String field : List.of("rpid", "oid", "type", "parent", "root", "ctime", "content")) {
        if (!previous.path(field).asText().equals(node.path(field).asText()))
          throw new CollectionFailure("COLLECTION_SOURCE_CHANGED");
      }
      // 回复数量是采集时的声明，不属于正文身份；投影取观测上界，原页事实仍不修改。
      if (node.path("reply_count").longValue() > previous.path("reply_count").longValue())
        target.put(id, node);
    }
  }

  /** 将已完成的楼中楼重新挂在来源 root 下展示，内部 parent 不改写；适配器仍验证缺父与数量。 */
  private ArrayNode joined(State state) {
    ArrayNode output = json.createArrayNode();
    for (var item : state.roots().entrySet()) {
      ObjectNode root = item.getValue().deepCopy();
      ArrayNode children = json.createArrayNode();
      state.replies().getOrDefault(item.getKey(), Map.of()).values().forEach(children::add);
      root.set("replies", children);
      output.add(root);
    }
    return output;
  }

  /** 游标只接受受限数字，拒绝任意 URL/查询参数和循环。 */
  private String cursor(String value) {
    if (value == null || !value.matches("[0-9]{1,18}"))
      throw new CollectionFailure("INVALID_PAGE_CURSOR");
    return value;
  }

  /** 状态仅由成功页重建，不在服务内持有跨任务可变会话。 */
  private record State(
      JsonNode video,
      String aid,
      Map<String, JsonNode> roots,
      Map<String, Map<String, JsonNode>> replies,
      boolean rootEnded,
      String nextKey,
      int comments) {}
}
