/* 文件职责：把采集可见范围与分析快照范围分开，在接受新请求时冻结来源诊断，避免历史报告随重采变化。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.project.ProjectStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 计数是确定性事实，不计算“代表全部观众”的可信度；旧请求不倒填不存在的接受时诊断。 */
@Service
public class SourceCoverageStore {
  private final ProjectStore projects;
  private final ImportMapper imports;
  private final JdbcTemplate db;
  private final ObjectMapper json;

  /** 复用明确项目快照，不从最新作品替换请求资料。 */
  public SourceCoverageStore(
      ProjectStore projects, ImportMapper imports, JdbcTemplate db, ObjectMapper json) {
    this.projects = projects;
    this.imports = imports;
    this.db = db;
    this.json = json;
  }

  /** declaredDifference可为负，平台数量会变动；null表示来源未提供，不能显示成零缺失。 */
  public record Coverage(
      String version,
      String snapshotId,
      String collectionId,
      String completeness,
      int savedComments,
      Integer sourceReportedComments,
      Integer declaredDifference,
      int missingParentLinks,
      boolean visibleTraversalReconciled,
      List<String> notes) {}

  /** frozen=false专门标记旧请求的回溯投影，不冒充接受时已经冻结。 */
  public record View(boolean frozen, Coverage coverage) {}

  /** 1、核对项目快照；2、读取明确版本计数/缺父；3、返回来源事实和无法证明的边界。 */
  @Transactional(readOnly = true)
  public Coverage prepare(String projectId, String snapshotId) {
    var snapshot = projects.snapshot(projectId, snapshotId);
    ImportDocument input;
    try {
      input = json.readValue(imports.snapshot(snapshotId).documentJson(), ImportDocument.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    Integer declared = snapshot.sourceReportedComments();
    int saved = input.comments().size();
    boolean reconciled =
        "VISIBLE_EXHAUSTED".equals(snapshot.sourceCompleteness())
            && declared != null
            && declared == saved
            && input.contextGaps().isEmpty();
    List<String> notes = new ArrayList<>();
    notes.add("快照评论数、平台声明数、送入模型数和形成判断数是不同口径，不能替代。");
    notes.add(
        snapshot.collectionId() == null
            ? "文件导入未冻结平台分页证据，平台全量未知。"
            : "采集成功只表示保存了资料；隐藏、删除或权限不可见评论无法证明已包含。");
    if (declared != null && declared != saved) notes.add("平台声明与保存数量未对齐；差异原因未知，不能据此虚构缺失评论的观点。");
    if (!input.contextGaps().isEmpty()) notes.add("存在缺失父评论，相关语境不足；完整命题请求会拒绝，而有限预览会排除受影响节点。");
    return new Coverage(
        "source-coverage-v1",
        snapshotId,
        snapshot.collectionId(),
        snapshot.sourceCompleteness(),
        saved,
        declared,
        declared == null ? null : declared - saved,
        input.contextGaps().size(),
        reconciled,
        List.copyOf(notes));
  }

  /** 与分析请求同事务追加；旧来源计数以后变化也不改写本次记录。 */
  public void save(String requestId, Coverage coverage) {
    try {
      db.update(
          "INSERT INTO analysis_request_source_coverage(request_id,coverage_json) VALUES(?,?)",
          requestId,
          json.writeValueAsString(coverage));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 调用者先核对请求资格；没有历史冻结行时明确提供回溯范围，不回填数据库。 */
  @Transactional(readOnly = true)
  public View get(String projectId, String requestId, String snapshotId) {
    projects.snapshot(projectId, snapshotId);
    var rows =
        db.queryForList(
            "SELECT c.coverage_json FROM analysis_request_source_coverage c JOIN analysis_request r"
                + " ON r.id=c.request_id WHERE r.project_id=? AND r.id=?",
            String.class,
            projectId,
            requestId);
    if (rows.isEmpty()) return new View(false, prepare(projectId, snapshotId));
    try {
      return new View(true, json.readValue(rows.getFirst(), Coverage.class));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid source coverage", e);
    }
  }
}
