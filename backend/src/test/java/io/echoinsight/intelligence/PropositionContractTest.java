/* 文件职责：验证用户命题边界、多主张覆盖、引用、统计与旧版本JSON兼容。 */
package io.echoinsight.intelligence;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 纯内存合成测试，不调用任何模型或平台，不把断言当作语义准确率。 */
class PropositionContractTest {
  /** 普通讨论无需目标，含糊立场须澄清，明确引用或补充才获得规格。 */
  @Test
  void targetMustComeFromUser() {
    assertThat(AnalysisSpecification.resolve("总结讨论的逻辑", null).stanceRequested()).isFalse();
    assertThat(AnalysisSpecification.resolve("哪些评论支持这个观点", null).needsProposition()).isTrue();
    assertThat(
            AnalysisSpecification.resolve("围绕命题「剧情因果自洽」分析支持反对", null).specification().proposition())
        .isEqualTo("剧情因果自洽");
    assertThat(AnalysisSpecification.resolve("分析立场", "剧情因果自洽").specification().origin())
        .isEqualTo("USER_CLARIFICATION");
  }

  /** 多命题、冲突和超长不静默改写，用户可以纠正后重新预检。 */
  @Test
  void ambiguousOrConflictingTargetIsRejected() {
    assertThatThrownBy(() -> AnalysisSpecification.resolve("命题「甲」和命题「乙」", null))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(() -> AnalysisSpecification.resolve("命题「甲」", "乙"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(() -> AnalysisSpecification.resolve("分析立场", "长".repeat(301)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(AnalysisSpecification.resolve("讨论台词「我支持你」", null).specification()).isNull();
  }

  private DiscussionModelContract.Request request() {
    return new DiscussionModelContract.Request(
        "snapshot",
        "分析立场",
        "合成",
        "SYNTHETIC",
        false,
        3,
        List.of(
            new DiscussionModelContract.Text("a", null, "支持前半，反对后半"),
            new DiscussionModelContract.Text("b", "a", "仅在条件成立时支持"),
            new DiscussionModelContract.Text("c", null, "为什么？")),
        128000,
        4096,
        List.of(),
        StanceProtocol.VERSION,
        new AnalysisSpecification(AnalysisSpecification.VERSION, "命题", "USER_CLARIFICATION"));
  }

  private StanceProtocol.Claim claim(String id, StanceProtocol.Position p, String condition) {
    return new StanceProtocol.Claim("合成主张" + p, p, condition, "合成理由", List.of(id));
  }

  private List<StanceProtocol.Decision> decisions() {
    return List.of(
        new StanceProtocol.Decision(
            "a",
            StanceProtocol.Coverage.CLAIMS,
            "多主张",
            List.of(
                claim("a", StanceProtocol.Position.SUPPORT, ""),
                claim("a", StanceProtocol.Position.OPPOSE, ""))),
        new StanceProtocol.Decision(
            "b",
            StanceProtocol.Coverage.CLAIMS,
            "限定",
            List.of(claim("b", StanceProtocol.Position.QUALIFIED, "条件成立"))),
        new StanceProtocol.Decision("c", StanceProtocol.Coverage.QUESTION, "仅提问", List.of()));
  }

  /** 评论分母3、主张分母3；支持和反对同属a，不把重叠评论相加为人数。 */
  @Test
  void multipleClaimsAndOverlappingCommentsHaveIndependentDenominators() {
    StanceProtocol.validate(request(), decisions());
    var s = StanceProtocol.summarize(decisions());
    assertThat(s.coveredComments()).isEqualTo(3);
    assertThat(s.claimComments()).isEqualTo(2);
    assertThat(s.totalClaims()).isEqualTo(3);
    assertThat(s.supportComments()).isEqualTo(1);
    assertThat(s.opposeComments()).isEqualTo(1);
    assertThat(s.mixedComments()).isEqualTo(1);
    assertThat(s.qualifiedClaims()).isEqualTo(1);
    assertThat(s.questionComments()).isEqualTo(1);
  }

  /** 漏掉难评论、重复覆盖或越界来源均不得发布。 */
  @Test
  void missingDuplicateAndForeignCoverageAreRejected() {
    assertThatThrownBy(() -> StanceProtocol.validate(request(), decisions().subList(0, 2)))
        .isInstanceOf(ModelContract.Failure.class);
    assertThatThrownBy(
            () ->
                StanceProtocol.validate(
                    request(), List.of(decisions().get(0), decisions().get(0), decisions().get(2))))
        .isInstanceOf(ModelContract.Failure.class);
    var bad =
        new StanceProtocol.Decision("outside", StanceProtocol.Coverage.UNKNOWN, "未知", List.of());
    assertThatThrownBy(
            () ->
                StanceProtocol.validate(
                    request(), List.of(bad, decisions().get(1), decisions().get(2))))
        .isInstanceOf(ModelContract.Failure.class);
  }

  /** 限定必须给条件；不能只引用别人的话为当前评论表达背书。 */
  @Test
  void qualifiedNeedsConditionAndClaimNeedsOwnCommentEvidence() {
    var bad =
        new StanceProtocol.Decision(
            "a",
            StanceProtocol.Coverage.CLAIMS,
            "理由",
            List.of(claim("a", StanceProtocol.Position.QUALIFIED, "")));
    assertThatThrownBy(
            () ->
                StanceProtocol.validate(
                    request(), List.of(bad, decisions().get(1), decisions().get(2))))
        .isInstanceOf(ModelContract.Failure.class);
    var foreign =
        new StanceProtocol.Decision(
            "a",
            StanceProtocol.Coverage.CLAIMS,
            "理由",
            List.of(claim("b", StanceProtocol.Position.SUPPORT, "")));
    assertThatThrownBy(
            () ->
                StanceProtocol.validate(
                    request(), List.of(foreign, decisions().get(1), decisions().get(2))))
        .isInstanceOf(ModelContract.Failure.class);
  }

  /** 全部无法判断仍完成逐条覆盖，主张分母是0而非伪造中立或支持率。 */
  @Test
  void allUnknownProducesZeroClaims() {
    var rows =
        request().comments().stream()
            .map(
                c ->
                    new StanceProtocol.Decision(
                        c.commentId(), StanceProtocol.Coverage.UNKNOWN, "缺语境", List.of()))
            .toList();
    StanceProtocol.validate(request(), rows);
    assertThat(StanceProtocol.summarize(rows).totalClaims()).isZero();
    assertThat(StanceProtocol.summarize(rows).unknownComments()).isEqualTo(3);
  }

  /** v3序列化仍没有新字段；v5固定规则在系统消息，用户命题只在用户数据。 */
  @Test
  void oldJsonAndSystemBoundaryRemainSeparate() {
    var old =
        new DiscussionModelContract.Request(
            "s",
            "总结讨论",
            "标题",
            "SYNTHETIC",
            false,
            1,
            List.of(new DiscussionModelContract.Text("a", null, "原文")),
            128000,
            4096);
    assertThat(ModelContract.encode(old))
        .doesNotContain("specification", "promptVersion", "context");
    var prompt = io.echoinsight.model.springai.StancePrompt.build(request(), "test-only");
    assertThat(prompt.getInstructions().getFirst().getText()).doesNotContain("支持前半");
    assertThat(prompt.getInstructions().getLast().getText()).contains("支持前半", "USER_CLARIFICATION");
  }
}
