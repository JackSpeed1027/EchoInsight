/* 文件职责：独立内容问题与冻结输入同事务绑定原任务，状态只来自analysis_job，不维护第二状态机。 */
package io.echoinsight.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.ConfiguredModelPlan;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** jobId同时是本内容问题的持久身份；输入绑定没有status、租约或费用字段，全部复用原任务。 */
@Service
public class ContentAnalysisStore {
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ContentMaterialStore materials;
  private final JobStore jobs;
  private final ConfiguredModelPlan plans;
  private final ObjectMapper json;
  private final String scope;

  /** 服务器执行范围来自配置，不能从HTTP接收或绕过项目资格。 */
  public ContentAnalysisStore(
      JdbcTemplate db,
      ProjectStore projects,
      ContentMaterialStore materials,
      JobStore jobs,
      ConfiguredModelPlan plans,
      ObjectMapper json,
      @Value("${echoinsight.worker.execution-scope:local}") String scope) {
    this.db = db;
    this.projects = projects;
    this.materials = materials;
    this.jobs = jobs;
    this.plans = plans;
    this.json = json;
    this.scope = scope;
  }

  /** 材料元数据与正文范围一起冻结；旧任务不读取新版本、标题或来源说明。 */
  public record Frozen(ContentMaterialStore.Summary material, ContentSelection selection) {}

  /** 输入资格与收费预检，未截取可完整纳入，否则先明确同意有限输入。 */
  public record Preview(
      Frozen input,
      String sourceMode,
      long reservationUnits,
      long budgetUnits,
      String budgetUnit,
      boolean budgetSufficient,
      boolean requiresPartialConsent,
      List<String> notes) {}

  /** 已保存的问题和不可变输入，实际状态另读原任务。 */
  public record Input(
      String jobId,
      String projectId,
      String materialId,
      String instruction,
      Frozen frozen,
      String createdAt) {}

  /** 已接受与网络重发区分返回，不创建重复任务。 */
  public record Created(View analysis, boolean created) {}

  /** 成功后才含artifact；等待/失败/取消仍保留输入与错误，用户可以在任务中心继续。 */
  public record View(
      String jobId,
      String projectId,
      String instruction,
      Frozen input,
      String status,
      String errorCode,
      String createdAt,
      JsonNode artifact) {}

  /** 历史列表只含摘要，不下载所有原文与报告。 */
  public record Summary(
      String jobId,
      String materialId,
      String title,
      String instruction,
      String status,
      String errorCode,
      String createdAt) {}

  /** 只读核对材料和可用范围，不初始化账本或创建任务。 */
  @Transactional(readOnly = true)
  public Preview preview(String p, String materialId, String instruction, Long from, Long to) {
    validateInstruction(instruction);
    var material = materials.get(p, materialId);
    var selected = ContentSelection.select(material, from, to);
    var plan = plans.content();
    long reservation =
        "MOCK".equals(plan.reportMode())
            ? 0
            : Math.addExact(
                Math.multiplyExact((long) plan.inputTokenCeiling(), plan.inputUnitRate()),
                Math.multiplyExact((long) plan.maxOutputTokens(), plan.outputUnitRate()));
    return new Preview(
        new Frozen(material.summary(), selected),
        plan.reportMode(),
        reservation,
        plan.budgetLimit(),
        plan.unit(),
        reservation <= plan.budgetLimit(),
        selected.truncated(),
        List.of(
            "仅分析本次选入的文本/字幕原文，没有评论、画面或音频，不判断观众立场或认证作者意图。",
            "最多40完整段、12000个UTF16字符，超出按原序取前缀；时间窗口保留相交字幕整段。",
            "结构演示只展示输入与定位，不生成语义结论；真实模型调用另受原任务预算约束。"));
  }

  /** 1、项目锁先于首次一致性读；2、重发比较原始输入；3、重新预检/冻结当前明确版本； 4、原任务与输入绑定同事务接受，后台只看到完整提交，未进行任何网络调用。 */
  @Transactional
  public Created create(
      String p,
      String key,
      String materialId,
      String instruction,
      Long from,
      Long to,
      boolean allowPartial) {
    ContentMaterialStore.validateKey(key);
    validateInstruction(instruction);
    projects.lock(p);
    String question = instruction.strip();
    String requestHash =
        ReferenceMaterialStore.hash(
            encode(Arrays.asList(materialId, question, from, to, allowPartial)));
    var old =
        db.queryForList(
            "SELECT job_id,request_sha256 FROM content_analysis_input WHERE project_id=? AND"
                + " request_key=?",
            p,
            key);
    if (!old.isEmpty()) {
      if (!requestHash.equals(old.getFirst().get("request_sha256")))
        throw conflict("该请求键已用于其他内容版本、问题或范围");
      return new Created(get(p, (String) old.getFirst().get("job_id")), false);
    }
    var prepared = preview(p, materialId, question, from, to);
    if (prepared.input().selection().segments().isEmpty()) throw invalid("所选范围没有正文，请调整材料或时间范围");
    if (prepared.requiresPartialConsent() && !allowPartial)
      throw invalid("材料超出本次输入上限，请缩小时间范围或明确同意有限输入");
    if (!prepared.budgetSufficient()) throw invalid("原任务预算不足以覆盖一次保守调用上界");
    var job =
        jobs.createContent(
            "content:" + ReferenceMaterialStore.hash(p + "\n" + key), plans.content());
    String frozen = encode(prepared.input());
    db.update(
        "INSERT INTO"
            + " content_analysis_input(job_id,project_id,material_id,request_key,request_sha256,instruction,selection_json,selection_sha256)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        job.id(),
        p,
        materialId,
        key,
        requestHash,
        question,
        frozen,
        ReferenceMaterialStore.hash(frozen));
    return new Created(get(p, job.id()), true);
  }

