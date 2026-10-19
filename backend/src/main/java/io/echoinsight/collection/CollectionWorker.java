/* 文件职责：在数据库事务之外执行网络工具，再原子发布可验证的项目资料。 */
package io.echoinsight.collection;

import org.springframework.stereotype.Component;

/** 工作者把“外部采集”和“内部发布”分成两个阶段，避免长事务占用连接和项目锁。 */
@Component
public class CollectionWorker {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(CollectionWorker.class);
  private final PagedBilibiliCollection paged;
  private final CollectionStore store;
  private final BilibiliUrlParser urls;
  private final BilibiliCliCollector collector;
  private final BilibiliCliAdapter adapter;
  private final CollectionArchive archive;
  private final CollectionPublicationService publication;

  public CollectionWorker(
      CollectionStore store,
      BilibiliUrlParser urls,
      BilibiliCliCollector collector,
      BilibiliCliAdapter adapter,
      CollectionArchive archive,
      CollectionPublicationService publication,
      PagedBilibiliCollection paged) {
    this.paged = paged;
    this.store = store;
    this.urls = urls;
    this.collector = collector;
    this.adapter = adapter;
    this.archive = archive;
    this.publication = publication;
  }

  /** 1、抢租约；2、有界调用工具；3、严格适配并存档；4、在短事务中导入并发布。 */
  public void runOne(String id) {
    CollectionStore.Lease lease = store.claim(id);
    if (lease == null) return;
    try {
      store.check(lease);
      var target = urls.parse(lease.batch().canonicalUrl());
      if (lease.batch().collectAll()) {
        paged.step(lease, target);
        return;
      }
      var payload = collector.collect(target, lease.batch().requestedRootLimit());
      String storageKey =
          archive.save(lease.batch().id(), lease.batch().attempts(), target, payload);
      var converted =
          adapter.convert(
              target, lease.batch().requestedRootLimit(), payload.video(), payload.comments());
      publication.publish(lease, converted, storageKey);
    } catch (CollectionStore.LostLease ignored) {
      // 新执行者已接管时，旧执行者不能覆盖新状态；已产生的追加存档仍保留用于审计。
    } catch (CollectionFailure failure) {
      store.fail(id, lease.token(), failure.code());
    } catch (io.echoinsight.ingestion.ImportFailure invalid) {
      store.fail(id, lease.token(), "INVALID_IMPORTED_DATA");
    } catch (RuntimeException unexpected) {
      log.warn("Collection {} failed with {}", id, unexpected.getClass().getSimpleName());
      store.fail(id, lease.token(), "COLLECTION_FAILED");
    }
  }
}
