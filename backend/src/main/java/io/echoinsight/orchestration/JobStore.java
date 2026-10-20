/* 文件职责：持久化任务状态、执行租约和历史尝试，并在短事务中完成受保护的写入。 */
package io.echoinsight.orchestration;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Repository
/** 持久化任务状态、执行租约和历史尝试，并在短事务中完成受保护的写入。 */
public class JobStore {
  public static final String MODEL = "synthetic-fixture-v1",
      PROMPT = "no-prompt-v1",
      TAXONOMY = "sentiment-v1";
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final int leaseSeconds;
  private final String executionScope;

  /** 注入本类需要的依赖，避免在业务方法中自行创建连接或组件。 */
  public JobStore(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      @org.springframework.beans.factory.annotation.Value("${echoinsight.worker.lease-seconds:30}")
          int leaseSeconds,
      @org.springframework.beans.factory.annotation.Value(
              "${echoinsight.worker.execution-scope:local}")
          String executionScope) {
    if (leaseSeconds < 1 || leaseSeconds > 300)
      throw new IllegalArgumentException("Invalid lease length");
    this.leaseSeconds = leaseSeconds;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    if (!executionScope.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid analysis execution scope");
    this.executionScope = executionScope;
  }

  /** 持久化任务的当前状态和版本。 */
  public record Job(
      String id,
      String snapshotId,
      String kind,
      String analysisRequestId,
      String status,
      int attempts,
      int recoveries,
      String ownerToken,
      String lastErrorCode,
      String modelVersion,
      String promptVersion,
      String taxonomyVersion) {
    /** 兼容既有报告单元测试和固定反馈任务构造；新任务从数据库读取明确类型。 */
    public Job(
        String id,
        String snapshotId,
        String status,
        int attempts,
        int recoveries,
        String ownerToken,
        String lastErrorCode,
        String modelVersion,
        String promptVersion,
        String taxonomyVersion) {
      this(
          id,
          snapshotId,
          "FEEDBACK",
          null,
          status,
          attempts,
          recoveries,
          ownerToken,
          lastErrorCode,
          modelVersion,
          promptVersion,
          taxonomyVersion);
    }
  }

  /** 步骤状态及可复用的中间结果。 */
  public record Step(String key, String status, String resultJson) {}

  /** 一次执行的结果，不覆盖此前尝试。 */
  public record Attempt(int attempt, String status, String errorCode) {}

  /** 已发布报告及其绑定的快照。 */
  public record ReportRow(String id, String snapshotId, String json) {}

  /** 将数据库当前行转换为任务对象，不把数据库连接暴露给调用方。 */
  private Job row(java.sql.ResultSet r) throws java.sql.SQLException {
    return new Job(
        r.getString("id"),
        r.getString("snapshot_id"),
        r.getString("job_kind"),
        r.getString("analysis_request_id"),
        r.getString("status"),
        r.getInt("attempts"),
        r.getInt("recoveries"),
        r.getString("owner_token"),
        r.getString("last_error_code"),
        r.getString("model_version"),
        r.getString("prompt_version"),
        r.getString("taxonomy_version"));
  }

  /** 读取任务当前状态；任务不存在时返回明确的 404。 */
  public Job get(String id) {
    return db.query("SELECT * FROM analysis_job WHERE id=?", (r, n) -> row(r), id).stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
  }

  /** 1、验证快照存在；2、按请求键创建或复用任务；3、仅为新任务初始化步骤。 */
  public Job create(String snapshotId, String key) {
    return create(snapshotId, key, io.echoinsight.model.ModelContract.Plan.mock());
  }

  /** 内部入口严格比较固定计划；HTTP 入口另外遵循只含快照的请求幂等语义。 */
  public Job create(String snapshotId, String key, io.echoinsight.model.ModelContract.Plan plan) {
    return createInternal(snapshotId, key, plan, true);
  }

  /** HTTP 重发只比较请求中的快照；复用旧任务固定计划，不随服务器配置变化创建或覆盖任务。 */
  public Job createFromApi(
      String snapshotId, String key, io.echoinsight.model.ModelContract.Plan plan) {
    return createInternal(snapshotId, key, plan, false);
  }

  /** 1、原子插入或定位请求键；2、锁行核对请求；3、只给新任务初始化步骤，保留旧计划。 */
  private Job createInternal(
      String snapshotId,
      String key,
      io.echoinsight.model.ModelContract.Plan plan,
      boolean comparePlan) {
    return tx.execute(
        s -> {
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM data_snapshot WHERE id=?", Integer.class, snapshotId)
              == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "快照不存在");
          String candidate = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " analysis_job(id,snapshot_id,request_key,status,model_version,prompt_version,taxonomy_version,execution_plan_json,execution_scope)"
                  + " VALUES(?,?,?,'PENDING',?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
              candidate,
              snapshotId,
              key,
              plan.modelVersion(),
              plan.promptVersion(),
              plan.taxonomyVersion(),
              io.echoinsight.model.ModelContract.encode(plan),
              executionScope);
          Job job =
              db.queryForObject(
                  "SELECT * FROM analysis_job WHERE request_key=? FOR UPDATE",
                  (r, n) -> row(r),
                  key);
          requireExecutionScope(job.id());
          if (!job.snapshotId().equals(snapshotId) || (comparePlan && !plan.equals(plan(job.id()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求键不能用于不同快照");
          if (candidate.equals(job.id())) {
            db.update(
                "INSERT INTO job_step(job_id,step_key,ordinal_no,status)"
                    + " VALUES(?,'CLASSIFY',1,'READY'),(?,'REPORT',2,'READY')",
                candidate,
                candidate);
          }
          return job;
        });
  }

  /**
   * 为自然语言请求创建讨论任务，继续使用同一租约、尝试历史和执行计划。
   *
   * <p>1、确认请求已在外层事务保存；2、按内部唯一键幂等创建 DISCUSSION 任务；3、核对请求、快照与计划；4、初始化讨论和产物发布两个步骤。
   */
  public Job createDiscussion(
      String snapshotId,
      String key,
      io.echoinsight.model.ModelContract.Plan plan,
      String analysisRequestId) {
    return tx.execute(
        status -> {
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM analysis_request WHERE id=? AND snapshot_id=?",
                  Integer.class,
                  analysisRequestId,
                  snapshotId)
              != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析请求或资料不存在");
          String candidate = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " analysis_job(id,snapshot_id,request_key,status,model_version,prompt_version,taxonomy_version,execution_plan_json,job_kind,analysis_request_id,execution_scope)"
                  + " VALUES(?,?,?,'PENDING',?,?,?,?,'DISCUSSION',?,?) ON DUPLICATE KEY UPDATE"
                  + " id=id",
              candidate,
              snapshotId,
              key,
              plan.modelVersion(),
              plan.promptVersion(),
              plan.taxonomyVersion(),
              io.echoinsight.model.ModelContract.encode(plan),
              analysisRequestId,
              executionScope);
          Job job =
              db.queryForObject(
                  "SELECT * FROM analysis_job WHERE analysis_request_id=? FOR UPDATE",
                  (r, n) -> row(r),
                  analysisRequestId);
          requireExecutionScope(job.id());
          if (!"DISCUSSION".equals(job.kind())
              || !snapshotId.equals(job.snapshotId())
              || !analysisRequestId.equals(job.analysisRequestId())
              || !plan.equals(plan(job.id()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分析请求已经绑定其他任务内容");
          }
          if (candidate.equals(job.id())) {
            db.update(
                "INSERT INTO job_step(job_id,step_key,ordinal_no,status)"
                    + " VALUES(?,'DISCUSSION',1,'READY'),(?,'ARTIFACT',2,'READY')",
                candidate,
                candidate);
          }
          return job;
        });
  }

  /** 创建无评论快照的CONTENT任务；输入绑定由外层业务事务随后写入，不能先提交半份任务。 */
  public Job createContent(String key, io.echoinsight.model.ModelContract.Plan plan) {
    return tx.execute(
        status -> {
          String id = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " analysis_job(id,snapshot_id,request_key,status,model_version,prompt_version,taxonomy_version,execution_plan_json,job_kind,execution_scope)"
                  + " VALUES(?,NULL,?,'PENDING',?,?,?,?,'CONTENT',?)",
              id,
              key,
              plan.modelVersion(),
              plan.promptVersion(),
              plan.taxonomyVersion(),
              io.echoinsight.model.ModelContract.encode(plan),
              executionScope);
          db.update(
              "INSERT INTO job_step(job_id,step_key,ordinal_no,status)"
                  + " VALUES(?,'CONTENT',1,'READY'),(?,'ARTIFACT',2,'READY')",
              id,
              id);
          return get(id);
        });
  }

  /** 保存独立内容业务草稿；成功步骤与原模型批次可在恢复时复用。 */
  public void saveContent(String id, String token, String result) {
    tx.executeWithoutResult(
        status -> {
          own(id, token);
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED',result_json=? WHERE job_id=? AND"
                      + " step_key='CONTENT' AND status='RUNNING'",
                  result,
                  id)
              != 1) throw new IllegalStateException("Invalid content transition");
        });
  }

