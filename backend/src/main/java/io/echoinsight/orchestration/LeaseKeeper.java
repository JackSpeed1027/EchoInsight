/* 文件职责：模型等待期间按时间续租，不再依赖处理到第几条评论才续租。 */
package io.echoinsight.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 单独的定时线程只做短续租，不承担分类或长期数据库事务。 */
@Component
public class LeaseKeeper implements AutoCloseable {
  private final JobStore store;
  private final long intervalMs;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var thread = new Thread(r, "echoinsight-lease-keeper");
            thread.setDaemon(true);
            return thread;
          });

  /** 默认 5 秒续租，租约默认 30 秒；间隔必须小于租约长度。 */
  public LeaseKeeper(
      JobStore store, @Value("${echoinsight.worker.heartbeat-ms:5000}") long intervalMs) {
    if (intervalMs < 20 || intervalMs >= store.leaseSeconds() * 1000L)
      throw new IllegalArgumentException("Invalid heartbeat interval");
    this.store = store;
    this.intervalMs = intervalMs;
  }

  /** 每次执行有独立失效标志；关闭只取消这一条续租任务。 */
  public final class Handle implements AutoCloseable {
    private final AtomicBoolean lost = new AtomicBoolean();
    private final ScheduledFuture<?> future;

    /** 续租失败后保守停止派发，最终写入仍会再次核对真实租约。 */
    private Handle(String jobId, String token) {
      future =
          timer.scheduleAtFixedRate(
              () -> {
                if (lost.get()) return;
                try {
                  store.renew(jobId, token);
                } catch (Exception e) {
                  lost.set(true);
                }
              },
              intervalMs,
              intervalMs,
              TimeUnit.MILLISECONDS);
    }

    /** 在下一次调用和报告发布前检查续租状态。 */
    public void check() {
      if (lost.get()) throw new JobStore.LostLease();
    }

    /** 释放当前执行的续租资源，不删除持久化任务。 */
    @Override
    public void close() {
      future.cancel(false);
    }
  }

  /** 为已领取的任务开始时间续租。 */
  public Handle keep(String jobId, String token) {
    return new Handle(jobId, token);
  }

  /** 应用退出时停止后台计时器。 */
  @Override
  @PreDestroy
  public void close() {
    timer.shutdownNow();
  }
}
