/* 文件职责：追加保存已成功读取的采集页，沿用原采集批次和租约，不创建第二套任务状态。 */
package io.echoinsight.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 每页提交后释放任务回到 PENDING，避免长网络任务占用事务；重启从不可变页继续。 */
@Repository
public class CollectionPageStore {
  public static final int MAX_CHECKPOINT_BYTES = 16 * 1024 * 1024;
  private final JdbcTemplate db;
  private final ProjectStore projects;
  private final ObjectMapper json;

  /** JDBC 与项目工作空间一起注入，任何读取都不接受客户端指定工作空间。 */
  public CollectionPageStore(JdbcTemplate db, ProjectStore projects, ObjectMapper json) {
    this.db = db;
    this.projects = projects;
    this.json = json;
  }

  /** 页键仅来自编排器，例如 META、ROOT:0、REPLY:123:1；不包含用户自由文本。 */
  public record Saved(String key, BilibiliPageClient.Page page) {}

  /** 返回本工作空间的追加页；未提交、其他项目或旧执行者的数据不可见。 */
  public List<Saved> read(String batchId) {
    return db.query(
        "SELECT p.page_key,p.page_json FROM source_collection_page p JOIN source_collection_batch b"
            + " ON b.id=p.batch_id WHERE b.id=? AND b.workspace_scope=? ORDER BY p.ordinal_no",
        (r, n) -> new Saved(r.getString(1), decode(r.getString(2))),
        batchId,
        projects.workspace());
  }

  /** 读检查不持有网络事务；写入时append仍重新锁行核验，关闭检查后的并发窗口。 */
  public void check(CollectionStore.Lease lease) {
    int count =
        db.queryForObject(
            "SELECT COUNT(*) FROM source_collection_batch WHERE id=? AND workspace_scope=? AND"
                + " status='RUNNING' AND owner_token=? AND lease_until>=UTC_TIMESTAMP(6)",
            Integer.class,
            lease.batch().id(),
            projects.workspace(),
            lease.token());
    if (count != 1) throw new CollectionStore.LostLease();
  }

  /** 1、锁定并核验当前租约；2、追加成功页；3、释放为待执行；三步同事务提交，不覆盖旧页。 */
  @Transactional
  public void append(CollectionStore.Lease lease, String key, BilibiliPageClient.Page page) {
    var owned =
        db.queryForList(
            "SELECT id FROM source_collection_batch WHERE id=? AND workspace_scope=? AND"
                + " status='RUNNING' AND owner_token=? AND lease_until>=UTC_TIMESTAMP(6) FOR"
                + " UPDATE",
            String.class,
            lease.batch().id(),
            projects.workspace(),
            lease.token());
    if (owned.size() != 1) throw new CollectionStore.LostLease();
    int ordinal =
        db.queryForObject(
            "SELECT COUNT(*) FROM source_collection_page WHERE batch_id=?",
            Integer.class,
            lease.batch().id());
    String encoded = encode(page);
    long storedBytes =
        db.queryForObject(
            "SELECT COALESCE(SUM(OCTET_LENGTH(page_json)),0) FROM source_collection_page WHERE"
                + " batch_id=?",
            Long.class,
            lease.batch().id());
    if (storedBytes + encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > MAX_CHECKPOINT_BYTES) throw new CollectionFailure("COLLECTION_STORAGE_LIMIT");
    db.update(
        "INSERT INTO source_collection_page(batch_id,ordinal_no,page_key,page_json)"
            + " VALUES(?,?,?,?)",
        lease.batch().id(),
        ordinal,
        key,
        encoded);
    db.update(
        "UPDATE source_collection_batch SET status='PENDING',owner_token=NULL,lease_until=NULL"
            + " WHERE id=? AND owner_token=?",
        lease.batch().id(),
        lease.token());
  }

  /** 编解码失败是存储契约错误，不丢弃检查点重新从头采集。 */
  private BilibiliPageClient.Page decode(String value) {
    try {
      return json.readValue(value, BilibiliPageClient.Page.class);
    } catch (java.io.IOException e) {
      throw new CollectionFailure("PAGE_CHECKPOINT_INVALID", e);
    }
  }

  /** 正文存储只含白名单字段；异常细节不回传客户端。 */
  private String encode(BilibiliPageClient.Page value) {
    try {
      return json.writeValueAsString(value);
    } catch (java.io.IOException e) {
      throw new CollectionFailure("PAGE_CHECKPOINT_INVALID", e);
    }
  }
}
