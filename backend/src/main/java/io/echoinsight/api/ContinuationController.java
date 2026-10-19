/* 文件职责：提供评论追问的候选、只读预检、幂等接受和分支历史，复用原分析任务。 */
package io.echoinsight.api;

import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.ContinuationPlanner;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 页面只提交评论身份与自然语言问题，不能指定快照、模型、历史正文或费用。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/analysis-requests/{requestId}")
public class ContinuationController {
  private final ContinuationPlanner planner;
  private final AnalysisRequestStore requests;

  /** 接入原请求存储及只读准备器。 */
  public ContinuationController(ContinuationPlanner planner, AnalysisRequestStore requests) {
    this.planner = planner;
    this.requests = requests;
  }

  /** allowPartialInput仅确认有界范围，不是费用无限授权。 */
  public record Create(String anchorCommentId, String question, Boolean allowPartialInput) {}

  /** 父报告的评论候选按原证据身份展示；背景不作为观众评论。 */
  @GetMapping("/follow-up-options")
  public List<ContinuationPlanner.Anchor> options(
      @PathVariable String projectId, @PathVariable String requestId) {
    return planner.anchors(projectId, requestId);
  }

  /** 只读范围/摘录预检，不保存或触发模型。 */
  @PostMapping("/follow-ups/preview")
  public ContinuationPlanner.Prepared preview(
      @PathVariable String projectId, @PathVariable String requestId, @RequestBody Create body) {
    return planner.prepare(projectId, requestId, body.anchorCommentId(), body.question());
  }

  /** 首次201/重发200，子请求、任务、背景及关系在原事务中一起接受。 */
  @PostMapping("/follow-ups")
  public ResponseEntity<AnalysisRequestStore.RequestRow> create(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Create body) {
    var result =
        requests.createFollowup(
            projectId,
            key,
            requestId,
            body.anchorCommentId(),
            body.question(),
            Boolean.TRUE.equals(body.allowPartialInput()));
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.request());
  }

  /** 所有分支复用原请求历史，按稳定页读取；没有新增会话状态机。 */
  @GetMapping("/conversation")
  public ProjectStore.Page<ContinuationPlanner.Link> conversation(
      @PathVariable String projectId,
      @PathVariable String requestId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return planner.conversation(projectId, requestId, page, size);
  }
}
