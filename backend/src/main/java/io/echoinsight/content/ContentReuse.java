/* 文件职责：定义跨版本分片的局部语义输入、指纹、冻结复用凭证与当前原文引用映射。 */
package io.echoinsight.content;

import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.ModelContract;
import java.util.*;

/** 仅v3把版本/全局片号从模型MAP输入剥离；v2含这些字段，不能伪装成可跨版本缓存。 */
public final class ContentReuse {
  public static final String PROMPT = "content-local-map-json-v3",
      TAXONOMY = "content-local-evidence-v3";
  public static final String VERSION = "content-reuse-v1",
      LOCAL_VERSION = "content-local-window-v1";
  private static final String LOCAL_ID = "local-content";

  private ContentReuse() {}

  /** 模型实际看到的MAP数据，不能追加job/material版本、批号、全篇总数等跨片依赖而沿用此版本。 */
  public record Payload(
      String promptVersion,
      String title,
      String format,
      String sourceDescription,
      String instruction,
      ContentSelection selection) {}

  /** 已成功的原收费调用响应及出处；响应属于旧调用，不作为新任务零费用调用插入账本。 */
  public record Cached(
      String sourceJobId,
      int sourceBatchIndex,
      String responseHash,
      ContentModelContract.Response response) {}

  /** 每个当前分片独立指纹，cached为空表示必须实际执行。 */
  public record Entry(int index, String inputHash, Cached cached) {}

  /** 复用资格在接受事务冻结；状态、费用与租约仍只存原任务基础设施。 */
  public record Plan(String version, String baselineJobId, List<Entry> entries) {
    /** 这是已确认可复用数，不是新任务实际模型调用数。 */
    public int reused() {
      return (int) entries.stream().filter(x -> x.cached() != null).count();
    }
  }

  /** 报告只公开出处/哈希，不把历史用量当作新费用或生成“节省金额”。 */
  public record Origin(int index, String sourceJobId, int sourceBatchIndex, String responseHash) {}

  /** 关联用户明确选择的基准和真实复用来源。 */
  public record Receipt(String baselineJobId, int reusedChunks, List<Origin> origins) {}

  /** 固定提示/输出体系同时匹配；不能只比模型名称。 */
  public static boolean enabled(ModelContract.Plan plan) {
    return PROMPT.equals(plan.promptVersion()) && TAXONOMY.equals(plan.taxonomyVersion());
  }

  /** 1、按当前完整分片取原段；2、把版本内ID映射为局部e1…；3、保留文字/位置/窗口；4、移除全篇元数据。 */
  public static ContentFullContract.Request request(
      ContentAnalysisStore.Frozen input,
      String instruction,
      ModelContract.Plan plan,
      ContentChunkPlan.Chunk chunk) {
    var original = input.fullPlan().select(input.selection(), chunk);
    List<ContentMaterialParser.Segment> local = new ArrayList<>();
    for (var s : original.segments())
      local.add(
          new ContentMaterialParser.Segment(
              "e" + (local.size() + 1),
              s.text(),
              s.startChar(),
              s.endChar(),
              s.startMs(),
              s.endMs()));
    String hash = ReferenceMaterialStore.hash(ModelContract.encode(local));
    var selection =
        new ContentSelection(
            LOCAL_VERSION,
            LOCAL_ID,
            hash,
            hash,
            original.fromMs(),
            original.toMs(),
            local.size(),
            local.size(),
            0,
            0,
            original.selectedChars(),
            List.copyOf(local));
    var m = input.material();
    return new ContentFullContract.Request(
        PROMPT,
        LOCAL_ID,
        m.title(),
        m.format(),
        m.sourceDescription(),
        instruction,
        "MAP",
        chunk.index(),
        selection,
        null,
        plan.inputTokenCeiling() == null ? 0 : plan.inputTokenCeiling(),
        plan.maxOutputTokens());
  }

  /** 只序列化模型真正依赖的数据；执行批号留在网关请求身份中，不发送给模型。 */
  public static Payload payload(ContentFullContract.Request request) {
    return new Payload(
        request.promptVersion(),
        request.title(),
        request.format(),
        request.sourceDescription(),
        request.instruction(),
        request.selection());
  }

