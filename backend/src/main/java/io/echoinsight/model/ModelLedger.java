/* 文件职责：在短事务中预留模拟预算、保存批次检查点，保留结果未知的调用记录。 */
package io.echoinsight.model;

import io.echoinsight.orchestration.JobStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 所有余额变化先锁任务行；数据库事务中不发送 HTTP 或等待模型。 */
@Repository
public class ModelLedger {
  private final JdbcTemplate db;
  private final JobStore jobs;
  private final TransactionTemplate tx;
  private final TransactionTemplate read;

  /** 注入与任务引擎相同的事务管理器，使任务归属校验和账本写入共同提交。 */
  public ModelLedger(JdbcTemplate db, JobStore jobs, PlatformTransactionManager manager) {
    this.db = db;
    this.jobs = jobs;
    tx = new TransactionTemplate(manager);
    read = new TransactionTemplate(manager);
    read.setReadOnly(true);
  }

  /** cached 非空时复用成功产物；failure 非空时提交必要审计后拒绝新调用。 */
  public record Prepared(String callId, String cached, String failure) {}

  /** reserved 是未结清的预留，settled 是模拟用量结算，两者不能混算。 */
  public record Budget(long limitUnits, long reservedUnits, long settledUnits, String unit) {}

  /** 物理调用尝试；未知用量保留 null，不能假设为零。 */
  public record Call(
      String id,
      String state,
      long reservedUnits,
      Long settledUnits,
      Integer inputTokens,
      Integer outputTokens,
      String requestId,
      String errorCode,
      String createdAt) {}

  /** 每批仅展示最近三次尝试，成功产物正文不在诊断接口回传。 */
  public record Batch(int index, String status, int attempts, List<Call> recentCalls) {}

  /** 批次分页与执行计划用于说明发生了什么，不提供付费模型切换功能。 */
  public record View(
      String jobId,
      ModelContract.Plan plan,
      Budget budget,
      int total,
      int page,
      int size,
      List<Batch> items) {}

  /**
   * 在同一短事务中决定该批是否允许再次出站，避免并发调用重复花费同一份余额。
   *
   * <p>1、锁定任务并验证 token、固定计划和输入指纹。 2、已成功则返回缓存；同执行者的在途调用返回忙，不能取消它。 3、恢复旧尝试时，未发送的释放额度，已发送的保留未知与预留。
   * 4、确认可用额度后，新建 RESERVED 尝试并增加预留；事务中不执行 HTTP。
   *
   * <p>需要保存审计的失败通过 Prepared.failure 返回，让状态先提交； 若直接抛异常回滚，会把“已经发现结果未知”的事实也丢掉。
   *
   * @return 缓存结果、新调用身份或受控拒绝原因，三种情况由网关分别处理
   */
  public Prepared prepare(
      String jobId,
      String token,
      int index,
      String hash,
      long reservation,
      ModelContract.Plan plan) {
    return jobs.withLease(
        jobId,
        token,
        () -> {
          if (!plan.equals(jobs.plan(jobId))) throw new ModelContract.Failure("MODEL_PLAN_CHANGED");
          db.update(
              "INSERT INTO model_budget(job_id,limit_units,unit) VALUES(?,?,?) ON DUPLICATE KEY"
                  + " UPDATE job_id=job_id",
              jobId,
              plan.budgetLimit(),
              plan.unit());
          db.update(
              "INSERT INTO model_batch(job_id,batch_index,input_hash,status) VALUES(?,?,?,'READY')"
                  + " ON DUPLICATE KEY UPDATE job_id=job_id",
              jobId,
              index,
              hash);
          var batch =
              db.queryForMap(
                  "SELECT * FROM model_batch WHERE job_id=? AND batch_index=?", jobId, index);
          if (!hash.equals(batch.get("input_hash")))
            throw new ModelContract.Failure("MODEL_INPUT_CHANGED");
          if ("SUCCEEDED".equals(batch.get("status")))
            return new Prepared(null, (String) batch.get("result_json"), null);
          if ("OUTCOME_UNKNOWN".equals(batch.get("status")))
            return new Prepared(null, null, "MODEL_OUTCOME_UNKNOWN");
          String previous = (String) batch.get("current_call_id");
          if (previous != null && "RUNNING".equals(batch.get("status"))) {
            String previousOwner =
                db.queryForObject(
                    "SELECT owner_token FROM model_call WHERE id=?", String.class, previous);
            if (token.equals(previousOwner)) return new Prepared(null, null, "MODEL_BATCH_BUSY");
            String state = abort(previous, "WORKER_INTERRUPTED");
            // 返回失败而非在事务内抛出，确保未知状态与未释放预算能真正保存。
            if ("OUTCOME_UNKNOWN".equals(state))
              return new Prepared(null, null, "MODEL_OUTCOME_UNKNOWN");
          }
          Budget budget = budget(jobId, plan);
          if (reservation > budget.limitUnits() - budget.reservedUnits() - budget.settledUnits())
            return new Prepared(null, null, "MODEL_BUDGET_EXCEEDED");
          String callId = UUID.randomUUID().toString();
          db.update(
              "UPDATE model_budget SET reserved_units=reserved_units+? WHERE job_id=?",
              reservation,
              jobId);
          db.update(
              "INSERT INTO"
                  + " model_call(id,job_id,batch_index,owner_token,state,reserved_units,created_at)"
                  + " VALUES(?,?,?,?,'RESERVED',?,UTC_TIMESTAMP(6))",
              callId,
              jobId,
              index,
              token,
              reservation);
          db.update(
              "UPDATE model_batch SET status='RUNNING',current_call_id=? WHERE job_id=? AND"
                  + " batch_index=?",
              callId,
              jobId,
              index);
          return new Prepared(callId, null, null);
        });
  }

