/* 文件职责：验证冻结清单的幂等、项目归属、版本歧义和后续更新不改历史。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.project.ProjectImportService;
import io.echoinsight.project.ProjectStore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/** 仅追加独立项目范围的合成材料，不采集平台或调用模型。 */
@SpringBootTest(properties = {"echoinsight.projects.workspace=manifest-it"})
class InputManifestIT {
  @Autowired InputManifestStore manifests;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;

  /** 同键同逻辑顺序重排仍复用，改变范围/名称则冲突；新版本不追溯修改旧集合。 */
  @Test
  void freezesMultipleWorksAndKeepsHistoryAfterLaterRevision() {
    String project = project();
    String source = key();
    String first = imports.ingest(project, document(source, "旧正文")).snapshotId();
    String other = imports.ingest(project, document(key(), "另一作品")).snapshotId();
    String key = key();
    var initial = manifests.create(project, key, "两份作品", List.of(first, other));
    var repeat = manifests.create(project, key, "两份作品", List.of(other, first));
    assertThat(repeat.summary().id()).isEqualTo(initial.summary().id());
    imports.ingest(project, document(source, "新正文"));
    assertThat(manifests.get(project, initial.summary().id())).isEqualTo(initial);
    assertThat(initial.items())
        .extracting(InputManifestStore.Item::snapshotId)
        .containsExactlyInAnyOrder(first, other);
    assertThat(initial.items()).allSatisfy(item -> assertThat(item.documentSha256()).hasSize(64));
    assertThatThrownBy(() -> manifests.create(project, key, "改变名称", List.of(first, other)))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 同作品选两个版本和跨项目快照都拒绝，失败不会留下半份清单。 */
  @Test
  void rejectsAmbiguousVersionsAndForeignMembersAtomically() {
    String project = project();
    String source = key();
    String first = imports.ingest(project, document(source, "版本一")).snapshotId();
    String second = imports.ingest(project, document(source, "版本二")).snapshotId();
    assertThatThrownBy(() -> manifests.create(project, key(), "歧义", List.of(first, second)))
        .isInstanceOf(ResponseStatusException.class);
    String foreign = imports.ingest(project(), document(key(), "其他项目")).snapshotId();
    assertThatThrownBy(() -> manifests.create(project, key(), "越界", List.of(first, foreign)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(manifests.list(project, 1, 10).total()).isZero();
  }

  /** 集合详情按项目路径核对范围，不能拿其他项目的清单ID绕过成员选择。 */
  @Test
  void detailsAreProjectScopedAndCountsAreReal() {
    String project = project();
    String snapshot = imports.ingest(project, document(key(), "合成材料")).snapshotId();
    var manifest = manifests.create(project, key(), "明确范围", List.of(snapshot));
    assertThat(manifests.list(project, 1, 10).items().getFirst().itemCount()).isEqualTo(1);
    assertThatThrownBy(() -> manifests.get(project(), manifest.summary().id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 供后续集合差异/调度验收共享最小合成材料，不包含真实用户正文。 */
  static ImportDocument document(String source, String text) {
    var time = OffsetDateTime.parse("2026-10-17T08:00:00Z");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("local", "SYNTHETIC", source, "fixture", "集合合成验收", time),
        List.of(new ImportDocument.Comment("c1", "1", null, text, time)),
        List.of());
  }

  private String project() {
    return projects.create(key(), "集合验收", "检查冻结范围").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }
}
