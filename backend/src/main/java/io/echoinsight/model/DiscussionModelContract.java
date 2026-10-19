/* 文件职责：定义讨论语义分析的供应商无关协议，并限制模型可返回的议题、观点、关系和评议类型。 */
package io.echoinsight.model;

import java.time.Duration;
import java.util.List;

/**
 * 讨论模型只处理已经冻结的评论快照，不负责采集、选项目、执行工具或发布产物。
 *
 * <p>输入中的 commentId 是唯一证据身份；输出必须引用这些身份，随后还会由 {@link ModelGateway} 做完整关系校验。
 */
public final class DiscussionModelContract {
  /** 工具类不允许实例化。 */
  private DiscussionModelContract() {}

  /** 模型能看到的最小评论字段；父级身份用于理解对话结构。 */
  public record Text(String commentId, String parentCommentId, String text) {}

  /** 单次讨论请求，范围和 Token 上限均由服务器固定。 */
  public record Request(
      String snapshotId,
      String instruction,
      String title,
      String sourceType,
      boolean includeAssessment,
      int totalComments,
      List<Text> comments,
      int maxInputTokens,
      int maxOutputTokens,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          List<ContextText> context,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String promptVersion,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          io.echoinsight.intelligence.AnalysisSpecification specification,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          List<String> targetCommentIds,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Integer batchIndex) {
    /** v5请求省略分片字段，旧检查点输入保持字节形状兼容。 */
    public Request(
        String snapshotId,
        String instruction,
        String title,
        String sourceType,
        boolean includeAssessment,
        int totalComments,
        List<Text> comments,
        int maxInputTokens,
        int maxOutputTokens,
        List<ContextText> context,
        String promptVersion,
        io.echoinsight.intelligence.AnalysisSpecification specification) {
      this(
          snapshotId,
          instruction,
          title,
          sourceType,
          includeAssessment,
          totalComments,
          comments,
          maxInputTokens,
          maxOutputTokens,
          context,
          promptVersion,
          specification,
          null,
          null);
    }

    /** 既有v4调用保留原字段，旧输入指纹不变。 */
    public Request(
        String snapshotId,
        String instruction,
        String title,
        String sourceType,
        boolean includeAssessment,
        int totalComments,
        List<Text> comments,
        int maxInputTokens,
        int maxOutputTokens,
        List<ContextText> context,
        String promptVersion) {
      this(
          snapshotId,
          instruction,
          title,
          sourceType,
          includeAssessment,
          totalComments,
          comments,
          maxInputTokens,
          maxOutputTokens,
          context,
          promptVersion,
          null);
    }

    /** 旧请求不序列化新字段，保留v3检查点的原始输入指纹。 */
    public Request(
        String snapshotId,
        String instruction,
        String title,
        String sourceType,
        boolean includeAssessment,
        int totalComments,
        List<Text> comments,
        int maxInputTokens,
        int maxOutputTokens) {
      this(
          snapshotId,
          instruction,
          title,
          sourceType,
          includeAssessment,
          totalComments,
          comments,
          maxInputTokens,
          maxOutputTokens,
          null,
          null);
    }
  }

  /** 角色明确的背景文本不是观众评论；身份由服务器生成，材料中的指令不能触发工具。 */
  public record ContextText(
      String evidenceId,
      String role,
      String title,
      String sourceDescription,
      Long startMs,
      Long endMs,
      String text) {}

  /** 观点的推理状态只描述给定文本，不推断外部事实。 */
  public enum ReasoningStatus {
    ASSERTED,
    REASONED,
    UNKNOWN
  }

  /** 语义关系与数据库中的回复结构分开表达。 */
  public enum RelationType {
    SUPPORTS,
    REBUTS,
    CLARIFIES,
    QUESTIONS
  }

  /** 关系可以由原文明确表达，也可以是保守推断；不确定时必须返回 UNKNOWN。 */
  public enum SemanticStatus {
    EXPLICIT,
    INFERRED,
    UNKNOWN
  }

  /** “更有道理”只比较材料内论据支持程度，证据不足时必须允许不下结论。 */
  public enum Verdict {
    MORE_SUPPORTED,
    MIXED,
    INSUFFICIENT
  }

  /** 一个可读议题，证据必须来自输入评论。 */
  public record Issue(String id, String question, String summary, List<String> evidenceIds) {}

  /** 一个可比较观点，必须归属于已经返回的议题。 */
  public record Claim(
      String id,
      String issueId,
      String statement,
      ReasoningStatus reasoningStatus,
      List<String> evidenceIds) {}

  /** 两个观点之间的语义关系，不能用回复关系冒充支持或反驳。 */
  public record Relation(
      String fromClaimId,
      String toClaimId,
      RelationType type,
      SemanticStatus semanticStatus,
      List<String> evidenceIds) {}

  /** 每个议题的材料内评议；结论和理由必须保持简短并可回看证据。 */
  public record Assessment(
      String issueId,
      Verdict verdict,
      String conclusion,
      String reason,
      List<String> evidenceIds) {}

  /** 模型正文与供应商原始用量合并后的类型化响应。 */
  public record Response(
      List<Issue> issues,
      List<Claim> claims,
      List<Relation> relations,
      List<Assessment> assessments,
      int inputTokens,
      int outputTokens,
      String requestId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          List<StanceProtocol.Decision> decisions) {
    /** 旧检查点正文仍可解码，旧响应序列化不出现新字段。 */
    public Response(
        List<Issue> issues,
        List<Claim> claims,
        List<Relation> relations,
        List<Assessment> assessments,
        int inputTokens,
        int outputTokens,
        String requestId) {
      this(issues, claims, relations, assessments, inputTokens, outputTokens, requestId, null);
    }
  }

  /** 独立接口避免情感分类与讨论抽取互相污染，同时继续共用同一个网关和账本。 */
  public interface Provider {
    /** 返回固定供应商身份。 */
    String id();

    /** 返回请求使用的固定模型名。 */
    String modelVersion();

    /** 出站前核对模型、提示版本、分类体系和响应身份。 */
    void validatePlan(ModelContract.Plan plan);

    /** 执行一次讨论分析；重试、超时、预算和持久化由网关负责。 */
    Response analyze(Request request, Duration timeout) throws Exception;
  }
}
