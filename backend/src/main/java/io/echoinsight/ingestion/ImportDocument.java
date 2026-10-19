/* 文件职责：定义不可变导入契约；缺失父上下文单独声明，原始父引用不被改写。 */
package io.echoinsight.ingestion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** 评论、字幕与上下文缺口分别保存；新增可空字段不改变旧文档的JSON身份。 */
public record ImportDocument(
    @NotNull @Pattern(regexp = "1\\.0") String schemaVersion,
    @NotNull @Valid Content content,
    @NotEmpty @Size(max = 5000) List<@NotNull @Valid Comment> comments,
    @NotNull @Size(max = 1000) List<@NotNull @Valid Segment> transcriptSegments,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Size(max = 5000)
        List<@NotNull @Valid MissingParent> missingParents) {
  /** 兼容既有四字段构造及历史指纹，不为空缺口伪造新字段。 */
  public ImportDocument(
      String schemaVersion,
      Content content,
      List<Comment> comments,
      List<Segment> transcriptSegments) {
    this(schemaVersion, content, comments, transcriptSegments, null);
  }

  /** 只记录确实缺失的原始父引用；这不是一条虚构父评论，也不是新的回复边。 */
  public record MissingParent(
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String commentId,
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String parentCommentId) {}

  /** 调用方得到只读空列表；JSON仍保留历史null字段的省略语义。 */
  public List<MissingParent> contextGaps() {
    return missingParents == null ? List.of() : missingParents;
  }

  public record Content(
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String id,
      @NotNull @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String sourceType,
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String sourceContentId,
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String creatorId,
      @NotBlank @Size(max = 200) String title,
      @NotNull OffsetDateTime publishedAt) {}

  public record Comment(
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String id,
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String sourceCommentId,
      @Size(max = 128) @Pattern(regexp = "\\S+") String parentId,
      @NotBlank @Size(max = 10000) String text,
      @NotNull OffsetDateTime occurredAt) {}

  public record Segment(
      @NotBlank @Size(max = 128) @Pattern(regexp = "\\S+") String id,
      @NotNull @PositiveOrZero Long startMs,
      @NotNull @Positive Long endMs,
      @NotBlank @Size(max = 10000) String text) {}
}
