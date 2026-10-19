/* 文件职责：在真实数据库验证冻结集合比较、幂等历史、原文指纹校验及项目隔离。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.project.ProjectImportService;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 只追加独立合成项目；测试修改自己的清单指纹，不接触真实用户材料。 */
@SpringBootTest(properties = "echoinsight.projects.workspace=comparison-it")
class ManifestComparisonIT {
  @Autowired InputManifestStore manifests;
  @Autowired ManifestComparisonStore comparisons;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired JdbcTemplate db;

  /** 同作品正文修订与新增作品分开，重发读同一结果，跨项目读取拒绝。 */
  @Test
  void comparesFrozenVersionsAndPersistsIdempotentResult() {
    String project = project();
    String source = key();
    String old = imports.ingest(project, InputManifestIT.document(source, "以前")).snapshotId();
    var base = manifests.create(project, key(), "以前", List.of(old));
    String revised = imports.ingest(project, InputManifestIT.document(source, "现在")).snapshotId();
    String added = imports.ingest(project, InputManifestIT.document(key(), "新增作品")).snapshotId();
    var target = manifests.create(project, key(), "现在", List.of(revised, added));
    String requestKey = key();
    var result =
        comparisons.create(project, requestKey, base.summary().id(), target.summary().id());
    assertThat(result.result().works())
        .extracting(ManifestComparisonStore.Work::status)
        .containsExactlyInAnyOrder("ADDED_WORK", "REVISED_WORK");
    assertThat(
            result.result().works().stream()
                .filter(work -> work.comments() != null)
                .findFirst()
                .orElseThrow()
                .comments()
                .revised())
        .isEqualTo(1);
    assertThat(comparisons.create(project, requestKey, base.summary().id(), target.summary().id()))
        .isEqualTo(result);
    assertThatThrownBy(
            () ->
                comparisons.create(project, requestKey, target.summary().id(), base.summary().id()))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> comparisons.get(project(), result.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 指纹损坏的合成清单不能继续比较；数据库事务回滚，不留下伪结果。 */
  @Test
  void refusesCorruptFrozenDigest() {
    String project = project();
    String snapshot = imports.ingest(project, InputManifestIT.document(key(), "指纹验收")).snapshotId();
    var base = manifests.create(project, key(), "原集合", List.of(snapshot));
    var target = manifests.create(project, key(), "目标集合", List.of(snapshot));
    db.update(
        "UPDATE input_manifest_item SET document_sha256=? WHERE manifest_id=?",
        "0".repeat(64),
        target.summary().id());
    assertThatThrownBy(
            () -> comparisons.create(project, key(), base.summary().id(), target.summary().id()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM manifest_comparison WHERE project_id=?",
                Integer.class,
                project))
        .isZero();
  }

  private String project() {
    return projects.create(key(), "差分验收", "确认来源和失效").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }
}
