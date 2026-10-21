/* 文件职责：定义普通讨论逐片归纳/分层汇总输入，严格核对父语境、目标与引用闭包。 */
package io.echoinsight.model;

import io.echoinsight.intelligence.DiscussionDigestPlan;
import java.util.*;

/** 中间摘要是模型生成数据，归并只能引用可追溯原文，不能把摘要身份当证据。 */
public final class DiscussionDigestContract {
  /** 契约工具类不保存会话。 */
  private DiscussionDigestContract() {}

  /** 去掉供应商用量，避免把前片费用重复算入归并；局部议题/主张ID仅在本摘要内有效。 */
  public record Summary(
      int index,
      List<DiscussionModelContract.Issue> issues,
      List<DiscussionModelContract.Claim> claims,
      List<DiscussionModelContract.Relation> relations,
      List<DiscussionModelContract.Assessment> assessments) {}

  /** 执行身份留在网关指纹中；MAP实际模型输入使用localPayload剥离全局依赖。 */
  public record Request(
      String promptVersion,
      String snapshotId,
      String instruction,
      String title,
      String sourceType,
      boolean includeAssessment,
      int totalComments,
      int batchIndex,
      String phase,
      List<DiscussionModelContract.Text> comments,
      List<String> targetCommentIds,
      List<DiscussionModelContract.ContextText> context,
      List<Summary> summaries,
      int maxInputTokens,
      int maxOutputTokens) {}

  /** MAP只依赖本片原文、目标角色、问题和显式背景，不能看到其他片的版本/计数。 */
  public record LocalPayload(
      String promptVersion,
      String instruction,
      String title,
      String sourceType,
      boolean includeAssessment,
      List<DiscussionModelContract.Text> comments,
      List<String> targetCommentIds,
      List<DiscussionModelContract.ContextText> context) {}

  /** 返回真实发送给模型的局部数据，跨版复用也只以这些依赖和全计划为身份。 */
  public static LocalPayload localPayload(Request r) {
    return new LocalPayload(
        r.promptVersion(),
        r.instruction(),
        r.title(),
        r.sourceType(),
        r.includeAssessment(),
        r.comments(),
        r.targetCommentIds(),
        r.context());
  }

  /** 冻结成功摘要，不把元数据或用量作为下一层语义来源。 */
  public static Summary summary(int index, DiscussionModelContract.Response r) {
    return new Summary(index, r.issues(), r.claims(), r.relations(), r.assessments());
  }

  /** 1、计划/限额严格匹配；2、背景独立；3、MAP父闭包完整；4、归并输入有限且引用来自成功子摘要。 */
  public static void validateRequest(ModelContract.Plan p, Request r) {
    if (r == null
        || !DiscussionDigestPlan.enabled(p)
        || !p.promptVersion().equals(r.promptVersion())
        || p.inputTokenCeiling() == null
        || r.maxInputTokens() != p.inputTokenCeiling()
        || r.maxOutputTokens() != p.maxOutputTokens()
        || !id(r.snapshotId())
        || !text(r.instruction(), 1000)
        || !text(r.title(), 200)
        || !text(r.sourceType(), 40)
        || r.totalComments() < 1
        || r.totalComments() > 5000
        || r.batchIndex() < 0
        || r.batchIndex() >= DiscussionDigestPlan.MAX_CALLS
        || r.context() == null
        || r.comments() == null
        || r.targetCommentIds() == null
        || r.summaries() == null) boundary();
    Set<String> background = validateContext(r.context());
    if ("MAP".equals(r.phase())) {
      if (r.comments().isEmpty()
          || r.comments().size() > 120
          || r.targetCommentIds().isEmpty()
          || r.targetCommentIds().size() > DiscussionDigestPlan.TARGETS
          || !r.summaries().isEmpty()) boundary();
      Set<String> ids = new LinkedHashSet<>();
      int chars = 0;
      for (int n = 0; n < r.comments().size(); n++) {
        var c = r.comments().get(n);
        if (c == null
            || !("c" + (n + 1)).equals(c.commentId())
            || !ids.add(c.commentId())
            || !text(c.text(), 10000)) boundary();
        chars += c.text().length();
      }
      if (chars > 80000
          || r.comments().size() > r.totalComments()
          || !ids.containsAll(r.targetCommentIds())
          || new HashSet<>(r.targetCommentIds()).size() != r.targetCommentIds().size()) boundary();
      for (var c : r.comments())
        if (c.parentCommentId() != null && !ids.contains(c.parentCommentId())) boundary();
      // 父引用存在还不够，循环图同样不能成为可复用输入。
      Map<String, String> parents = new HashMap<>();
      r.comments().forEach(c -> parents.put(c.commentId(), c.parentCommentId()));
      for (var c : r.comments()) {
        var seen = new HashSet<String>();
        String at = c.commentId();
        while (at != null) {
          if (!seen.add(at)) boundary();
          at = parents.get(at);
        }
      }
      if (!Collections.disjoint(ids, background)) boundary();
    } else {
      if (!Set.of("REDUCE", "FINAL").contains(r.phase())
          || !r.comments().isEmpty()
          || !r.targetCommentIds().isEmpty()
          || r.summaries().isEmpty()
          || r.summaries().size() > DiscussionDigestPlan.FAN_IN) boundary();
      Set<Integer> children = new HashSet<>();
      for (var summary : r.summaries()) {
        if (summary == null
            || summary.index() < 0
            || summary.index() >= r.batchIndex()
            || !children.add(summary.index())) boundary();
        var response =
            new DiscussionModelContract.Response(
                summary.issues(),
                summary.claims(),
                summary.relations(),
                summary.assessments(),
                0,
                0,
                null);
        var refs = references(response);
        var comments = new HashSet<>(refs);
        comments.removeAll(background);
        ArgumentMapValidator.validate(
            response,
            refs,
            comments,
            r.includeAssessment(),
            0,
            0,
            ArgumentMapValidator.Limits.compact());
        compactReferences(response, 4);
      }
      if (ModelContract.encode(r).length() > 100000) boundary();
    }
  }

