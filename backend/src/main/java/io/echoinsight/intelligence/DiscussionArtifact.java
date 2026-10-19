/* 文件职责：定义讨论分析的类型化产物，区分结构事实、语义关系、证据与判断边界。 */
package io.echoinsight.intelligence;

import java.util.List;

/**
 * 单资料讨论分析产物。
 *
 * <p>情感标签不复用到这里。议题、观点、回复关系和评议分别建模，证据保留原文；免费结构演示只能陈述回复结构，不能冒充语义判断。
 */
public record DiscussionArtifact(
    String schemaVersion,
    String artifactId,
    String requestId,
    String jobId,
    String snapshotId,
    String instruction,
    String title,
    String sourceType,
    String sourceMode,
    Scope scope,
    List<Issue> issues,
    List<Claim> claims,
    List<Relation> relations,
    List<Assessment> assessments,
    List<Evidence> evidence,
    List<String> limitations,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        ContextUsage context,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Stance stance) {

  /** 兼容v4原产物构造，旧正文不包含命题字段。 */
  public DiscussionArtifact(
      String schemaVersion,
      String artifactId,
      String requestId,
      String jobId,
      String snapshotId,
      String instruction,
      String title,
      String sourceType,
      String sourceMode,
      Scope scope,
      List<Issue> issues,
      List<Claim> claims,
      List<Relation> relations,
      List<Assessment> assessments,
      List<Evidence> evidence,
      List<String> limitations,
      ContextUsage context) {
    this(
        schemaVersion,
        artifactId,
        requestId,
        jobId,
        snapshotId,
        instruction,
        title,
        sourceType,
        sourceMode,
        scope,
        issues,
        claims,
        relations,
        assessments,
        evidence,
        limitations,
        context,
        null);
  }

  /** 未实际分析时统计保持null，不能把结构演示的0解释为没有人支持。 */
  public record Stance(
      AnalysisSpecification specification,
      boolean analyzed,
      io.echoinsight.model.StanceProtocol.Statistics statistics,
      List<io.echoinsight.model.StanceProtocol.Decision> decisions,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Completion completion) {
    /** v5产物不序列化新字段，旧JSON与检查点保持不变。 */
    public Stance(
        AnalysisSpecification specification,
        boolean analyzed,
        io.echoinsight.model.StanceProtocol.Statistics statistics,
        List<io.echoinsight.model.StanceProtocol.Decision> decisions) {
      this(specification, analyzed, statistics, decisions, null);
    }
  }

  /** 完整只指本快照：所有目标成功且唯一归属，重复祖先只记语境输入次数。 */
  public record Completion(
      String mode,
      String selectionVersion,
      int targetComments,
      int completedComments,
      int batchCount,
      int contextOccurrences,
      boolean coverageVerified) {}

  /** 命题产物使用独立版本，并明确两个分母以及判断边界。 */
  public DiscussionArtifact withStance(Stance result) {
    var notes = new java.util.ArrayList<>(limitations);
    if (result.completion() != null)
      notes.add("已逐条覆盖当前冻结快照的所有目标评论；重复祖先仅作语境，不重复计数。每评论仍最多两条主张，完整覆盖不等于原文所有细节或语义正确性已验证。");
    notes.add(
        result.analyzed()
            ? "命题分析逐条覆盖本次选入评论；每条最多提取两条主张，未选部分没有立场结论。"
            : "命题已冻结；当前仅结构演示，未判断任何立场，统计为空。启用模型后需新建请求。");
    notes.add("支持/反对只针对所列命题；主张数与评论数分母独立，同一评论可同时支持和反对，不能相加为人数或总体支持率。");
    notes.add("限定支持独立列出条件；仅提问、无关、语境不足不合并为中立。每条最多两条主张不证明原文全部细节已提取。");
    notes.add("模型可能误解讽刺或指代；存在引用不等于语义判断正确。创作者意图与外部事实未被认证。");
    return new DiscussionArtifact(
        result.completion() != null
            ? "discussion-artifact-v6-stance"
            : "discussion-artifact-v5-stance",
        artifactId,
        requestId,
        jobId,
        snapshotId,
        instruction,
        title,
        sourceType,
        sourceMode,
        scope,
        issues,
        claims,
        relations,
        assessments,
        evidence,
        List.copyOf(notes),
        context,
        result);
  }

  /** 旧产物构造方式保持不变，未知背景字段不补默认值。 */
  public DiscussionArtifact(
      String schemaVersion,
      String artifactId,
      String requestId,
      String jobId,
      String snapshotId,
      String instruction,
      String title,
      String sourceType,
      String sourceMode,
      Scope scope,
      List<Issue> issues,
      List<Claim> claims,
      List<Relation> relations,
      List<Assessment> assessments,
      List<Evidence> evidence,
      List<String> limitations) {
    this(
        schemaVersion,
        artifactId,
        requestId,
        jobId,
        snapshotId,
        instruction,
        title,
        sourceType,
        sourceMode,
        scope,
        issues,
        claims,
        relations,
        assessments,
        evidence,
        limitations,
        null);
  }

  /** 冻结数量与是否实际送入模型分开；免费结构演示不能冒称已理解背景。 */
  public record ContextUsage(
      String fingerprint,
      String selectionVersion,
      boolean sentToModel,
      int referenceDocuments,
      int totalTranscriptSegments,
      int selectedTranscriptSegments,
      boolean transcriptTruncated,
      int textChars) {}

  /** 背景请求使用独立版本并追加范围解释，原有评论分母与所有观点结果保持不变。 */
  public DiscussionArtifact withContext(ContextUsage usage) {
    var notes = new java.util.ArrayList<>(limitations);
    if (AnalysisContextStore.FOLLOWUP_VERSION.equals(usage.selectionVersion())) {
      notes.add("本次为同一冻结资料上的评论追问；上一轮输出仅作待复核背景，不是已验证事实。未自动读取新资料或整段会话。");
    }

    notes.add(
        usage.sentToModel()
            ? "字幕和参考文本作为独立背景送入模型，不计入评论条数；用户来源声明未被自动认证。"
            : "MODEL".equals(sourceMode)
                ? "本次没有可用背景原文，模型只收到评论，没有自动补入其他资料。"
                : "当前仅结构演示；背景已冻结但未交给模型，也没有完成背景语义分析。");
    if (usage.transcriptTruncated())
      notes.add(
          AnalysisContextStore.RETRIEVAL_VERSION.equals(usage.selectionVersion())
              ? "上传字幕只使用本次问题字词命中及邻域的完整段，不是全片语义检索；未选范围没有结论。"
              : "字幕超过段数或字符上限，仅使用按时间排序的完整段前缀；未处理的部分不推断为不存在。");
    return new DiscussionArtifact(
        "discussion-artifact-v4-context",
        artifactId,
        requestId,
        jobId,
        snapshotId,
        instruction,
        title,
        sourceType,
        sourceMode,
        scope,
        issues,
        claims,
        relations,
        assessments,
        evidence,
        List.copyOf(notes),
        usage,
        stance);
  }

  /** 输入分母和本轮选取范围，避免把抽样结果说成所有讨论。 */
  public record Scope(
      int totalComments,
      int rootComments,
      int replyComments,
      int selectedThreads,
      boolean truncated,
      Integer selectedComments,
      Integer selectedTextChars,
      String selectionVersion) {
    /** 历史结构演示与旧产物仍按原5字段读取，不为未知的处理数伪造默认值。 */
    public Scope(
        int totalComments,
        int rootComments,
        int replyComments,
        int selectedThreads,
        boolean truncated) {
      this(
          totalComments, rootComments, replyComments, selectedThreads, truncated, null, null, null);
    }
  }

  /** 一个议题绑定触发它的原评论，并说明当前是否已经完成语义归纳。 */
  public record Issue(String id, String question, String summary, List<String> evidenceIds) {}

  /** 观点陈述与推理状态分开；UNKNOWN 表示尚未做可靠语义判断。 */
  public record Claim(
      String id,
      String issueId,
      String statement,
      String reasoningStatus,
      List<String> evidenceIds) {}

  /** REPLY_TO 是可由数据确定的结构关系，SUPPORT/REBUT 等语义关系需模型与质量验证。 */
  public record Relation(
      String fromClaimId,
      String toClaimId,
      String type,
      String semanticStatus,
      List<String> evidenceIds) {}

  /** 评议允许证据不足，不能为满足用户问题而强行宣布胜负。 */
  public record Assessment(
      String issueId, String verdict, String conclusion, String reason, List<String> evidenceIds) {}

  /** 引用保存当前快照中的原文和父级身份，页面可回到材料核对。 */
  public record Evidence(
      String id,
      String sourceCommentId,
      String parentCommentId,
      String text,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String sourceRole,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String materialId,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String title,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Long startMs,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Long endMs) {
    /** 历史证据只有评论字段，序列化时保持旧形状，前端按旧版评论理解。 */
    public Evidence(String id, String sourceCommentId, String parentCommentId, String text) {
      this(id, sourceCommentId, parentCommentId, text, null, null, null, null, null);
    }
  }
}