  /** 同一只读事务返回原输入和当前任务，成功才读发布产物，不解析评论协议。 */
  @Transactional(readOnly = true)
  public View get(String p, String id) {
    projects.get(p);
    var input = read(p, id);
    var job = jobs.get(id);
    JsonNode artifact = null;
    if ("SUCCESS".equals(job.status())) {
      var rows =
          db.queryForList(
              "SELECT artifact_json FROM analysis_artifact WHERE job_id=? AND"
                  + " schema_version='content-artifact-v1'",
              String.class,
              id);
      if (rows.size() != 1) throw new IllegalStateException("Missing content artifact");
      try {
        artifact = json.readTree(rows.getFirst());
      } catch (java.io.IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return new View(
        id,
        p,
        input.instruction(),
        input.frozen(),
        job.status(),
        job.lastErrorCode(),
        input.createdAt(),
        artifact);
  }

  /** 工作者只读取自己已领取任务的冻结输入，绝不查项目最新材料来替代。 */
  public Input byJob(String id) {
    return read(null, id);
  }

  /** 稳定分页问题历史，只展示同项目及同服务器执行范围。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String p, int page, int size) {
    projects.get(p);
    ContentMaterialStore.validatePage(page, size);
    String where =
        " FROM content_analysis_input c JOIN analysis_job j ON j.id=c.job_id WHERE c.project_id=?"
            + " AND j.execution_scope=?";
    long total = db.queryForObject("SELECT COUNT(*)" + where, Long.class, p, scope);
    var rows =
        db.query(
            "SELECT"
                + " c.job_id,c.material_id,JSON_UNQUOTE(JSON_EXTRACT(c.selection_json,'$.material.title'))"
                + " title,c.instruction,j.status,j.last_error_code,DATE_FORMAT(c.created_at,'%Y-%m-%d"
                + " %H:%i:%s') created_time"
                + where
                + " ORDER BY c.created_at DESC,c.job_id DESC LIMIT ? OFFSET ?",
            (r, n) ->
                new Summary(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getString(5),
                    r.getString(6),
                    r.getString(7)),
            p,
            scope,
            size,
            (page - 1) * size);
    return new ProjectStore.Page<>(List.copyOf(rows), total, page, size, (total + size - 1) / size);
  }

  /** 输入哈希和范围资格同时核对；不接受其他项目、执行范围或错误任务类型。 */
  private Input read(String projectId, String id) {
    var args = new ArrayList<Object>(List.of(id, scope));
    String restriction = "";
    if (projectId != null) {
      restriction = " AND c.project_id=?";
      args.add(projectId);
    }
    var rows =
        db.query(
            "SELECT c.*,DATE_FORMAT(c.created_at,'%Y-%m-%d %H:%i:%s') created_time FROM"
                + " content_analysis_input c JOIN analysis_job j ON j.id=c.job_id WHERE j.id=? AND"
                + " j.execution_scope=? AND j.job_kind='CONTENT' AND j.snapshot_id IS NULL"
                + restriction,
            (r, n) -> {
              String body = r.getString("selection_json");
              if (!ReferenceMaterialStore.hash(body).equals(r.getString("selection_sha256")))
                throw new IllegalStateException("Content frozen input hash mismatch");
              try {
                return new Input(
                    id,
                    r.getString("project_id"),
                    r.getString("material_id"),
                    r.getString("instruction"),
                    json.readValue(body, Frozen.class),
                    r.getString("created_time"));
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
            },
            args.toArray());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容分析不存在");
    return rows.getFirst();
  }

  /** 问题是数据，最多1000字；不以关键词悄悄跳到评论/采集Agent流程。 */
  private void validateInstruction(String value) {
    if (value == null || value.isBlank() || value.strip().length() > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空且最多1000字");
  }

  /** 固定对象序列化，作为本次输入身份。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 幂等冲突不覆盖旧任务。 */
  private ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 无正文、费用不足或未同意裁剪均拒绝派工，不静默降级。 */
  private ResponseStatusException invalid(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }
}