  /** 与旧网关相同的背景总边界，新增CONTENT_TEXT仍是用户选取原文，不是创作者认证。 */
  public static Set<String> validateContext(List<DiscussionModelContract.ContextText> sources) {
    if (sources == null || sources.size() > 59) boundary();
    Set<String> ids = new HashSet<>();
    int refs = 0, refChars = 0, subs = 0, subChars = 0, content = 0, contentChars = 0, total = 0;
    for (var s : sources) {
      if (s == null
          || !id(s.evidenceId())
          || !s.evidenceId().startsWith("context-")
          || !ids.add(s.evidenceId())
          || !text(s.title(), 200)
          || s.sourceDescription() == null
          || s.sourceDescription().length() > 200
          || !text(s.text(), 12000)) boundary();
      switch (Objects.toString(s.role(), "")) {
        case "REFERENCE_TEXT" -> {
          refs++;
          refChars += s.text().length();
          if (s.startMs() != null || s.endMs() != null) boundary();
        }
        case "TRANSCRIPT" -> {
          subs++;
          subChars += s.text().length();
          if (s.startMs() == null || s.endMs() == null) boundary();
        }
        case "CONTENT_TEXT" -> {
          content++;
          contentChars += s.text().length();
          if ((s.startMs() == null) != (s.endMs() == null)) boundary();
        }
        default -> boundary();
      }
      if (s.startMs() != null && (s.startMs() < 0 || s.endMs() <= s.startMs())) boundary();
      if ("CONTENT_TEXT".equals(s.role())) {
        if (s.startMs() == null && s.startChar() == null) boundary();
        if ((s.startChar() == null) != (s.endChar() == null)) boundary();
        if (s.startChar() != null
            && (s.startChar() < 0
                || s.endChar() <= s.startChar()
                || s.endChar() - s.startChar() != s.text().length())) boundary();
      } else if (s.startChar() != null || s.endChar() != null) boundary();
      total += s.text().length();
    }
    if (refs > 3
        || refChars > 12000
        || subs > 40
        || subChars > 8000
        || content > 16
        || contentChars > 8000
        || total > 20000) boundary();
    return ids;
  }

  /** 引用必须来自本片或成功子摘要；每项至少一条评论，背景不能代替观众发言。 */
  public static void validate(Request r, DiscussionModelContract.Response response) {
    Set<String> background = new HashSet<>();
    r.context().forEach(s -> background.add(s.evidenceId()));
    Set<String> comments = new HashSet<>();
    if ("MAP".equals(r.phase())) r.comments().forEach(c -> comments.add(c.commentId()));
    else
      for (var s : r.summaries())
        comments.addAll(
            references(
                new DiscussionModelContract.Response(
                    s.issues(), s.claims(), s.relations(), s.assessments(), 0, 0, null)));
    comments.removeAll(background);
    var allowed = new HashSet<>(comments);
    allowed.addAll(background);
    ArgumentMapValidator.validate(
        response,
        allowed,
        comments,
        r.includeAssessment(),
        r.maxInputTokens(),
        r.maxOutputTokens(),
        "FINAL".equals(r.phase())
            ? ArgumentMapValidator.Limits.report()
            : ArgumentMapValidator.Limits.compact());
    compactReferences(response, "FINAL".equals(r.phase()) ? 8 : 4);
  }

  /** 摘要引用容量也是协议的一部分，不能让大量ID占满下一层输入。 */
  private static void compactReferences(DiscussionModelContract.Response r, int max) {
    for (var i : r.issues()) if (i.evidenceIds().size() > max) invalid();
    for (var c : r.claims()) if (c.evidenceIds().size() > max) invalid();
    for (var x : r.relations()) if (x.evidenceIds().size() > max) invalid();
    for (var a : r.assessments()) if (a.evidenceIds().size() > max) invalid();
  }

  /** 只抽取来源引用，不把主张/议题ID提升为证据。 */
  public static Set<String> references(DiscussionModelContract.Response r) {
    Set<String> ids = new LinkedHashSet<>();
    if (r == null
        || r.issues() == null
        || r.claims() == null
        || r.relations() == null
        || r.assessments() == null) invalid();
    for (var i : r.issues()) {
      if (i == null || i.evidenceIds() == null) invalid();
      ids.addAll(i.evidenceIds());
    }
    for (var c : r.claims()) {
      if (c == null || c.evidenceIds() == null) invalid();
      ids.addAll(c.evidenceIds());
    }
    for (var x : r.relations()) {
      if (x == null || x.evidenceIds() == null) invalid();
      ids.addAll(x.evidenceIds());
    }
    for (var a : r.assessments()) {
      if (a == null || a.evidenceIds() == null) invalid();
      ids.addAll(a.evidenceIds());
    }
    return ids;
  }

  /** 身份不允许正文或控制字符。 */
  private static boolean id(String s) {
    return s != null && s.matches("[A-Za-z0-9._:-]{1,64}");
  }

  /** 展示字段采用统一UTF16边界。 */
  private static boolean text(String s, int max) {
    return s != null && !s.isBlank() && s.length() <= max;
  }

  /** 请求错误在预留之前拒绝。 */
  private static void boundary() {
    throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
  }

  /** 响应错误保留原网关的发送后未知费用保护。 */
  private static void invalid() {
    throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
  }
}
