/* 文件职责：从原不可变讨论产物投影统一报告、按命题筛选分页和安全导出，不重跑模型或回写统计。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.intelligence.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** v3/v4/v5/v6统一读投影；HTTP分页减少传输，当前仍读取原JSON，不冒称数据库逐行分页。 */
@Service
public class DiscussionReportService {
  private final AnalysisRequestStore requests;
  private final SourceCoverageStore coverage;
  private final ObjectMapper json;

  /** 原请求服务核对项目/发布状态，来源服务提供接受时诊断。 */
  public DiscussionReportService(
      AnalysisRequestStore requests, SourceCoverageStore coverage, ObjectMapper json) {
    this.requests = requests;
    this.coverage = coverage;
    this.json = json;
  }

  /** 命题首屏仅带总统计；决策与证据由分页接口按需读取，普通讨论维持其证据展示。 */
  public record Overview(
      JsonNode artifact, SourceCoverageStore.View sourceCoverage, int decisionCount) {}

  /** 过滤只改变列表总数，globalStatistics始终是原产物分母，不能用当前页重新计算支持比例。 */
  public record DecisionPage(
      List<JsonNode> items,
      long total,
      int page,
      int size,
      long totalPages,
      JsonNode globalStatistics,
      List<JsonNode> evidence) {}

  private static final Set<String> FILTERS =
      Set.of(
          "ALL",
          "SUPPORT",
          "OPPOSE",
          "QUALIFIED",
          "UNKNOWN",
          "QUESTION",
          "IRRELEVANT",
          "MIXED",
          "CLAIMS");

  /** 同事务一致读取请求及产物，旧JSON保持原样；只修改返回的深拷贝。 */
  @Transactional(readOnly = true)
  public Overview overview(String projectId, String requestId) {
    var request = requests.get(projectId, requestId);
    JsonNode original = requests.artifact(projectId, requestId);
    ObjectNode projected = original.deepCopy();
    int count = original.path("stance").path("decisions").size();
    if (original.path("stance").path("analyzed").asBoolean()) {
      ((ObjectNode) projected.path("stance")).set("decisions", json.createArrayNode());
      projected.set("evidence", json.createArrayNode());
    }
    if (original.path("processing").isObject()) {
      ((ObjectNode) projected.path("processing")).set("nodes", json.createArrayNode());
      projected.set("evidence", json.valueToTree(evidenceFor(original, List.of(original))));
    }
    return new Overview(projected, coverage.get(projectId, requestId, request.snapshotId()), count);
  }

  /** 分层中间摘要只读分页，原文按本页引用及祖先闭包返回，不修改全局分母。 */
  public record NodePage(
      List<JsonNode> items,
      long total,
      int page,
      int size,
      long totalPages,
      List<JsonNode> evidence) {}

