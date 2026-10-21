/* 文件职责：验证普通讨论归并的唯一覆盖、父语境、协议、局部依赖和有界内容背景选择。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.content.*;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.material.SubtitleParser;
import io.echoinsight.model.*;
import io.echoinsight.model.springai.DiscussionDigestPrompt;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 合成测试不调用网络或模型，输入覆盖与引用存在不作为语义质量证明。 */
class DiscussionDigestTest {
  /** 5000短评论全部唯一归属，125片经16＋2＋1次归并，不截断尾组。 */
  @Test
  void coversFiveThousandTargetsAndAllReductionChildren() {
    var scope = DiscussionDigestPlan.plan("s", document(5000, "raw", false));
    assertThat(scope.chunks()).hasSize(125);
    assertThat(scope.chunks().stream().flatMap(c -> c.targetIds().stream()).distinct().count())
        .isEqualTo(5000);
    var reductions = DiscussionDigestPlan.reductions(125);
    assertThat(reductions).hasSize(19);
    Set<Integer> children = new HashSet<>();
    for (var node : reductions) {
      assertThat(node.children()).hasSizeLessThanOrEqualTo(8);
      assertThat(node.children()).allMatch(c -> c < node.index());
      children.addAll(node.children());
    }
    assertThat(children).hasSize(143);
    assertThat(reductions.getLast().phase()).isEqualTo("FINAL");
  }

  /** 一片仍需要独立最终汇总，不虚构零调用完成。 */
  @Test
  void singleMapStillHasFinalSynthesis() {
    assertThat(DiscussionDigestPlan.reductions(1))
        .containsExactly(new DiscussionDigestPlan.Node(1, "FINAL", List.of(0)));
    assertThatThrownBy(() -> DiscussionDigestPlan.reductions(513))
        .hasMessageContaining("DIGEST_CAPACITY_EXCEEDED");
  }

  /** 回复跨片时保留父原文，每个目标仍只归属一次。 */
  @Test
  void preservesParentsWithoutCountingThemTwice() {
    var input = document(100, "raw", true);
    var scope = DiscussionDigestPlan.plan("s", input);
    Map<String, ImportDocument.Comment> all = new HashMap<>();
    input.comments().forEach(c -> all.put(c.id(), c));
    for (var chunk : scope.chunks())
      for (String id : chunk.targetIds()) {
        var comment = all.get(id);
        assertThat(chunk.inputIds()).contains(id);
        if (comment.parentId() != null) assertThat(chunk.inputIds()).contains(comment.parentId());
      }
    assertThat(scope.chunks().stream().flatMap(c -> c.targetIds().stream()).toList())
        .hasSize(100)
        .doesNotHaveDuplicates();
    assertThat(scope.contextOccurrences()).isPositive();
  }

  /** 缺父范围不能通过跳过断边伪装完整。 */
  @Test
  void refusesMissingParentForFullScope() {
    var d = document(1, "r", false);
    var c = new ImportDocument.Comment("c", "source", "missing", "原文", OffsetDateTime.now());
    var broken =
        new ImportDocument(
            d.schemaVersion(),
            d.content(),
            List.of(c),
            List.of(),
            List.of(new ImportDocument.MissingParent("c", "missing")));
    assertThatThrownBy(() -> DiscussionDigestPlan.plan("s", broken))
        .hasMessageContaining("FULL_CONTEXT_MISSING");
  }

  /** 旧命题规划仍12目标，新普通讨论40目标且策略身份不同。 */
  @Test
  void keepsLegacyStancePartitionUnchanged() {
    var d = document(41, "r", false);
    assertThat(StanceChunkPlanner.plan("s", d).chunks()).hasSize(4);
    assertThat(DiscussionDigestPlan.plan("s", d).chunks()).hasSize(2);
    assertThat(StanceChunkPlanner.plan("s", d).version()).isEqualTo(StanceChunkPlanner.VERSION);
  }

