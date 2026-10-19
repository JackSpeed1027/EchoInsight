/* 文件职责：从持久任务与调用账本读取失败诊断，保留任务失败和费用未知的区别。 */
package io.echoinsight.intelligence;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只返回受控错误码，不返回评论、供应商响应、地址、凭据或存档位置。 */
@Service
public class AnalysisFailureQuery {
  private final AnalysisRequestStore requests;
  private final JdbcTemplate db;

  /** 项目资格仍由请求存储统一校验，避免另造一套权限判断。 */
  public AnalysisFailureQuery(AnalysisRequestStore requests, JdbcTemplate db) {
    this.requests = requests;
    this.db = db;
  }

  /** feeUncertain为真实账本未决状态；canRetry只说明服务端当前允许重试，不代表错误已修好。 */
  public record View(
      String requestId,
      String jobId,
      String status,
      String errorCode,
      String causeCode,
      boolean feeUncertain,
      boolean canRetry) {}

  /** 同一只读事务核对资格、当前状态和最近错误；查询不会调用模型或初始化预算。 */
  @Transactional(readOnly = true)
  public View get(String projectId, String requestId) {
    var request = requests.get(projectId, requestId);
    int pending =
        db.queryForObject(
            "SELECT COUNT(*) FROM model_call WHERE job_id=? AND state IN"
                + " ('SENT','OUTCOME_UNKNOWN')",
            Integer.class,
            request.jobId());
    List<String> causes =
        db.queryForList(
            "SELECT error_code FROM model_call WHERE job_id=? AND error_code IS NOT NULL ORDER BY"
                + " created_at DESC,id DESC LIMIT 1",
            String.class,
            request.jobId());
    return new View(
        request.id(),
        request.jobId(),
        request.status(),
        safe(request.lastErrorCode()),
        causes.isEmpty() ? null : safe(causes.getFirst()),
        pending > 0,
        "FAILED".equals(request.status()) && pending == 0);
  }

  /** 防止手工损坏的数据库内容变成任意可展示字符串。 */
  private String safe(String value) {
    return value == null
        ? null
        : value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : "UNRECOGNIZED_FAILURE";
  }
}
