/* 文件职责：在同项目同作品两份成功报告之间检查比较条件，返回有界差异页与完整只读导出。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisContextStore;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.SourceCoverageStore;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.project.ProjectStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 输入条件不一致时仍能看资料差异，但不统计标签转移；两次模型标签不同也不证明观众改变观点。 */
@Service
public class RevisionComparisonService {
  private final ProjectStore projects;
  private final AnalysisRequestStore requests;
  private final AnalysisContextStore contexts;
  private final SourceCoverageStore coverage;
  private final ImportMapper imports;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final StanceRevisionDiffer differ;

  /** 全部输入来自原请求/产物，不接受用户提供模型输出或比较分母。 */
  public RevisionComparisonService(
      ProjectStore projects,
      AnalysisRequestStore requests,
      AnalysisContextStore contexts,
      SourceCoverageStore coverage,
      ImportMapper imports,
      JdbcTemplate db,
      ObjectMapper json,
      StanceRevisionDiffer differ) {
    this.projects = projects;
    this.requests = requests;
    this.contexts = contexts;
    this.coverage = coverage;
    this.imports = imports;
    this.db = db;
    this.json = json;
    this.differ = differ;
  }

  /** 理由为固定业务说明，comparable不代表独立语义质量已经通过。 */
  public record Compatibility(boolean comparable, List<String> reasons) {}

  /** 原文页包含两侧有限父语境；超出120条/80000字会显式标记，导出不重复展开每条祖先。 */
  public record Evidence(List<ImportDocument.Comment> items, boolean truncated) {}

  /** 统计来自整个来源并集，分页/筛选不改变它；covered范围两侧分别保留。 */
  public record Page(
      String algorithmVersion,
      String beforeRequestId,
      String afterRequestId,
      String beforeSnapshotId,
      String afterSnapshotId,
      String beforeArtifactSha256,
      String afterArtifactSha256,
      Compatibility compatibility,
      Map<String, Integer> counts,
      Map<String, Integer> transitions,
      long changedLabels,
      long total,
      int page,
      int size,
      long totalPages,
      List<StanceRevisionDiffer.Row> items,
      Evidence beforeEvidence,
      Evidence afterEvidence,
      SourceCoverageStore.View beforeCoverage,
      SourceCoverageStore.View afterCoverage,
      List<String> limitations) {}

  /** 完整比较仍是只读投影；原报告和任务不会因查看/导出变动。 */
  private record Prepared(
      AnalysisRequestStore.RequestRow before,
      AnalysisRequestStore.RequestRow after,
      ImportDocument left,
      ImportDocument right,
      JsonNode leftArtifact,
      JsonNode rightArtifact,
      Compatibility compatibility,
      StanceRevisionDiffer.Result result) {}