  /** 全局快照/总数/原ID变化不进入局部模型Payload，追加不会无故使前片失效。 */
  @Test
  void fingerprintUsesActualLocalPayloadAndFullPlan() {
    var a = document(40, "first", false);
    var b = document(41, "second", false);
    var p = plan();
    var ma = DiscussionDigestPlan.plan("old", a);
    var mb = DiscussionDigestPlan.plan("new", b);
    var r = map(a, ma, 0);
    var next = map(b, mb, 0);
    assertThat(DiscussionDigestReuse.fingerprint(p, r))
        .isEqualTo(DiscussionDigestReuse.fingerprint(p, next));
    assertThat(ModelContract.encode(DiscussionDigestContract.localPayload(r)))
        .doesNotContain("snapshotId", "totalComments", "batchIndex", "first");
    var changed =
        new DiscussionDigestContract.Request(
            r.promptVersion(),
            r.snapshotId(),
            "总结新问题",
            r.title(),
            r.sourceType(),
            r.includeAssessment(),
            r.totalComments(),
            r.batchIndex(),
            r.phase(),
            r.comments(),
            r.targetCommentIds(),
            r.context(),
            r.summaries(),
            r.maxInputTokens(),
            r.maxOutputTokens());
    assertThat(DiscussionDigestReuse.fingerprint(p, changed))
        .isNotEqualTo(DiscussionDigestReuse.fingerprint(p, r));
  }

  /** 相同原文在父语境和归属目标之间变换，模型依赖不同，不能复用。 */
  @Test
  void fingerprintIncludesTargetRoles() {
    var d = document(2, "r", false);
    var r = map(d, DiscussionDigestPlan.plan("s", d), 0);
    var changed =
        new DiscussionDigestContract.Request(
            r.promptVersion(),
            r.snapshotId(),
            r.instruction(),
            r.title(),
            r.sourceType(),
            r.includeAssessment(),
            r.totalComments(),
            r.batchIndex(),
            r.phase(),
            r.comments(),
            List.of("c1"),
            r.context(),
            r.summaries(),
            r.maxInputTokens(),
            r.maxOutputTokens());
    assertThat(DiscussionDigestReuse.fingerprint(plan(), r))
        .isNotEqualTo(DiscussionDigestReuse.fingerprint(plan(), changed));
  }

  /** MAP局部引用可以准确映射到当前快照全局别名。 */
  @Test
  void remapsLocalEvidenceIntoCurrentSnapshot() {
    var d = document(41, "r", false);
    var m = DiscussionDigestPlan.plan("s", d);
    var local =
        FullDiscussionAnalyzer.mapInput(
            d, m, m.chunks().getLast(), AnalysisContextStore.View.none(), plan(), "总结所有评论", false);
    var response =
        FullDiscussionAnalyzer.mapEvidence(
            response("c1"), id -> local.localToCurrent().getOrDefault(id, id));
    assertThat(response.issues().getFirst().evidenceIds()).containsExactly("comment-41");
  }

  /** 每项必须有评论引用，单独背景不能证明观众表达。 */
  @Test
  void rejectsBackgroundOnlyClaimsAndInventedEvidence() {
    var d = document(1, "r", false);
    var r = map(d, DiscussionDigestPlan.plan("s", d), 0);
    assertThatThrownBy(() -> DiscussionDigestContract.validate(r, response("not-present")))
        .hasMessageContaining("MODEL_EVIDENCE_INVALID");
    var context =
        new DiscussionModelContract.ContextText(
            "context-ref", "REFERENCE_TEXT", "背景", "声明", null, null, "原文");
    var with =
        new DiscussionDigestContract.Request(
            r.promptVersion(),
            r.snapshotId(),
            r.instruction(),
            r.title(),
            r.sourceType(),
            false,
            1,
            0,
            "MAP",
            r.comments(),
            r.targetCommentIds(),
            List.of(context),
            List.of(),
            10000,
            4096);
    assertThatThrownBy(() -> DiscussionDigestContract.validate(with, response("context-ref")))
        .hasMessageContaining("MODEL_EVIDENCE_INVALID");
  }

