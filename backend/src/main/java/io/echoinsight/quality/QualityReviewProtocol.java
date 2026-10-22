/* 文件职责：定义不带模型预测的人工核对输入、确定性抽样和人工意见校验。 */
package io.echoinsight.quality;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisContextStore;
import io.echoinsight.material.ReferenceMaterialStore;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 人工意见是声明，不认证评审人，也不自动升级为独立裁决金标准。 */
public final class QualityReviewProtocol {
  private QualityReviewProtocol() {}

  public static final String VERSION = "quality-blind-input-v1";

  /** count是归属目标数，祖先只供语境；seed固定抽样顺序，不基于模型引用挑选。 */
  public record Create(String requestId, int count, String seed) {}

  /** 保留当前原文与真实来源身份，isTarget不由模型产生。 */
  public record Comment(
      String id, String sourceCommentId, String parentId, String text, boolean target) {}

  /** input不含任何模型输出；背景仅来自原请求的明确材料，追问历史另行拒绝。 */
  public record Input(
      String version,
      String projectId,
      String requestId,
      String jobId,
      String snapshotId,
      String snapshotSha256,
      String planSha256,
      String instruction,
      String title,
      String sourceType,
      String sourceContentId,
      String mode,
      String proposition,
      String seed,
      int requestedTargets,
      int eligibleTargets,
      List<String> targetIds,
      List<Comment> comments,
      List<AnalysisContextStore.Source> background,
      List<String> notes) {}

  /** 同一目标可含多种立场；condition只在限定支持时必填，未知不是中立。 */
  public record Label(
      String commentId,
      String coverage,
      List<String> positions,
      String statement,
      String condition,
      String rationale,
      List<String> evidenceIds) {}

  /** previousId用于防止多标签页互相覆盖；所有保存都是追加快照。 */
  public record RecordBody(
      String reviewer, String exposure, String kind, String previousId, List<Label> labels) {}

  /** 1、按源ID与固定seed排序；2、逐目标保留完整祖先；3、超界整目标跳过；4、报告实际样本数。 */
  public static List<Comment> sample(
      ImportDocument doc, Set<String> eligible, int count, String seed) {
    if (count < 1 || count > 20 || seed == null || !seed.matches("[A-Za-z0-9._:-]{1,64}"))
      throw bad("人工核对目标1～20条，抽样标记为1～64位英文数字及._:-");
    Map<String, ImportDocument.Comment> byId = new LinkedHashMap<>();
    for (var c : doc.comments())
      if (byId.put(c.id(), c) != null)
        throw new IllegalStateException("Duplicate review source identity");
    if (!byId.keySet().containsAll(eligible))
      throw new IllegalStateException("Review scope identity mismatch");
    var ordered =
        doc.comments().stream()
            .filter(c -> eligible.contains(c.id()))
            .sorted(
                Comparator.comparing(
                        (ImportDocument.Comment c) ->
                            ReferenceMaterialStore.hash(seed + "\n" + c.sourceCommentId()))
                    .thenComparing(ImportDocument.Comment::sourceCommentId)
                    .thenComparing(ImportDocument.Comment::id))
            .toList();
    Set<String> selected = new LinkedHashSet<>(), targets = new LinkedHashSet<>();
    int chars = 0;
    for (var c : ordered) {
      if (targets.size() == count) break;
      var chain = new LinkedHashSet<String>();
      String at = c.id();
      boolean valid = true;
      while (at != null) {
        if (!chain.add(at) || !byId.containsKey(at)) {
          valid = false;
          break;
        }
        at = byId.get(at).parentId();
      }
      if (!valid) continue;
      var additions = chain.stream().filter(id -> !selected.contains(id)).toList();
      int addedChars = additions.stream().mapToInt(id -> byId.get(id).text().length()).sum();
      if (selected.size() + additions.size() > 120 || chars + addedChars > 80000) continue;
      selected.addAll(additions);
      chars += addedChars;
      targets.add(c.id());
    }
    if (targets.isEmpty()) throw bad("当前没有父语境完整且满足边界的人工核对目标");
    // 父先于子，保证表单中的语境可直接阅读；目标与语境角色独立。
    List<Comment> result = new ArrayList<>();
    Set<String> emitted = new HashSet<>();
    for (String id : selected) {
      var chain = new ArrayList<ImportDocument.Comment>();
      String at = id;
      while (at != null && !emitted.contains(at)) {
        var c = byId.get(at);
        chain.add(c);
        at = c.parentId();
      }
      Collections.reverse(chain);
      for (var c : chain)
        if (emitted.add(c.id()))
          result.add(
              new Comment(
                  c.id(), c.sourceCommentId(), c.parentId(), c.text(), targets.contains(c.id())));
    }
    return List.copyOf(result);
  }

