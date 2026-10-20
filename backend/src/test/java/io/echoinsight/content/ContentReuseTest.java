/* 文件职责：验证跨版本复用的局部依赖、引用重映射和冻结凭证防损坏，不访问模型或数据库。 */
package io.echoinsight.content;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.model.ModelContract;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 同字数、同模型名不足以复用；实际局部输入和完整模型计划必须一致。 */
class ContentReuseTest {
  /** 全局版本、原段ID、总片数和执行片号不属于模型局部输入。 */
  @Test
  void ignoresOnlyDependenciesRemovedFromActualModelPayload() {
    var a = frozen("a", "原段1", "原文", 0, null, null, "标题", "声明", false);
    var b = frozen("b", "新版原段", "原文", 0, null, null, "标题", "声明", true);
    var first = request(a, "总结", plan(100000));
    var second = request(b, "总结", plan(100000));
    assertThat(ContentReuse.fingerprint(plan(100000), first))
        .isEqualTo(ContentReuse.fingerprint(plan(100000), second));
    assertThat(ModelContract.encode(ContentReuse.payload(first)))
        .doesNotContain("原段1", "batchIndex", "summaries", "maxInputTokens")
        .contains("e1", "local-content", "原文");
    assertThat(second.batchIndex()).isEqualTo(1);
    ContentFullContract.validateRequest(plan(100000), first);
    ContentFullContract.validateRequest(plan(100000), second);
  }

  /** 原文、位置、窗口、来源或标题任何变化都使该片重新执行。 */
  @Test
  void invalidatesEveryVisibleLocalDependency() {
    var original = frozen("a", "s", "原文", 0, null, null, "标题", "声明", false);
    String hash = ContentReuse.fingerprint(plan(100000), request(original, "总结", plan(100000)));
    for (var changed :
        List.of(
            frozen("a", "s", "修改", 0, null, null, "标题", "声明", false),
            frozen("a", "s", "原文", 1, null, null, "标题", "声明", false),
            frozen("a", "s", "原文", 0, 1L, 3L, "标题", "声明", false),
            frozen("a", "s", "原文", 0, null, null, "改标题", "声明", false),
            frozen("a", "s", "原文", 0, null, null, "标题", "改声明", false)))
      assertThat(ContentReuse.fingerprint(plan(100000), request(changed, "总结", plan(100000))))
          .isNotEqualTo(hash);
    assertThat(ContentReuse.fingerprint(plan(100000), request(original, "比较", plan(100000))))
        .isNotEqualTo(hash);
  }

  /** 预算也纳入保守身份，计划变化不能沿用已有资格。 */
  @Test
  void includesCompleteModelPlanInFingerprint() {
    var f = frozen("a", "s", "原文", 0, null, null, "标题", "声明", false);
    assertThat(ContentReuse.fingerprint(plan(100000), request(f, "总结", plan(100000))))
        .isNotEqualTo(ContentReuse.fingerprint(plan(100001), request(f, "总结", plan(100001))));
  }

  /** 字幕的原始时间同样是模型依赖，不把相同台词的不同位置误认为同片。 */
  @Test
  void includesSubtitleTiming() {
    var f = frozen("a", "s", "原文", 0, null, null, "标题", "声明", false);
    var original = request(f, "总结", plan(100000));
    var s = original.selection();
    var timed = new ContentMaterialParser.Segment("e1", "原文", null, null, 1000L, 2000L);
    var altered =
        selection(
            s, List.of(timed), ReferenceMaterialStore.hash(ModelContract.encode(List.of(timed))));
    assertThat(ContentReuse.fingerprint(plan(100000), withSelection(original, altered)))
        .isNotEqualTo(ContentReuse.fingerprint(plan(100000), original));
  }