  /** 1、校验项目/作品；2、检查命题/计划/背景/范围；3、做来源对齐；4、筛选响应页及双侧证据。 */
  @Transactional(readOnly = true)
  public Page compare(
      String projectId, String beforeId, String afterId, String filter, int page, int size) {
    if (page < 1
        || page > 10000
        || size < 1
        || size > 50
        || !Set.of(
                "ALL",
                "ADDED",
                "UNKNOWN_MISSING",
                "REVISED",
                "CONTEXT_CHANGED",
                "COMPARABLE",
                "NOT_COMPARABLE",
                "LABEL_CHANGED")
            .contains(filter == null ? "" : filter)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "比较筛选无效：页码1～10000、每页1～50条");
    }
    var prepared = prepare(projectId, beforeId, afterId);
    List<StanceRevisionDiffer.Row> rows =
        prepared.result().rows().stream()
            .filter(
                r ->
                    filter.equals("ALL")
                        || (filter.equals("LABEL_CHANGED")
                            ? r.labelChanged()
                            : r.category().equals(filter)))
            .toList();
    int from = Math.min(rows.size(), (page - 1) * size), to = Math.min(rows.size(), from + size);
    var items = rows.subList(from, to);
    return new Page(
        "stance-revision-diff-v1",
        beforeId,
        afterId,
        prepared.before().snapshotId(),
        prepared.after().snapshotId(),
        artifactHash(beforeId),
        artifactHash(afterId),
        prepared.compatibility(),
        prepared.result().counts(),
        prepared.result().transitions(),
        prepared.result().rows().stream().filter(StanceRevisionDiffer.Row::labelChanged).count(),
        rows.size(),
        page,
        size,
        (rows.size() + size - 1) / size,
        List.copyOf(items),
        evidence(
            prepared.left(),
            items.stream()
                .map(StanceRevisionDiffer.Row::beforeComment)
                .filter(Objects::nonNull)
                .toList()),
        evidence(
            prepared.right(),
            items.stream()
                .map(StanceRevisionDiffer.Row::afterComment)
                .filter(Objects::nonNull)
                .toList()),
        coverage.get(projectId, beforeId, prepared.before().snapshotId()),
        coverage.get(projectId, afterId, prepared.after().snapshotId()),
        limitations());
  }

  /** JSON导出完整互斥行、原判断与自身原文；不把祖先为每行重复展开造成平方级文件。 */
  @Transactional(readOnly = true)
  public String export(String projectId, String beforeId, String afterId) {
    var prepared = prepare(projectId, beforeId, afterId);
    ObjectNode result = json.createObjectNode();
    result.put("algorithmVersion", "stance-revision-diff-v1");
    result.put("beforeRequestId", beforeId);
    result.put("afterRequestId", afterId);
    result.put("beforeSnapshotId", prepared.before().snapshotId());
    result.put("afterSnapshotId", prepared.after().snapshotId());
    result.put("beforeArtifactSha256", artifactHash(beforeId));
    result.put("afterArtifactSha256", artifactHash(afterId));
    result.set("compatibility", json.valueToTree(prepared.compatibility()));
    result.set("difference", json.valueToTree(prepared.result()));
    result.set(
        "beforeCoverage",
        json.valueToTree(coverage.get(projectId, beforeId, prepared.before().snapshotId())));
    result.set(
        "afterCoverage",
        json.valueToTree(coverage.get(projectId, afterId, prepared.after().snapshotId())));
    result.set("limitations", json.valueToTree(limitations()));
    return result.toPrettyString();
  }

  /** 必须是同项目明确作品；方向由before/after指定，不根据系统时间猜新旧。 */
  private Prepared prepare(String projectId, String beforeId, String afterId) {
    var before = requests.get(projectId, beforeId);
    var after = requests.get(projectId, afterId);
    var oldMember = projects.snapshot(projectId, before.snapshotId());
    var newMember = projects.snapshot(projectId, after.snapshotId());
    if (!oldMember.contentId().equals(newMember.contentId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能比较同一作品的报告，请核对新旧资料");
    }
    JsonNode left = requests.artifact(projectId, beforeId),
        right = requests.artifact(projectId, afterId);
    List<String> reasons = new ArrayList<>();
    if (!before.instruction().equals(after.instruction())) {
      reasons.add("问题原话不同；不能把改问题后的标签直接比较。");
    }
    JsonNode oldSpec = left.path("stance").path("specification").path("proposition");
    JsonNode newSpec = right.path("stance").path("specification").path("proposition");
    if (!oldSpec.isTextual() || !oldSpec.equals(newSpec)) {
      reasons.add("命题缺失或不一致。");
    }
    if (!"MODEL".equals(left.path("sourceMode").asText())
        || !"MODEL".equals(right.path("sourceMode").asText())
        || !left.path("stance").path("analyzed").asBoolean()
        || !right.path("stance").path("analyzed").asBoolean()) {
      reasons.add("至少一侧没有实际逐评论命题判断，结构演示不提供立场比较。");
    }
    if (!left.path("stance").path("completion").path("coverageVerified").asBoolean()
        || !right.path("stance").path("completion").path("coverageVerified").asBoolean()) {
      reasons.add("至少一侧不是已完整对账的快照分析，不能将预览选取差异当变化。");
    }
    String oldPlan = plan(before.jobId()), newPlan = plan(after.jobId());
    if (oldPlan == null || newPlan == null || !Objects.equals(oldPlan, newPlan)) {
      reasons.add("冻结执行计划不同（包括模型、提示、价格或运行边界）。");
    }
    if (!normalizedContext(contexts.get(projectId, beforeId))
        .equals(normalizedContext(contexts.get(projectId, afterId)))) {
      reasons.add("冻结背景内容、来源或选择策略不同。");
    }
    var compatibility = new Compatibility(reasons.isEmpty(), List.copyOf(reasons));
    ImportDocument oldDocument = document(before.snapshotId()),
        newDocument = document(after.snapshotId());
    if (!oldDocument.content().title().equals(newDocument.content().title())
        || !oldDocument.content().creatorId().equals(newDocument.content().creatorId())
        || !oldDocument
            .content()
            .publishedAt()
            .toInstant()
            .equals(newDocument.content().publishedAt().toInstant())) {
      reasons.add("作品标题、创作者来源身份或发布时间不同。");
    }
    if (!left.path("schemaVersion").equals(right.path("schemaVersion"))
        || !left.path("stance")
            .path("completion")
            .path("selectionVersion")
            .equals(right.path("stance").path("completion").path("selectionVersion"))) {
      reasons.add("输出协议或完整选择规则不同。");
    }
    compatibility = new Compatibility(reasons.isEmpty(), List.copyOf(reasons));
    return new Prepared(
        before,
        after,
        oldDocument,
        newDocument,
        left,
        right,
        compatibility,
        differ.compare(
            oldDocument,
            newDocument,
            ReportCommentIndex.normalize(left, oldDocument),
            ReportCommentIndex.normalize(right, newDocument),
            compatibility.comparable()));
  }

  /** 只忽略选择对象的快照ID，字幕/参考身份、原文、查询和历史来源全部参与资格比较。 */
  private JsonNode normalizedContext(AnalysisContextStore.View view) {
    if (view.selection() == null) {
      return json.nullNode();
    }
    ObjectNode node = json.valueToTree(view.selection());
    node.remove("snapshotId");
    return node;
  }

  /** 完整冻结计划保守精确比较；不因计划看起来相近就推断结果可复用。 */
  private String plan(String jobId) {
    return db.queryForObject(
        "SELECT execution_plan_json FROM analysis_job WHERE id=?", String.class, jobId);
  }

  /** 原始存储文本求身份；只在资格验证之后调用。 */
  private String artifactHash(String requestId) {
    return ReferenceMaterialStore.hash(
        db.queryForObject(
            "SELECT artifact_json FROM analysis_artifact WHERE request_id=?",
            String.class,
            requestId));
  }

  /** 原文来自冻结快照，不从报告摘要反向重建评论。 */
  private ImportDocument document(String snapshotId) {
    try {
      return json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Invalid comparison snapshot", failure);
    }
  }

  /** 自身优先，再沿直接父链回溯；跨条共享父节点只返回一次，明确有界截取。 */
  private Evidence evidence(ImportDocument document, List<ImportDocument.Comment> selected) {
    Map<String, ImportDocument.Comment> byId = new HashMap<>();
    document.comments().forEach(c -> byId.put(c.id(), c));
    Map<String, ImportDocument.Comment> result = new LinkedHashMap<>();
    int chars = 0;
    boolean truncated = false;
    for (var comment : selected) {
      String cursor = comment.id();
      Set<String> visited = new java.util.HashSet<>();
      while (cursor != null && visited.add(cursor)) {
        var current = byId.get(cursor);
        if (current == null) {
          truncated = true;
          break;
        }
        if (!result.containsKey(cursor)) {
          if (result.size() >= 120 || chars + current.text().length() > 80000) {
            truncated = true;
            break;
          }
          result.put(cursor, current);
          chars += current.text().length();
        }
        cursor = current.parentId();
      }
    }
    return new Evidence(List.copyOf(result.values()), truncated);
  }

  /** 输出解释与算法一起版本化，不宣称语义增量或人群观点迁移。 */
  private List<String> limitations() {
    return List.of(
        "方向由用户选择，before不保证日历时间更早；统计基于两份资料的来源评论ID并集。",
        "未再出现的评论是未知缺失，不证明平台删除；来源覆盖两侧分别展示。",
        "只有问题、命题、完整范围、计划和背景相同且自身/全部祖先未变的评论可统计标签差异。",
        "标签变化只比较覆盖类型与立场集合，不检测同义表达、条件/理由改写，不证明观众改变观点或模型判断正确。",
        "报告证据别名按来源ID转换为快照ID。标签差异也可能来自新增全局语境或模型不稳定，不代表因果关系。当前仍全量重算后比较，没有跨版本语义复用或实际费用节省。分页为服务端JSON投影，不是数据库逐行优化。",
        "证据每侧最多120条/80000字，缺父或截断会标记；完整JSON导出不重复展开每条祖先。");
  }
}
