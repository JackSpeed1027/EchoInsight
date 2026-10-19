/* 文件职责：提供任务创建、查询、重试以及报告和证据读取接口。 */
package io.echoinsight.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.orchestration.JobStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
/** 提供任务创建、查询、重试以及报告和证据读取接口。 */
public class AnalysisJobController {
  private final JobStore store;
  private final ImportMapper imports;
  private final ObjectMapper json;
  private final io.echoinsight.model.ConfiguredModelPlan plans;

  /** 注入本类需要的依赖，避免在业务方法中自行创建连接或组件。 */
  public AnalysisJobController(
      JobStore store,
      ImportMapper imports,
      ObjectMapper json,
      io.echoinsight.model.ConfiguredModelPlan plans) {
    this.plans = plans;
    this.store = store;
    this.imports = imports;
    this.json = json;
  }

  /** 创建任务所需的快照标识。 */
  public record Request(@NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String snapshotId) {}

  /** 给页面展示的步骤状态。 */
  public record StepView(String key, String status) {}

  /** 任务进度、报告入口和执行历史。 */
  public record JobView(
      String id,
      String snapshotId,
      String status,
      int progress,
      int attempts,
      String lastErrorCode,
      String reportId,
      List<StepView> steps,
      List<JobStore.Attempt> history) {}

  @PostMapping("/analysis-jobs")
  /** 1、验证快照存在；2、按请求键创建或复用任务；3、仅为新任务初始化步骤。 */
  public ResponseEntity<JobView> create(
      @Valid @RequestBody Request request, @RequestHeader("Idempotency-Key") String key) {
    if (!key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求键格式错误");
    var job = store.createFromApi(request.snapshotId(), key, plans.current());
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/analysis-jobs/" + job.id()))
        .body(view(job.id()));
  }

  /** 只读查询任务与粗粒度进度；查询不会启动分析或刷新模型结果。 */
  @GetMapping("/analysis-jobs/{id}")
  public JobView get(@PathVariable String id) {
    return view(id);
  }

  /** 恢复可安全重试的失败任务；状态与未知调用校验交给 JobStore，冲突返回 409。 */
  @PostMapping("/analysis-jobs/{id}/retry")
  public ResponseEntity<JobView> retry(@PathVariable String id) {
    store.retry(id);
    return ResponseEntity.accepted().body(view(id));
  }

  /** 把持久化步骤映射为粗粒度进度，同时返回报告入口和执行历史。 */
  private JobView view(String id) {
    var job = store.get(id);
    var steps = store.steps(id);
    int progress = 0;
    for (var step : steps) {
      if (step.key().equals("CLASSIFY"))
        progress =
            step.status().equals("SUCCEEDED") ? 60 : step.status().equals("RUNNING") ? 10 : 0;
      if (step.key().equals("REPORT") && step.status().equals("RUNNING")) progress = 80;
    }
    if (job.status().equals("SUCCESS")) progress = 100;
    var report = store.reportForJob(id);
    return new JobView(
        id,
        job.snapshotId(),
        job.status(),
        progress,
        job.attempts(),
        job.lastErrorCode(),
        report == null ? null : report.id(),
        steps.stream().map(s -> new StepView(s.key(), s.status())).toList(),
        store.attempts(id));
  }

  /** 读取已发布的不可变报告，路径参数是 reportId，不能用任务 ID 替代。 */
  @GetMapping("/reports/{id}")
  public JsonNode report(@PathVariable String id) throws JsonProcessingException {
    return json.readTree(store.report(id).json());
  }

  /** 绑定历史快照的评论原文。 */
  public record Evidence(String commentId, String sourceCommentId, String text) {}

  /** 从报告绑定的历史快照提取被引用原文；后续导入不能改变旧证据。 */
  @GetMapping("/reports/{id}/evidence")
  public List<Evidence> evidence(@PathVariable String id) throws JsonProcessingException {
    var report = store.report(id);
    var body = json.readTree(report.json());
    Set<String> ids = new LinkedHashSet<>();
    body.path("findings").forEach(f -> f.path("evidenceIds").forEach(e -> ids.add(e.asText())));
    var snapshot = json.readTree(imports.snapshot(report.snapshotId()).documentJson());
    List<Evidence> evidence = new ArrayList<>();
    snapshot
        .path("comments")
        .forEach(
            c -> {
              if (ids.contains(c.path("id").asText()))
                evidence.add(
                    new Evidence(
                        c.path("id").asText(),
                        c.path("sourceCommentId").asText(),
                        c.path("text").asText()));
            });
    return evidence;
  }
}