  /** MAP请求不能携带未来节点或未规范的证据别名。 */
  @Test
  void rejectsNonCanonicalAliasesAndFutureReductionChildren() {
    var d = document(1, "r", false);
    var r = map(d, DiscussionDigestPlan.plan("s", d), 0);
    var wrong =
        new DiscussionDigestContract.Request(
            r.promptVersion(),
            r.snapshotId(),
            r.instruction(),
            r.title(),
            r.sourceType(),
            false,
            1,
            0,
            "MAP",
            List.of(new DiscussionModelContract.Text("c01", null, "原文")),
            List.of("c01"),
            List.of(),
            List.of(),
            10000,
            4096);
    assertThatThrownBy(() -> DiscussionDigestContract.validateRequest(plan(), wrong))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
    var future =
        new DiscussionDigestContract.Request(
            r.promptVersion(),
            r.snapshotId(),
            r.instruction(),
            r.title(),
            r.sourceType(),
            false,
            1,
            1,
            "FINAL",
            List.of(),
            List.of(),
            List.of(),
            List.of(DiscussionDigestContract.summary(1, response("comment-1"))),
            10000,
            4096);
    assertThatThrownBy(() -> DiscussionDigestContract.validateRequest(plan(), future))
        .hasMessageContaining("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 压缩字段有硬上限，不能用无限摘要挤爆后续归并。 */
  @Test
  void enforcesCompactSummaries() {
    var d = document(1, "r", false);
    var r = map(d, DiscussionDigestPlan.plan("s", d), 0);
    var tooLong =
        new DiscussionModelContract.Response(
            List.of(new DiscussionModelContract.Issue("i", "议题", "长".repeat(321), List.of("c1"))),
            List.of(),
            List.of(),
            List.of(),
            2,
            3,
            "fixture");
    assertThatThrownBy(() -> DiscussionDigestContract.validate(r, tooLong))
        .hasMessageContaining("MODEL_ISSUE_INVALID");
  }

  /** 中间归并不能创造未在子摘要出现的评论引用。 */
  @Test
  void reductionEvidenceIsClosedOverChildren() {
    var r =
        new DiscussionDigestContract.Request(
            DiscussionDigestPlan.PROMPT,
            "s",
            "总结",
            "合成",
            "SYNTHETIC",
            false,
            40,
            1,
            "FINAL",
            List.of(),
            List.of(),
            List.of(),
            List.of(DiscussionDigestContract.summary(0, response("comment-1"))),
            10000,
            4096);
    DiscussionDigestContract.validateRequest(plan(), r);
    assertThatThrownBy(() -> DiscussionDigestContract.validate(r, response("comment-2")))
        .hasMessageContaining("MODEL_EVIDENCE_INVALID");
  }

  /** 提示实际只发送局部依赖，数据中注入命令不能进入系统规则或开启工具。 */
  @Test
  void promptKeepsPayloadSeparateFromRules() {
    var d = document(1, "r", false);
    var r = map(d, DiscussionDigestPlan.plan("s", d), 0);
    var prompt = DiscussionDigestPrompt.build(r, "fixture");
    assertThat(prompt.getInstructions().getLast().getText())
        .isEqualTo(ModelContract.encode(DiscussionDigestContract.localPayload(r)));
    assertThat(
            ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions())
                .getInternalToolExecutionEnabled())
        .isFalse();
  }

  /** 小材料全选，保留UTF16偏移与原文，不为检索而丢失短上下文。 */
  @Test
  void smallContentIsSelectedCompletely() {
    var m = material("短内容😀原文");
    var r = ContentEvidenceSelector.select(m, "总结", null, null);
    assertThat(r.strategy()).isEqualTo("CONTENT_COMPLETE_SMALL");
    assertThat(r.selectedChars()).isEqualTo(7);
    assertThat(r.segments().getFirst().text()).isEqualTo("短内容😀原文");
  }

  /** 大材料命中尾部时带邻段，不再自动只读前缀。 */
  @Test
  void lexicalEvidenceCanReachTailWithOriginalLocations() {
    var m = material("前".repeat(18000) + "世界线因果反例" + "尾".repeat(1000));
    var r = ContentEvidenceSelector.select(m, "总结世界线因果讨论", null, null);
    assertThat(r.matchedSegments()).isPositive();
    assertThat(r.selectedChars()).isLessThanOrEqualTo(8000);
    assertThat(r.segments())
        .anySatisfy(
            x -> {
              assertThat(x.text()).contains("世界线因果反例");
              assertThat(x.startChar()).isEqualTo(18000);
            });
  }

  /** 没有命中明确空缺，不伪造语义相关片或自动补前缀。 */
  @Test
  void unmatchedLargeContentRemainsExplicitlyEmpty() {
    var r = ContentEvidenceSelector.select(material("原".repeat(20000)), "总结所有评论", null, null);
    assertThat(r.segments()).isEmpty();
    assertThat(r.matchedSegments()).isZero();
    assertThat(r.notes()).anyMatch(n -> n.contains("没有可纳入"));
  }

  /** 旧DTO缺字符定位仍保持原JSON形状，不破坏旧检查点指纹。 */
  @Test
  void oldContextEncodingOmitsNewNullableFields() throws Exception {
    var old =
        new DiscussionModelContract.ContextText(
            "context-ref", "REFERENCE_TEXT", "标题", "声明", null, null, "原文");
    assertThat(new ObjectMapper().writeValueAsString(old)).doesNotContain("startChar", "endChar");
    var source =
        new AnalysisContextStore.Source(
            "context-ref",
            AnalysisContextStore.Role.REFERENCE_TEXT,
            "m",
            "标题",
            "声明",
            null,
            null,
            null,
            "原文");
    assertThat(new ObjectMapper().writeValueAsString(source))
        .doesNotContain("startChar", "endChar");
  }

  /** 使用实际TXT解析，不伪造字符位置。 */
  private ContentMaterialStore.Material material(String text) {
    var parsed =
        new ContentMaterialParser(new SubtitleParser())
            .parse("TXT", text.getBytes(StandardCharsets.UTF_8));
    return new ContentMaterialStore.Material(
        new ContentMaterialStore.Summary(
            "m",
            "p",
            null,
            1,
            "标题",
            "声明",
            "TXT",
            parsed.segments().size(),
            text.length(),
            "a",
            "b",
            "fixture"),
        parsed.segments());
  }

  /** 统一构造MAP，背景与问题固定。 */
  private DiscussionDigestContract.Request map(
      ImportDocument d, StanceChunkPlanner.Manifest m, int index) {
    return FullDiscussionAnalyzer.mapInput(
            d, m, m.chunks().get(index), AnalysisContextStore.View.none(), plan(), "总结所有评论", false)
        .request();
  }

  /** 合法最小图，非语义标注。 */
  private DiscussionModelContract.Response response(String id) {
    return new DiscussionModelContract.Response(
        List.of(new DiscussionModelContract.Issue("i", "议题", "工程归纳", List.of(id))),
        List.of(
            new DiscussionModelContract.Claim(
                "c", "i", "工程主张", DiscussionModelContract.ReasoningStatus.REASONED, List.of(id))),
        List.of(),
        List.of(),
        2,
        3,
        "fixture");
  }

  /** 仅模拟费用。 */
  private ModelContract.Plan plan() {
    return new ModelContract.Plan(
        "DIGEST_FIXTURE",
        "fixture",
        DiscussionDigestPlan.PROMPT,
        DiscussionDigestPlan.TAXONOMY,
        40,
        5000,
        4096,
        "SIMULATED_UNITS",
        "fixture",
        1,
        1,
        1000000000,
        10000,
        "fixture",
        "LOCAL_STUB");
  }

  /** 稳定来源排序确保追加输入不会改变前片目标。 */
  private ImportDocument document(int count, String prefix, boolean replies) {
    var time = OffsetDateTime.parse("2026-10-20T00:00:00Z");
    List<ImportDocument.Comment> comments = new ArrayList<>();
    for (int n = 0; n < count; n++)
      comments.add(
          new ImportDocument.Comment(
              prefix + n,
              String.format("source%05d", n),
              replies && n > 0 ? prefix + 0 : null,
              "论点" + n,
              time));
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("work", "SYNTHETIC", "work", "creator", "合成", time),
        comments,
        List.of());
  }
}
