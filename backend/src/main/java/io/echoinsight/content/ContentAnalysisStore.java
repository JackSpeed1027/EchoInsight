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
  public record Frozen(
      ContentMaterialStore.Summary material,
      ContentSelection selection,
      ContentChunkPlan fullPlan,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ContentReuse.Plan reusePlan) {
    /** 兼容v2冻结记录和旧调用，不为旧任务补写复用清单。 */
    public Frozen(
        ContentMaterialStore.Summary material,
        ContentSelection selection,
        ContentChunkPlan fullPlan) {
      this(material, selection, fullPlan, null);
    }

    /** 历史前缀输入保留原含义，缺失fullPlan不推断为完整方式。 */
    public Frozen(ContentMaterialStore.Summary material, ContentSelection selection) {
      this(material, selection, null, null);
    }
  }

  /** 输入资格与收费预检，未截取可完整纳入，否则先明确同意有限输入。 */
  public record Preview(
      Frozen input,
      String sourceMode,
      long reservationUnits,
      long budgetUnits,
      String budgetUnit,
      boolean budgetSufficient,
      boolean requiresPartialConsent,
      List<String> notes,
      int plannedCalls,
      long perCallReservationUnits) {}

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
      JsonNode artifact,
      Progress progress) {}

  /** 完成数读取原model_batch，不创建第二进度表；免费结构演示的语义完成数始终为0。 */
  public record Progress(
      int plannedChunks,
      int completedChunks,
      String synthesisStatus,
      int plannedCalls,
      int successfulCalls,
      String phase,
      int reusedChunks) {}

  /** 历史列表只含摘要，不下载所有原文与报告。 */
  public record Summary(
      String jobId,
      String materialId,
      String title,
      String instruction,
      String status,
      String errorCode,
      String createdAt,
      String coverageMode,
      boolean reuseCapable) {}

  /** 兼容旧调用，未指定方式时仍为前缀，不改旧幂等输入或提示版本。 */
  @Transactional(readOnly = true)
  public Preview preview(String p, String materialId, String instruction, Long from, Long to) {
    return preview(p, materialId, instruction, from, to, null);
  }

  /** 1、核对明确版本/范围；2、冻结原文分片；3、预检全部调用上界；4、不派工或初始化账本。 */
  @Transactional(readOnly = true)
  public Preview preview(
      String p, String materialId, String instruction, Long from, Long to, String coverageMode) {
    return preview(p, materialId, instruction, from, to, coverageMode, null);
  }

  /** 复用只来自用户明确选择的同项目成功基准，资格和费用在创建时仍会重新核验。 */
  @Transactional(readOnly = true)
  public Preview preview(
      String p,
      String materialId,
      String instruction,
      Long from,
      Long to,
      String coverageMode,
      String baselineJobId) {
    baselineJobId = baselineId(baselineJobId);
    validateInstruction(instruction);
    boolean full = fullMode(coverageMode);
    var material = materials.get(p, materialId);
    var selected =
        full
            ? ContentSelection.full(material, from, to)
            : ContentSelection.select(material, from, to);
    var chunkPlan = full ? ContentChunkPlan.create(selected) : null;
    var plan = full ? plans.contentFull() : plans.content();
    if (baselineJobId != null && !full) throw invalid("仅完整内容分析支持选择复用基准");
    var frozen = new Frozen(material.summary(), selected, chunkPlan);
    var reuse =
        full && ContentReuse.enabled(plan)
            ? prepareReuse(p, frozen, instruction.strip(), plan, baselineJobId)
            : null;
    if (baselineJobId != null && reuse == null) throw invalid("当前协议不支持复用，请不选基准重新分析");
    int calls = full ? chunkPlan.chunks().size() - (reuse == null ? 0 : reuse.reused()) + 1 : 1;
    long perCall =
        "MOCK".equals(plan.reportMode())
            ? 0
            : Math.addExact(
                Math.multiplyExact((long) plan.inputTokenCeiling(), plan.inputUnitRate()),
                Math.multiplyExact((long) plan.maxOutputTokens(), plan.outputUnitRate()));
    if (perCall > Long.MAX_VALUE / calls) throw invalid("全部调用费用上界溢出，不能接受任务");
    long reservation = perCall * calls;
    return new Preview(
        new Frozen(material.summary(), selected, chunkPlan, reuse),
        plan.reportMode(),
        reservation,
        plan.budgetLimit(),
        plan.unit(),
        reservation <= plan.budgetLimit(),
        selected.truncated(),
        List.of(
            "仅处理所选文本/字幕，没有评论、画面或音频，来源说明不是创作者身份认证。",
            full
                ? "完整方式逐片纳入所选范围，最多24片，再汇总全部成功摘要；压缩可能损失细节，输入覆盖不等于语义准确。"
                : "有限方式最多40完整段/12000字符，按原顺序取前缀，遗漏必须明确同意。",
            "费用为全部计划调用的保守上界，不是实际报价扣款；失败/未知后不会自动再次付费。"),
        calls,
        perCall);
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
    return create(p, key, materialId, instruction, from, to, allowPartial, null);
  }

  /** 完整与有限方式是不同输入；旧方式的幂等指纹保持不变，新方式在接受前核对全量预算。 */
  @Transactional
  public Created create(
      String p,
      String key,
      String materialId,
      String instruction,
      Long from,
      Long to,
      boolean allowPartial,
      String coverageMode) {
    return create(p, key, materialId, instruction, from, to, allowPartial, coverageMode, null);
  }

  /** 基准身份参与幂等；接受时复制合格响应及出处，不依赖执行时再次查询项目“最新”报告。 */
  @Transactional
  public Created create(
      String p,
      String key,
      String materialId,
      String instruction,
      Long from,
      Long to,
      boolean allowPartial,
      String coverageMode,
      String baselineJobId) {
    baselineJobId = baselineId(baselineJobId);
    boolean full = fullMode(coverageMode);
    if (baselineJobId != null && !full) throw invalid("仅完整内容分析支持选择复用基准");
    ContentMaterialStore.validateKey(key);
    validateInstruction(instruction);
    projects.lock(p);
    String question = instruction.strip();
    String requestHash =
        ReferenceMaterialStore.hash(
            encode(
                baselineJobId != null
                    ? Arrays.asList(
                        materialId, question, from, to, allowPartial, "FULL", baselineJobId)
                    : full
                        ? Arrays.asList(materialId, question, from, to, allowPartial, "FULL")
                        : Arrays.asList(materialId, question, from, to, allowPartial)));
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
    var prepared = preview(p, materialId, question, from, to, coverageMode, baselineJobId);
    if (prepared.input().selection().segments().isEmpty()) throw invalid("所选范围没有正文，请调整材料或时间范围");
    if (prepared.requiresPartialConsent() && !allowPartial)
      throw invalid("材料超出本次输入上限，请缩小时间范围或明确同意有限输入");
    if (!prepared.budgetSufficient())
      throw invalid(full ? "本任务预算不足以覆盖全部计划调用的保守上界，请缩小范围" : "原任务预算不足以覆盖一次保守调用上界");
    var job =
        jobs.createContent(
            "content:" + ReferenceMaterialStore.hash(p + "\n" + key),
            full ? plans.contentFull() : plans.content());
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
                  + " schema_version IN ('content-artifact-v1','content-artifact-v2')",
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
        artifact,
        progress(id, input.frozen()));
  }

  /** 只读取最多25个原批次状态，取消/失败时同样保留已成功片数。 */
  private Progress progress(String id, Frozen frozen) {
    if (frozen.fullPlan() == null) return null;
    int reused = frozen.reusePlan() == null ? 0 : frozen.reusePlan().reused();
    int count = frozen.fullPlan().chunks().size(), maps = reused, successful = 0;
    String synthesis = "NOT_STARTED";
    for (var row :
        db.queryForList(
            "SELECT batch_index,status FROM model_batch WHERE job_id=? ORDER BY batch_index", id)) {
      int index = ((Number) row.get("batch_index")).intValue();
      String state = (String) row.get("status");
      if (index < 0 || index > count)
        throw new IllegalStateException("Invalid content batch identity");
      if (index < count
          && frozen.reusePlan() != null
          && frozen.reusePlan().entries().get(index).cached() != null)
        throw new IllegalStateException("Reused content has unexpected new call");
      if ("SUCCEEDED".equals(state)) {
        successful++;
        if (index < count) maps++;
      }
      if (index == count) synthesis = state;
    }
    boolean demo = "MOCK".equals(jobs.plan(id).reportMode());
    return new Progress(
        count,
        maps,
        demo ? "NOT_EXECUTED" : synthesis,
        count - reused + 1,
        successful,
        demo
            ? "STRUCTURAL_DEMO"
            : "SUCCEEDED".equals(synthesis) ? "COMPLETE" : maps == count ? "REDUCE" : "MAP",
        reused);
  }

  /** 工作者只读取自己已领取任务的冻结输入，绝不查项目最新材料来替代。 */
  public Input byJob(String id) {
    return read(null, id);
  }

  /** 稳定分页问题历史，只展示同项目及同服务器执行范围。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String p, int page, int size) {
    return list(p, page, size, null);
  }

  /** 基准候选沿用历史分页；先验证目标材料资格，再限定当前/直接父版本的成功v3语义任务。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(
      String p, int page, int size, String baselineForMaterialId) {
    projects.get(p);
    ContentMaterialStore.validatePage(page, size);
    var args = new ArrayList<Object>(List.of(p, scope));
    String where =
        " FROM content_analysis_input c JOIN analysis_job j ON j.id=c.job_id WHERE c.project_id=?"
            + " AND j.execution_scope=?";
    if (baselineForMaterialId != null) {
      var target = materials.get(p, baselineForMaterialId).summary();
      where +=
          " AND (c.material_id=? OR c.material_id=?) AND j.prompt_version=? AND j.status='SUCCESS'"
              + " AND JSON_UNQUOTE(JSON_EXTRACT(j.execution_plan_json,'$.executionMode')) IN"
              + " ('MODEL','LOCAL_STUB')";
      args.add(target.id());
      args.add(target.previousId());
      args.add(ContentReuse.PROMPT);
    }
    long total = db.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var rows =
        db.query(
            "SELECT"
                + " c.job_id,c.material_id,JSON_UNQUOTE(JSON_EXTRACT(c.selection_json,'$.material.title'))"
                + " title,c.instruction,j.status,j.last_error_code,DATE_FORMAT(c.created_at,'%Y-%m-%d"
                + " %H:%i:%s')"
                + " created_time,j.prompt_version,JSON_UNQUOTE(JSON_EXTRACT(j.execution_plan_json,'$.executionMode'))"
                + " mode"
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
                    r.getString(7),
                    Set.of(ContentFullContract.PROMPT, ContentReuse.PROMPT).contains(r.getString(8))
                        ? "FULL"
                        : "PREFIX",
                    ContentReuse.PROMPT.equals(r.getString(8))
                        && "SUCCESS".equals(r.getString(5))
                        && Set.of("MODEL", "LOCAL_STUB")
                            .contains(Objects.toString(r.getString(9), ""))),
            args.toArray());
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
                var frozen = json.readValue(body, Frozen.class);
                if (frozen.fullPlan() != null) frozen.fullPlan().verify(frozen.selection());
                return new Input(
                    id,
                    r.getString("project_id"),
                    r.getString("material_id"),
                    r.getString("instruction"),
                    frozen,
                    r.getString("created_time"));
              } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
              }
            },
            args.toArray());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "内容分析不存在");
    var result = rows.getFirst();
    ContentReuse.verify(result.frozen(), result.instruction(), jobs.plan(id));
    return result;
  }

  /** 1、验证明确基准资格；2、比局部模型输入指纹；3、只复制原成功响应；4、冻结出处，不初始化新账本。 */
  private ContentReuse.Plan prepareReuse(
      String p,
      Frozen current,
      String question,
      io.echoinsight.model.ModelContract.Plan plan,
      String baselineId) {
    Input source = null;
    if (baselineId != null) {
      source = read(p, baselineId);
      var sourceJob = jobs.get(baselineId);
      var sourcePlan = jobs.plan(baselineId);
      if (!"SUCCESS".equals(sourceJob.status())) throw invalid("复用基准必须是已完成的内容分析");
      if (!ContentReuse.enabled(sourcePlan) || source.frozen().reusePlan() == null)
        throw invalid("旧协议报告不能作为复用基准，请先完成一次新版完整分析");
      if ("MOCK".equals(sourcePlan.reportMode())) throw invalid("免费演示没有语义分片结果，不能作为复用基准");
      if (!source.instruction().equals(question)) throw invalid("复用基准的问题不同，请保持原问题或不选基准");
      if (!Objects.equals(source.materialId(), current.material().id())
          && !Objects.equals(source.materialId(), current.material().previousId()))
        throw invalid("复用基准只能来自当前材料或它的直接前一版本");
      if (!encode(sourcePlan).equals(encode(plan))) throw invalid("模型协议或费用计划已变化，请不选基准重新分析");
      if (db.queryForObject(
              "SELECT COUNT(*) FROM model_call WHERE job_id=? AND state IN"
                  + " ('RESERVED','SENT','OUTCOME_UNKNOWN')",
              Long.class,
              baselineId)
          != 0) throw invalid("复用基准存在未决调用，不能复用");
    }
    Map<String, ContentReuse.Entry> previous = new HashMap<>();
    if (source != null)
      for (var entry : source.frozen().reusePlan().entries())
        previous.putIfAbsent(entry.inputHash(), entry);
    List<ContentReuse.Entry> entries = new ArrayList<>();
    for (var chunk : current.fullPlan().chunks()) {
      var request = ContentReuse.request(current, question, plan, chunk);
      String hash = ContentReuse.fingerprint(plan, request);
      var match = previous.get(hash);
      ContentReuse.Cached cached = null;
      if (match != null) {
        if (match.cached() != null) cached = match.cached();
        else {
          var oldChunk = source.frozen().fullPlan().chunks().get(match.index());
          var oldRequest =
              ContentReuse.request(source.frozen(), source.instruction(), plan, oldChunk);
          var rows =
              db.queryForList(
                  "SELECT input_hash,result_json FROM model_batch WHERE job_id=? AND batch_index=?"
                      + " AND status='SUCCEEDED'",
                  baselineId,
                  match.index());
          String expected =
              io.echoinsight.material.ReferenceMaterialStore.hash(
                  encode(plan) + encode(oldRequest));
          if (rows.size() != 1 || !expected.equals(rows.getFirst().get("input_hash")))
            throw invalid("基准成功分片检查点不可用，请不选基准重新分析");
          try {
            var response =
                json.readValue(
                    (String) rows.getFirst().get("result_json"),
                    ContentModelContract.Response.class);
            ContentFullContract.validate(oldRequest, response);
            cached =
                new ContentReuse.Cached(
                    baselineId,
                    match.index(),
                    io.echoinsight.material.ReferenceMaterialStore.hash(encode(response)),
                    response);
          } catch (Exception error) {
            throw invalid("基准成功分片检查点不可用，请不选基准重新分析");
          }
        }
        ContentFullContract.validate(request, cached.response());
      }
      entries.add(new ContentReuse.Entry(chunk.index(), hash, cached));
    }
    return new ContentReuse.Plan(ContentReuse.VERSION, baselineId, List.copyOf(entries));
  }

  /** 基准由用户明确选择，空值表示全部重新执行；不自动猜测最近报告。 */
  private String baselineId(String id) {
    if (id == null || id.isBlank()) return null;
    String value = id.strip();
    if (!value.matches("[A-Za-z0-9_-]{1,64}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "复用基准任务身份无效");
    return value;
  }

  /** 仅支持两个业务处理范围，未知值不静默退回前缀。 */
  private boolean fullMode(String mode) {
    if (mode == null || "PREFIX".equals(mode)) return false;
    if ("FULL".equals(mode)) return true;
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理方式仅支持FULL或PREFIX");
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
