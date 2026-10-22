/* 文件职责：保存用户原问题，串联原采集、明确资料绑定及原分析请求；查询不自动出站或派工。 */
package io.echoinsight.intake;

import static io.echoinsight.intake.AnalysisIntakeProtocol.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.collection.*;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.intelligence.*;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.*;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 入口只保存业务要求和一次关联；阶段从原采集/请求推导，没有第二份status、租约或费用账本。 */
@Service
public class AnalysisIntakeStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final ProjectStore projects;
  private final AnalysisIntakeProtocol protocol;
  private final CollectionStore collections;
  private final BilibiliCollectionSettings settings;
  private final AnalysisRequestStore requests;
  private final AnalysisPreflight preflight;
  private final AnalysisScopeStore scopes;
  private final ImportMapper imports;
  private final ConfiguredModelPlan plans;
  private final boolean collectionWorker;

  /** 复用既有资格、行锁、采集及分析存储，构造器不启动任何后台线程或网络调用。 */
  public AnalysisIntakeStore(
      JdbcTemplate db,
      ObjectMapper json,
      ProjectStore projects,
      AnalysisIntakeProtocol protocol,
      CollectionStore collections,
      BilibiliCollectionSettings settings,
      AnalysisRequestStore requests,
      AnalysisPreflight preflight,
      AnalysisScopeStore scopes,
      ImportMapper imports,
      ConfiguredModelPlan plans,
      Environment environment) {
    this.db = db;
    this.json = json;
    this.projects = projects;
    this.protocol = protocol;
    this.collections = collections;
    this.settings = settings;
    this.requests = requests;
    this.preflight = preflight;
    this.scopes = scopes;
    this.imports = imports;
    this.plans = plans;
    collectionWorker =
        environment.getProperty(
            "echoinsight.collection.bilibili.worker-enabled", Boolean.class, true);
  }

  /** 只读业务规划；命题缺失通过goal返回业务澄清，不建立任务或猜测标准。 */
  public record Planning(
      Input input,
      ProjectStore.Page<ProjectStore.Snapshot> candidates,
      boolean collectionEnabled,
      boolean collectionWorkerRunning,
      List<String> notes) {}

  /** 历史列表不包含报告或模型响应，只提供原话及入口身份。 */
  public record Summary(String id, String instruction, String canonicalUrl, String createdAt) {}

  /** 阶段完全从链接与原子任务事实推导，collection/analysis始终是原系统身份。 */
  public record View(
      String id,
      Input input,
      String phase,
      ProjectStore.Snapshot source,
      CollectionStore.Batch collection,
      AnalysisRequestStore.RequestRow analysis,
      boolean collectionEnabled,
      boolean collectionWorkerRunning,
      String createdAt,
      List<String> notes) {}

  /** 创建重发与首次接受使用不同HTTP状态，但只有一条入口记录。 */
  public record Created(View value, boolean created) {}

  /** 精确费用用字符串保留整数单位；是本地保守预留上界，不是供应商账单。 */
  public record Cost(
      String mode, String unit, String maximumUnits, String budgetLimitUnits, int plannedCalls) {}

  /** 指纹绑定完整计划、冻结问题与资料及当前预检，用户确认不能跨配置/范围沿用。 */
  public record Quote(
      String sha256,
      String snapshotId,
      AnalysisPreflight.View preview,
      Cost cost,
      boolean available) {}

  /** 哈希内包含项目身份，不能把另一项目的原话JSON直接搬来伪造入口。 */
  private record SavedInput(String projectId, Input input) {}

  /** 仅规划来源候选，不跟随短链、不采集、不选“最新”替用户决定版本。 */
  @Transactional(readOnly = true)
  public Planning preview(String p, Draft draft) {
    projects.get(p);
    var input = protocol.normalize(draft);
    return new Planning(
        input, candidates(p, input, 1, 10), settings.enabled(), collectionWorker, notes());
  }

  /** 1、纯校验；2、先锁项目再查幂等；3、冻结原话；4、可选绑定明确快照，不启动任何子任务。 */
  @Transactional
  public Created create(String p, String key, Draft draft) {
    key(key);
    var input = protocol.normalize(draft);
    if (input.goal().needsProposition())
      throw reject(HttpStatus.UNPROCESSABLE_ENTITY, "请先补充需要判断支持或反对的明确命题");
    String encoded = encode(new SavedInput(p, input)), sha = hash(encoded);
    projects.lock(p);
    var existing =
        db.queryForList(
            "SELECT id,input_sha256 FROM analysis_intake WHERE project_id=? AND execution_scope=?"
                + " AND request_key=? FOR UPDATE",
            p,
            scopes.executionScope(),
            key);
    if (!existing.isEmpty()) {
      if (!sha.equals(existing.getFirst().get("input_sha256")))
        throw reject(HttpStatus.CONFLICT, "入口请求键已用于其他资料或问题，请恢复原要求或另建一次");
      return new Created(get(p, (String) existing.getFirst().get("id")), false);
    }
    String snapshot = input.requestedSnapshotId(), snapshotHash = null;
    if (snapshot != null) {
      requireCandidate(p, input, snapshot);
      snapshotHash = snapshotHash(p, snapshot);
    }
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " analysis_intake(id,project_id,execution_scope,request_key,input_json,input_sha256,snapshot_id,snapshot_sha256)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        p,
        scopes.executionScope(),
        key,
        encoded,
        sha,
        snapshot,
        snapshotHash);
    return new Created(get(p, id), true);
  }

  /** 同一范围只读分页，不把轮询变成采集/分析的触发器。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<Summary> list(String p, int page, int size) {
    projects.get(p);
    page(page, size);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM analysis_intake WHERE project_id=? AND execution_scope=?",
            Long.class,
            p,
            scopes.executionScope());
    var rows =
        db.query(
            "SELECT id FROM analysis_intake WHERE project_id=? AND execution_scope=? ORDER BY"
                + " created_at DESC,id DESC LIMIT ? OFFSET ?",
            (r, n) -> r.getString(1),
            p,
            scopes.executionScope(),
            size,
            (page - 1) * size);
    var items =
        rows.stream()
            .map(
                id -> {
                  var row = row(p, id, false);
                  var input = input(p, row);
                  return new Summary(
                      id,
                      input.instruction(),
                      input.canonicalUrl(),
                      (String) row.get("created_time"));
                })
            .toList();
    return new ProjectStore.Page<>(items, total, page, size, (total + size - 1) / size);
  }

  /** 页面刷新/重启后按持久身份恢复；只读取子任务当前状态，不下载报告或改任务。 */
  @Transactional(readOnly = true)
  public View get(String p, String id) {
    projects.get(p);
    var row = row(p, id, false);
    var input = input(p, row);
    var batch =
        row.get("collection_id") == null
            ? null
            : collections.get(p, (String) row.get("collection_id"));
    var source =
        row.get("snapshot_id") == null
            ? null
            : projects.snapshot(p, (String) row.get("snapshot_id"));
    var request =
        row.get("analysis_request_id") == null
            ? null
            : requests.get(p, (String) row.get("analysis_request_id"));
    String phase =
        request != null
            ? "ANALYSIS_ACCEPTED"
            : source != null
                ? "READY_FOR_ANALYSIS"
                : batch == null
                    ? "NEEDS_SOURCE"
                    : switch (batch.status()) {
                      case "SUCCESS" -> "READY_TO_BIND";
                      case "FAILED" -> "COLLECTION_FAILED";
                      case "CANCELLED" -> "COLLECTION_CANCELLED";
                      default -> "COLLECTING";
                    };
    return new View(
        id,
        input,
        phase,
        source,
        batch,
        request,
        settings.enabled(),
        collectionWorker,
        (String) row.get("created_time"),
        notes());
  }

  /** 只返回同项目、有来源身份依据的版本候选；BV/av不互猜，缺映射时由用户明确选资料。 */
  @Transactional(readOnly = true)
  public ProjectStore.Page<ProjectStore.Snapshot> candidates(
      String p, String id, int page, int size) {
    projects.get(p);
    return candidates(p, input(p, row(p, id, false)), page, size);
  }

  /** 1、项目锁；2、重发先返回旧关联；3、明确采集范围；4、创建原批次并一次绑定，无新调度器。 */
  @Transactional
  public View collect(String p, String id, Collect body) {
    var normalized = protocol.collect(body);
    String fingerprint = hash(encode(normalized));
    projects.lock(p);
    var row = row(p, id, true);
    var input = input(p, row);
    if (row.get("collection_id") != null) {
      if (!fingerprint.equals(row.get("collection_fingerprint")))
        throw reject(HttpStatus.CONFLICT, "本次入口已绑定其他采集范围，请查看原任务或另建入口");
      return get(p, id);
    }
    if (row.get("snapshot_id") != null || row.get("analysis_request_id") != null)
      throw reject(HttpStatus.CONFLICT, "本次已固定资料，重新采集请沿用问题另建入口");
    if (input.canonicalUrl() == null) throw bad("已保存资料入口不需要采集，请直接核对分析范围");
    var target = new BilibiliUrlParser.Target(input.sourceId(), input.canonicalUrl());
    var batch =
        collections.createWithMode(
            p,
            "intake-collection:" + id,
            target,
            normalized.rootLimit() == null ? 50 : normalized.rootLimit(),
            normalized.collectAll());
    db.update(
        "UPDATE analysis_intake SET collection_id=?,collection_fingerprint=? WHERE id=?",
        batch.id(),
        fingerprint,
        id);
    return get(p, id);
  }

  /** 只绑定一次明确版本；采集链只能使用该批次成功快照，不能在客户端偷换为别处资料。 */
  @Transactional
  public View bind(String p, String id, String requestedSnapshot) {
    projects.lock(p);
    var row = row(p, id, true);
    var input = input(p, row);
    if (row.get("snapshot_id") != null) {
      if (requestedSnapshot != null && !requestedSnapshot.equals(row.get("snapshot_id")))
        throw reject(HttpStatus.CONFLICT, "本次入口资料已固定，换版本请沿用问题另建入口");
      return get(p, id);
    }
    String snapshot = requestedSnapshot;
    if (row.get("collection_id") != null) {
      var batch = collections.get(p, (String) row.get("collection_id"));
      if (!"SUCCESS".equals(batch.status()) || batch.snapshotId() == null)
        throw reject(HttpStatus.CONFLICT, "采集尚未生成可用资料，请查看原采集任务");
      if (snapshot != null && !snapshot.equals(batch.snapshotId()))
        throw reject(HttpStatus.CONFLICT, "采集入口只能绑定这次采集实际生成的资料");
      snapshot = batch.snapshotId();
    } else requireCandidate(p, input, snapshot);
    String sha = snapshotHash(p, snapshot);
    db.update(
        "UPDATE analysis_intake SET snapshot_id=?,snapshot_sha256=? WHERE id=?", snapshot, sha, id);
    return get(p, id);
  }

  /** 范围与费用仍由原预检计算；新链接尚未采到资料时不会估造评论数。 */
  @Transactional(readOnly = true)
  public Quote quote(String p, String id) {
    projects.get(p);
    return quote(p, row(p, id, false));
  }

  /** 1、锁原项目；2、已接受原样重发先返回；3、重算预检指纹；4、原请求/任务与入口关联同事务提交。 */
  @Transactional
  public AnalysisRequestStore.Created accept(String p, String id, Accept body) {
    if (body == null || body.quoteSha256() == null || !body.quoteSha256().matches("[a-f0-9]{64}"))
      throw bad("请先核对当前资料、处理范围和费用");
    String fingerprint = hash(encode(body));
    projects.lock(p);
    var row = row(p, id, true);
    var input = input(p, row);
    if (row.get("analysis_request_id") != null) {
      if (!fingerprint.equals(row.get("analysis_fingerprint")))
        throw reject(HttpStatus.CONFLICT, "本次入口已接受另一份分析确认，请查看原分析结果");
      return new AnalysisRequestStore.Created(
          requests.get(p, (String) row.get("analysis_request_id")), false);
    }
    var quote = quote(p, row);
    if (!quote.sha256().equals(body.quoteSha256()))
      throw reject(HttpStatus.CONFLICT, "资料或分析计划已变化，请重新核对范围和费用后确认");
    if (!quote.available())
      throw reject(HttpStatus.UNPROCESSABLE_ENTITY, "当前范围无法完整执行，请查看预检原因并另建合适范围");
    var created =
        requests.createWithScope(
            p,
            "intake-analysis:" + id,
            quote.snapshotId(),
            input.instruction(),
            false,
            List.of(),
            body.allowPartialInput(),
            input.proposition(),
            input.analysisScope(),
            body.confirmFullScope());
    db.update(
        "UPDATE analysis_intake SET analysis_request_id=?,analysis_fingerprint=? WHERE id=?",
        created.request().id(),
        fingerprint,
        id);
    return created;
  }

  /** 计划/原话/来源哈希一起参与指纹，预览与完整分别保守计价，未调用供应商。 */
  private Quote quote(String p, Map<String, Object> row) {
    var input = input(p, row);
    String snapshot = (String) row.get("snapshot_id");
    if (snapshot == null) throw reject(HttpStatus.CONFLICT, "请先明确并固定本次分析资料");
    if (!snapshotHash(p, snapshot).equals(row.get("snapshot_sha256")))
      throw new IllegalStateException("Intake source changed");
    var now =
        protocol.normalize(
            new Draft(
                input.canonicalUrl(),
                input.requestedSnapshotId(),
                input.instruction(),
                input.proposition(),
                input.analysisScope()));
    if (!input.equals(now)) throw reject(HttpStatus.CONFLICT, "入口识别规则已变化，请沿用问题另建入口");
    boolean full = "FULL".equals(input.analysisScope()), stance = input.goal().stanceRequested();
    var plan =
        full
            ? (stance ? plans.discussionFull() : plans.discussionDigest())
            : (stance ? plans.discussionWithProposition() : plans.discussion());
    var view =
        preflight.preview(
            p,
            snapshot,
            false,
            List.of(),
            input.instruction(),
            input.proposition(),
            input.analysisScope());
    long maximum;
    int calls;
    if (full) {
      maximum = view.fullScope().maximumUnits();
      calls = view.fullScope().plannedBatches();
    } else {
      maximum =
          "MODEL".equals(plan.reportMode())
              ? Math.addExact(
                  Math.multiplyExact((long) plan.inputTokenCeiling(), plan.inputUnitRate()),
                  Math.multiplyExact((long) plan.maxOutputTokens(), plan.outputUnitRate()))
              : 0;
      calls = 1;
    }
    var cost =
        new Cost(
            view.mode(),
            plan.unit(),
            Long.toString(maximum),
            Long.toString(plan.budgetLimit()),
            calls);
    String sha =
        hash(
            row.get("id")
                + "\n"
                + row.get("input_sha256")
                + "\n"
                + row.get("snapshot_sha256")
                + "\n"
                + ModelContract.encode(plan)
                + "\n"
                + encode(view));
    return new Quote(sha, snapshot, view, cost, !full || view.fullAnalysisAvailable());
  }

  /** SQL只用固定片段与参数，来源身份映射必须来自同项目成功采集或导入身份。 */
  private ProjectStore.Page<ProjectStore.Snapshot> candidates(
      String p, Input input, int page, int size) {
    page(page, size);
    if (input.canonicalUrl() == null) {
      var item = projects.snapshot(p, input.requestedSnapshotId());
      return new ProjectStore.Page<>(page == 1 ? List.of(item) : List.of(), 1, page, size, 1);
    }
    var args = matchArgs(p, input);
    long total = db.queryForObject("SELECT COUNT(*) " + matchSql(), Long.class, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    var ids =
        db.queryForList(
            "SELECT s.id " + matchSql() + " ORDER BY ps.id DESC LIMIT ? OFFSET ?",
            String.class,
            args.toArray());
    return new ProjectStore.Page<>(
        ids.stream().map(x -> projects.snapshot(p, x)).toList(),
        total,
        page,
        size,
        (total + size - 1) / size);
  }

  /** 已保存资料可来自任意受支持导入平台；给URL时还必须证明是同一个作品。 */
  private void requireCandidate(String p, Input input, String snapshot) {
    if (snapshot == null || snapshot.isBlank()) throw bad("请明确选择一份已保存资料或确认使用本次采集结果");
    projects.snapshot(p, snapshot);
    if (input.canonicalUrl() != null) {
      var args = matchArgs(p, input);
      args.add(snapshot);
      if (db.queryForObject(
              "SELECT COUNT(*) " + matchSql() + " AND s.id=?", Integer.class, args.toArray())
          != 1) throw reject(HttpStatus.CONFLICT, "选择的资料与本次视频链接不一致，请重新选择");
    }
  }

  /** 共享快照不代表知道BV/av互换关系；只有持久来源证据可建立这种映射。 */
  private String matchSql() {
    return "FROM project_snapshot ps JOIN data_snapshot s ON s.id=ps.snapshot_id JOIN content_item"
        + " c ON c.id=s.content_id WHERE ps.project_id=? AND c.source_type='BILIBILI' AND"
        + " (c.source_content_id IN (?,?) OR EXISTS(SELECT 1 FROM source_collection_batch b"
        + " WHERE b.project_id=ps.project_id AND b.snapshot_id=s.id AND b.status='SUCCESS'"
        + " AND b.workspace_scope=? AND (b.canonical_url=? OR b.requested_source_id=? OR"
        + " b.resolved_bvid=? OR b.resolved_aid=?)))";
  }

  /** av数字部分与规范URL共同参与参数，不做不可靠的BV算术换算。 */
  private ArrayList<Object> matchArgs(String p, Input input) {
    String nativeId =
        input.sourceId().startsWith("av") ? input.sourceId().substring(2) : input.sourceId();
    return new ArrayList<>(
        List.of(
            p,
            input.sourceId(),
            nativeId,
            projects.workspace(),
            input.canonicalUrl(),
            input.sourceId(),
            input.sourceId(),
            nativeId));
  }

  /** 使用资料前核对持久正文与输入哈希，不把损坏原文重新算成“最新正确版本”。 */
  private String snapshotHash(String p, String snapshot) {
    projects.snapshot(p, snapshot);
    var row = imports.snapshot(snapshot);
    if (row == null || !hash(row.documentJson()).equals(row.inputHash()))
      throw new IllegalStateException("Intake snapshot hash mismatch");
    return row.inputHash();
  }

  /** 所有写操作先锁项目，本查询再锁入口；只读查询不建立新的行锁。 */
  private Map<String, Object> row(String p, String id, boolean lock) {
    var rows =
        db.queryForList(
            "SELECT *,DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') created_time FROM analysis_intake"
                + " WHERE project_id=? AND execution_scope=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),
            p,
            scopes.executionScope(),
            id);
    if (rows.isEmpty()) throw reject(HttpStatus.NOT_FOUND, "分析入口不存在");
    return rows.getFirst();
  }

  /** 冻结JSON与项目身份损坏时失败关闭，不能重建原问题或把另一项目正文当成本项目。 */
  private Input input(String p, Map<String, Object> row) {
    String text = (String) row.get("input_json");
    if (!hash(text).equals(row.get("input_sha256")))
      throw new IllegalStateException("Intake input hash mismatch");
    try {
      var saved = json.readValue(text, SavedInput.class);
      if (!p.equals(saved.projectId())
          || saved.input() == null
          || !VERSION.equals(saved.input().version()))
        throw new IllegalStateException("Intake input identity mismatch");
      return saved.input();
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid stored intake", error);
    }
  }

  /** 仅存业务字段，编码错误为服务器错误，不反射原文。 */
  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot encode intake", error);
    }
  }

  /** 统一UTF8 SHA用于原问题、资料和明确确认；不是用户身份认证。 */
  private String hash(String text) {
    return ReferenceMaterialStore.hash(text);
  }

  /** 恢复列表和候选列表有界，不一次拉取全部历史。 */
  private void page(int page, int size) {
    if (page < 1 || page > 10000 || size < 1 || size > 20) throw bad("分析入口页码1～10000，每页1～20条");
  }

  /** 请求键不能从内部子任务键反推或覆盖别的入口。 */
  private void key(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw bad("需要有效的分析入口请求键");
  }

  /** 固定边界说明，不把流程串联包装成动态Agent、平台全量或语义质量通过。 */
  private List<String> notes() {
    return List.of(
        "当前串联单作品评论；支持B站官方视频URL或项目内已存评论资料。",
        "URL仅解析官方身份，不跟随短链；UP候选筛选仍用原入口；小红书在线采集和视频理解尚未实现。",
        "保存问题、采集、确认资料、确认分析分开；轮询/刷新不会自动采集、付费重试或调用模型。",
        "全部指本次资料中全部已保存评论，不证明平台全量或语义正确；字幕/参考及增量对照仍使用原资料分析入口。");
  }

  /** 只允许固定业务原因进入原统一错误投影。 */
  private ResponseStatusException reject(HttpStatus status, String reason) {
    return new ResponseStatusException(status, reason);
  }
}
