/* 文件职责：在原任务行锁内取消/继续/重试，同时保留检查点、费用不确定性和追加控制审计。 */
package io.echoinsight.orchestration;

import io.echoinsight.collection.CollectionStore;
import io.echoinsight.model.ModelLedger;
import io.echoinsight.project.ProjectStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 所有网络在本服务之外；行锁与原Worker写入互斥，谁先提交决定取消或发布的结果。 */
@Service
public class TaskControlService {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ProjectTaskQuery query;
  private final JobStore jobs;
  private final CollectionStore collections;
  private final ModelLedger ledger;

  /** 组合已有存储服务；不为任务中心另造租约、派发器、预算或重试执行器。 */
  public TaskControlService(
      JdbcTemplate db,
      ProjectStore projects,
      ProjectTaskQuery query,
      JobStore jobs,
      CollectionStore collections,
      ModelLedger ledger) {
    this.db = db;
    this.projects = projects;
    this.query = query;
    this.jobs = jobs;
    this.collections = collections;
    this.ledger = ledger;
  }

  /** 立即提交逻辑取消。1、项目锁及任务锁重核资格；2、未发出释放，已发出保留未知； 3、撤销原租约、同步请求/活动步骤/尝试；4、只追加一次事件。成功检查点永不清空。 */
  @Transactional
  public ProjectTaskQuery.Detail cancel(String projectId, String kind, String id) {
    String previous = lock(projectId, kind, id);
    if ("CANCELLED".equals(previous)) return query.detail(projectId, kind, id);
    if (!TaskPolicy.canCancel(previous)) throw conflict("只有等待或运行中的任务可以取消");
    if ("ANALYSIS".equals(kind)) {
      var calls =
          db.queryForList(
              "SELECT id FROM model_call WHERE job_id=? AND state IN ('RESERVED','SENT') ORDER BY"
                  + " id",
              String.class,
              id);
      for (String call : calls) ledger.abort(call, "USER_CANCELLED");
      db.update(
          "UPDATE analysis_job SET"
              + " status='CANCELLED',owner_token=NULL,lease_until=NULL,last_error_code='USER_CANCELLED'"
              + " WHERE id=?",
          id);
      db.update(
          "UPDATE analysis_request SET status='CANCELLED',last_error_code='USER_CANCELLED' WHERE"
              + " job_id=?",
          id);
      db.update(
          "UPDATE job_step SET status='CANCELLED' WHERE job_id=? AND status IN ('READY','RUNNING')",
          id);
      db.update(
          "UPDATE job_attempt SET"
              + " status='CANCELLED',error_code='USER_CANCELLED',finished_at=UTC_TIMESTAMP(6) WHERE"
              + " job_id=? AND status='RUNNING'",
          id);
    } else {
      db.update(
          "UPDATE source_collection_batch SET"
              + " status='CANCELLED',owner_token=NULL,lease_until=NULL,last_error_code='USER_CANCELLED',finished_at=UTC_TIMESTAMP(6)"
              + " WHERE id=?",
          id);
    }
    event(projectId, kind, id, "CANCEL", previous, "CANCELLED");
    return query.detail(projectId, kind, id);
  }

  /** 显式继续已取消任务。1、同一锁顺序；2、拒绝SENT/UNKNOWN，不能把未知账单变成零； 3、只重置被取消步骤，不改成功片/输入/计划/预算；4、追加事件，原派发器随后领取。 */
  @Transactional
  public ProjectTaskQuery.Detail resume(String projectId, String kind, String id) {
    String previous = lock(projectId, kind, id);
    if (!"CANCELLED".equals(previous)) throw conflict("只有已取消任务可以继续；失败任务请使用安全重试");
    if ("ANALYSIS".equals(kind)) {
      if (uncertain(id)) throw conflict("模型调用结果与费用未决，不能继续或直接重试");
      db.update(
          "UPDATE analysis_job SET"
              + " status='PENDING',recoveries=0,owner_token=NULL,lease_until=NULL,last_error_code=NULL"
              + " WHERE id=?",
          id);
      db.update(
          "UPDATE analysis_request SET status='PENDING',last_error_code=NULL WHERE job_id=?", id);
      db.update("UPDATE job_step SET status='READY' WHERE job_id=? AND status='CANCELLED'", id);
    } else {
      db.update(
          "UPDATE source_collection_batch SET"
              + " status='PENDING',recoveries=0,owner_token=NULL,lease_until=NULL,last_error_code=NULL,finished_at=NULL"
              + " WHERE id=?",
          id);
    }
    event(projectId, kind, id, "RESUME", previous, "PENDING");
    return query.detail(projectId, kind, id);
  }

  /** 项目任务中心的安全重试委托原服务；FAILED外拒绝，费用未决仍由原账本保护。 */
  @Transactional
  public ProjectTaskQuery.Detail retry(String projectId, String kind, String id) {
    String previous = lock(projectId, kind, id);
    if (!TaskPolicy.canRetry(previous, "ANALYSIS".equals(kind) && uncertain(id)))
      throw conflict("只有没有未决模型调用的失败任务可以安全重试");
    if ("ANALYSIS".equals(kind)) jobs.retry(id);
    else collections.retry(projectId, id);
    event(projectId, kind, id, "RETRY", previous, "PENDING");
    return query.detail(projectId, kind, id);
  }

  /** 先锁项目再锁原任务，顺序与接受/发布一致。资格查询在首个一致性读之前取得任务锁， 避免MySQL默认RR读取旧状态；表名只来自固定枚举，ID和值全部绑定参数。 */
  private String lock(String projectId, String kind, String id) {
    TaskPolicy.kind(kind);
    projects.lock(projectId);
    String table = "ANALYSIS".equals(kind) ? "analysis_job" : "source_collection_batch";
    var states =
        db.queryForList("SELECT status FROM " + table + " WHERE id=? FOR UPDATE", String.class, id);
    if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目任务不存在");
    query.item(projectId, kind, id);
    return states.getFirst();
  }

  /** 行锁内检查账本，取消状态不掩盖供应商可能已经计费的事实。 */
  private boolean uncertain(String id) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM model_call WHERE job_id=? AND state IN"
                + " ('SENT','OUTCOME_UNKNOWN')",
            Integer.class,
            id)
        > 0;
  }

  /** 只记录真正发生的转换；取消重发不重复追加，也不回填旧操作。 */
  private void event(
      String projectId, String kind, String id, String action, String previous, String next) {
    db.update(
        "INSERT INTO"
            + " task_control_event(id,project_id,task_kind,task_id,action,previous_status,next_status)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        projectId,
        kind,
        id,
        action,
        previous,
        next);
  }

  /** 把状态冲突转换为可读的409，底层异常和供应商正文不进入界面。 */
  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
