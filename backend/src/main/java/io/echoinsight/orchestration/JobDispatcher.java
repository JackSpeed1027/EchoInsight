/* 文件职责：定时发现可执行任务，使用两个工作线程及有界队列控制并发。 */
package io.echoinsight.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
    name = "echoinsight.worker.enabled",
    havingValue = "true",
    matchIfMissing = true)
/** 定时发现可执行任务，使用两个工作线程及有界队列控制并发。 */
public class JobDispatcher {
  private final JobStore store;
  private final AnalysisWorker worker;
  private final Set<String> submitted = ConcurrentHashMap.newKeySet();
  private final ThreadPoolExecutor pool =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(8),
          r -> {
            var t = new Thread(r, "echoinsight-analysis");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /** 注入本类需要的依赖，避免在业务方法中自行创建连接或组件。 */
  public JobDispatcher(JobStore store, AnalysisWorker worker) {
    this.store = store;
    this.worker = worker;
  }

  @Scheduled(fixedDelay = 1000, initialDelay = 1000)
  /** 把候选任务送入有界队列；队列已满时保留数据库待执行状态。 */
  public void dispatch() {
    for (var id : store.candidates()) {
      if (!submitted.add(id)) continue;
      try {
        pool.execute(
            () -> {
              try {
                worker.runOne(id);
              } finally {
                submitted.remove(id);
              }
            });
      } catch (RejectedExecutionException e) {
        submitted.remove(id);
        break;
      }
    }
  }

  @PreDestroy
  public void close() throws InterruptedException {
    pool.shutdown();
    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) pool.shutdownNow();
  }
}