  /** 独立内容产物与任务一次发布；没有评论request/snapshot，不造假填充外键。 */
  public void publishContent(String id, String token, String artifactId, String artifact) {
    publishContent(id, token, artifactId, artifact, "content-artifact-v1");
  }

  /** 新旧内容产物显式匹配冻结协议，原发布租约/步骤/任务事务不变。 */
  public void publishContent(
      String id, String token, String artifactId, String artifact, String schema) {
    boolean full = io.echoinsight.content.ContentFullContract.supports(plan(id));
    if (!(full ? "content-artifact-v2" : "content-artifact-v1").equals(schema))
      throw new IllegalArgumentException("Content artifact protocol mismatch");
    tx.executeWithoutResult(
        status -> {
          own(id, token);
          var job = get(id);
          if (!"CONTENT".equals(job.kind())
              || job.snapshotId() != null
              || db.queryForObject(
                      "SELECT COUNT(*) FROM content_analysis_input WHERE job_id=?",
                      Integer.class,
                      id)
                  != 1) throw new IllegalStateException("Invalid content binding");
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED' WHERE job_id=? AND step_key='ARTIFACT'"
                      + " AND status='RUNNING'",
                  id)
              != 1) throw new IllegalStateException("Invalid content publication");
          db.update(
              "INSERT INTO"
                  + " analysis_artifact(id,request_id,job_id,snapshot_id,schema_version,artifact_json)"
                  + " VALUES(?,NULL,?,NULL,?,?)",
              artifactId,
              id,
              schema,
              artifact);
          db.update(
              "UPDATE analysis_job SET status='SUCCESS',owner_token=NULL,lease_until=NULL WHERE"
                  + " id=?",
              id);
          db.update(
              "UPDATE job_attempt SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(6) WHERE"
                  + " token=?",
              token);
        });
  }

  /** 按依赖顺序读取步骤与已保存的分类结果。 */
  public List<Step> steps(String id) {
    return db.query(
        "SELECT step_key,status,result_json FROM job_step WHERE job_id=? ORDER BY ordinal_no",
        (r, n) -> new Step(r.getString(1), r.getString(2), r.getString(3)),
        id);
  }

  /** 读取历次执行记录，重试不会覆盖此前的失败原因。 */
  public List<Attempt> attempts(String id) {
    return db.query(
        "SELECT attempt_no,status,error_code FROM job_attempt WHERE job_id=? ORDER BY attempt_no",
        (r, n) -> new Attempt(r.getInt(1), r.getString(2), r.getString(3)),
        id);
  }

  /** 只选择待执行或租约已过期的任务，单次限制候选数量。 */
  public List<String> candidates() {
    return db.queryForList(
        "SELECT id FROM analysis_job WHERE execution_scope=? AND (status='PENDING' OR"
            + " (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6))) ORDER BY created_at,id LIMIT"
            + " 16",
        String.class,
        executionScope);
  }

  /** 1、锁定可领取任务；2、记录旧租约失效；3、限制自动恢复次数；4、签发新 token。 */
  public String claim(String id) {
    return tx.execute(
        s -> {
          var jobs =
              db.query(
                  "SELECT * FROM analysis_job WHERE id=? AND execution_scope=? AND"
                      + " (status='PENDING' OR (status='RUNNING' AND lease_until<UTC_TIMESTAMP(6)))"
                      + " FOR UPDATE",
                  (r, n) -> row(r),
                  id,
                  executionScope);
          if (jobs.isEmpty()) return null;
          Job job = jobs.getFirst();
          boolean recovery = job.status().equals("RUNNING");
          if (recovery) {
            db.update(
                "UPDATE job_attempt SET"
                    + " status='ABANDONED',error_code='LEASE_EXPIRED',finished_at=UTC_TIMESTAMP(6)"
                    + " WHERE job_id=? AND status='RUNNING'",
                id);
            if (job.recoveries() >= 3) {
              db.update(
                  "UPDATE analysis_job SET"
                      + " status='FAILED',last_error_code='RECOVERY_LIMIT',owner_token=NULL,lease_until=NULL"
                      + " WHERE id=?",
                  id);
              db.update(
                  "UPDATE analysis_request SET status='FAILED',last_error_code='RECOVERY_LIMIT'"
                      + " WHERE job_id=?",
                  id);
              db.update(
                  "UPDATE job_step SET status='FAILED' WHERE job_id=? AND status='RUNNING'", id);
              return null;
            }
          }
          String token = UUID.randomUUID().toString();
          db.update(
              "UPDATE analysis_job SET"
                  + " status='RUNNING',attempts=attempts+1,recoveries=recoveries+?,owner_token=?,lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))"
                  + " WHERE id=?",
              recovery ? 1 : 0,
              token,
              leaseSeconds,
              id);
          db.update("UPDATE job_step SET status='READY' WHERE job_id=? AND status='RUNNING'", id);
          db.update(
              "INSERT INTO job_attempt(token,job_id,attempt_no,status) VALUES(?,?,?,'RUNNING')",
              token,
              id,
              job.attempts() + 1);
          return token;
        });
  }

  /** 锁定任务并校验 token、状态和租约，阻止过期执行者提交结果。 */
  private void own(String id, String token) {
    var owners =
        db.queryForList(
            "SELECT id FROM analysis_job WHERE id=? AND owner_token=? AND status='RUNNING' AND"
                + " lease_until>=UTC_TIMESTAMP(6) FOR UPDATE",
            String.class,
            id,
            token);
    if (owners.isEmpty()) throw new LostLease();
  }

  /** 只有仍有效的执行者能续期，不能复活已过期的 token。 */
  public void renew(String id, String token) {
    int updated =
        db.update(
            "UPDATE analysis_job SET lease_until=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)) WHERE"
                + " id=? AND owner_token=? AND status='RUNNING' AND lease_until>=UTC_TIMESTAMP(6)",
            leaseSeconds,
            id,
            token);
    if (updated == 0) throw new LostLease();
  }

  /** 先检查任务归属和前置步骤，再从 READY 进入 RUNNING。 */
  public void startStep(String id, String token, String step) {
    tx.executeWithoutResult(
        s -> {
          own(id, token);
          if (step.equals("REPORT")
              && db.queryForObject(
                      "SELECT COUNT(*) FROM job_step WHERE job_id=? AND step_key='CLASSIFY' AND"
                          + " status='SUCCEEDED'",
                      Integer.class,
                      id)
                  != 1) throw new IllegalStateException("Dependency not satisfied");
          if (step.equals("ARTIFACT")
              && db.queryForObject(
                      "SELECT COUNT(*) FROM job_step WHERE job_id=? AND step_key IN"
                          + " ('DISCUSSION','CONTENT') AND status='SUCCEEDED'",
                      Integer.class,
                      id)
                  != 1) throw new IllegalStateException("Dependency not satisfied");
          if (db.update(
                  "UPDATE job_step SET status='RUNNING' WHERE job_id=? AND step_key=? AND"
                      + " status='READY'",
                  id,
                  step)
              != 1) throw new IllegalStateException("Invalid step transition");
        });
  }

  /** 将分类结果与步骤成功状态原子保存，供后续重试复用。 */
  public void saveClassification(String id, String token, String result) {
    tx.executeWithoutResult(
        s -> {
          own(id, token);
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED',result_json=? WHERE job_id=? AND"
                      + " step_key='CLASSIFY' AND status='RUNNING'",
                  result,
                  id)
              != 1) throw new IllegalStateException("Invalid classification transition");
        });
  }

  /** 保存讨论步骤的类型化草稿；重试时复用，避免重复整理同一快照。 */
  public void saveDiscussion(String id, String token, String result) {
    tx.executeWithoutResult(
        status -> {
          own(id, token);
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED',result_json=? WHERE job_id=? AND"
                      + " step_key='DISCUSSION' AND status='RUNNING'",
                  result,
                  id)
              != 1) throw new IllegalStateException("Invalid discussion transition");
        });
  }

  /** 1、校验执行权限；2、保存唯一报告；3、原子提交任务和步骤成功状态。 */
  public void publish(String id, String token, String reportId, String snapshotId, String report) {
    tx.executeWithoutResult(
        s -> {
          own(id, token);
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED' WHERE job_id=? AND step_key='REPORT' AND"
                      + " status='RUNNING'",
                  id)
              != 1) throw new IllegalStateException("Invalid publication transition");
          db.update(
              "INSERT INTO analysis_report(id,job_id,snapshot_id,version,report_json)"
                  + " VALUES(?,?,?,1,?)",
              reportId,
              id,
              snapshotId,
              report);
          db.update(
              "UPDATE analysis_job SET status='SUCCESS',owner_token=NULL,lease_until=NULL WHERE"
                  + " id=?",
              id);
          db.update(
              "UPDATE job_attempt SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(6) WHERE"
                  + " token=?",
              token);
        });
  }

  /**
   * 原子发布讨论产物和请求成功状态。
   *
   * <p>1、校验租约；2、完成 ARTIFACT 步骤；3、保存一份不可变类型化产物；4、同时提交任务、请求和尝试成功，页面不会看到半发布状态。
   */
  public void publishArtifact(
      String id,
      String token,
      String artifactId,
      String requestId,
      String snapshotId,
      String artifact) {
    publishArtifact(
        id, token, artifactId, requestId, snapshotId, "discussion-artifact-v1", artifact);
  }

  /** 新Worker显式传入类型化产物版本，数据库索引与正文一致，旧数据不做回填。 */
  public void publishArtifact(
      String id,
      String token,
      String artifactId,
      String requestId,
      String snapshotId,
      String schemaVersion,
      String artifact) {
    if (!Set.of(
            "discussion-artifact-v1",
            "discussion-artifact-v3",
            "discussion-artifact-v4-context",
            "discussion-artifact-v5-stance",
            "discussion-artifact-v6-stance")
        .contains(schemaVersion))
      throw new IllegalArgumentException("Unsupported artifact schema version");
    tx.executeWithoutResult(
        status -> {
          own(id, token);
          if (db.update(
                  "UPDATE job_step SET status='SUCCEEDED' WHERE job_id=? AND step_key='ARTIFACT'"
                      + " AND status='RUNNING'",
                  id)
              != 1) throw new IllegalStateException("Invalid artifact transition");
          db.update(
              "INSERT INTO"
                  + " analysis_artifact(id,request_id,job_id,snapshot_id,schema_version,artifact_json)"
                  + " VALUES(?,?,?,?,?,?)",
              artifactId,
              requestId,
              id,
              snapshotId,
              schemaVersion,
              artifact);
          db.update(
              "UPDATE analysis_job SET status='SUCCESS',owner_token=NULL,lease_until=NULL WHERE"
                  + " id=?",
              id);
          db.update(
              "UPDATE analysis_request SET status='SUCCESS',last_error_code=NULL WHERE id=? AND"
                  + " job_id=?",
              requestId,
              id);
          db.update(
              "UPDATE job_attempt SET status='SUCCEEDED',finished_at=UTC_TIMESTAMP(6) WHERE"
                  + " token=?",
              token);
        });
  }

  /** 将当前执行记为失败，保留成功步骤和历史执行记录。 */
  public void fail(String id, String token) {
    fail(id, token, "PIPELINE_ERROR");
  }

  /** 保存可解释的模型错误码，仍只允许有效执行者改变任务状态。 */
  public void fail(String id, String token, String code) {
    tx.executeWithoutResult(
        s -> {
          own(id, token);
          db.update("UPDATE job_step SET status='FAILED' WHERE job_id=? AND status='RUNNING'", id);
          db.update(
              "UPDATE analysis_job SET"
                  + " status='FAILED',last_error_code=?,owner_token=NULL,lease_until=NULL"
                  + " WHERE id=?",
              code,
              id);
          db.update(
              "UPDATE analysis_request SET status='FAILED',last_error_code=? WHERE job_id=?",
              code,
              id);
          db.update(
              "UPDATE job_attempt SET"
                  + " status='FAILED',error_code=?,finished_at=UTC_TIMESTAMP(6)"
                  + " WHERE token=?",
              code,
              token);
        });
  }

  /** 仅允许 FAILED 任务重回待执行；已成功步骤不回退。 */
  public Job retry(String id) {
    tx.executeWithoutResult(
        s -> {
          // 先锁原任务，再读取账本；避免与取消/恢复交错或读取RR旧视图。
          var locked =
              db.queryForList(
                  "SELECT id FROM analysis_job WHERE id=? FOR UPDATE", String.class, id);
          if (locked.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
          requireExecutionScope(id);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM model_call WHERE job_id=? AND state IN"
                      + " ('SENT','OUTCOME_UNKNOWN')",
                  Integer.class,
                  id)
              > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "模型调用结果尚未确认，不能直接重试");
          if (db.update(
                  "UPDATE analysis_job SET"
                      + " status='PENDING',recoveries=0,owner_token=NULL,lease_until=NULL WHERE"
                      + " id=? AND status='FAILED'",
                  id)
              != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败任务可以重试");
          db.update("UPDATE job_step SET status='READY' WHERE job_id=? AND status='FAILED'", id);
          db.update(
              "UPDATE analysis_request SET status='PENDING',last_error_code=NULL WHERE job_id=?",
              id);
        });
    return get(id);
  }

  /** 查找任务已经发布的报告；尚未发布时返回空值。 */
  public ReportRow reportForJob(String id) {
    return db
        .query(
            "SELECT id,snapshot_id,report_json FROM analysis_report WHERE job_id=?",
            (r, n) -> new ReportRow(r.getString(1), r.getString(2), r.getString(3)),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /** 读取不可变报告；不存在时返回 404。 */
  public ReportRow report(String id) {
    return db
        .query(
            "SELECT id,snapshot_id,report_json FROM analysis_report WHERE id=?",
            (r, n) -> new ReportRow(r.getString(1), r.getString(2), r.getString(3)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "报告不存在"));
  }

  /** 返回当前租约长度，供独立续租器验证配置。 */
  public int leaseSeconds() {
    return leaseSeconds;
  }

  /** 同键重发不能把其他服务器执行范围的任务带入当前队列；这不是用户鉴权替代。 */
  private void requireExecutionScope(String id) {
    String saved =
        db.queryForObject("SELECT execution_scope FROM analysis_job WHERE id=?", String.class, id);
    if (!executionScope.equals(saved))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已绑定其他执行范围");
  }

  /** 在短事务中验证 token 并执行数据库操作；调用者不得在这里等待网络。 */
  public <T> T withLease(String id, String token, java.util.function.Supplier<T> operation) {
    return tx.execute(
        status -> {
          own(id, token);
          return operation.get();
        });
  }

  /** 读取任务创建时保存的计划；旧任务按历史免费 Mock 协议读取。 */
  public io.echoinsight.model.ModelContract.Plan plan(String id) {
    return io.echoinsight.model.ModelContract.plan(
        db.queryForObject(
            "SELECT execution_plan_json FROM analysis_job WHERE id=?", String.class, id));
  }

  /** 执行者已失去写入权限的控制信号。 */
  public static class LostLease extends RuntimeException {}
}
