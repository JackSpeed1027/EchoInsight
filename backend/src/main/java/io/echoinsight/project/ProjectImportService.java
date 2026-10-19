/* 文件职责：将项目归档与原导入放在同一事务，保持无项目旧入口兼容。 */
package io.echoinsight.project;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.ingestion.ImportReceipt;
import io.echoinsight.ingestion.ImportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 避免导入成功而项目挂载失败的半完成业务；复用原始文本修订与快照规则。 */
@Service
public class ProjectImportService {
  private final ProjectStore projects;
  private final ImportService imports;

  /** 注入既有业务服务，资料归档不引入模型执行。 */
  public ProjectImportService(ProjectStore projects, ImportService imports) {
    this.projects = projects;
    this.imports = imports;
  }

  /** 1、验证并锁项目；2、复用导入；3、幂等挂载；4、失败整体回滚。 */
  @Transactional
  public ImportReceipt ingest(String projectId, ImportDocument document) {
    if (projectId != null) projects.lock(projectId);
    var receipt = imports.ingest(document);
    if (projectId != null) projects.attach(projectId, receipt.snapshotId());
    return receipt;
  }
}
