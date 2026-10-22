/* 文件职责：仅在人工提交后按原评论身份并列对照原模型结果，不把初审分歧当准确率。 */
package io.echoinsight.quality;

import static io.echoinsight.quality.QualityReviewProtocol.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.ModelContract;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.report.ReportCommentIndex;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对照只是定位待复查案例；模型与人工谁正确仍需证据和独立裁决。 */
@Service
public class QualityReviewComparison {
  private final QualityReviewStore reviews;
  private final AnalysisRequestStore requests;
  private final ImportMapper imports;
  private final ObjectMapper json;
  private final JobStore jobs;

  /** 只读取已有报告，不依赖供应商、网关或模型出站执行器。 */
  public QualityReviewComparison(
      QualityReviewStore reviews,
      AnalysisRequestStore requests,
      ImportMapper imports,
      ObjectMapper json,
      JobStore jobs) {
    this.reviews = reviews;
    this.requests = requests;
    this.imports = imports;
    this.json = json;
    this.jobs = jobs;
  }

  /** 命题才有覆盖/立场字段对照；普通讨论只显示明确引用该目标的主张，不猜语义匹配。 */
  public record Item(
      String commentId,
      Label human,
      JsonNode modelDecision,
      List<JsonNode> citedClaims,
      String coverageComparison,
      String positionComparison,
      String note) {}

  /** hash固定本次对照依赖，无总体准确率、自动质量通过或认证评审人字段。 */
  public record View(
      String inputId,
      String inputSha256,
      String recordId,
      String recordSha256,
      String requestId,
      String artifactId,
      String artifactSha256,
      String sourceMode,
      String state,
      int sampledTargets,
      int coverageDisagreements,
      int positionDisagreements,
      List<Item> items,
      boolean independentQualityProven,
      List<String> notes) {}

  /** 1、必须提交；2、原资料/计划未漂移；3、只读原产物；4、以来源映射对照，不改原判断。 */
  @Transactional(readOnly = true)
  public View compare(String projectId, String inputId, String recordId) {
    var input = reviews.get(projectId, inputId);
    var saved = reviews.record(projectId, inputId, recordId);
    if (!saved.body().kind().equals("SUBMISSION"))
      throw QualityReviewProtocol.bad("请先提交全部人工判断，再打开模型对照");
    var request = requests.get(projectId, input.input().requestId());
    String raw = imports.snapshot(request.snapshotId()).documentJson();
    if (!ReferenceMaterialStore.hash(raw).equals(input.input().snapshotSha256())
        || !ReferenceMaterialStore.hash(ModelContract.encode(jobs.plan(request.jobId())))
            .equals(input.input().planSha256()))
      throw new IllegalStateException("Quality comparison source drift");
    var notes =
        List.of(
            "人工已提交后才由此接口读取预测；无法认证此前是否在其他页面看过报告。",
            "单人初审、声明未看过及本样本字段相同，均不能证明独立质量通过。",
            "未引用该评论只表示没有显式引用，不等于未考虑、无观点或模型判断错误。",
            "仅显示本次抽样目标；差异是复查线索，不是准确率，也不是整视频/平台覆盖率。");
    if (!request.status().equals("SUCCESS"))
      return view(input, saved, null, "UNAVAILABLE", "REPORT_NOT_READY", 0, 0, List.of(), notes);
    var original = requests.artifact(projectId, request.id());
    String mode = original.path("sourceMode").asText();
    if (Set.of("MODEL", "LOCAL_STUB").contains(mode)
        && !mode.equals(jobs.plan(request.jobId()).reportMode()))
      throw new IllegalStateException("Quality prediction source mode mismatch");
    if (!Set.of("MODEL", "LOCAL_STUB").contains(mode))
      return view(input, saved, original, mode, "NO_SEMANTIC_PREDICTION", 0, 0, List.of(), notes);
    ImportDocument doc;
    try {
      doc = json.readValue(raw, ImportDocument.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Quality comparison source invalid", e);
    }
    var report = ReportCommentIndex.normalize(original, doc);
    Map<String, JsonNode> decisions = new HashMap<>();
    for (var d : report.path("stance").path("decisions"))
      decisions.put(d.path("commentId").asText(), d);
    List<Item> items = new ArrayList<>();
    int coverageDiff = 0, positionDiff = 0;
    for (var label : saved.body().labels()) {
      var decision = decisions.get(label.commentId());
      String coverage = "NOT_APPLICABLE", positions = "NOT_APPLICABLE";
      if (input.input().mode().equals("STANCE")) {
        if (decision == null) {
          coverage = "PREDICTION_MISSING";
          positions = "PREDICTION_MISSING";
        } else {
          coverage =
              decision.path("coverage").asText().equals(label.coverage()) ? "SAME" : "DIFFERENT";
          Set<String> actual = new HashSet<>();
          decision.path("claims").forEach(c -> actual.add(c.path("position").asText()));
          positions = actual.equals(new HashSet<>(label.positions())) ? "SAME" : "DIFFERENT";
          if (coverage.equals("DIFFERENT")) coverageDiff++;
          if (positions.equals("DIFFERENT")) positionDiff++;
        }
      }
      List<JsonNode> cited = new ArrayList<>();
      for (var claim : report.path("claims"))
        for (var evidence : claim.path("evidenceIds"))
          if (evidence.asText().equals(label.commentId())) {
            cited.add(claim);
            break;
          }
      items.add(
          new Item(
              label.commentId(),
              label,
              decision,
              List.copyOf(cited),
              coverage,
              positions,
              input.input().mode().equals("STANCE")
                  ? "只比较字段集合，理由和引用语义仍需人工复查。"
                  : "仅按显式原文引用关联，不自动判定归纳是否完整或正确。"));
    }
    return view(
        input,
        saved,
        original,
        mode,
        mode.equals("LOCAL_STUB") ? "ENGINEERING_ONLY" : "HUMAN_FIRST_REVIEW",
        coverageDiff,
        positionDiff,
        List.copyOf(items),
        notes);
  }

  /** 只投影不可变身份/哈希及样本计数，不把字段匹配换算为宣称准确率的百分比。 */
  private View view(
      QualityReviewStore.View input,
      QualityReviewStore.Saved saved,
      JsonNode artifact,
      String mode,
      String state,
      int coverage,
      int positions,
      List<Item> items,
      List<String> notes) {
    return new View(
        input.id(),
        input.sha256(),
        saved.id(),
        saved.sha256(),
        input.input().requestId(),
        artifact == null ? null : artifact.path("artifactId").asText(),
        artifact == null ? null : ReferenceMaterialStore.hash(artifact.toString()),
        mode,
        state,
        input.input().targetIds().size(),
        coverage,
        positions,
        items,
        false,
        notes);
  }
}
