/* 文件职责：验证完整范围分片的唯一归属、稳定顺序、父语境和容量拒绝。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.model.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 只验证算法和工程契约；合成标签不作为模型质量或生产容量成绩。 */
class StanceChunkPlannerTest {
  private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-10-18T00:00:00Z");

  /** 创建普通JSON同形的不可变资料，不调用平台。 */
  private ImportDocument document(int count, boolean chain) {
    List<ImportDocument.Comment> comments = new ArrayList<>();
    for (int n = 0; n < count; n++)
      comments.add(
          new ImportDocument.Comment(
              "c" + n,
              String.format("s%05d", n),
              n == 0 ? null : chain ? "c" + (n - 1) : "c0",
              "合成评论" + n,
              TIME));
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("content", "SYNTHETIC", "x", "x", "分片合成", TIME),
        comments,
        List.of());
  }

  /** 25条拆三片，根评论重复作语境两次，但每条只归属一个目标。 */
  @Test
  void eachTargetHasSingleOwnerAndRepeatedParentIsOnlyContext() {
    var manifest = StanceChunkPlanner.plan("s", document(25, false));
    assertThat(manifest.chunks()).hasSize(3);
    assertThat(manifest.contextOccurrences()).isEqualTo(2);
    var targets = manifest.chunks().stream().flatMap(c -> c.targetIds().stream()).toList();
    assertThat(targets).hasSize(25).doesNotHaveDuplicates();
    for (var chunk : manifest.chunks()) {
      assertThat(chunk.targetIds().size()).isLessThanOrEqualTo(12);
      assertThat(chunk.inputIds()).contains("c0").containsAll(chunk.targetIds());
    }
    assertThat(manifest).isEqualTo(StanceChunkPlanner.plan("s", document(25, false)));
  }

  /** 5000目标仅验证分配不丢不重，不宣称5000条已通过真实模型质量/性能验收。 */
  @Test
  void maximumSnapshotPartitionsWithoutDuplicateDenominator() {
    var manifest = StanceChunkPlanner.plan("s", document(5000, false));
    assertThat(manifest.targetComments()).isEqualTo(5000);
    assertThat(manifest.chunks()).hasSize(417);
    assertThat(manifest.chunks().stream().mapToInt(c -> c.targetIds().size()).sum())
        .isEqualTo(5000);
    assertThat(manifest.chunks().stream().flatMap(c -> c.targetIds().stream()).distinct().count())
        .isEqualTo(5000);
  }

  /** 单节点祖先闭包超120条时整个完整计划拒绝，不截断父语境。 */
  @Test
  void unsplittableContextCannotMasqueradeAsFull() {
    assertThatThrownBy(() -> StanceChunkPlanner.plan("s", document(121, true)))
        .hasMessage("FULL_CONTEXT_TOO_LARGE");
  }

  /** 原始缺口可用于有限预览，但不能承诺完整判断。 */
  @Test
  void declaredMissingParentRefusesFullScope() {
    var input = document(2, false);
    var rows =
        List.of(
            input.comments().getFirst(),
            new ImportDocument.Comment("gap", "g", "absent", "缺上下文", TIME));
    var gap =
        new ImportDocument(
            input.schemaVersion(),
            input.content(),
            rows,
            List.of(),
            List.of(new ImportDocument.MissingParent("gap", "absent")));
    assertThatThrownBy(() -> StanceChunkPlanner.plan("s", gap)).hasMessage("FULL_CONTEXT_MISSING");
    var onlyGap =
        new ImportDocument(
            input.schemaVersion(),
            input.content(),
            List.of(rows.get(1)),
            List.of(),
            gap.missingParents());
    assertThatThrownBy(() -> StanceChunkPlanner.plan("s", onlyGap))
        .hasMessage("FULL_CONTEXT_MISSING");
  }

  /** 命题正文中的“所有”不代表操作范围；明确全部评论才自动使用完整范围。 */
  @Test
  void scopeWordsAreBusinessOperationsNotPropositionText() {
    assertThat(AnalysisScopeStore.full("围绕命题「所有评论都合理」分析立场", null)).isFalse();
    assertThat(AnalysisScopeStore.full("分析全部评论对命题「剧情合理」的立场", null)).isTrue();
    assertThat(AnalysisScopeStore.full("分析全部评论", "PREVIEW")).isFalse();
    assertThatThrownBy(() -> AnalysisScopeStore.full("分析", "OTHER"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** v6只覆盖归属目标，父语境不得多返回一条决策而扩大分母。 */
  @Test
  void contextCannotReturnExtraDecisions() {
    var spec = new AnalysisSpecification(AnalysisSpecification.VERSION, "命题", "USER_CLARIFICATION");
    var request =
        new DiscussionModelContract.Request(
            "s",
            "分析立场",
            "x",
            "SYNTHETIC",
            false,
            2,
            List.of(
                new DiscussionModelContract.Text("a", null, "父评论"),
                new DiscussionModelContract.Text("b", "a", "回复")),
            128000,
            4096,
            List.of(),
            StanceChunkPlanner.PROMPT,
            spec,
            List.of("b"),
            1);
    var target =
        new StanceProtocol.Decision("b", StanceProtocol.Coverage.UNKNOWN, "语境不足", List.of());
    StanceProtocol.validate(request, List.of(target));
    assertThatThrownBy(
            () ->
                StanceProtocol.validate(
                    request,
                    List.of(
                        target,
                        new StanceProtocol.Decision(
                            "a", StanceProtocol.Coverage.UNKNOWN, "语境不足", List.of()))))
        .hasMessage("MODEL_STANCE_INVALID");
  }

  /** 旧v5请求省略新字段，命题空白语法及原检查点兼容。 */
  @Test
  void oldV5JsonDoesNotGrowPartitionFields() {
    var spec = AnalysisSpecification.resolve("命题 「剧情合理」分析立场", null).specification();
    assertThat(spec.proposition()).isEqualTo("剧情合理");
    var input =
        new DiscussionModelContract.Request(
            "s",
            "分析立场",
            "x",
            "SYNTHETIC",
            false,
            1,
            List.of(new DiscussionModelContract.Text("a", null, "合成")),
            128000,
            4096,
            List.of(),
            StanceProtocol.VERSION,
            spec);
    assertThat(ModelContract.encode(input)).doesNotContain("targetCommentIds", "batchIndex");
  }

  /** v6生产适配器只要求归属目标输出，父语境留在用户数据；严格解析和模型身份仍生效。 */
  @Test
  void productionProviderRecognizesPartitionedProtocol() {
    var spec =
        new AnalysisSpecification(AnalysisSpecification.VERSION, "剧情合理", "USER_CLARIFICATION");
    var input =
        new DiscussionModelContract.Request(
            "s",
            "分析全部评论",
            "合成",
            "SYNTHETIC",
            false,
            2,
            List.of(
                new DiscussionModelContract.Text("a", null, "父评论"),
                new DiscussionModelContract.Text("b", "a", "回复")),
            128000,
            4096,
            List.of(),
            StanceChunkPlanner.PROMPT,
            spec,
            List.of("b"),
            1);
    var prompt = io.echoinsight.model.springai.DiscussionPrompt.build(input, "local");
    assertThat(prompt.getInstructions().getFirst().getText())
        .contains("只对targetCommentIds", "仅供理解与引用");
    assertThat(prompt.getInstructions().getLast().getText())
        .contains("targetCommentIds", "batchIndex", "父评论");
    var provider =
        new io.echoinsight.model.springai.SpringAiDiscussionProvider(
            ignored ->
                new org.springframework.ai.chat.model.ChatResponse(
                    List.of(
                        new org.springframework.ai.chat.model.Generation(
                            new org.springframework.ai.chat.messages.AssistantMessage(
                                "{\"decisions\":[{\"commentId\":\"b\",\"coverage\":\"UNKNOWN\",\"reason\":\"语境不足\",\"claims\":[]"
                                    + " }]}"),
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason("stop")
                                .build())),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .id("local")
                        .model("local")
                        .usage(
                            new org.springframework.ai.chat.metadata.DefaultUsage(
                                10,
                                2,
                                12,
                                new org.springframework.ai.openai.api.OpenAiApi.Usage(2, 10, 12)))
                        .build()),
            "local");
    provider.validatePlan(
        new ModelContract.Plan(
            "SPRING_AI",
            "local",
            StanceChunkPlanner.PROMPT,
            StanceChunkPlanner.TAXONOMY,
            12,
            3000,
            4096,
            "USD_NANOS",
            "synthetic",
            1,
            1,
            1000000,
            128000,
            "local",
            "MODEL"));
    var result = provider.analyze(input, java.time.Duration.ofSeconds(2));
    StanceProtocol.validate(input, result.decisions());
    assertThat(result.decisions()).hasSize(1);
  }
}