  /** 出站线程即将调用供应商前再次校验租约和预约状态，已释放的请求不能再发出。 */
  public void sent(String jobId, String token, String callId) {
    jobs.withLease(
        jobId,
        token,
        () -> {
          if (db.update(
                  "UPDATE model_call SET state='SENT' WHERE id=? AND owner_token=? AND"
                      + " state='RESERVED'",
                  callId,
                  token)
              != 1) throw new JobStore.LostLease();
          return null;
        });
  }

  /** 1、验证有效执行者；2、提交批次结果；3、结算用量并释放差额，整体同事务。 */
  public void complete(
      String jobId,
      String token,
      int index,
      String callId,
      ModelContract.Response result,
      long cost) {
    completeJson(
        jobId,
        token,
        index,
        callId,
        ModelContract.encode(result),
        result.inputTokens(),
        result.outputTokens(),
        result.requestId(),
        cost);
  }

  /**
   * 提交任意已经过网关校验的类型化模型结果，使分类与讨论共用同一套预算和恢复语义。
   *
   * <p>正文在调用前已经序列化；本方法只接受用量和请求身份，不解释具体业务 Schema。
   */
  public void completeJson(
      String jobId,
      String token,
      int index,
      String callId,
      String resultJson,
      int inputTokens,
      int outputTokens,
      String requestId,
      long cost) {
    jobs.withLease(
        jobId,
        token,
        () -> {
          var call =
              db.queryForMap(
                  "SELECT state,reserved_units FROM model_call WHERE id=? AND owner_token=?",
                  callId,
                  token);
          long reserved = ((Number) call.get("reserved_units")).longValue();
          if (!"SENT".equals(call.get("state")) || cost < 0 || cost > reserved)
            throw new ModelContract.Failure("MODEL_INVALID_USAGE");
          if (db.update(
                  "UPDATE model_batch SET status='SUCCEEDED',result_json=? WHERE job_id=? AND"
                      + " batch_index=? AND current_call_id=? AND status='RUNNING'",
                  resultJson,
                  jobId,
                  index,
                  callId)
              != 1) throw new JobStore.LostLease();
          db.update(
              "UPDATE model_call SET"
                  + " state='SUCCEEDED',settled_units=?,input_tokens=?,output_tokens=?,request_id=?,finished_at=UTC_TIMESTAMP(6)"
                  + " WHERE id=?",
              cost,
              inputTokens,
              outputTokens,
              requestId,
              callId);
          db.update(
              "UPDATE model_budget SET"
                  + " reserved_units=reserved_units-?,settled_units=settled_units+? WHERE job_id=?",
              reserved,
              cost,
              jobId);
          return null;
        });
  }

