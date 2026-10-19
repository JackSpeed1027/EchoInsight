/* 文件职责：从已发布命题产物导出未裁决候选及实际预测，拒绝把模型结果回填成金标准。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisContextStore;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.ThreadContextSelector;
import io.echoinsight.material.ReferenceMaterialStore;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 导出包含原文且未匿名；下载是用户明确动作，样本仍需授权确认、独立标注与作品级划分。 */
@Service
public class EvaluationPackageService {
  private final AnalysisRequestStore requests;
  private final AnalysisContextStore contexts;
  private final ImportMapper imports;
  private final ObjectMapper json;
  private final ThreadContextSelector selector;

  /** 仅依赖已有不可变来源，不创建样本库或自动发送原文。 */
  public EvaluationPackageService(
      AnalysisRequestStore requests,
      AnalysisContextStore contexts,
      ImportMapper imports,
      ObjectMapper json,
      ThreadContextSelector selector) {
    this.requests = requests;
    this.contexts = contexts;
    this.imports = imports;
    this.json = json;
    this.selector = selector;
  }

  /** 1、核对命题产物；2、有界选择且保留父链；3、导出原预测；4、将gold和授权声明保持待确认。 */
  @Transactional(readOnly = true)
  public String export(String projectId, String requestId, String split, int limit) {
    if (!Set.of("dev", "test").contains(split == null ? "" : split) || limit < 1 || limit > 120) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评测候选需要dev或test划分，数量1～120");
    }
    var request = requests.get(projectId, requestId);
    var artifact = requests.artifact(projectId, requestId);
    if (!artifact.path("stance").path("analyzed").asBoolean()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "只有已发布的逐评论命题判断可导出评测候选");
    }
    ImportDocument input;
    try {
      input =
          json.readValue(
              imports.snapshot(request.snapshotId()).documentJson(), ImportDocument.class);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Invalid evaluation snapshot", failure);
    }
    var originalArtifact = artifact;
    artifact = ReportCommentIndex.normalize(artifact, input);
    var decisions = new HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    for (var decision : artifact.path("stance").path("decisions")) {
      decisions.put(decision.path("commentId").asText(), decision);
    }
    var judged = input.comments().stream().filter(c -> decisions.containsKey(c.id())).toList();
    Set<String> ids = judged.stream().map(ImportDocument.Comment::id).collect(Collectors.toSet());
    if (judged.isEmpty()
        || judged.stream().anyMatch(c -> c.parentId() != null && !ids.contains(c.parentId()))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "原判断缺少可独立评测的父语境，请核对报告范围");
    }
    var selection =
        selector.select(
            new ImportDocument(input.schemaVersion(), input.content(), judged, List.of()),
            limit,
            80000);
    String caseId = requestId + "-" + limit;
    ObjectNode candidate = json.createObjectNode();
    candidate.put("id", caseId);
    candidate.put(
        "contentId", input.content().sourceType() + ":" + input.content().sourceContentId());
    candidate.put("split", split);
    candidate.put(
        "sourceKind",
        input.content().sourceType().equals("SYNTHETIC") ? "SYNTHETIC" : "UNVERIFIED");
    candidate.putNull("permissionRef");
    candidate.put(
        "proposition", artifact.path("stance").path("specification").path("proposition").asText());
    var comments = candidate.putArray("comments");
    for (var c : selection.comments()) {
      var node = comments.addObject();
      node.put("id", c.id());
      node.put("sourceCommentId", c.sourceCommentId());
      node.put("text", c.text());
      node.put("parentId", c.parentId());
    }
    var background = contexts.get(projectId, requestId);
    var backgroundIds = candidate.putArray("backgroundEvidenceIds");
    if (background.selection() != null) {
      background.selection().sources().forEach(s -> backgroundIds.add(s.id()));
      candidate.set("background", json.valueToTree(background.selection().sources()));
    }
    candidate.putNull("gold");
    ObjectNode prediction = json.createObjectNode();
    var actual = prediction.putArray("decisions");
    selection.comments().forEach(c -> actual.add(decisions.get(c.id())));
    ObjectNode bundle = json.createObjectNode();
    bundle.put("schemaVersion", "evaluation-candidate-package-v1");
    bundle.put("status", "DRAFT_UNREVIEWED");
    bundle.put("requestId", requestId);
    bundle.put("snapshotId", request.snapshotId());
    bundle.set(
        "commentIdMapping", json.valueToTree(ReportCommentIndex.mapping(originalArtifact, input)));
    bundle.put("sourceMode", artifact.path("sourceMode").asText());
    bundle.put("totalJudgedComments", decisions.size());
    bundle.put("selectedComments", selection.comments().size());
    bundle.put("omittedComments", decisions.size() - selection.comments().size());
    String caseText = candidate.toString(), predictionText = prediction.toString();
    bundle.put("caseJson", caseText);
    bundle.put("caseSha256", ReferenceMaterialStore.hash(caseText));
    bundle.put("predictionJson", predictionText);
    bundle.put("predictionSha256", ReferenceMaterialStore.hash(predictionText));
    bundle.set(
        "notes",
        json.valueToTree(
            List.of(
                "含原文与来源身份，尚未匿名，不会自动公开或发送给评审。",
                "预测来自原产物，仅转换评论证据命名空间而不改判断；映射随包保存，gold仍为空；导出不是独立质量通过，也不证明来源授权。",
                "真实来源默认UNVERIFIED，需确认权限再改AUTHORIZED并填写permissionRef。",
                "同作品及近重复组不得跨dev/test；选择dev或test只是候选意图，需合并整个数据集再检查泄漏。",
                "需要隐藏预测由独立人员标注，再裁决；可用tools/prepare_review.py拆分文件。")));
    return bundle.toPrettyString();
  }
}
