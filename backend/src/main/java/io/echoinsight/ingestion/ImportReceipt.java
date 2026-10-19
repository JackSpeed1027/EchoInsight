/* 文件职责：返回导入回执，保留旧计数字段并提供明确的原始行数与快照数别名。 */
package io.echoinsight.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;

/** importedComments 为去重前行数，eligibleComments 为规范化快照的评论数量。 */
public record ImportReceipt(
    String id,
    String contentId,
    String snapshotId,
    String status,
    int importedComments,
    int eligibleComments,
    int duplicateComments,
    int newRevisions,
    boolean snapshotReused) {
  /** 原始上传记录数；同一来源的重复记录仍计入。 */
  @JsonProperty("rawRecordCount")
  public int rawRecordCount() {
    return importedComments;
  }

  /** 当前完整快照中的有效评论数量，不代表平台总量。 */
  @JsonProperty("snapshotCommentCount")
  public int snapshotCommentCount() {
    return eligibleComments;
  }
}
