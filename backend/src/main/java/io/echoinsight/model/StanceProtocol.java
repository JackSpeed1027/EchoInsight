/* 文件职责：定义逐评论、多主张的命题协议及确定性统计；情感不参与判断。 */
package io.echoinsight.model;

import java.util.*;

/** 命题是唯一参照；模型负责语义，Java负责覆盖、引用闭包与分母，不把计数当民调或事实验证。 */
public final class StanceProtocol {
  public static final String VERSION = "discussion-json-v5-proposition";
  public static final String TAXONOMY = "proposition-stance-v1";
  public static final int MAX_COMMENTS = 12;
  public static final int MAX_CHARS = 12000;

  /** 协议工具类不保存任何项目、模型或会话状态。 */
  private StanceProtocol() {}

  /** 无主张的疑问、无关和无法判断分别记录，不合并为中立。 */
  public enum Coverage {
    CLAIMS,
    QUESTION,
    IRRELEVANT,
    UNKNOWN
  }

  /** 一条评论可拆多条主张，有限定支持不能并入无条件支持。 */
  public enum Position {
    SUPPORT,
    OPPOSE,
    QUALIFIED,
    UNKNOWN
  }

  /** 限定主张必须给条件；证据必须包含当前评论，允许附带祖先或角色化背景。 */
  public record Claim(
      String statement,
      Position position,
      String condition,
      String reason,
      List<String> evidenceIds) {}

  /** 每条实际选入评论必须恰好一个覆盖记录，不允许省略困难评论来提高比例。 */
  public record Decision(String commentId, Coverage coverage, String reason, List<Claim> claims) {}

  /** 供应商只返回逐评论判断，不返回自己编造的统计。 */
  public record Payload(List<Decision> decisions) {}

  /** 统计评论主张的重叠覆盖，支持与反对可以来自同一评论，不能相加当独立用户数。 */
  public record Statistics(
      int selectedComments,
      int coveredComments,
      int claimComments,
      int questionComments,
      int irrelevantComments,
      int unknownComments,
      int totalClaims,
      int supportClaims,
      int opposeClaims,
      int qualifiedClaims,
      int unknownClaims,
      int supportComments,
      int opposeComments,
      int qualifiedComments,
      int mixedComments) {}

  /** 1、逐条覆盖必须等于输入；2、最多拆两条主张；3、主张引用当前评论且不越界；4、先校验后计数。 这是工程结构校验，不证明模型在语义上判断正确。 */
  public static void validate(DiscussionModelContract.Request request, List<Decision> decisions) {
    Set<String> comments = new HashSet<>();
    for (var comment : request.comments()) comments.add(comment.commentId());
    Set<String> allowed = new HashSet<>(comments);
    if (io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(request.promptVersion()))
      comments = new HashSet<>(request.targetCommentIds());
    if (request.context() != null) request.context().forEach(c -> allowed.add(c.evidenceId()));
    if (decisions == null || decisions.size() != comments.size()) fail();
    Set<String> seen = new HashSet<>();
    for (var decision : decisions) {
      if (decision == null
          || !comments.contains(decision.commentId())
          || !seen.add(decision.commentId())
          || decision.coverage() == null
          || !text(decision.reason(), 160)
          || decision.claims() == null
          || decision.claims().size() > 2
          || (decision.coverage() == Coverage.CLAIMS) != !decision.claims().isEmpty()) fail();
      Set<String> statements = new HashSet<>();
      for (var claim : decision.claims()) {
        if (claim == null
            || !text(claim.statement(), 160)
            || !statements.add(claim.statement().strip())
            || claim.position() == null
            || !text(claim.reason(), 160)
            || claim.condition() == null
            || claim.condition().length() > 120
            || (claim.position() == Position.QUALIFIED && claim.condition().isBlank())
            || (claim.position() != Position.QUALIFIED && !claim.condition().isEmpty())
            || claim.evidenceIds() == null
            || claim.evidenceIds().isEmpty()
            || claim.evidenceIds().size() > 6
            || !claim.evidenceIds().contains(decision.commentId())
            || new HashSet<>(claim.evidenceIds()).size() != claim.evidenceIds().size()
            || !allowed.containsAll(claim.evidenceIds())) fail();
      }
    }
    if (!seen.equals(comments)) fail();
  }

  /** 从已校验的决策计算两个分母：覆盖评论数与提取主张数；没有主张时保持0，不除以0。 */
  public static Statistics summarize(List<Decision> decisions) {
    int claimComments = 0;
    int questionComments = 0;
    int irrelevantComments = 0;
    int unknownComments = 0;
    int totalClaims = 0;
    int supportClaims = 0;
    int opposeClaims = 0;
    int qualifiedClaims = 0;
    int unknownClaims = 0;
    int supportComments = 0;
    int opposeComments = 0;
    int qualifiedComments = 0;
    int mixedComments = 0;
    for (var decision : decisions) {
      switch (decision.coverage()) {
        case CLAIMS -> claimComments++;
        case QUESTION -> questionComments++;
        case IRRELEVANT -> irrelevantComments++;
        case UNKNOWN -> unknownComments++;
      }
      Set<Position> positions = new HashSet<>();
      for (var claim : decision.claims()) {
        totalClaims++;
        positions.add(claim.position());
        switch (claim.position()) {
          case SUPPORT -> supportClaims++;
          case OPPOSE -> opposeClaims++;
          case QUALIFIED -> qualifiedClaims++;
          case UNKNOWN -> unknownClaims++;
        }
      }
      if (positions.contains(Position.SUPPORT)) supportComments++;
      if (positions.contains(Position.OPPOSE)) opposeComments++;
      if (positions.contains(Position.QUALIFIED)) qualifiedComments++;
      if (positions.contains(Position.SUPPORT) && positions.contains(Position.OPPOSE))
        mixedComments++;
    }
    return new Statistics(
        decisions.size(),
        decisions.size(),
        claimComments,
        questionComments,
        irrelevantComments,
        unknownComments,
        totalClaims,
        supportClaims,
        opposeClaims,
        qualifiedClaims,
        unknownClaims,
        supportComments,
        opposeComments,
        qualifiedComments,
        mixedComments);
  }

  /** 限长且非空，避免模型返回无法解释的标签。 */
  private static boolean text(String value, int limit) {
    return value != null && !value.isBlank() && value.length() <= limit;
  }

  /** 返回固定错误码，模型正文不进入异常信息。 */
  private static void fail() {
    throw new ModelContract.Failure("MODEL_STANCE_INVALID");
  }
}