  /** 取消或崩溃恢复时按事实处理：未发送释放，已发送保留未知；不需要旧租约仍有效。 */
  public String abort(String callId, String error) {
    String jobId =
        db.queryForObject("SELECT job_id FROM model_call WHERE id=?", String.class, callId);
    return tx.execute(
        status -> {
          db.queryForList("SELECT id FROM analysis_job WHERE id=? FOR UPDATE", String.class, jobId);
          var call = db.queryForMap("SELECT * FROM model_call WHERE id=? FOR UPDATE", callId);
          String state = (String) call.get("state");
          if (!"SENT".equals(state) && !"RESERVED".equals(state)) return state;
          boolean unknown = "SENT".equals(state);
          String next = unknown ? "OUTCOME_UNKNOWN" : "RELEASED";
          db.update(
              "UPDATE model_call SET state=?,error_code=?,finished_at=UTC_TIMESTAMP(6) WHERE id=?",
              next,
              error,
              callId);
          if (!unknown)
            db.update(
                "UPDATE model_budget SET reserved_units=reserved_units-? WHERE job_id=?",
                call.get("reserved_units"),
                jobId);
          db.update(
              "UPDATE model_batch SET status=? WHERE job_id=? AND batch_index=? AND"
                  + " current_call_id=? AND status='RUNNING'",
              unknown ? "OUTCOME_UNKNOWN" : "READY",
              jobId,
              call.get("batch_index"),
              callId);
          return next;
        });
  }

  /** 尚未调用过的任务只返回零用量视图，查询不初始化预算行。 */
  private Budget budget(String jobId, ModelContract.Plan plan) {
    var rows =
        db.query(
            "SELECT limit_units,reserved_units,settled_units,unit FROM model_budget WHERE job_id=?",
            (r, n) -> new Budget(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4)),
            jobId);
    return rows.isEmpty() ? new Budget(plan.budgetLimit(), 0, 0, plan.unit()) : rows.getFirst();
  }

  /** 只读取有限批次与调用摘要，不返回评论、密钥、供应商端点或请求原文。 */
  public View view(String jobId, int page, int size) {
    return read.execute(
        status -> {
          jobs.get(jobId);
          var plan = jobs.plan(jobId);
          int total =
              db.queryForObject(
                  "SELECT COUNT(*) FROM model_batch WHERE job_id=?", Integer.class, jobId);
          var indexes =
              db.queryForList(
                  "SELECT batch_index FROM model_batch WHERE job_id=? ORDER BY batch_index LIMIT ?"
                      + " OFFSET ?",
                  Integer.class,
                  jobId,
                  size,
                  (page - 1) * size);
          List<Batch> batches = new ArrayList<>();
          for (int index : indexes) {
            String state =
                db.queryForObject(
                    "SELECT status FROM model_batch WHERE job_id=? AND batch_index=?",
                    String.class,
                    jobId,
                    index);
            int attempts =
                db.queryForObject(
                    "SELECT COUNT(*) FROM model_call WHERE job_id=? AND batch_index=?",
                    Integer.class,
                    jobId,
                    index);
            var calls =
                db.query(
                    "SELECT"
                        + " id,state,reserved_units,settled_units,input_tokens,output_tokens,request_id,error_code,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')"
                        + " created_time FROM model_call WHERE job_id=? AND batch_index=? ORDER BY"
                        + " created_at DESC,id DESC LIMIT 3",
                    (r, n) ->
                        new Call(
                            r.getString("id"),
                            r.getString("state"),
                            r.getLong("reserved_units"),
                            (Long) r.getObject("settled_units"),
                            (Integer) r.getObject("input_tokens"),
                            (Integer) r.getObject("output_tokens"),
                            r.getString("request_id"),
                            r.getString("error_code"),
                            r.getString("created_time")),
                    jobId,
                    index);
            batches.add(new Batch(index, state, attempts, calls));
          }
          return new View(
              jobId, plan, budget(jobId, plan), total, page, size, List.copyOf(batches));
        });
  }
}