  /** 节点顺序固定，引用从原产物查找，不重新调用模型或数据库逐行分页冒充。 */
  @Transactional(readOnly = true)
  public NodePage nodes(String projectId, String requestId, int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 10) throw bad("归并节点页码1～10000，每页1～10个");
    var original = requests.artifact(projectId, requestId);
    var nodes = original.path("processing").path("nodes");
    if (!original.path("processing").isObject())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "当前报告没有完整讨论归并节点");
    int from = Math.min(nodes.size(), (page - 1) * size), to = Math.min(nodes.size(), from + size);
    List<JsonNode> items = new ArrayList<>();
    for (int n = from; n < to; n++) items.add(nodes.get(n));
    return new NodePage(
        List.copyOf(items),
        nodes.size(),
        page,
        size,
        (nodes.size() + size - 1) / size,
        evidenceFor(original, items.stream().map(n -> n.path("summary")).toList()));
  }

  /** 只抽取业务图的引用，不能把中间摘要或原文里包含的字符串当新证据。 */
  private List<JsonNode> evidenceFor(JsonNode original, List<JsonNode> graphs) {
    Map<String, JsonNode> evidence = new LinkedHashMap<>();
    original.path("evidence").forEach(n -> evidence.put(n.path("id").asText(), n));
    Set<String> selected = new LinkedHashSet<>();
    for (var graph : graphs)
      for (String field : List.of("issues", "claims", "relations", "assessments"))
        for (var item : graph.path(field))
          for (var id : item.path("evidenceIds")) selected.add(id.asText());
    for (String id : new ArrayList<>(selected)) {
      var seen = new HashSet<String>();
      String at = id;
      while (at != null && seen.add(at)) {
        var node = evidence.get(at);
        if (node == null) throw new IllegalStateException("Published evidence missing");
        selected.add(at);
        at =
            node.path("parentCommentId").isTextual() ? node.path("parentCommentId").asText() : null;
      }
    }
    return evidence.values().stream()
        .filter(n -> selected.contains(n.path("id").asText()))
        .toList();
  }

  /** 1、校验过滤/分页；2、保留原决策顺序筛选；3、抽取本页证据及完整父链；4、附原全局统计。 */
  @Transactional(readOnly = true)
  public DecisionPage decisions(
      String projectId, String requestId, String filter, String query, int page, int size) {
    if (!FILTERS.contains(filter == null ? "" : filter)
        || query == null
        || query.length() > 80
        || page < 1
        || page > 10000
        || size < 1
        || size > 50) throw bad("结果筛选无效：页码1～10000、每页1～50条、关键词最多80字");
    JsonNode original = requests.artifact(projectId, requestId);
    JsonNode stance = original.path("stance");
    if (!stance.path("analyzed").asBoolean())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "当前结果没有逐评论命题判断");
    Map<String, JsonNode> evidence = new LinkedHashMap<>();
    for (JsonNode node : original.path("evidence")) evidence.put(node.path("id").asText(), node);
    List<JsonNode> selected = new ArrayList<>();
    String needle = normalized(query);
    for (JsonNode decision : stance.path("decisions")) {
      JsonNode own = evidence.get(decision.path("commentId").asText());
      String searchable =
          decision.toString() + " " + (own == null ? "" : own.path("text").asText());
      if (matches(decision, filter) && normalized(searchable).contains(needle))
        selected.add(decision);
    }
    int from = Math.min(selected.size(), (page - 1) * size),
        to = Math.min(selected.size(), from + size);
    List<JsonNode> items = List.copyOf(selected.subList(from, to));
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode decision : items) {
      ids.add(decision.path("commentId").asText());
      for (JsonNode claim : decision.path("claims"))
        for (JsonNode id : claim.path("evidenceIds")) ids.add(id.asText());
    }
    for (String id : new ArrayList<>(ids)) {
      Set<String> seen = new HashSet<>();
      String current = id;
      while (current != null && seen.add(current)) {
        JsonNode node = evidence.get(current);
        if (node == null) break;
        ids.add(current);
        JsonNode parent = node.path("parentCommentId");
        current = parent.isTextual() ? parent.asText() : null;
      }
    }
    return new DecisionPage(
        items,
        selected.size(),
        page,
        size,
        (selected.size() + size - 1) / size,
        stance.path("statistics"),
        ids.stream().filter(evidence::containsKey).map(evidence::get).toList());
  }

  /** JSON导出含完整原产物和来源投影，Markdown按相同结果生成；任何格式都不触发新调用。 */
  @Transactional(readOnly = true)
  public String export(String projectId, String requestId, String format) {
    if (!Set.of("json", "markdown", "review-json").contains(format == null ? "" : format))
      throw bad("报告格式只能是json或markdown");
    var request = requests.get(projectId, requestId);
    var original = requests.artifact(projectId, requestId);
    var source = coverage.get(projectId, requestId, request.snapshotId());
    if (format.equals("review-json")) {
      ObjectNode packet = json.createObjectNode();
      packet.put("schemaVersion", "argument-review-packet-v1");
      packet.put("requestId", requestId);
      packet.put(
          "artifactSha256",
          io.echoinsight.material.ReferenceMaterialStore.hash(original.toString()));
      packet.put("independentQualityProven", false);
      packet.put("status", "DRAFT");
      packet.put("note", "含模型预测的人工复核资料包，不是独立金标准，不得据此宣称准确率。材料可能含用户原文，默认仅下载到本机。");
      packet.set("artifact", original);
      packet.set("sourceCoverage", json.valueToTree(source));
      packet.set("context", json.valueToTree(requests.context(projectId, requestId)));
      packet.set("annotations", json.createArrayNode());
      try {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(packet);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    if (format.equals("json")) {
      ObjectNode wrapper = json.createObjectNode();
      wrapper.set("artifact", original);
      wrapper.set("sourceCoverage", json.valueToTree(source));
      try {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(wrapper);
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    StringBuilder out = new StringBuilder("# EchoInsight 讨论分析报告\n\n");
    line(out, "作品", original.path("title").asText());
    line(out, "问题", original.path("instruction").asText());
    line(out, "模式", original.path("sourceMode").asText());
    line(out, "请求", requestId);
    line(out, "快照", request.snapshotId());
    if (original.path("processing").isObject()) {
      out.append("\n## 完整讨论归并范围\n\n");
      line(out, "分片", original.path("processing").path("mapCount").asText());
      line(out, "归并", original.path("processing").path("reductionCount").asText());
      line(out, "复用分片", original.path("processing").path("reuse").path("reusedChunks").asText("0"));
      out.append("逐片压缩可能遗漏/误读，完整输入处理不等于语义质量已验证。\n");
    }
    out.append("\n## 来源与处理范围\n\n");
    line(out, "来源诊断", source.frozen() ? "接受时已冻结" : "历史请求回溯；并非接受时记录");
    line(out, "保存评论", Integer.toString(source.coverage().savedComments()));
    line(
        out,
        "平台声明",
        source.coverage().sourceReportedComments() == null
            ? "未知"
            : source.coverage().sourceReportedComments().toString());
    for (String note : source.coverage().notes()) out.append("- ").append(safe(note)).append('\n');
    JsonNode stance = original.path("stance");
    if (stance.isObject()) {
      out.append("\n## 命题与全局统计\n\n");
      line(out, "命题", stance.path("specification").path("proposition").asText());
      line(out, "实际语义分析", Boolean.toString(stance.path("analyzed").asBoolean()));
      if (!stance.path("statistics").isNull() && !stance.path("statistics").isMissingNode())
        stance
            .path("statistics")
            .fields()
            .forEachRemaining(e -> line(out, e.getKey(), e.getValue().asText()));
      out.append("\n## 逐评论判断\n\n");
      for (JsonNode decision : stance.path("decisions")) {
        line(out, "评论", decision.path("commentId").asText());
        line(out, "覆盖", decision.path("coverage").asText());
        line(out, "说明", decision.path("reason").asText());
        for (JsonNode claim : decision.path("claims")) {
          line(out, "主张", claim.path("statement").asText());
          line(out, "立场", claim.path("position").asText());
          line(out, "条件", claim.path("condition").asText());
          line(out, "理由", claim.path("reason").asText());
          line(out, "证据ID", claim.path("evidenceIds").toString());
        }
        out.append('\n');
      }
    }
    for (String section : List.of("issues", "claims", "relations", "assessments")) {
      if (original.path(section).size() > 0) {
        out.append("\n## ")
            .append(
                Map.of("issues", "议题", "claims", "观点", "relations", "关系", "assessments", "理由评议")
                    .get(section))
            .append("\n\n");
        for (JsonNode node : original.path(section))
          node.fields()
              .forEachRemaining(
                  e ->
                      line(
                          out,
                          e.getKey(),
                          e.getValue().isTextual()
                              ? e.getValue().asText()
                              : e.getValue().toString()));
      }
    }
    out.append("\n## 原文证据\n\n");
    for (JsonNode node : original.path("evidence")) {
      line(out, "证据", node.path("id").asText());
      line(out, "角色", node.path("sourceRole").asText("COMMENT"));
      line(out, "来源版本", node.path("materialId").asText(request.snapshotId()));
      if (node.path("startMs").isNumber())
        line(out, "原片毫秒区间", node.path("startMs").asText() + "～" + node.path("endMs").asText());
      out.append("> ")
          .append(safe(node.path("text").asText()).replace("\n", "\n> "))
          .append("\n\n");
    }
    out.append("\n## 限制\n\n");
    for (JsonNode note : original.path("limitations"))
      out.append("- ").append(safe(note.asText())).append('\n');
    return out.toString();
  }

  /** 同一评论可同时出现在多个主张过滤中；MIXED只表示同时支持和反对，不是独立人数。 */
  private boolean matches(JsonNode decision, String filter) {
    if (filter.equals("ALL")) return true;
    Set<String> positions = new HashSet<>();
    for (JsonNode claim : decision.path("claims")) positions.add(claim.path("position").asText());
    return filter.equals("MIXED")
        ? positions.containsAll(Set.of("SUPPORT", "OPPOSE"))
        : filter.equals(decision.path("coverage").asText()) || positions.contains(filter);
  }

  /** 普通文字只能作为文字进入Markdown，不能注入HTML、链接或新标题。 */
  static String safe(String value) {
    String text =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "");
    return text.replaceAll("([\\\\`*_{}\\[\\]()#!|])", "\\\\$1");
  }

  /** 固定标签与转义内容逐行输出。 */
  private void line(StringBuilder out, String label, String value) {
    label =
        Map.ofEntries(
                Map.entry("selectedComments", "选入评论"),
                Map.entry("coveredComments", "形成覆盖记录"),
                Map.entry("claimComments", "含主张评论"),
                Map.entry("questionComments", "仅提问评论"),
                Map.entry("irrelevantComments", "无关评论"),
                Map.entry("unknownComments", "无法判断评论"),
                Map.entry("totalClaims", "主张总数"),
                Map.entry("supportClaims", "支持主张"),
                Map.entry("opposeClaims", "反对主张"),
                Map.entry("qualifiedClaims", "附条件主张"),
                Map.entry("unknownClaims", "无法判断主张"),
                Map.entry("supportComments", "含支持的评论"),
                Map.entry("opposeComments", "含反对的评论"),
                Map.entry("qualifiedComments", "含条件的评论"),
                Map.entry("mixedComments", "同时支持与反对评论"),
                Map.entry("question", "问题"),
                Map.entry("summary", "归纳"),
                Map.entry("statement", "主张"),
                Map.entry("evidenceIds", "证据ID"),
                Map.entry("conclusion", "结论"),
                Map.entry("reason", "理由"),
                Map.entry("verdict", "评议状态"))
            .getOrDefault(label, label);

    out.append("- ")
        .append(safe(label))
        .append(": ")
        .append(safe(value).replace("\n", " "))
        .append('\n');
  }

  /** 仅用于搜索，不改写证据。 */
  private String normalized(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  /** 不回显原筛选词。 */
  private ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
