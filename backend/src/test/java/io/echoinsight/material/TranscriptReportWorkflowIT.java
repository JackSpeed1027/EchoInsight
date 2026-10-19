/* 文件职责：用真实MySQL和合成文件验证字幕版本/请求冻结/覆盖/报告，所有平台和模型自动执行关闭。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.echoinsight.collection.*;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.*;
import io.echoinsight.orchestration.*;
import io.echoinsight.project.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

/** 独立workspace的追加测试，保留夹具/产物；投影夹具不是实际LLM语义成绩。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=transcript-report-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=true",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class TranscriptReportWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired TranscriptMaterialStore transcripts;
  @Autowired AnalysisContextStore contexts;
  @Autowired AnalysisRequestStore requests;
  @Autowired AnalysisPreflight preflight;
  @Autowired SourceCoverageStore coverage;
  @Autowired AnalysisWorker worker;
  @Autowired JobStore jobs;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestRestTemplate http;
  @Autowired CollectionStore collections;
  @Autowired CollectionPublicationService publication;
  @Autowired BilibiliCliAdapter adapter;

  /** 上传同键返回同版本，改正文409；作品评论数不因独立字幕增加。 */
  @Test
  void immutableSubtitleVersionAndIdempotentUpload() {
    String p = project(), s = snapshot(p), key = key();
    var first = transcripts.create(p, key, s, "合成字幕", "自制合成", "SRT", srt("时间机器"));
    assertThat(transcripts.create(p, key, s, "合成字幕", "自制合成", "SRT", srt("时间机器")).summary().id())
        .isEqualTo(first.summary().id());
    assertThatThrownBy(() -> transcripts.create(p, key, s, "合成字幕", "自制合成", "SRT", srt("修订原文")))
        .hasMessageContaining("该请求键已用于其他字幕资料");
    assertThat(projects.snapshot(p, s).commentCount()).isEqualTo(1);
    var next = transcripts.create(p, key(), s, "修订字幕", "自制合成", "SRT", srt("修订原文"));
    assertThat(next.summary().id()).isNotEqualTo(first.summary().id());
    assertThat(transcripts.get(p, first.summary().id()).segments().getFirst().text())
        .isEqualTo("时间机器");
  }

  /** 多个评论版本可以显式选择同作品字幕，另一个作品和另一个项目均不可借用。 */
  @Test
  void bindsTranscriptToWorkAndProject() {
    String p = project(), source = key();
    String s = imports.ingest(p, document(source, "初始评论")).snapshotId();
    var material = transcripts.create(p, key(), s, "合成字幕", "测试来源", "SRT", srt("时间机器"));
    String revision = imports.ingest(p, document(source, "修订评论")).snapshotId();
    assertThat(transcripts.forSnapshot(p, material.summary().id(), revision).summary().id())
        .isEqualTo(material.summary().id());
    assertThatThrownBy(() -> transcripts.forSnapshot(p, material.summary().id(), snapshot(p)))
        .hasMessageContaining(TranscriptMaterialStore.MISMATCH);
    assertThatThrownBy(() -> transcripts.get(project(), material.summary().id()))
        .hasMessageContaining("字幕资料不存在");
  }

  /** 预检/接受使用相同检索；不同问题或字幕版本不能复用同一请求键，新增字幕不改旧冻结。 */
  @Test
  void freezesRetrievedTimeEvidenceAndPreservesOldRequest() {
    String p = project(), s = snapshot(p);
    var material = transcripts.create(p, key(), s, "时间证据", "合成未认证", "SRT", srt("时间机器的因果"));
    String key = key();
    var accepted =
        requests.createWithMaterials(
            p,
            key,
            s,
            "总结时间机器的因果讨论",
            false,
            List.of(),
            true,
            null,
            null,
            false,
            material.summary().id());
    var frozen = contexts.get(p, accepted.request().id());
    assertThat(frozen.selection().version()).isEqualTo(AnalysisContextStore.RETRIEVAL_VERSION);
    assertThat(frozen.selection().sources().getFirst().materialId())
        .isEqualTo(material.summary().id());
    assertThat(frozen.selection().sources().getFirst().startMs()).isEqualTo(1000L);
    assertThat(
            requests
                .createWithMaterials(
                    p,
                    key,
                    s,
                    "总结时间机器的因果讨论",
                    false,
                    List.of(),
                    true,
                    null,
                    null,
                    false,
                    material.summary().id())
                .created())
        .isFalse();
    var another = transcripts.create(p, key(), s, "新版", "合成", "SRT", srt("时间机器新版"));
    assertThatThrownBy(
            () ->
                requests.createWithMaterials(
                    p,
                    key,
                    s,
                    "总结时间机器的因果讨论",
                    false,
                    List.of(),
                    true,
                    null,
                    null,
                    false,
                    another.summary().id()))
        .hasMessageContaining("该请求键已经用于其他分析背景");
    assertThat(contexts.get(p, accepted.request().id())).isEqualTo(frozen);
    worker.runOne(accepted.request().jobId());
    var report = requests.artifact(p, accepted.request().id());
    assertThat(report.path("context").path("sentToModel").asBoolean()).isFalse();
    assertThat(report.path("context").path("selectionVersion").asText())
        .isEqualTo(AnalysisContextStore.RETRIEVAL_VERSION);
    assertThat(report.path("scope").path("totalComments").asInt()).isEqualTo(1);
  }

  /** 旧v1没有新retrieval字段，纯评论保持null；独立字幕与自带字幕互斥。 */
  @Test
  void keepsLegacyContextJsonAndRejectsAmbiguousSubtitleSelection() throws Exception {
    String p = project(), s = snapshot(p);
    var legacy = contexts.prepare(p, s, true, List.of());
    assertThat(json.readTree(json.writeValueAsString(legacy.selection())).has("retrieval"))
        .isFalse();
    assertThat(contexts.prepare(p, s, false, List.of()).selection()).isNull();
    var note = transcripts.create(p, key(), s, "合成字幕", "测试", "SRT", srt("时间机器"));
    assertThatThrownBy(
            () -> contexts.prepareWithMaterial(p, s, true, List.of(), note.summary().id(), "时间机器"))
        .hasMessageContaining("不能同时选择");
  }

  /** 无命中保留0段和明确说明，预检不创建请求或调用账本。 */
  @Test
  void noLexicalMatchIsExplicitAndPreviewIsReadOnly() {
    String p = project(), s = snapshot(p);
    var material = transcripts.create(p, key(), s, "字幕", "合成", "SRT", srt("时间机器"));
    long before =
        db.queryForObject(
            "SELECT COUNT(*) FROM analysis_request WHERE project_id=?", Long.class, p);
    var view =
        preflight.previewWithMaterial(
            p, s, false, List.of(), "总结人物服饰讨论", null, null, material.summary().id());
    assertThat(view.transcriptSegments()).isZero();
    assertThat(view.notes()).anyMatch(note -> note.contains("没有字词命中"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_request WHERE project_id=?", Long.class, p))
        .isEqualTo(before);
  }

  /** 首次接受冻结平台声明；后来对同一合成采集诊断的修订不能漂移旧请求。 */
  @Test
  void freezesSourceCoverageIndependentlyFromLaterCollectionDiagnostics() throws Exception {
    String p = project();
    var target = new BilibiliUrlParser().parse("https://www.bilibili.com/video/BV1ntuF64EhF");
    var batch = collections.create(p, key(), target, 50);
    var lease = collections.claim(batch.id());
    long aid =
        8_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
    var video = json.createArrayNode();
    video
        .addObject()
        .put("aid", aid)
        .put("bvid", target.sourceId())
        .put("owner_mid", 1)
        .put("title", "合成来源覆盖夹具")
        .put("pubdate", 1785810000)
        .put("reply_count", 9);
    var comments = json.createArrayNode();
    comments
        .addObject()
        .put("rpid", aid + 1)
        .put("oid", aid)
        .put("type", 1)
        .put("parent", 0)
        .put("content", "合成观点")
        .put("ctime", 1785810001)
        .put("reply_count", 0);
    var converted =
        adapter.convert(
            target, 50, json.writeValueAsBytes(video), json.writeValueAsBytes(comments));
    var receipt = publication.publish(lease, converted, "synthetic-coverage-only");
    var request = requests.create(p, key(), receipt.snapshotId(), "总结讨论").request();
    assertThat(
            coverage.get(p, request.id(), receipt.snapshotId()).coverage().sourceReportedComments())
        .isEqualTo(9);
    db.update(
        "UPDATE source_collection_batch SET source_reported_comments=10 WHERE id=?", batch.id());
    assertThat(coverage.prepare(p, receipt.snapshotId()).sourceReportedComments()).isEqualTo(10);
    assertThat(
            coverage.get(p, request.id(), receipt.snapshotId()).coverage().sourceReportedComments())
        .isEqualTo(9);
  }

  /** 历史请求没有诊断行时明确回溯，不自动补写“当时已经冻结”。 */
  @Test
  void legacyCoverageProjectionDoesNotBackfill() {
    String p = project(), s = snapshot(p), id = key();
    db.update(
        "INSERT INTO"
            + " analysis_request(id,project_id,snapshot_id,request_key,instruction,intent,include_assessment,status)"
            + " VALUES(?,?,?,?,?,'DISCUSSION',0,'PENDING')",
        id,
        p,
        s,
        key(),
        "合成历史接受形状");
    assertThat(coverage.get(p, id, s).frozen()).isFalse();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_request_source_coverage WHERE request_id=?",
                Long.class,
                id))
        .isZero();
  }

  /** 真HTTP字幕上传、列表/检索、坏格式及项目隔离；不需要真实视频文件。 */
  @Test
  void multipartSubtitleRoutesAndSafeErrors() {
    String p = project(), s = snapshot(p);
    var parts = new LinkedMultiValueMap<String, Object>();
    parts.add("snapshotId", s);
    parts.add("title", "HTTP合成字幕");
    parts.add(
        "file",
        new ByteArrayResource(srt("时间机器")) {
          @Override
          public String getFilename() {
            return "synthetic.srt";
          }
        });
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.set("Idempotency-Key", key());
    var response =
        http.postForEntity(
            base(p) + "transcripts", new HttpEntity<>(parts, headers), JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = response.getBody().path("summary").path("id").asText();
    assertThat(
            http.getForObject(base(p) + "transcripts?snapshotId=" + s, JsonNode.class)
                .path("total")
                .asInt())
        .isEqualTo(1);
    assertThat(
            http.getForObject(
                    base(p) + "transcripts/" + id + "/search?snapshotId=" + s + "&query=时间机器",
                    JsonNode.class)
                .path("segments"))
        .hasSize(1);
    assertThat(
            http.getForEntity(base(project()) + "transcripts/" + id, JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** 普通讨论演示可导出统一报告，状态未发布409，跨项目404，且查询/导出不新增模型账本。 */
  @Test
  void reportRoutesRespectPublicationAndNeverCreateCalls() {
    String p = project(), s = snapshot(p);
    var request = requests.create(p, key(), s, "总结讨论").request();
    String route = base(p) + "analysis-requests/" + request.id();
    assertThat(http.getForEntity(route + "/report", JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    worker.runOne(request.jobId());
    long before =
        db.queryForObject(
            "SELECT COUNT(*) FROM model_call WHERE job_id=?", Long.class, request.jobId());
    assertThat(
            http.getForObject(route + "/report", JsonNode.class)
                .path("sourceCoverage")
                .path("frozen")
                .asBoolean())
        .isTrue();
    var markdown = http.getForEntity(route + "/report/export?format=markdown", String.class);
    assertThat(markdown.getBody()).contains("来源与处理范围", "原文证据");
    assertThat(markdown.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(http.getForEntity(route + "/decisions", JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            http.getForEntity(
                    base(project()) + "analysis-requests/" + request.id() + "/report",
                    JsonNode.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM model_call WHERE job_id=?", Long.class, request.jobId()))
        .isEqualTo(before);
  }

  /** 合成资料只含一条真实形状评论；字幕作为独立材料不得污染分母。 */
  private ImportDocument document(String source, String text) {
    var time = OffsetDateTime.parse("2026-10-18T10:00:00Z");
    return new ImportDocument(
        "1.0",
        new ImportDocument.Content("source", "SYNTHETIC", source, "fixture", "字幕与报告合成验收", time),
        List.of(new ImportDocument.Comment("c1", "c1", null, text, time)),
        List.of());
  }

  /** 每次新作品避免与历史测试混版。 */
  private String snapshot(String project) {
    return imports.ingest(project, document(key(), "观众讨论时间机器的因果")).snapshotId();
  }

  /** 简单合成字幕段，有效原片时间1～2秒。 */
  private byte[] srt(String text) {
    return ("1\n00:00:01,000 --> 00:00:02,000\n" + text + "\n").getBytes(StandardCharsets.UTF_8);
  }

  /** 业务测试全部隔离在独立项目列表。 */
  private String project() {
    return projects.create(key(), "合成字幕与报告验收", "只测工程契约，不作语义成绩").project().id();
  }

  /** 本机路由只接受明确项目路径。 */
  private String base(String project) {
    return "/api/v1/projects/" + project + "/";
  }

  /** 避免并发验收相互覆盖。 */
  private String key() {
    return UUID.randomUUID().toString();
  }
}