  /** 局部引用转回当前版本身份，旧版本ID不能进入新产物。 */
  @Test
  void remapsCanonicalEvidenceToCurrentOriginalIds() {
    var mapped =
        ContentReuse.toOriginal(
            response("e1"), new ContentChunkPlan.Chunk(0, List.of("new-source"), 2));
    assertThat(mapped.findings().getFirst().evidenceIds()).containsExactly("new-source");
    assertThat(mapped.inputTokens()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                ContentReuse.toOriginal(
                    response("e2"), new ContentChunkPlan.Chunk(0, List.of("s"), 2)))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 局部哈希损坏，即使JSON结构完整也不能进入收费网关。 */
  @Test
  void rejectsCorruptLocalHash() {
    var r = request(frozen("a", "s", "原文", 0, null, null, "标题", "声明", false), "总结", plan(100000));
    assertThatThrownBy(
            () ->
                ContentFullContract.validateRequest(
                    plan(100000),
                    withSelection(r, selection(r.selection(), r.selection().segments(), "broken"))))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 任意非规范别名都不能隐式转换成另一个证据ID。 */
  @Test
  void rejectsNonCanonicalEvidenceAliases() {
    var r = request(frozen("a", "s", "原文", 0, null, null, "标题", "声明", false), "总结", plan(100000));
    var aliases = List.of(new ContentMaterialParser.Segment("e01", "原文", 0, 2, null, null));
    var s =
        selection(
            r.selection(), aliases, ReferenceMaterialStore.hash(ModelContract.encode(aliases)));
    assertThatThrownBy(() -> ContentFullContract.validateRequest(plan(100000), withSelection(r, s)))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** v3恢复必须有完整清单，旧任务缺字段则仍由旧协议解释。 */
  @Test
  void rejectsMissingOrWrongManifestFingerprint() {
    var f = frozen("a", "s", "原文", 0, null, null, "标题", "声明", false);
    assertThatThrownBy(() -> ContentReuse.verify(f, "总结", plan(100000)))
        .hasMessageContaining("Missing content reuse manifest");
    var broken =
        new ContentAnalysisStore.Frozen(
            f.material(),
            f.selection(),
            f.fullPlan(),
            new ContentReuse.Plan(
                ContentReuse.VERSION, null, List.of(new ContentReuse.Entry(0, "bad", null))));
    assertThatThrownBy(() -> ContentReuse.verify(broken, "总结", plan(100000)))
        .hasMessageContaining("fingerprint mismatch");
  }

  /** 响应哈希验证保留原收费来源，不允许通过单个计数伪造复用。 */
  @Test
  void verifiesResponseHashAndReceiptOrigin() {
    var f = frozen("a", "s", "原文", 0, null, null, "标题", "声明", false);
    var r = request(f, "总结", plan(100000));
    var cached =
        new ContentReuse.Cached(
            "original-job",
            2,
            ReferenceMaterialStore.hash(ModelContract.encode(response("e1"))),
            response("e1"));
    var reuse =
        new ContentReuse.Plan(
            ContentReuse.VERSION,
            "baseline-job",
            List.of(new ContentReuse.Entry(0, ContentReuse.fingerprint(plan(100000), r), cached)));
    ContentReuse.verify(
        new ContentAnalysisStore.Frozen(f.material(), f.selection(), f.fullPlan(), reuse),
        "总结",
        plan(100000));
    assertThat(ContentReuse.receipt(reuse).origins().getFirst().sourceJobId())
        .isEqualTo("original-job");
    var bad =
        new ContentReuse.Plan(
            ContentReuse.VERSION,
            "baseline-job",
            List.of(
                new ContentReuse.Entry(
                    0,
                    ContentReuse.fingerprint(plan(100000), r),
                    new ContentReuse.Cached("original-job", 2, "broken", response("e1")))));
    assertThatThrownBy(
            () ->
                ContentReuse.verify(
                    new ContentAnalysisStore.Frozen(f.material(), f.selection(), f.fullPlan(), bad),
                    "总结",
                    plan(100000)))
        .hasMessageContaining("response hash mismatch");
  }

  /** 模型版本和输出体系必须成对匹配，不接受混合协议。 */
  @Test
  void distinguishesLegacyV2AndV3Pair() {
    var p = plan(100000);
    var mixed =
        new ModelContract.Plan(
            p.providerId(),
            p.modelVersion(),
            p.promptVersion(),
            ContentFullContract.TAXONOMY,
            p.batchSize(),
            p.timeoutMs(),
            p.maxOutputTokens(),
            p.unit(),
            p.priceVersion(),
            p.inputUnitRate(),
            p.outputUnitRate(),
            p.budgetLimit(),
            p.inputTokenCeiling(),
            p.responseModel(),
            p.executionMode());
    assertThat(ContentFullContract.supports(p)).isTrue();
    assertThat(ContentFullContract.supports(mixed)).isFalse();
  }

  /** 构造位置稳定的原段，额外首片模拟全局片号与总片数变化。 */
  private ContentAnalysisStore.Frozen frozen(
      String id,
      String segmentId,
      String text,
      int start,
      Long from,
      Long to,
      String title,
      String source,
      boolean extra) {
    List<ContentMaterialParser.Segment> segments = new ArrayList<>();
    if (extra)
      segments.add(
          new ContentMaterialParser.Segment("another", "字".repeat(12000), 100, 12100, null, null));
    segments.add(
        new ContentMaterialParser.Segment(
            segmentId, text, start, start + text.length(), null, null));
    var material =
        new ContentMaterialStore.Summary(
            id,
            "p",
            null,
            1,
            title,
            source,
            "TXT",
            segments.size(),
            segments.stream().mapToInt(x -> x.text().length()).sum(),
            "a",
            "b",
            "fixture");
    var selection =
        new ContentSelection(
            "content-full-window-v1",
            id,
            "a",
            "b",
            from,
            to,
            segments.size(),
            segments.size(),
            0,
            0,
            material.textChars(),
            segments);
    return new ContentAnalysisStore.Frozen(material, selection, ContentChunkPlan.create(selection));
  }

  /** 本测试总是检查最后一片。 */
  private ContentFullContract.Request request(
      ContentAnalysisStore.Frozen f, String question, ModelContract.Plan p) {
    return ContentReuse.request(f, question, p, f.fullPlan().chunks().getLast());
  }

  /** 只改局部选择供边界测试。 */
  private ContentFullContract.Request withSelection(
      ContentFullContract.Request r, ContentSelection s) {
    return new ContentFullContract.Request(
        r.promptVersion(),
        r.materialId(),
        r.title(),
        r.format(),
        r.sourceDescription(),
        r.instruction(),
        r.phase(),
        r.batchIndex(),
        s,
        null,
        r.maxInputTokens(),
        r.maxOutputTokens());
  }

  /** 只替换原段和局部哈希。 */
  private ContentSelection selection(
      ContentSelection s, List<ContentMaterialParser.Segment> segments, String hash) {
    return new ContentSelection(
        s.version(),
        s.materialId(),
        hash,
        hash,
        s.fromMs(),
        s.toMs(),
        s.totalSegments(),
        s.eligibleSegments(),
        0,
        0,
        s.selectedChars(),
        segments);
  }

  /** 合法短摘要只为工程协议测试，不代表独立标注。 */
  private ContentModelContract.Response response(String id) {
    return new ContentModelContract.Response(
        List.of(
            new ContentModelContract.Finding(
                "归纳", ContentModelContract.Kind.OBSERVATION, "工程夹具", List.of(id))),
        List.of(),
        2,
        3,
        "fixture");
  }

  /** 无外部供应商，固定模拟费率。 */
  private ModelContract.Plan plan(long budget) {
    return new ModelContract.Plan(
        "REUSE_FIXTURE",
        "fixture",
        ContentReuse.PROMPT,
        ContentReuse.TAXONOMY,
        100,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        budget,
        10000,
        "fixture",
        "LOCAL_STUB");
  }
}
