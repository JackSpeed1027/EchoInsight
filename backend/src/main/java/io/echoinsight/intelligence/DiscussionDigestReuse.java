/* 文件职责：普通完整讨论的局部语义指纹与冻结复用凭证；历史收费出处和当前原文映射分开。 */
package io.echoinsight.intelligence;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.*;
import java.util.*;

/** 仅复用明确基准中的成功MAP，全部归并每次重新执行，旧误读也可能被继承。 */
public final class DiscussionDigestReuse {
  public static final String VERSION = "discussion-map-reuse-v1";

  /** 无独立缓存库或状态。 */
  private DiscussionDigestReuse() {}

  /** 响应保留局部c1…身份，执行时映射到当前快照评论别名。 */
  public record Cached(
      String sourceRequestId,
      String sourceJobId,
      int sourceBatchIndex,
      String responseHash,
      DiscussionModelContract.Response response) {}

  /** 当前片指纹；cached为空代表必须新调用。 */
  public record Entry(int index, String inputHash, Cached cached) {}

  /** 冻结于既有scope JSON，不增加第二任务或费用状态。 */
  public record Plan(String version, String baselineRequestId, List<Entry> entries) {
    /** 命中数不是本任务新调用成功数。 */
    public int reused() {
      return (int) entries.stream().filter(e -> e.cached() != null).count();
    }
  }

  /** 指向实际收费的原任务，可能早于用户显式选择的基准。 */
  public record Origin(
      int index,
      String sourceRequestId,
      String sourceJobId,
      int sourceBatchIndex,
      String responseHash) {}

  /** 报告只投影出处，不虚构零费用账单或节省金额。 */
  public record Receipt(String baselineRequestId, int reusedChunks, List<Origin> origins) {}

  /** 局部模型真实Payload和完整计划一致才允许命中，不以相似度或全篇统计猜测。 */
  public static String fingerprint(ModelContract.Plan p, DiscussionDigestContract.Request r) {
    return ReferenceMaterialStore.hash(
        ModelContract.encode(p) + ModelContract.encode(DiscussionDigestContract.localPayload(r)));
  }

  /** 恢复时再次校验所有输入指纹/响应哈希，不能把单个reused数当成可信缓存。 */
  public static void verify(
      ImportDocument input,
      StanceChunkPlanner.Manifest manifest,
      AnalysisContextStore.View context,
      ModelContract.Plan model,
      String question,
      boolean assessment) {
    var plan = manifest.reuse();
    if (!DiscussionDigestPlan.enabled(model)
        || plan == null
        || !VERSION.equals(plan.version())
        || plan.entries() == null
        || plan.entries().size() != manifest.chunks().size())
      throw new IllegalStateException("Missing discussion reuse manifest");
    for (var chunk : manifest.chunks()) {
      var entry = plan.entries().get(chunk.index());
      var r =
          FullDiscussionAnalyzer.mapInput(
                  input, manifest, chunk, context, model, question, assessment)
              .request();
      if (entry == null
          || entry.index() != chunk.index()
          || !fingerprint(model, r).equals(entry.inputHash()))
        throw new IllegalStateException("Discussion reuse fingerprint mismatch");
      var cached = entry.cached();
      if (cached != null) {
        if (plan.baselineRequestId() == null
            || "MOCK".equals(model.reportMode())
            || cached.sourceRequestId() == null
            || cached.sourceJobId() == null
            || cached.sourceBatchIndex() < 0
            || cached.sourceBatchIndex() >= DiscussionDigestPlan.MAX_CALLS
            || cached.response() == null
            || !ReferenceMaterialStore.hash(ModelContract.encode(cached.response()))
                .equals(cached.responseHash()))
          throw new IllegalStateException("Discussion reused response hash mismatch");
        DiscussionDigestContract.validate(r, cached.response());
      }
    }
  }

  /** 只读出处投影。 */
  public static Receipt receipt(Plan p) {
    return new Receipt(
        p.baselineRequestId(),
        p.reused(),
        p.entries().stream()
            .filter(e -> e.cached() != null)
            .map(
                e ->
                    new Origin(
                        e.index(),
                        e.cached().sourceRequestId(),
                        e.cached().sourceJobId(),
                        e.cached().sourceBatchIndex(),
                        e.cached().responseHash()))
            .toList());
  }
}
