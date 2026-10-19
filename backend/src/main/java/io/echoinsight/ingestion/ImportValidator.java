/* 文件职责：校验导入图、显式上下文缺口与规范化顺序，不修造用户原始关系。 */
package io.echoinsight.ingestion;

import jakarta.validation.Validator;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Component;

/** 缺口只能精确对应不存在的父评论；未声明断边、循环及冲突仍拒绝。 */
@Component
public class ImportValidator {
  private final Validator validator;

  /** 复用Bean Validation字段边界，再校验跨字段和图约束。 */
  public ImportValidator(Validator validator) {
    this.validator = validator;
  }

  /** 1、校验字段；2、去重；3、核对显式缺口和无环图；4、统一时区及顺序。 */
  public ImportDocument normalize(ImportDocument input) {
    if (input == null) throw new ImportFailure("$", "需要 JSON 对象");
    var violations = validator.validate(input);
    if (!violations.isEmpty())
      throw new ImportFailure(
          violations.stream()
              .map(
                  v -> new ImportFailure.Issue(v.getPropertyPath().toString(), "字段缺失、长度超限或格式不符合契约"))
              .sorted(Comparator.comparing(ImportFailure.Issue::path))
              .toList());
    Map<String, ImportDocument.Comment> sources = new HashMap<>();
    Map<String, ImportDocument.Comment> ids = new HashMap<>();
    List<ImportDocument.Comment> comments = new ArrayList<>();
    for (int i = 0; i < input.comments().size(); i++) {
      var c = input.comments().get(i);
      var normalized =
          new ImportDocument.Comment(
              c.id(),
              c.sourceCommentId(),
              c.parentId(),
              c.text(),
              c.occurredAt().withOffsetSameInstant(ZoneOffset.UTC));
      var previous = sources.putIfAbsent(c.sourceCommentId(), normalized);
      if (previous != null) {
        if (!previous.equals(normalized))
          throw new ImportFailure(
              "comments[" + i + "].sourceCommentId", "同一来源评论 ID 出现冲突记录，不能静默选择其中一条");
        continue;
      }
      if (ids.putIfAbsent(c.id(), normalized) != null)
        throw new ImportFailure("comments[" + i + "].id", "同一输入中的评论 ID 不能指向不同来源评论");
      comments.add(normalized);
    }
    Map<String, String> gaps = new HashMap<>();
    for (var gap : input.contextGaps()) {
      var comment = ids.get(gap.commentId());
      if (comment == null
          || !Objects.equals(comment.parentId(), gap.parentCommentId())
          || ids.containsKey(gap.parentCommentId())
          || gaps.putIfAbsent(gap.commentId(), gap.parentCommentId()) != null)
        throw new ImportFailure("missingParents", "缺口必须唯一对应当前评论中确实未提供的父引用");
    }
    Set<String> resolved = new HashSet<>();
    for (int i = 0; i < comments.size(); i++) {
      var c = comments.get(i);
      if (c.parentId() != null && !ids.containsKey(c.parentId()) && !gaps.containsKey(c.id()))
        throw new ImportFailure("comments", "父评论必须存在于当前输入中");
      Set<String> path = new HashSet<>();
      String cursor = c.id();
      while (cursor != null && !resolved.contains(cursor)) {
        if (!path.add(cursor)) throw new ImportFailure("comments", "父评论引用不能形成循环");
        var node = ids.get(cursor);
        if (node == null) throw new ImportFailure("comments", "父评论必须存在于当前输入中");
        cursor = gaps.containsKey(node.id()) ? null : node.parentId();
      }
      resolved.addAll(path);
    }
    Set<String> segments = new HashSet<>();
    for (int i = 0; i < input.transcriptSegments().size(); i++) {
      var s = input.transcriptSegments().get(i);
      if (!segments.add(s.id()))
        throw new ImportFailure("transcriptSegments[" + i + "].id", "字幕 ID 重复");
      if (s.startMs() >= s.endMs())
        throw new ImportFailure("transcriptSegments[" + i + "]", "字幕结束时间必须大于开始时间");
    }
    var content = input.content();
    comments.sort(Comparator.comparing(ImportDocument.Comment::sourceCommentId));
    return new ImportDocument(
        input.schemaVersion(),
        new ImportDocument.Content(
            content.id(),
            content.sourceType(),
            content.sourceContentId(),
            content.creatorId(),
            content.title(),
            content.publishedAt().withOffsetSameInstant(ZoneOffset.UTC)),
        List.copyOf(comments),
        input.transcriptSegments().stream()
            .sorted(Comparator.comparing(ImportDocument.Segment::id))
            .toList(),
        gaps.isEmpty()
            ? null
            : input.contextGaps().stream()
                .sorted(Comparator.comparing(ImportDocument.MissingParent::commentId))
                .toList());
  }
}
