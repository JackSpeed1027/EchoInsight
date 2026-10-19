/* 文件职责：定时发现采集任务，用单工作线程和有界队列限制对平台的并发访问。 */
package io.echoinsight.collection;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单线程保证本机不会并发轰击平台；数据库待执行状态承担背压和重启恢复。 */
@Component
@ConditionalOnProperty(
    name = "echoinsight.collection.bilibili.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CollectionDispatcher {
  private final CreatorDiscoveryStore discoveries;
  private final CreatorDiscoveryWorker discoveryWorker;
  private final CollectionStore store;
  private final CollectionWorker worker;
  private final BilibiliCollectionSettings settings;
  private final Set<String> submitted = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(3),
          task -> {
            Thread thread = new Thread(task, "echoinsight-bilibili-collection");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public CollectionDispatcher(
      CollectionStore store,
      CollectionWorker worker,
      BilibiliCollectionSettings settings,
      CreatorDiscoveryStore discoveries,
      CreatorDiscoveryWorker discoveryWorker) {
    this.discoveries = discoveries;
    this.discoveryWorker = discoveryWorker;
    this.store = store;
    this.worker = worker;
    this.settings = settings;
  }

  /** 只提交队列有容量的任务；满载时批次保留在数据库，下一轮继续发现。 */
  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  public void dispatch() {
    if (!settings.enabled()) return;
    store.expireExhausted();
    // 两类候选交错提交并共用有限队列；满载时留在数据库，不绕开单线程平台限流。
    java.util.List<String> tasks = new java.util.ArrayList<>();
    var discoveryIds = discoveries.candidates();
    var collectionIds = store.candidates();
    for (int n = 0; n < Math.max(discoveryIds.size(), collectionIds.size()); n++) {
      if (n < discoveryIds.size()) tasks.add("discovery:" + discoveryIds.get(n));
      if (n < collectionIds.size()) tasks.add("collection:" + collectionIds.get(n));
    }
    for (String task : tasks) {
      if (!submitted.add(task)) continue;
      try {
        pool.execute(
            () -> {
              try {
                String id = task.substring(task.indexOf(':') + 1);
                if (task.startsWith("discovery:")) discoveryWorker.runOne(id);
                else worker.runOne(id);
              } finally {
                submitted.remove(task);
              }
            });
      } catch (RejectedExecutionException full) {
        submitted.remove(task);
        break;
      }
    }
  }

  /** 应用关闭时先等待当前短任务，超时后中断；数据库租约负责后续恢复。 */
  @PreDestroy
  public void close() throws InterruptedException {
    pool.shutdown();
    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
  }
}
