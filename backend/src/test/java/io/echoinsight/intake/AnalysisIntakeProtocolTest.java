/* 文件职责：离线验证入口边界、命题澄清及明确采集确认，不调用任何外部服务。 */
package io.echoinsight.intake;

import static io.echoinsight.intake.AnalysisIntakeProtocol.*;
import static org.assertj.core.api.Assertions.*;

import io.echoinsight.collection.BilibiliUrlParser;
import io.echoinsight.intelligence.IntentPlanner;
import org.junit.jupiter.api.Test;

/** 入口复用原规则，用户原话与业务范围的规范化必须可重复。 */
class AnalysisIntakeProtocolTest {
  private final AnalysisIntakeProtocol protocol =
      new AnalysisIntakeProtocol(new BilibiliUrlParser(), new IntentPlanner());
  private static final String SID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  /** 删除追踪参数，保留问题内部换行与明确完整范围。 */
  @Test
  void preservesQuestionAndCanonicalIdentity() {
    var input =
        protocol.normalize(
            new Draft(
                " https://www.bilibili.com/video/BV1ntuF64EhF/?spm_id=x ",
                null,
                " 总结观点\n并比较理由 ",
                null,
                null));
    assertThat(input.canonicalUrl()).isEqualTo("https://www.bilibili.com/video/BV1ntuF64EhF");
    assertThat(input.instruction()).isEqualTo("总结观点\n并比较理由");
    assertThat(input.analysisScope()).isEqualTo("FULL");
    assertThat(input.includeAssessment()).isTrue();
  }

  /** 已存资料不依赖URL，可来自原受支持的任意评论导入。 */
  @Test
  void acceptsExplicitLocalSnapshot() {
    var input = protocol.normalize(new Draft(null, SID, "总结讨论", null, "PREVIEW"));
    assertThat(input.requestedSnapshotId()).isEqualTo(SID);
    assertThat(input.canonicalUrl()).isNull();
    assertThat(input.goal().stanceRequested()).isFalse();
  }

  /** 含糊支持反对需要用户标准，不从情绪或台词猜命题。 */
  @Test
  void asksBusinessClarification() {
    var input = protocol.normalize(new Draft(null, SID, "哪些评论支持这个观点", null, null));
    assertThat(input.goal().needsProposition()).isTrue();
    assertThat(input.goal().specification()).isNull();
  }

  /** 用户补充命题后保持原问题；标准从用户提供，不由入口发明。 */
  @Test
  void keepsClarifiedProposition() {
    var input = protocol.normalize(new Draft(null, SID, "哪些评论支持这个观点", "结局否定宿命论", null));
    assertThat(input.proposition()).isEqualTo("结局否定宿命论");
    assertThat(input.goal().needsProposition()).isFalse();
  }

  /** 明确引号语法与补充不一致，拒绝混用判断标准。 */
  @Test
  void rejectsConflictingPropositions() {
    assertThatThrownBy(() -> protocol.normalize(new Draft(null, SID, "分析命题「A」", "B", null)))
        .hasMessageContaining("不一致");
  }

  /** 只有官方单视频链接可通过，不跟随短链或接受用户指定主机。 */
  @Test
  void rejectsUnsupportedNetworkTargets() {
    for (String url :
        new String[] {
          "http://127.0.0.1/video/av1",
          "https://www.bilibili.com.evil.test/video/av1",
          "https://b23.tv/x",
          "https://space.bilibili.com/1",
          "https://www.xiaohongshu.com/explore/x",
          "https://www.bilibili.com:443/video/av1"
        })
      assertThatThrownBy(() -> protocol.normalize(new Draft(url, null, "总结观点", null, null)))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 空来源和坏身份明确拒绝，不能自动选全局最新快照。 */
  @Test
  void rejectsMissingOrInvalidSource() {
    for (String snapshot : new String[] {null, "", "latest", "../x"})
      assertThatThrownBy(() -> protocol.normalize(new Draft(null, snapshot, "总结观点", null, null)))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 未实现业务与未知范围不能静默改为别的分析。 */
  @Test
  void rejectsUnsupportedGoalAndScope() {
    assertThatThrownBy(() -> protocol.normalize(new Draft(null, SID, "删除所有数据", null, null)))
        .hasMessageContaining("当前先支持");
    assertThatThrownBy(() -> protocol.normalize(new Draft(null, SID, "总结观点", null, "AUTO")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  /** 采集确认独立于保存问题，未同意不创建批次。 */
  @Test
  void requiresExplicitCollectionConfirmation() {
    assertThatThrownBy(() -> protocol.collect(new Collect(null, true, false)))
        .hasMessageContaining("确认");
    assertThat(protocol.collect(new Collect(null, false, true)))
        .isEqualTo(new Collect(50, false, true));
    assertThat(protocol.collect(new Collect(null, true, true)))
        .isEqualTo(new Collect(null, true, true));
  }

  /** 当前可见遍历与根限量互斥，限量不能溢出原采集器边界。 */
  @Test
  void rejectsAmbiguousOrOversizedCollection() {
    for (int limit : new int[] {0, 201, -1})
      assertThatThrownBy(() -> protocol.collect(new Collect(limit, false, true)))
          .hasMessageContaining("1～200");
    assertThatThrownBy(() -> protocol.collect(new Collect(50, true, true)))
        .hasMessageContaining("不要同时");
  }
}
