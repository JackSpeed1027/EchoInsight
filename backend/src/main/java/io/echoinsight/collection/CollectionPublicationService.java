/* 文件职责：把标准资料导入、项目挂载和采集成功状态放在同一数据库事务中提交。 */
package io.echoinsight.collection;

import io.echoinsight.ingestion.ImportReceipt;
import io.echoinsight.project.ProjectImportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 任何一步失败都会回滚数据库写入，页面不会看到“采集成功但项目没有资料”的半状态。 */
@Service
public class CollectionPublicationService {
  private final ProjectImportService imports;
  private final CollectionStore store;

  public CollectionPublicationService(ProjectImportService imports, CollectionStore store) {
    this.imports = imports;
    this.store = store;
  }

  /** 1、复用标准导入与去重；2、挂载项目；3、核对租约；4、绑定范围诊断并提交。 */
  @Transactional
  public ImportReceipt publish(
      CollectionStore.Lease lease, BilibiliCliAdapter.Converted converted, String storageKey) {
    ImportReceipt receipt = imports.ingest(lease.batch().projectId(), converted.document());
    store.succeed(
        lease.batch().id(),
        lease.token(),
        converted,
        receipt.snapshotId(),
        receipt.id(),
        storageKey);
    return receipt;
  }
}
