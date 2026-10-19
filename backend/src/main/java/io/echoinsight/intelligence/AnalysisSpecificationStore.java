/* 文件职责：同原请求事务冻结命题规格，并按项目边界只读恢复历史规格。 */
package io.echoinsight.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.ModelContract;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 与原请求一对一追加，不回填旧请求，不引入另一套任务、预算或对话状态。 */
@Service
public class AnalysisSpecificationStore {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  /** 依赖现有数据库和JSON配置，写入事务由请求服务负责。 */
  public AnalysisSpecificationStore(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  /** 新请求只保存用户明确的规格；普通讨论没有记录，历史指纹保持不变。 */
  public void save(String requestId, AnalysisSpecification specification) {
    if (specification == null) return;
    db.update(
        "INSERT INTO analysis_request_specification(request_id,specification_json) VALUES(?,?)",
        requestId,
        ModelContract.encode(specification));
  }

  /** 调用方先校验项目资格；SQL再连接项目范围，旧请求返回null而不生成默认命题。 */
  public AnalysisSpecification get(String projectId, String requestId) {
    var rows =
        db.queryForList(
            """
            SELECT s.specification_json FROM analysis_request_specification s
            JOIN analysis_request r ON r.id=s.request_id WHERE r.project_id=? AND r.id=?
            """,
            String.class,
            projectId,
            requestId);
    if (rows.isEmpty()) return null;
    try {
      var value = json.readValue(rows.getFirst(), AnalysisSpecification.class);
      if (!AnalysisSpecification.VERSION.equals(value.version()))
        throw new IllegalStateException("Unsupported specification version");
      return value;
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid stored specification", error);
    }
  }
}
