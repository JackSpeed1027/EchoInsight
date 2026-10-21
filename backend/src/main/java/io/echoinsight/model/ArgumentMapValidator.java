/* 文件职责：共享讨论图的字段、关系、引用与用量校验，区分工程结构校验和语义质量。 */
package io.echoinsight.model;

import java.util.*;

/** 旧单次讨论和新分层归并共用引用闭包，不能用模型自己给出的统计认证结果。 */
public final class ArgumentMapValidator {
  /** 纯校验器不持有运行状态。 */
  private ArgumentMapValidator() {}

  /** 不同阶段限制摘要体积，旧协议限额保持不变。 */
  public record Limits(
      int issues,
      int claims,
      int relations,
      int questionChars,
      int summaryChars,
      int statementChars,
      int conclusionChars,
      int reasonChars) {
    /** 原v3/v4讨论协议。 */
    public static Limits legacy() {
      return new Limits(10, 40, 60, 300, 1200, 1000, 1000, 1200);
    }

    /** 中间摘要压缩，避免归并输入无限增长。 */
    public static Limits compact() {
      return new Limits(3, 6, 6, 160, 320, 240, 240, 320);
    }

    /** 最终报告允许更多观点，仍受固定输出Token上限。 */
    public static Limits report() {
      return new Limits(8, 24, 24, 300, 1000, 600, 800, 1000);
    }
  }

  /** 1、验证信封/用量；2、核对议题与主张；3、关系必须在同议题闭合；4、评议必须引用真实评论。 */
  public static void validate(
      DiscussionModelContract.Response response,
      Set<String> evidence,
      Set<String> commentEvidence,
      boolean includeAssessment,
      int maxInputTokens,
      int maxOutputTokens,
      Limits limits) {
    if (response == null
        || response.decisions() != null
        || response.issues() == null
        || response.claims() == null
        || response.relations() == null
        || response.assessments() == null
        || response.issues().isEmpty()
        || response.issues().size() > limits.issues()
        || response.claims().size() > limits.claims()
        || response.relations().size() > limits.relations()
        || response.inputTokens() < 0
        || response.outputTokens() < 0
        || response.inputTokens() > maxInputTokens
        || response.outputTokens() > maxOutputTokens
        || (response.requestId() != null && response.requestId().length() > 128)) {
      invalid("MODEL_RESPONSE_SCHEMA");
    }
    Set<String> issueIds = new HashSet<>(), claimIds = new HashSet<>();
    for (var issue : response.issues()) {
      if (issue != null
          && (!validEvidence(issue.evidenceIds(), evidence)
              || !hasComment(issue.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (issue == null
          || !validId(issue.id())
          || !issueIds.add(issue.id())
          || !validText(issue.question(), limits.questionChars())
          || !validText(issue.summary(), limits.summaryChars())
          || !validEvidence(issue.evidenceIds(), evidence)
          || !hasComment(issue.evidenceIds(), commentEvidence)) invalid("MODEL_ISSUE_INVALID");
    }
    Map<String, String> claimIssues = new HashMap<>();
    for (var claim : response.claims()) {
      if (claim != null
          && (!validEvidence(claim.evidenceIds(), evidence)
              || !hasComment(claim.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (claim == null
          || !validId(claim.id())
          || !claimIds.add(claim.id())
          || !issueIds.contains(claim.issueId())
          || claim.reasoningStatus() == null
          || !validText(claim.statement(), limits.statementChars())
          || !validEvidence(claim.evidenceIds(), evidence)
          || !hasComment(claim.evidenceIds(), commentEvidence)) invalid("MODEL_CLAIM_INVALID");
      claimIssues.put(claim.id(), claim.issueId());
    }
    for (var relation : response.relations()) {
      if (relation != null
          && (!validEvidence(relation.evidenceIds(), evidence)
              || !hasComment(relation.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (relation == null
          || relation.fromClaimId() == null
          || relation.toClaimId() == null
          || relation.fromClaimId().equals(relation.toClaimId())
          || !claimIds.contains(relation.fromClaimId())
          || !claimIds.contains(relation.toClaimId())
          || !Objects.equals(
              claimIssues.get(relation.fromClaimId()), claimIssues.get(relation.toClaimId()))
          || relation.type() == null
          || relation.semanticStatus() == null
          || !validEvidence(relation.evidenceIds(), evidence)
          || !hasComment(relation.evidenceIds(), commentEvidence))
        invalid("MODEL_RELATION_INVALID");
    }
    Set<String> assessed = new HashSet<>();
    for (var assessment : response.assessments()) {
      if (assessment != null
          && (!validEvidence(assessment.evidenceIds(), evidence)
              || !hasComment(assessment.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (assessment == null
          || !issueIds.contains(assessment.issueId())
          || !assessed.add(assessment.issueId())
          || assessment.verdict() == null
          || !validText(assessment.conclusion(), limits.conclusionChars())
          || !validText(assessment.reason(), limits.reasonChars())
          || !validEvidence(assessment.evidenceIds(), evidence)
          || !hasComment(assessment.evidenceIds(), commentEvidence))
        invalid("MODEL_ASSESSMENT_INVALID");
    }
    if (includeAssessment ? !assessed.equals(issueIds) : !response.assessments().isEmpty())
      invalid("MODEL_ASSESSMENT_INVALID");
  }

  /** 背景可以补充解释，但每个观众议题/观点/关系/评议仍须绑定至少一条真实评论。 */
  private static boolean hasComment(List<String> ids, Set<String> comments) {
    return ids != null && ids.stream().anyMatch(comments::contains);
  }

  /** 标识符只允许短 ASCII，避免把长文本或控制字符混入关系字段。 */
  private static boolean validId(String value) {
    return value != null && value.matches("[A-Za-z0-9._:-]{1,64}");
  }

  /** 所有可展示结论都必须非空且有界。 */
  private static boolean validText(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  /** 每个结论至少绑定一个输入证据，且不允许重复或越界编号。 */
  private static boolean validEvidence(List<String> ids, Set<String> allowed) {
    return ids != null
        && !ids.isEmpty()
        && ids.size() <= 20
        && new HashSet<>(ids).size() == ids.size()
        && allowed.containsAll(ids);
  }

  /** 统一抛出公开错误码，不把模型正文写入异常和日志。 */
  private static void invalid(String code) {
    throw new ModelContract.Failure(code);
  }
}
