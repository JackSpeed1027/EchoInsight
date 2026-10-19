package io.echoinsight.infrastructure.persistence;

import io.echoinsight.ingestion.ImportReceipt;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ImportMapper {
  @Insert(
      "INSERT INTO content_item(id,source_type,source_content_id) VALUES(#{id},#{type},#{source})"
          + " ON DUPLICATE KEY UPDATE id=id")
  void ensureContent(
      @Param("id") String id, @Param("type") String type, @Param("source") String source);

  // A current locking read serializes imports for one work and sees the preceding commit.
  @Select(
      "SELECT id FROM content_item WHERE source_type=#{type} AND source_content_id=#{source} FOR"
          + " UPDATE")
  String lockContent(@Param("type") String type, @Param("source") String source);

  @Select(
      "SELECT id FROM data_snapshot WHERE content_id=#{contentId} AND input_hash=#{hash} FOR"
          + " UPDATE")
  String findSnapshot(@Param("contentId") String contentId, @Param("hash") String hash);

  @Select(
      "SELECT COUNT(*) FROM comment_revision WHERE content_id=#{contentId} AND"
          + " source_comment_id=#{sourceId} AND revision_hash=#{hash}")
  int revisionExists(
      @Param("contentId") String contentId,
      @Param("sourceId") String sourceId,
      @Param("hash") String hash);

  @Insert(
      "INSERT INTO comment_revision(id,content_id,source_comment_id,revision_hash,payload_json)"
          + " VALUES(#{id},#{contentId},#{sourceId},#{hash},#{json})")
  void insertRevision(
      @Param("id") String id,
      @Param("contentId") String contentId,
      @Param("sourceId") String sourceId,
      @Param("hash") String hash,
      @Param("json") String json);

  @Insert(
      "INSERT INTO data_snapshot(id,content_id,input_hash,document_json)"
          + " VALUES(#{id},#{contentId},#{hash},#{json})")
  void insertSnapshot(
      @Param("id") String id,
      @Param("contentId") String contentId,
      @Param("hash") String hash,
      @Param("json") String json);

  @Insert(
      "INSERT INTO"
          + " ingestion_import(id,content_id,snapshot_id,status,imported_comments,eligible_comments,duplicate_comments,new_revisions,snapshot_reused)"
          + " VALUES(#{id},#{contentId},#{snapshotId},#{status},#{importedComments},#{eligibleComments},#{duplicateComments},#{newRevisions},#{snapshotReused})")
  void insertReceipt(ImportReceipt receipt);

  @Select(
      "SELECT"
          + " id,content_id,snapshot_id,status,imported_comments,eligible_comments,duplicate_comments,new_revisions,snapshot_reused"
          + " FROM ingestion_import WHERE id=#{id}")
  ImportReceipt receipt(String id);

  @Select("SELECT id,content_id,input_hash,document_json FROM data_snapshot WHERE id=#{id}")
  SnapshotRow snapshot(String id);

  record SnapshotRow(String id, String contentId, String inputHash, String documentJson) {}
}