  /** 价格/预算/超时也保守纳入身份，配置变化时要求新执行，不宣称已有更宽松语义缓存。 */
  public static String fingerprint(ModelContract.Plan plan, ContentFullContract.Request request) {
    return ReferenceMaterialStore.hash(
        ModelContract.encode(plan) + ModelContract.encode(payload(request)));
  }

  /** v3 MAP输入必须局部化；不允许外部调用者把全篇/版本依赖偷偷带入同一缓存协议。 */
  public static void validateLocal(ContentFullContract.Request r) {
    var s = r.selection();
    if (!LOCAL_ID.equals(r.materialId())
        || !LOCAL_VERSION.equals(s.version())
        || s.totalSegments() != s.segments().size()
        || s.eligibleSegments() != s.segments().size()
        || s.omittedSegments() != 0
        || s.outsideRange() != 0) fail();
    for (int i = 0; i < s.segments().size(); i++)
      if (!("e" + (i + 1)).equals(s.segments().get(i).id())) fail();
    String hash = ReferenceMaterialStore.hash(ModelContract.encode(s.segments()));
    if (!hash.equals(s.materialSha256()) || !hash.equals(s.segmentsSha256())) fail();
  }

  /** 当前原文定位保持不变，只把模型局部引用转回当前版本；旧版本ID不进入新报告。 */
  public static ContentModelContract.Response toOriginal(
      ContentModelContract.Response response, ContentChunkPlan.Chunk chunk) {
    var findings =
        response.findings().stream()
            .map(
                f ->
                    new ContentModelContract.Finding(
                        f.title(),
                        f.kind(),
                        f.explanation(),
                        f.evidenceIds().stream()
                            .map(
                                id -> {
                                  int i = Integer.parseInt(id.substring(1)) - 1;
                                  if (i < 0
                                      || i >= chunk.segmentIds().size()
                                      || !("e" + (i + 1)).equals(id)) fail();
                                  return chunk.segmentIds().get(i);
                                })
                            .toList()))
            .toList();
    return new ContentModelContract.Response(
        findings,
        response.uncertainties(),
        response.inputTokens(),
        response.outputTokens(),
        response.requestId());
  }

  /** 每次恢复重新核对指纹/响应哈希/协议，冻结清单不能靠一个reused数字冒充缓存。 */
  public static void verify(
      ContentAnalysisStore.Frozen frozen, String instruction, ModelContract.Plan model) {
    var plan = frozen.reusePlan();
    if (!enabled(model)) {
      if (plan != null) throw new IllegalStateException("Unexpected content reuse manifest");
      return;
    }
    if (plan == null
        || !VERSION.equals(plan.version())
        || frozen.fullPlan() == null
        || plan.entries() == null
        || plan.entries().size() != frozen.fullPlan().chunks().size())
      throw new IllegalStateException("Missing content reuse manifest");
    for (var chunk : frozen.fullPlan().chunks()) {
      var entry = plan.entries().get(chunk.index());
      var request = request(frozen, instruction, model, chunk);
      if (entry == null
          || entry.index() != chunk.index()
          || !fingerprint(model, request).equals(entry.inputHash()))
        throw new IllegalStateException("Content reuse fingerprint mismatch");
      if (entry.cached() != null) {
        var cached = entry.cached();
        if ("MOCK".equals(model.reportMode())
            || plan.baselineJobId() == null
            || cached.sourceJobId() == null
            || cached.sourceBatchIndex() < 0
            || cached.sourceBatchIndex() >= 24
            || cached.response() == null
            || !ReferenceMaterialStore.hash(ModelContract.encode(cached.response()))
                .equals(cached.responseHash()))
          throw new IllegalStateException("Content reused response hash mismatch");
        ContentFullContract.validate(request, cached.response());
      }
    }
  }

  /** 报告展示原收费出处，基准可能也复用了更早版本，不能把它标为本次新调用。 */
  public static Receipt receipt(Plan plan) {
    return new Receipt(
        plan.baselineJobId(),
        plan.reused(),
        plan.entries().stream()
            .filter(e -> e.cached() != null)
            .map(
                e ->
                    new Origin(
                        e.index(),
                        e.cached().sourceJobId(),
                        e.cached().sourceBatchIndex(),
                        e.cached().responseHash()))
            .toList());
  }

  /** 发送前边界错误不允许初始化费用账本。 */
  private static void fail() {
    throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
  }
}
