/* 文件职责：提供项目内自然语言分析请求、状态历史和类型化产物接口。 */
package io.echoinsight.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.echoinsight.intelligence.AnalysisRequestStore;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 用户只提交目标和可选资料；分析配方、模型、工具、权限与预算由服务端决定。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-requests")
public class AnalysisRequestController {
  private final io.echoinsight.intelligence.AnalysisPreflight preflight;
  private final io.echoinsight.intelligence.AnalysisFailureQuery failures;
  private final AnalysisRequestStore requests;

  /** 控制器只转换 HTTP 语义，业务范围和幂等由存储服务处理。 */
  public AnalysisRequestController(
      AnalysisRequestStore requests,
      io.echoinsight.intelligence.AnalysisPreflight preflight,
      io.echoinsight.intelligence.AnalysisFailureQuery failures) {
    this.preflight = preflight;
    this.failures = failures;
    this.requests = requests;
  }

  /** snapshotId 可空；只有项目恰好一份资料时才自动选择。 */
  public record Create(
      String instruction,
      String snapshotId,
      Boolean includeTranscript,
      java.util.List<String> referenceIds,
      Boolean allowPartialInput,
      String proposition,
      String analysisScope,
      Boolean confirmFullScope,
      String transcriptMaterialId,
      String contentMaterialId,
      Long contentFromMs,
      Long contentToMs,
      String baselineRequestId) {
    /** 旧9字段调用不选择新背景。 */
    public Create(
        String instruction,
        String snapshotId,
        Boolean includeTranscript,
        java.util.List<String> referenceIds,
        Boolean allowPartialInput,
        String proposition,
        String analysisScope,
        Boolean confirmFullScope,
        String transcriptMaterialId) {
      this(
          instruction,
          snapshotId,
          includeTranscript,
          referenceIds,
          allowPartialInput,
          proposition,
          analysisScope,
          confirmFullScope,
          transcriptMaterialId,
          null,
          null,
          null,
          null);
    }
  }

  /** 首次请求201，网络重发200；两者都返回可轮询的持久请求。 */
  @PostMapping
  public ResponseEntity<AnalysisRequestStore.RequestRow> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Create body) {
    var result =
        requests.createWithMaterials(
            projectId,
            key,
            body.snapshotId(),
            body.instruction(),
            Boolean.TRUE.equals(body.includeTranscript()),
            body.referenceIds(),
            Boolean.TRUE.equals(body.allowPartialInput()),
            body.proposition(),
            body.analysisScope(),
            Boolean.TRUE.equals(body.confirmFullScope()),
            body.transcriptMaterialId(),
            body.contentMaterialId(),
            body.contentFromMs(),
            body.contentToMs(),
            body.baselineRequestId());
    return ResponseEntity.status(result.created() ? 201 : 200)
        .location(
            URI.create(
                "/api/v1/projects/" + projectId + "/analysis-requests/" + result.request().id()))
        .body(result.request());
  }

  /** 只读预检；POST仅因背景选择需要请求体，不创建任务/预算，也不调用模型。 */
  @PostMapping("/preview")
  public io.echoinsight.intelligence.AnalysisPreflight.View preview(
      @PathVariable String projectId, @RequestBody Create body) {
    return preflight.previewWithContent(
        projectId,
        body.snapshotId(),
        Boolean.TRUE.equals(body.includeTranscript()),
        body.referenceIds(),
        body.instruction(),
        body.proposition(),
        body.analysisScope(),
        body.transcriptMaterialId(),
        body.contentMaterialId(),
        body.contentFromMs(),
        body.contentToMs(),
        body.baselineRequestId());
  }

  /** 外层对象始终返回JSON，旧请求specification为null，客户端无需解析空204正文。 */
  public record SpecificationView(
      io.echoinsight.intelligence.AnalysisSpecification specification) {}

  /** 按项目读取冻结规格；普通讨论不回填命题，也不触发调用。 */
  @GetMapping("/{requestId}/specification")
  public SpecificationView specification(
      @PathVariable String projectId, @PathVariable String requestId) {
    return new SpecificationView(requests.specification(projectId, requestId));
  }

  /** 完整分析真实进度；未开始/已完成/失败/结果未知来自原检查点，不重新派工。 */
  @GetMapping("/{requestId}/progress")
  public io.echoinsight.intelligence.AnalysisScopeStore.Progress progress(
      @PathVariable String projectId, @PathVariable String requestId) {
    return requests.progress(projectId, requestId);
  }

  /** 只读失败详情；项目越界仍404，状态未失败时不虚构失败原因。 */
  @GetMapping("/{requestId}/failure")
  public io.echoinsight.intelligence.AnalysisFailureQuery.View failure(
      @PathVariable String projectId, @PathVariable String requestId) {
    return failures.get(projectId, requestId);
  }

  /** 历史只读分页，不会重跑分析。 */
  @GetMapping
  public AnalysisRequestStore.Page<AnalysisRequestStore.RequestRow> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String baselineForSnapshotId) {
    return requests.list(projectId, page, size, baselineForSnapshotId);
  }

  /** 状态包含任务与产物入口，页面据此轮询，不自己估算完成时间。 */
  @GetMapping("/{requestId}")
  public AnalysisRequestStore.RequestRow get(
      @PathVariable String projectId, @PathVariable String requestId) {
    return requests.get(projectId, requestId);
  }

  /** 结果仅在原子发布成功后可读，未完成返回409。 */
  @GetMapping("/{requestId}/artifact")
  public JsonNode artifact(@PathVariable String projectId, @PathVariable String requestId) {
    return requests.artifact(projectId, requestId);
  }
}