  /** 草稿允许未完成字段；提交必须覆盖每个目标，空缺不能自动变成UNKNOWN。 */
  public static RecordBody validate(Input input, RecordBody body) {
    if (body == null
        || body.reviewer() == null
        || body.reviewer().isBlank()
        || body.reviewer().length() > 80
        || !Set.of("NOT_VIEWED", "VIEWED", "UNKNOWN")
            .contains(Objects.toString(body.exposure(), ""))
        || !Set.of("DRAFT", "SUBMISSION").contains(Objects.toString(body.kind(), "")))
      throw bad("请填写1～80字署名、是否看过预测及保存方式");
    if (body.previousId() != null && !body.previousId().matches("[a-f0-9-]{36}"))
      throw bad("核对记录身份无效");
    if (body.labels() == null || body.labels().size() > 20) throw bad("核对意见必须为最多20条目标判断");
    Set<String> targets = new LinkedHashSet<>(input.targetIds()),
        used = new HashSet<>(),
        evidence = new HashSet<>();
    input.comments().forEach(c -> evidence.add(c.id()));
    input.background().forEach(c -> evidence.add(c.id()));
    List<Label> clean = new ArrayList<>();
    for (var label : body.labels()) {
      if (label == null
          || !targets.contains(label.commentId())
          || !used.add(label.commentId())
          || label.coverage() == null
          || !Set.of("", "CLAIMS", "QUESTION", "IRRELEVANT", "UNKNOWN").contains(label.coverage())
          || label.positions() == null
          || label.positions().size() > 2
          || label.positions().stream().anyMatch(Objects::isNull)
          || new HashSet<>(label.positions()).size() != label.positions().size()
          || !Set.of("SUPPORT", "OPPOSE", "QUALIFIED", "UNKNOWN").containsAll(label.positions())
          || !bounded(label.statement(), 2000)
          || !bounded(label.condition(), 1000)
          || !bounded(label.rationale(), 2000)
          || label.evidenceIds() == null
          || label.evidenceIds().size() > 20
          || label.evidenceIds().stream().anyMatch(Objects::isNull)
          || new HashSet<>(label.evidenceIds()).size() != label.evidenceIds().size()
          || !evidence.containsAll(label.evidenceIds())) throw bad("核对意见的目标、字段或原文引用无效");
      if (!"STANCE".equals(input.mode()) && !label.positions().isEmpty())
        throw bad("普通讨论核对不填写命题立场");
      if (!label.coverage().equals("CLAIMS") && !label.positions().isEmpty())
        throw bad("非主张判断不能附带立场");
      if ("SUBMISSION".equals(body.kind())) {
        if (label.coverage().isEmpty()
            || label.rationale().isBlank()
            || !label.evidenceIds().contains(label.commentId())
            || (label.coverage().equals("CLAIMS")
                && (label.statement().isBlank()
                    || ("STANCE".equals(input.mode()) && label.positions().isEmpty())))
            || (label.positions().contains("QUALIFIED") && label.condition().isBlank()))
          throw bad("提交前请完成每条目标的判断、理由和依据，限定支持还需条件");
      }
      clean.add(
          new Label(
              label.commentId(),
              label.coverage(),
              label.positions().stream().sorted().toList(),
              label.statement().strip(),
              label.condition().strip(),
              label.rationale().strip(),
              label.evidenceIds().stream().sorted().toList()));
    }
    if ("SUBMISSION".equals(body.kind()) && !used.equals(targets))
      throw bad("提交前请完成全部抽样目标，语境评论不重复标注");
    clean.sort(Comparator.comparing(Label::commentId));
    return new RecordBody(
        body.reviewer().strip(),
        body.exposure(),
        body.kind(),
        body.previousId(),
        List.copyOf(clean));
  }

  /** 统一使用有界非null字符串，避免界面把缺字段悄悄解释为空判断。 */
  private static boolean bounded(String value, int max) {
    return value != null && value.length() <= max;
  }

  /** 只返回固定中文原因，不把用户原文或数据库异常拼入响应。 */
  public static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
