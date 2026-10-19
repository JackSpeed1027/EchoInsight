/* 文件职责：按已保存计划划分评论，逐批调用或复用检查点，不向模型提交全部数据。 */
package io.echoinsight.model;

import io.echoinsight.analysis.FixtureClassifier.Classification;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.orchestration.JobStore;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** 当前批次顺序取自不可变快照，计划改变需要创建新任务。 */
@Service
public class BatchClassifier {
  private final JobStore jobs;
  private final ModelGateway gateway;

  /** 注入短事务任务存储与统一模型调用网关。 */
  public BatchClassifier(JobStore jobs, ModelGateway gateway) {
    this.jobs = jobs;
    this.gateway = gateway;
  }

  /**
   * 按不可变快照的顺序生成批次，将多个已持久化响应汇总成原有分类步骤的输入。
   *
   * <p>1、核对任务与执行计划版本，防止恢复时静默换算法。 2、为评论补充同一快照中的直接父评论上下文，不声称取得完整对话。 3、每批出站前检查续租状态；网关内部仍会核对数据库中的真实租约。
   * 4、按顺序汇合成功响应，全部完成后才交给报告计算。
   *
   * @param checkLease 由独立续租器提供的失效检查，避免失去资格后继续派发下一批
   * @return 覆盖当前快照的完整分类列表；这是同任务恢复，不是跨快照增量缓存
   */
  public List<Classification> classify(
      JobStore.Job job, String token, ImportDocument input, Runnable checkLease) {
    var plan = jobs.plan(job.id());
    if (!plan.modelVersion().equals(job.modelVersion())
        || !plan.promptVersion().equals(job.promptVersion())
        || !plan.taxonomyVersion().equals(job.taxonomyVersion()))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
    List<Classification> result = new ArrayList<>();
    Map<String, String> context = new HashMap<>();
    input.comments().forEach(c -> context.put(c.id(), c.text()));
    for (int start = 0, index = 0;
        start < input.comments().size();
        start += plan.batchSize(), index++) {
      checkLease.run();
      var comments =
          input
              .comments()
              .subList(start, Math.min(start + plan.batchSize(), input.comments().size()))
              .stream()
              .map(
                  c ->
                      new ModelContract.Text(
                          c.id(), c.text(), c.parentId(), context.get(c.parentId())))
              .toList();
      // 这是本机 Stub 的保守契约上界，不是任意真实模型的通用分词器。
      int inputLimit =
          1024
              + comments.stream()
                  .mapToInt(
                      c ->
                          c.text().getBytes(StandardCharsets.UTF_8).length
                              + (c.parentText() == null
                                  ? 0
                                  : c.parentText().getBytes(StandardCharsets.UTF_8).length)
                              + c.commentId().length()
                              + 256)
                  .sum();
      var request =
          new ModelContract.Request(
              job.snapshotId(),
              index,
              input.content().sourceType(),
              comments,
              plan.inputTokenCeiling() == null ? inputLimit : plan.inputTokenCeiling(),
              plan.maxOutputTokens());
      result.addAll(gateway.classify(job.id(), token, plan, request).classifications());
    }
    return List.copyOf(result);
  }
}
