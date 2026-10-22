/* 文件职责：在隔离MySQL中验证资料导出/预检/新项目恢复、并发幂等和故障回滚，保留所有历史。 */
package io.echoinsight.archive;

import static io.echoinsight.archive.MaterialArchiveProtocol.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.content.ContentMaterialStore;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.material.*;
import io.echoinsight.project.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 真实数据库仅写本测试合成项目，worker/模型/付费/平台全关，不触碰用户配置或真实意见。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=material-archive-it",
      "echoinsight.worker.execution-scope=material-archive-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class MaterialArchiveWorkflowIT {
  @Autowired MaterialArchiveStore archives;
  @Autowired MaterialArchiveProtocol protocol;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired ContentMaterialStore contents;
  @Autowired ReferenceMaterialStore references;
  @MockitoSpyBean TranscriptMaterialStore subtitles;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @Autowired io.echoinsight.intelligence.AnalysisRequestStore requests;
  @Autowired io.echoinsight.orchestration.JobStore jobs;
  @Autowired io.echoinsight.orchestration.ProjectTaskQuery taskQuery;
  @Autowired io.echoinsight.orchestration.JobHistoryQuery history;
  @Autowired io.echoinsight.model.ConfiguredModelPlan plans;

  /** 导出包含四种原始资料，没有任务/模型/配置，并重复导出保持同哈希。 */
  @Test
  void exportsOnlyDefinedMaterialsDeterministically() {
    String p = fixture();
    var a = archives.export(p);
    assertThat(a.data().snapshots()).hasSize(1);
    assertThat(a.data().contents()).hasSize(2);
    assertThat(a.data().references()).hasSize(1);
    assertThat(a.data().subtitles()).hasSize(1);
    assertThat(archives.export(p)).isEqualTo(a);
    assertThat(protocol.encode(a))
        .doesNotContain("request_key", "apiKey", "execution_plan_json", "receipt_json");
    assertThat(projects.get(p).jobCount()).isZero();
  }

  /** 恢复到新项目并逐类核对哈希/版本父链/字幕作品；原项目数量不变。 */
  @Test
  void restorePreservesAllMaterialsAndLineage() {
    String p = fixture();
    var before = projects.get(p);
    var a = archives.export(p);
    var r = archives.restore(key(), body(a)).receipt();
    assertThat(r.projectId()).isNotEqualTo(p);
    assertThat(projects.get(p)).isEqualTo(before);
    assertThat(projects.get(r.projectId()).snapshotCount()).isEqualTo(1);
    for (var c : a.data().contents()) {
      var saved = contents.get(r.projectId(), r.contents().get(c.id()));
      assertThat(saved.summary().rawSha256()).isEqualTo(c.rawSha256());
      assertThat(saved.summary().segmentsSha256()).isEqualTo(c.segmentsSha256());
      assertThat(saved.summary().previousId())
          .isEqualTo(c.previousId() == null ? null : r.contents().get(c.previousId()));
    }
    for (var s : a.data().subtitles())
      assertThat(subtitles.get(r.projectId(), r.subtitles().get(s.id())).summary().snapshotId())
          .isEqualTo(r.snapshots().get(s.snapshotId()));
    for (var x : a.data().references())
      assertThat(references.get(r.projectId(), r.references().get(x.id())).text())
          .isEqualTo(x.text());
    assertThat(projects.get(r.projectId()).jobCount()).isZero();
  }

  /** 预检没有导入回执、材料或项目写入。 */
  @Test
  void previewIsReadOnly() {
    var a = archives.export(fixture());
    long before = projectCount(), receipts = count("ingestion_import");
    assertThat(archives.preview(body(a)).comments()).isEqualTo(2);
    assertThat(projectCount()).isEqualTo(before);
    assertThat(count("ingestion_import")).isEqualTo(receipts);
  }

  /** 同一恢复请求只提交一次资料及回执，网络重发返回相同身份。 */
  @Test
  void replayDoesNotImportAgain() {
    var a = archives.export(fixture());
    String k = key();
    var body = body(a);
    var first = archives.restore(k, body);
    long receipts = count("ingestion_import");
    var second = archives.restore(k, body);
    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.receipt()).isEqualTo(first.receipt());
    assertThat(count("ingestion_import")).isEqualTo(receipts);
  }

  /** 同键改归档、改目的要求均冲突，不向已经恢复的项目追加新资料。 */
  @Test
  void changedIntentConflictsWithoutMutation() {
    var a = archives.export(fixture());
    String k = key();
    var body = body(a);
    var r = archives.restore(k, body).receipt();
    long count = projectCount();
    var changed =
        protocol.seal(
            new Data(
                VERSION,
                a.data().project(),
                a.data().snapshots(),
                a.data().contents(),
                List.of(),
                a.data().subtitles()));
    assertThatThrownBy(
            () ->
                archives.restore(
                    k, new Restore(body.name(), body.analysisGoal(), changed.sha256(), changed)))
        .hasMessageContaining("请求键");
    assertThatThrownBy(
            () -> archives.restore(k, new Restore("另一名称", body.analysisGoal(), a.sha256(), a)))
        .hasMessageContaining("请求键");
    assertThat(projectCount()).isEqualTo(count);
    assertThat(projects.get(r.projectId()).snapshotCount()).isEqualTo(1);
  }

  /** 同时提交相同键在项目行串行化，只创建一份新项目。 */
  @Test
  void concurrentSameKeyCreatesOnlyOnce() throws Exception {
    var a = archives.export(fixture());
    String k = key();
    var body = body(a);
    long before = projectCount();
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<MaterialArchiveStore.Restored> action =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return archives.restore(k, body);
          };
      var x = pool.submit(action);
      var y = pool.submit(action);
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var left = x.get(30, TimeUnit.SECONDS);
      var right = y.get(30, TimeUnit.SECONDS);
      assertThat(left.receipt()).isEqualTo(right.receipt());
      assertThat(left.created()).isNotEqualTo(right.created());
    }
    assertThat(projectCount()).isEqualTo(before + 1);
  }

  /** 字幕写入阶段故障必须回滚此前项目、评论导入及内容/参考，不能留下半恢复项目。 */
  @Test
  void lateFailureRollsBackWholeRestore() {
    var a = archives.export(fixture());
    long p = projectCount(),
        im = count("ingestion_import"),
        cm = count("content_material"),
        rm = count("project_reference_material"),
        receipts = count("project_material_restore");
    doThrow(new IllegalStateException("synthetic late restore fault"))
        .when(subtitles)
        .create(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            eq("SRT"),
            any(byte[].class));
    assertThatThrownBy(() -> archives.restore(key(), body(a)))
        .hasMessageContaining("synthetic late restore fault");
    assertThat(projectCount()).isEqualTo(p);
    assertThat(count("ingestion_import")).isEqualTo(im);
    assertThat(count("content_material")).isEqualTo(cm);
    assertThat(count("project_reference_material")).isEqualTo(rm);
    assertThat(count("project_material_restore")).isEqualTo(receipts);
  }

  /** 持久原文损坏时导出500失败关闭，不重算哈希掩盖损坏。 */
  @Test
  void corruptStoredMaterialCannotBeExported() {
    String p = fixture(), id = contents.list(p, 1, 10).items().getFirst().id();
    db.update("UPDATE content_material SET raw_text='synthetic corrupted' WHERE id=?", id);
    assertThatThrownBy(() -> archives.export(p)).hasMessageContaining("hash mismatch");
  }

  /** 恢复复用本机同快照前再次核对持久原文；存在损坏不能静默挂载。 */
  @Test
  void corruptExistingSnapshotCannotBeReused() {
    var a = archives.export(fixture());
    long before = projectCount();
    db.update(
        "UPDATE data_snapshot SET document_json=CONCAT(document_json,' ') WHERE id=?",
        a.data().snapshots().getFirst().id());
    assertThatThrownBy(() -> archives.restore(key(), body(a)))
        .hasMessageContaining("snapshot corrupted");
    assertThat(projectCount()).isEqualTo(before);
  }

  /** 回执被损坏后重发拒绝，不创建新项目或臆造新的身份映射。 */
  @Test
  void corruptedReceiptIsNotRebuilt() {
    var a = archives.export(fixture());
    String k = key();
    var b = body(a);
    var r = archives.restore(k, b).receipt();
    db.update(
        "UPDATE project_material_restore SET receipt_json=CONCAT(receipt_json,' ') WHERE id=?",
        r.id());
    long before = projectCount();
    assertThatThrownBy(() -> archives.restore(k, b)).hasMessageContaining("receipt corrupted");
    assertThat(projectCount()).isEqualTo(before);
  }

  /** 归档不携带采集完成证据；恢复资料只知道存储原文，覆盖仍未知。 */
  @Test
  void restoreDoesNotPromotePlatformCoverageOrCreateJobs() {
    var a = archives.export(fixture());
    var r = archives.restore(key(), body(a)).receipt();
    var s = projects.snapshot(r.projectId(), r.snapshots().values().iterator().next());
    assertThat(s.collectionId()).isNull();
    assertThat(s.sourceCompleteness()).isNull();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_request WHERE project_id=?",
                Long.class,
                r.projectId()))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM project_material_restore WHERE project_id=?",
                Long.class,
                r.projectId()))
        .isEqualTo(1);
  }

  /** 不同服务器工作范围的项目统一404，不以猜UUID读取别处原文。 */
  @Test
  void exportEnforcesWorkspaceScope() {
    String p = fixture();
    db.update("UPDATE business_project SET workspace_scope='material-archive-other' WHERE id=?", p);
    assertThatThrownBy(() -> archives.export(p)).hasMessageContaining("404");
  }

  /** 确认哈希不匹配或外包被篡改时写入前拒绝。 */
  @Test
  void failedPreviewCannotWriteProject() {
    var a = archives.export(fixture());
    long before = projectCount();
    assertThatThrownBy(() -> archives.restore(key(), new Restore("新名称", "目标", "0".repeat(64), a)))
        .hasMessageContaining(HASH);
    assertThatThrownBy(
            () ->
                archives.restore(
                    key(),
                    new Restore("新名称", "目标", a.sha256(), new Archive(a.data(), "0".repeat(64)))))
        .hasMessageContaining(HASH);
    assertThat(projectCount()).isEqualTo(before);
  }

  /** 内容材料无需伪造评论也能独立归档/恢复。 */
  @Test
  void contentOnlyProjectRoundTrips() {
    String p = projects.create(key(), "仅文本", "内容研究").project().id();
    contents.create(p, key(), "笔记", "自写", null, "MD", bytes("# 合成笔记\n无评论也可研究"));
    var r = archives.restore(key(), body(archives.export(p))).receipt();
    assertThat(projects.get(r.projectId()).snapshotCount()).isZero();
    assertThat(projects.get(r.projectId()).contentMaterialCount()).isEqualTo(1);
  }

  /** 超过完整归档条数时拒绝，而不是导出列表第一页。 */
  @Test
  void overviewAndExportRejectTooManyMaterials() {
    String p = fixture();
    String source = contents.list(p, 1, 10).items().getFirst().id();
    for (int n = 0; n < 99; n++)
      db.update(
          "INSERT INTO"
              + " content_material(id,project_id,previous_id,version_depth,request_key,title,source_description,format_name,raw_text,raw_sha256,segments_json,segments_sha256,segment_count,text_chars)"
              + " SELECT"
              + " ?,project_id,NULL,1,?,title,source_description,format_name,raw_text,raw_sha256,segments_json,segments_sha256,segment_count,text_chars"
              + " FROM content_material WHERE id=?",
          key(),
          key(),
          source);
    assertThat(archives.overview(p).contents()).isEqualTo(101);
    assertThat(archives.overview(p).withinBounds()).isFalse();
    assertThatThrownBy(() -> archives.export(p)).hasMessageContaining(LIMIT);
  }

  /** HTTP下载安全头、预检无写入、恢复201/重发200与未知字段400一致。 */
  @Test
  void httpContractsAndErrorPrivacy() throws Exception {
    String p = fixture();
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key());
    var download = http.getForEntity("/api/v1/projects/" + p + "/material-archive", String.class);
    assertThat(download.getStatusCode().value()).isEqualTo(200);
    assertThat(download.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(download.getHeaders().getFirst("Content-Disposition"))
        .contains("echoinsight-materials.json");
    var a = json.readValue(download.getBody(), Archive.class);
    var request = new HttpEntity<>(body(a), headers);
    long before = projectCount();
    assertThat(
            http.postForEntity("/api/v1/material-archives/preview", request, String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(projectCount()).isEqualTo(before);
    var first = http.postForEntity("/api/v1/material-archives/restore", request, String.class);
    assertThat(first.getStatusCode().value()).isEqualTo(201);
    assertThat(
            http.postForEntity("/api/v1/material-archives/restore", request, String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    var bad = json.valueToTree(body(a));
    ((com.fasterxml.jackson.databind.node.ObjectNode) bad).put("sql", "PRIVATE-ERROR-CONTENT");
    var rejected =
        http.postForEntity(
            "/api/v1/material-archives/preview", new HttpEntity<>(bad, headers), String.class);
    assertThat(rejected.getStatusCode().value()).isEqualTo(400);
    assertThat(rejected.getBody())
        .doesNotContain("PRIVATE-ERROR-CONTENT", "SELECT", "documentJson");
  }

  /** 空项目归档合法，恢复只建立新的长期业务容器。 */
  @Test
  void emptyProjectRestoreIsExplicitAndSafe() {
    String p = projects.create(key(), "空项目", "准备研究").project().id();
    var r = archives.restore(key(), body(archives.export(p))).receipt();
    assertThat(r.snapshots()).isEmpty();
    assertThat(r.contents()).isEmpty();
    assertThat(projects.get(r.projectId()).jobCount()).isZero();
  }

  /** 共享原文不共享原项目的讨论任务或恢复前全局反馈；恢复后明确新建任务仍可见。 */
  @Test
  void sharedSnapshotDoesNotInheritOldTaskHistory() {
    String source = fixture();
    String sid = projects.snapshots(source, 1, 10).items().getFirst().snapshotId();
    requests.createWithScope(
        source, key(), sid, "总结主要观点", false, List.of(), true, null, "PREVIEW", false);
    var oldFeedback = jobs.createFromApi(sid, key(), plans.current());
    var a = archives.export(source);
    var restored = archives.restore(key(), body(a)).receipt();
    String target = restored.projectId(), restoredSnapshot = restored.snapshots().get(sid);
    assertThat(projects.get(source).jobCount()).isEqualTo(2);
    assertThat(projects.get(target).jobCount()).isZero();
    assertThat(projects.snapshot(target, restoredSnapshot).latestJobId()).isNull();
    assertThat(taskQuery.list(target, "ALL", "ALL", 1, 10).total()).isZero();
    assertThat(history.list(1, 10, "ALL", target).total()).isZero();
    assertThat(jobs.get(oldFeedback.id()).status()).isEqualTo("PENDING");
    var fresh = jobs.createFromApi(restoredSnapshot, key(), plans.current());
    requests.createWithScope(
        source, key(), sid, "总结新的讨论问题", false, List.of(), true, null, "PREVIEW", false);
    assertThat(projects.get(target).jobCount()).isEqualTo(1);
    assertThat(taskQuery.list(target, "ALL", "ALL", 1, 10).items())
        .extracting(x -> x.id())
        .containsExactly(fresh.id());
    assertThat(history.list(1, 10, "ALL", target).items())
        .extracting(x -> x.id())
        .containsExactly(fresh.id());
  }

  /** 合成样本含评论回复、两个内容版本、参考说明和同作品字幕，所有身份独立。 */
  private String fixture() {
    String p = projects.create(key(), "资料恢复合成项目", "验证原始资料可携带性").project().id();
    var time = OffsetDateTime.parse("2026-10-22T00:00:00Z");
    var doc =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("raw-" + key(), "SYNTHETIC", key(), "creator", "合成作品", time),
            List.of(
                new ImportDocument.Comment("a", "source-a", null, "原始主张", time),
                new ImportDocument.Comment("b", "source-b", "a", "带语境回复", time)),
            List.of());
    String sid = imports.ingest(p, doc).snapshotId();
    var first = contents.create(p, key(), "文本", "自写", null, "TXT", bytes("\ufeff原始笔记😀\r\n保留换行"));
    contents.create(p, key(), "文本修订", "自写", first.summary().id(), "TXT", bytes("修订版本，旧版保留"));
    references.create(p, key(), "参考说明", "这是一条自行编写的背景，不是外部事实。", "自写");
    subtitles.create(
        p, key(), sid, "合成字幕", "自写", "SRT", bytes("1\n00:00:00,000 --> 00:00:01,000\n合成字幕原文\n"));
    return p;
  }

  /** 目的字段与原包哈希明确绑定，每例使用新的可读项目身份。 */
  private Restore body(Archive a) {
    return new Restore("合成恢复-" + key(), "验证恢复，不自动分析", a.sha256(), a);
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private long projectCount() {
    return db.queryForObject(
        "SELECT COUNT(*) FROM business_project WHERE workspace_scope='material-archive-it'",
        Long.class);
  }

  private long count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }
}
