/* 文件职责：在共享单线程工具池内执行候选发现，并将外部等待与数据库事务分离。 */
package io.echoinsight.collection;

import org.springframework.stereotype.Component;

/** 不增加独立任务队列；与评论采集共享平台并发上限。 */
@Component
public class CreatorDiscoveryWorker {
  private final CreatorDiscoveryStore store;
  private final CreatorDiscovery adapter;
  private final BilibiliCliCollector tools;

  /** 依赖共用受限进程执行器，不直接使用shell或拼接用户命令。 */
  public CreatorDiscoveryWorker(
      CreatorDiscoveryStore store, CreatorDiscovery adapter, BilibiliCliCollector tools) {
    this.store = store;
    this.adapter = adapter;
    this.tools = tools;
  }

  /** 1、抢占租约；2、有界关键词发现；3、严格校验；4、按token发布或保留安全失败。 */
  public void runOne(String id) {
    var lease = store.claim(id);
    if (lease == null) return;
    try {
      var run = lease.run();
      byte[] raw = tools.discover(run.ownerMid(), run.keyword(), run.candidateLimit());
      store.succeed(lease, raw, adapter.parse(raw, run.ownerMid(), run.candidateLimit()));
    } catch (CollectionFailure e) {
      store.fail(lease, e.code());
    } catch (RuntimeException e) {
      store.fail(lease, "DISCOVERY_FAILED");
    }
  }
}
