/* 文件职责：在独立MySQL工作空间验证追问事务、持久导航、版本可比条件及评测候选；无真实网络模型或平台。 */
package io.echoinsight.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisContextStore;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.ContinuationPlanner;
import io.echoinsight.intelligence.DiscussionArtifact;
import io.echoinsight.intelligence.FullStanceAnalyzer;
import io.echoinsight.intelligence.StanceChunkPlanner;
import io.echoinsight.material.ReferenceMaterialStore;
import io.echoinsight.material.TranscriptMaterialStore;
import io.echoinsight.model.ConfiguredModelPlan;
import io.echoinsight.model.DiscussionModelContract;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelGateway;
import io.echoinsight.model.ModelLedger;
import io.echoinsight.model.ModelSettings;
import io.echoinsight.model.StanceProtocol;
import io.echoinsight.orchestration.AnalysisWorker;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.ProjectImportService;
import io.echoinsight.project.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 内存供应商经原网关/完整分片/发布，不把人为SUPPORT/OPPOSE规则当成独立质量成绩。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "echoinsight.projects.workspace=continuation-revision-it",
      "echoinsight.worker.enabled=false",
      "echoinsight.worker.execution-scope=continuation-revision-it",
      "echoinsight.model.enabled=false",
      "echoinsight.model.allow-paid-calls=false",
      "echoinsight.collection.bilibili.enabled=false",
      "echoinsight.collection.bilibili.worker-enabled=false"
    })
class ContinuationRevisionWorkflowIT {
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisRequestStore requests;
  @Autowired AnalysisContextStore contexts;
  @Autowired ContinuationPlanner continuations;
  @Autowired RevisionComparisonService comparisons;
  @Autowired EvaluationPackageService evaluations;
  @Autowired TranscriptMaterialStore transcripts;
  @Autowired AnalysisWorker worker;
  @Autowired JobStore jobs;
  @Autowired ModelLedger ledger;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired ImportMapper inputStore;
  @Autowired TestRestTemplate http;
  @MockitoBean ConfiguredModelPlan plans;

  /** 普通追问保持免费结构演示，完整命题只在显式测试中换成本地供应商计划。 */
  @BeforeEach
  void configureFreePlan() {
    var free =
        new ConfiguredModelPlan(
            new ModelSettings(false, false, null, null, null, null, null, null));
    when(plans.current()).thenReturn(free.current());
    when(plans.discussion()).thenReturn(free.discussion());
    when(plans.discussionWithContext()).thenReturn(free.discussionWithContext());
    when(plans.discussionWithProposition()).thenReturn(free.discussionWithProposition());
    when(plans.discussionFull()).thenReturn(free.discussionFull());
    when(plans.status()).thenReturn(free.status());
  }

  /** 同键复用同子请求，换问题回滚；父报告和原冻结输入不改写。 */
  @Test
  void followupFreezesParentAndIsIdempotent() {
    String p = project(), s = snapshot(p, key(), "原文", 3);
    var parent = demo(p, s);
    String original = requests.artifact(p, parent.id()).toString();
    String key = key();
    var child = requests.createFollowup(p, key, parent.id(), "c1", "为什么这样判断？", false);
    assertThat(child.created()).isTrue();
    assertThat(requests.createFollowup(p, key, parent.id(), "c1", "为什么这样判断？", false).created())
        .isFalse();
    assertThatThrownBy(() -> requests.createFollowup(p, key, parent.id(), "c1", "还有哪些前提？", false))
        .hasMessageContaining("该请求键");
    var frozen = contexts.get(p, child.request().id());
    assertThat(frozen.selection().history().parentRequestId()).isEqualTo(parent.id());
    assertThat(frozen.selection().history().depth()).isEqualTo(1);
    assertThat(frozen.selection().sources().getLast().text())
        .contains("待复核")
        .contains("previousQuestion");
    worker.runOne(child.request().jobId());
    var result = requests.artifact(p, child.request().id());
    assertThat(result.path("sourceMode").asText()).isEqualTo("STRUCTURAL_DEMO");
    assertThat(result.path("context").path("sentToModel").asBoolean()).isFalse();
    assertThat(requests.artifact(p, parent.id()).toString()).isEqualTo(original);
    assertThat(countRequests(p)).isEqualTo(2);
  }

  /** 预检只读；无效对象、范围或问题均在派工前拒绝。 */
  @Test
  void previewDoesNotWriteAndRejectsUnsupportedGoals() {
    String p = project(), s = snapshot(p, key(), "原文", 2);
    var parent = demo(p, s);
    int rows = countRequests(p);
    var prepared = continuations.prepare(p, parent.id(), "c1", "为什么？");
    assertThat(prepared.selectedComments()).isEqualTo(2);
    assertThat(countRequests(p)).isEqualTo(rows);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM analysis_continuation WHERE parent_request_id=?",
                Integer.class,
                parent.id()))
        .isZero();
    assertThatThrownBy(() -> continuations.prepare(p, parent.id(), "foreign", "为什么？"))
        .hasMessageContaining("请选择原报告");
    assertThatThrownBy(() -> continuations.prepare(p, parent.id(), "c1", "分析所有评论"))
        .hasMessageContaining("新建分析");
    assertThatThrownBy(() -> continuations.prepare(p, parent.id(), "c1", "分析命题「另一标准」"))
        .hasMessageContaining("新建分析");
    assertThatThrownBy(() -> continuations.prepare(p, parent.id(), "c1", " "))
        .hasMessageContaining("追问不能为空");
    assertThat(countRequests(p)).isEqualTo(rows);
  }

  /** 失败/等待不能作父报告，其他项目看不到候选或分支。 */
  @Test
  void parentStatusAndProjectIsolationAreEnforced() {
    String p = project(), s = snapshot(p, key(), "原文", 1);
    var pending = requests.create(p, key(), s, "总结讨论").request();
    assertThatThrownBy(() -> continuations.anchors(p, pending.id())).hasMessageContaining("只有已完成");
    assertThatThrownBy(() -> continuations.prepare(project(), pending.id(), "c1", "为什么？"))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> continuations.conversation(project(), pending.id(), 1, 20))
        .hasMessageContaining("不存在");
    assertThat(continuations.conversation(p, pending.id(), 1, 20).total()).isEqualTo(1);
  }

  /** 8层上限防止无限滚动历史；只保留原背景和一层相关摘录，不累计所有历史输出。 */
  @Test
  void depthBoundAndHistoryDoNotGrowWithoutLimit() {
    String p = project(), s = snapshot(p, key(), "原文", 2);
    var root = demo(p, s);
    var parent = root;
    for (int depth = 1; depth <= 8; depth++) {
      var child =
          requests.createFollowup(p, key(), parent.id(), "c1", "请解释前提和理由" + depth, false).request();
      worker.runOne(child.jobId());
      var context = contexts.get(p, child.id());
      assertThat(context.selection().history().depth()).isEqualTo(depth);
      assertThat(
              context.selection().sources().stream()
                  .filter(item -> item.id().equals("context-history"))
                  .count())
          .isEqualTo(1);
      parent = child;
    }
    String parentId = parent.id();
    assertThatThrownBy(() -> requests.createFollowup(p, key(), parentId, "c1", "第九次为什么？", false))
        .hasMessageContaining("最多8层");
    assertThat(continuations.conversation(p, parent.id(), 1, 5).total()).isEqualTo(9);
    assertThat(continuations.conversation(p, parent.id(), 2, 5).items()).hasSize(4);
    assertThat(countRequests(p)).isEqualTo(9);
  }

  /** 后来上传字幕不会进入追问；原检索范围不自动按新问题扩张。 */
  @Test
  void followupKeepsOriginalSubtitleAndOldJsonRemainsCompatible() throws Exception {
    String p = project(), s = snapshot(p, key(), "时间机器", 2);
    byte[] raw = "1\n00:00:01,000 --> 00:00:02,000\n时间机器需要前提A\n".getBytes(StandardCharsets.UTF_8);
    var subtitle = transcripts.create(p, key(), s, "旧字幕", "合成", "SRT", raw);
    var parent =
        requests
            .createWithMaterials(
                p,
                key(),
                s,
                "总结时间机器讨论",
                false,
                List.of(),
                false,
                null,
                null,
                false,
                subtitle.summary().id())
            .request();
    worker.runOne(parent.jobId());
    transcripts.create(
        p,
        key(),
        s,
        "新版字幕",
        "合成",
        "SRT",
        new String(raw, StandardCharsets.UTF_8)
            .replace("前提A", "前提B")
            .getBytes(StandardCharsets.UTF_8));
    var child = requests.createFollowup(p, key(), parent.id(), "c1", "为什么？", false).request();
    var context = contexts.get(p, child.id());
    assertThat(context.selection().sources().getFirst().text()).contains("前提A");
    assertThat(context.selection().retrieval().materialId()).isEqualTo(subtitle.summary().id());
    assertThat(json.writeValueAsString(contexts.get(p, parent.id()).selection()))
        .doesNotContain("\"history\"");
  }

  /** 超出背景上限不能静默丢原文，也不能接受半份任务。 */
  @Test
  void historyBackgroundLimitsRejectWithoutChangingOldContext() {
    var history = new AnalysisContextStore.History(key(), key(), "c1", 1, "hash", "hash");
    assertThatThrownBy(
            () ->
                contexts.withHistory(
                    "snapshot", AnalysisContextStore.View.none(), history, "字".repeat(4001)))
        .hasMessageContaining("背景超过");
    var selection =
        new AnalysisContextStore.Selection(
            AnalysisContextStore.VERSION,
            "snapshot",
            false,
            0,
            0,
            false,
            List.of(),
            List.of(
                new AnalysisContextStore.Source(
                    "context-reference-1",
                    AnalysisContextStore.Role.REFERENCE_TEXT,
                    "r",
                    "参考",
                    "合成",
                    null,
                    null,
                    null,
                    "字".repeat(19000))),
            19000);
    var parent = new AnalysisContextStore.View("unchanged", selection);
    assertThatThrownBy(() -> contexts.withHistory("snapshot", parent, history, "字".repeat(1001)))
        .hasMessageContaining("背景");
    assertThat(parent.selection().sources().getFirst().text()).hasSize(19000);
    var three =
        List.of(
            new AnalysisContextStore.Source(
                "r1",
                AnalysisContextStore.Role.REFERENCE_TEXT,
                "r1",
                "参考",
                "合成",
                null,
                null,
                null,
                "A"),
            new AnalysisContextStore.Source(
                "r2",
                AnalysisContextStore.Role.REFERENCE_TEXT,
                "r2",
                "参考",
                "合成",
                null,
                null,
                null,
                "B"),
            new AnalysisContextStore.Source(
                "r3",
                AnalysisContextStore.Role.REFERENCE_TEXT,
                "r3",
                "参考",
                "合成",
                null,
                null,
                null,
                "C"));
    var fullSlots =
        new AnalysisContextStore.Selection(
            AnalysisContextStore.VERSION, "snapshot", false, 0, 0, false, List.of(), three, 3);
    assertThatThrownBy(
            () ->
                contexts.withHistory(
                    "snapshot",
                    new AnalysisContextStore.View("original", fullSlots),
                    history,
                    "历史"))
        .hasMessageContaining("原参考最多2份");
  }

  /** 内存供应商真实走v6网关/分片/发布；原文不变的来源可以对照原标签。 */
  @Test
  void fullReportsCompareLabelsWithStableSourceAndParentContext() throws Exception {
    String p = project(), source = key();
    String before = snapshot(p, source, "同一原文", 3), after = snapshot(p, source, "同一原文", 4);
    var left = full(p, before, "剧情因果自洽", StanceProtocol.Position.SUPPORT);
    var right = full(p, after, "剧情因果自洽", StanceProtocol.Position.OPPOSE);
    var result = comparisons.compare(p, left.id(), right.id(), "ALL", 1, 2);
    assertThat(result.compatibility().comparable()).isTrue();
    assertThat(result.counts()).containsEntry("COMPARABLE", 3).containsEntry("ADDED", 1);
    assertThat(result.changedLabels()).isEqualTo(3);
    assertThat(result.items()).hasSize(2);
    assertThat(result.totalPages()).isEqualTo(2);
    assertThat(comparisons.compare(p, left.id(), right.id(), "LABEL_CHANGED", 1, 50).total())
        .isEqualTo(3);
    JsonNode exported = json.readTree(comparisons.export(p, left.id(), right.id()));
    assertThat(exported.path("difference").path("rows")).hasSize(4);
    assertThat(result.beforeCoverage().frozen()).isTrue();
  }

  /** 换问题/命题时不生成标签变化；仍给资料差异并说明原因。 */
  @Test
  void differentPropositionPreventsLabelTransition() throws Exception {
    String p = project(), source = key(), s = snapshot(p, source, "原文", 2);
    var before = full(p, s, "剧情因果自洽", StanceProtocol.Position.SUPPORT);
    var after = full(p, s, "另一命题", StanceProtocol.Position.OPPOSE);
    var result = comparisons.compare(p, before.id(), after.id(), "ALL", 1, 20);
    assertThat(result.compatibility().comparable()).isFalse();
    assertThat(result.compatibility().reasons()).anyMatch(item -> item.contains("命题"));
    assertThat(result.counts().get("NOT_COMPARABLE")).isEqualTo(2);
    assertThat(result.transitions()).isEmpty();
    assertThat(result.changedLabels()).isZero();
  }

  /** 演示、错误方向作品与越界项目均不能被解释为可比语义。 */
  @Test
  void demoReportsKeepDataDiffButRejectSemanticAndForeignWork() {
    String p = project(), source = key();
    var left = demo(p, snapshot(p, source, "旧正文", 1));
    var right = demo(p, snapshot(p, source, "新正文", 1));
    var result = comparisons.compare(p, left.id(), right.id(), "ALL", 1, 20);
    assertThat(result.compatibility().comparable()).isFalse();
    assertThat(result.counts().get("REVISED")).isEqualTo(1);
    var foreign = demo(p, snapshot(p, key(), "其他作品", 1));
    assertThatThrownBy(() -> comparisons.compare(p, left.id(), foreign.id(), "ALL", 1, 20))
        .hasMessageContaining("同一作品");
    assertThatThrownBy(() -> comparisons.compare(project(), left.id(), right.id(), "ALL", 1, 20))
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> comparisons.compare(p, left.id(), right.id(), "BAD", 1, 20))
        .hasMessageContaining("比较筛选无效");
  }

  /** 候选包含原文/原预测，但gold为空、来源未认证；导出不产生额外模型调用。 */
  @Test
  void evaluationCandidatePreservesPredictionButNeverInventsGold() throws Exception {
    String p = project(), s = snapshot(p, key(), "合成原文", 25);
    var request = full(p, s, "剧情因果自洽", StanceProtocol.Position.SUPPORT);
    int beforeCalls = calls(request.jobId());
    var bundle = json.readTree(evaluations.export(p, request.id(), "dev", 5));
    JsonNode candidate = json.readTree(bundle.path("caseJson").asText());
    JsonNode prediction = json.readTree(bundle.path("predictionJson").asText());
    assertThat(candidate.path("gold").isNull()).isTrue();
    assertThat(candidate.path("sourceKind").asText()).isEqualTo("SYNTHETIC");
    assertThat(candidate.path("permissionRef").isNull()).isTrue();
    assertThat(candidate.path("comments")).hasSize(5);
    assertThat(prediction.path("decisions")).hasSize(5);
    assertThat(bundle.path("omittedComments").asInt()).isEqualTo(20);
    assertThat(bundle.path("caseSha256").asText())
        .isEqualTo(ReferenceMaterialStore.hash(bundle.path("caseJson").asText()));
    assertThat(calls(request.jobId())).isEqualTo(beforeCalls);
  }

  /** 真正HTTP契约：预检/幂等提交/分支导航、比较、附件头及跨项目404。 */
  @Test
  void httpRoutesShareRequestPermissionsAndSafeErrors() throws Exception {
    String p = project(), s = snapshot(p, key(), "合成原文", 3);
    var parent = demo(p, s);
    String path = path(p, parent.id());
    assertThat(http.getForEntity(path + "/follow-up-options", JsonNode.class).getBody()).hasSize(3);
    var body = Map.of("anchorCommentId", "c1", "question", "为什么这样判断？");
    assertThat(post(path + "/follow-ups/preview", body, key()).getStatusCode().value())
        .isEqualTo(200);
    String key = key();
    var created = post(path + "/follow-ups", body, key);
    assertThat(created.getStatusCode().value()).isEqualTo(201);
    assertThat(post(path + "/follow-ups", body, key).getStatusCode().value()).isEqualTo(200);
    assertThat(
            http.getForEntity(path + "/conversation", JsonNode.class)
                .getBody()
                .path("total")
                .asInt())
        .isEqualTo(2);
    assertThat(
            http.getForEntity(path + "/comparison?beforeRequestId=" + parent.id(), JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    var download =
        http.getForEntity(path + "/comparison/export?beforeRequestId=" + parent.id(), String.class);
    assertThat(download.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(download.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(
            http.getForEntity(path + "/evaluation-package", JsonNode.class).getStatusCode().value())
        .isEqualTo(409);
    assertThat(
            http.getForEntity(path(project(), parent.id()) + "/conversation", JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            post(
                    path + "/follow-ups/preview",
                    Map.of("anchorCommentId", "c1", "question", ""),
                    key())
                .getBody()
                .path("message")
                .asText())
        .contains("追问");
  }

  /** 完整产物候选下载保持UTF8附件；非法数量在任何新调用之前拒绝。 */
  @Test
  void httpEvaluationDownloadIsBoundedAndReadOnly() throws Exception {
    String p = project(), s = snapshot(p, key(), "合成", 3);
    var request = full(p, s, "剧情因果自洽", StanceProtocol.Position.SUPPORT);
    int count = calls(request.jobId());
    var response =
        http.getForEntity(
            path(p, request.id()) + "/evaluation-package?split=test&limit=2", JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody().path("selectedComments").asInt()).isEqualTo(2);
    assertThat(
            http.getForEntity(
                    path(p, request.id()) + "/evaluation-package?limit=121", JsonNode.class)
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(calls(request.jobId())).isEqualTo(count);
  }

  /** v4真实网关边界也必须容纳历史背景；只用内存供应商，不调用网络。 */
  @Test
  void contextualModelFollowupPassesOriginalGatewayBoundary() throws Exception {
    String p = project(), s = snapshot(p, key(), "合成原文", 2);
    var parent = demo(p, s);
    var plan =
        new ModelContract.Plan(
            "SPRING_AI",
            "revision-context-fixture",
            io.echoinsight.model.springai.DiscussionPrompt.CONTEXT_VERSION,
            io.echoinsight.model.springai.DiscussionPrompt.CONTEXT_TAXONOMY,
            1,
            3000,
            4096,
            "USD_NANOS",
            "fixture-price-v1",
            1,
            1,
            1000000,
            128000,
            "revision-context-fixture",
            "MODEL");
    when(plans.discussionWithContext()).thenReturn(plan);
    var child = requests.createFollowup(p, key(), parent.id(), "c1", "为什么？", false).request();
    String token = jobs.claim(child.jobId());
    DiscussionModelContract.Provider provider =
        new DiscussionModelContract.Provider() {
          public String id() {
            return "SPRING_AI";
          }

          public String modelVersion() {
            return "revision-context-fixture";
          }

          public void validatePlan(ModelContract.Plan fixed) {
            assertThat(fixed).isEqualTo(plan);
          }

          public DiscussionModelContract.Response analyze(
              DiscussionModelContract.Request request, Duration timeout) {
            assertThat(request.instruction()).isEqualTo("为什么？");
            assertThat(request.context()).hasSize(1);
            assertThat(request.context().getFirst().text())
                .contains("待复核")
                .contains("anchorCommentId");
            String id = request.comments().getFirst().commentId();
            return new DiscussionModelContract.Response(
                List.of(
                    new DiscussionModelContract.Issue("issue-1", "需要哪些前提？", "合成判断", List.of(id))),
                List.of(),
                List.of(),
                List.of(
                    new DiscussionModelContract.Assessment(
                        "issue-1",
                        DiscussionModelContract.Verdict.INSUFFICIENT,
                        "资料不足",
                        "合成理由",
                        List.of(id))),
                10,
                2,
                "revision-context-fixture");
          }
        };
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider))) {
      var analyzer =
          new io.echoinsight.intelligence.DiscussionAnalysisService(
              new io.echoinsight.intelligence.StructuralDiscussionAnalyzer(),
              gateway,
              new io.echoinsight.intelligence.ThreadContextSelector());
      var input = json.readValue(inputStore.snapshot(s).documentJson(), ImportDocument.class);
      var result =
          analyzer.analyze(
              jobs.get(child.jobId()),
              token,
              plan,
              child,
              input,
              key(),
              () -> {},
              contexts.get(p, child.id()));
      assertThat(result.context().sentToModel()).isTrue();
      assertThat(result.schemaVersion()).isEqualTo("discussion-artifact-v4-context");
      assertThat(calls(child.jobId())).isEqualTo(1);
    }
  }

  /** 新建隔离项目不污染日常local目录。 */
  private String project() {
    return projects.create(key(), "追问和版本对照（合成）", "验证工程边界，不是质量成绩").project().id();
  }

  /** 稳定来源ID和相同父关系，版本追加一个回复；正文修订会传播祖先失效。 */
  private String snapshot(String p, String source, String text, int count) {
    var time = OffsetDateTime.parse("2026-10-18T00:00:00Z");
    var comments = new ArrayList<ImportDocument.Comment>();
    for (int i = 1; i <= count; i++) {
      comments.add(
          new ImportDocument.Comment("c" + i, "s" + i, i == 1 ? null : "c1", text + i, time));
    }
    return imports
        .ingest(
            p,
            new ImportDocument(
                "1.0",
                new ImportDocument.Content("work", "SYNTHETIC", source, "fixture", "工程合成资料", time),
                comments,
                List.of()))
        .snapshotId();
  }

  /** 原Worker免费发布的父报告，仍有评论证据可供导航。 */
  private AnalysisRequestStore.RequestRow demo(String p, String s) {
    var request = requests.create(p, key(), s, "总结讨论并比较理由").request();
    worker.runOne(request.jobId());
    assertThat(requests.get(p, request.id()).status()).isEqualTo("SUCCESS");
    return request;
  }

  /** 本地完整计划用于原预算与网关校验；没有实际DeepSeek端点。 */
  private ModelContract.Plan fullPlan() {
    return new ModelContract.Plan(
        "SPRING_AI",
        "revision-fixture-v1",
        StanceChunkPlanner.PROMPT,
        StanceChunkPlanner.TAXONOMY,
        12,
        3000,
        4096,
        "USD_NANOS",
        "fixture-price-v1",
        1,
        1,
        1000000,
        128000,
        "revision-fixture-v1",
        "MODEL");
  }

  /** 内存供应商只做确定性标签，完整执行原协议/祖先覆盖/账本/发布以验证比较输入。 */
  private AnalysisRequestStore.RequestRow full(
      String p, String s, String proposition, StanceProtocol.Position position) throws Exception {
    var plan = fullPlan();
    when(plans.discussionFull()).thenReturn(plan);
    var request =
        requests
            .createWithScope(
                p,
                key(),
                s,
                "分析全部评论对命题「" + proposition + "」的立场",
                false,
                List.of(),
                false,
                null,
                "FULL",
                true)
            .request();
    String token = jobs.claim(request.jobId());
    DiscussionModelContract.Provider provider =
        new DiscussionModelContract.Provider() {
          public String id() {
            return "SPRING_AI";
          }

          public String modelVersion() {
            return "revision-fixture-v1";
          }

          public void validatePlan(ModelContract.Plan fixed) {
            assertThat(fixed).isEqualTo(plan);
          }

          public DiscussionModelContract.Response analyze(
              DiscussionModelContract.Request input, Duration timeout) {
            var decisions =
                input.targetCommentIds().stream()
                    .map(
                        id ->
                            new StanceProtocol.Decision(
                                id,
                                StanceProtocol.Coverage.CLAIMS,
                                "合成规则",
                                List.of(
                                    new StanceProtocol.Claim(
                                        "合成主张", position, "", "合成理由", List.of(id)))))
                    .toList();
            return new DiscussionModelContract.Response(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                10,
                2,
                "revision-fixture-v1",
                decisions);
          }
        };
    try (var gateway = new ModelGateway(ledger, json, List.of(), List.of(provider))) {
      var input = json.readValue(inputStore.snapshot(s).documentJson(), ImportDocument.class);
      DiscussionArtifact artifact =
          new FullStanceAnalyzer(gateway)
              .analyze(
                  jobs.get(request.jobId()),
                  token,
                  plan,
                  request,
                  input,
                  key(),
                  () -> {},
                  contexts.get(p, request.id()),
                  requests.specification(p, request.id()),
                  requests.scope(p, request.id()));
      jobs.startStep(request.jobId(), token, "DISCUSSION");
      jobs.saveDiscussion(request.jobId(), token, json.writeValueAsString(artifact));
      jobs.startStep(request.jobId(), token, "ARTIFACT");
      jobs.publishArtifact(
          request.jobId(),
          token,
          artifact.artifactId(),
          request.id(),
          s,
          artifact.schemaVersion(),
          json.writeValueAsString(artifact));
    }
    return request;
  }

  /** 核对本项目行数以证明接受失败回滚。 */
  private int countRequests(String p) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM analysis_request WHERE project_id=?", Integer.class, p);
  }

  /** 原账本计数，不把本地模拟用量当供应商真实账单。 */
  private int calls(String job) {
    return db.queryForObject("SELECT COUNT(*) FROM model_call WHERE job_id=?", Integer.class, job);
  }

  /** 固定项目/请求路径，不能使用其他项目身份。 */
  private String path(String p, String r) {
    return "/api/v1/projects/" + p + "/analysis-requests/" + r;
  }

  /** 新输入身份避免覆盖历史夹具。 */
  private String key() {
    return UUID.randomUUID().toString();
  }

  /** 每个POST明确请求键，不依赖前端私有缓存。 */
  private org.springframework.http.ResponseEntity<JsonNode> post(
      String path, Object body, String key) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Idempotency-Key", key);
    return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
  }
}
