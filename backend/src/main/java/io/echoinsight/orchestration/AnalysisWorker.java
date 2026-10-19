/* 文件职责：按分类、报告顺序执行任务，复用已完成步骤并处理失效租约。 */
package io.echoinsight.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.analysis.FixtureClassifier.Classification;
import io.echoinsight.infrastructure.persistence.ImportMapper;
import io.echoinsight.ingestion.ImportDocument;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.DiscussionAnalysisService;
import io.echoinsight.intelligence.DiscussionArtifact;
import io.echoinsight.model.BatchClassifier;
import io.echoinsight.model.ModelContract;
import io.echoinsight.report.ReportBuilder;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
/** 按分类、报告顺序执行任务，复用已完成步骤并处理失效租约。 */
public class AnalysisWorker {
  private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
  private final JobStore store;
  private final ImportMapper imports;
  private final BatchClassifier classifier;
  private final LeaseKeeper leases;
  private final ReportBuilder reports;
  private final ObjectMapper json;
  private final AnalysisRequestStore requests;
  private final DiscussionAnalysisService discussions;

  /** 注入本类需要的依赖，避免在业务方法中自行创建连接或组件。 */
  @Autowired
  public AnalysisWorker(
      JobStore store,
      ImportMapper imports,
      BatchClassifier classifier,
      ReportBuilder reports,
      ObjectMapper json,
      LeaseKeeper leases,
      AnalysisRequestStore requests,
      DiscussionAnalysisService discussions) {
    this.leases = leases;
    this.store = store;
    this.imports = imports;
    this.classifier = classifier;
    this.reports = reports;
    this.json = json;
    this.requests = requests;
    this.discussions = discussions;
  }

  /**
   * 兼容既有分类网关的隔离集成测试；该构造器只允许执行 FEEDBACK 任务。
   *
   * <p>生产容器始终使用完整构造器，自然语言任务不会经过这个测试兼容入口。
   */
  public AnalysisWorker(
      JobStore store,
      ImportMapper imports,
      BatchClassifier classifier,
      ReportBuilder reports,
      ObjectMapper json,
      LeaseKeeper leases) {
    this(store, imports, classifier, reports, json, leases, null, null);
  }

  /**
   * 推进一个已保存任务，执行资格和业务状态始终以数据库为准。
   *
   * <p>1、短事务领取任务；未领取成功直接退出，避免两个执行者重复推进。 2、开启时间续租并读取绑定快照，优先复用已完成分类步骤，否则执行分批分类。
   * 3、再次检查资格，再由程序生成统计，原子发布报告与成功状态。 4、业务失败保存安全错误码；资格失效只放弃旧结果，不修改新执行者状态。
   *
   * @param id 扫描器给出的持久任务 UUID，方法不创建任务或替换输入
   */
  public void runOne(String id) {
    String token = store.claim(id);
    if (token == null) return;
    try (var lease = leases.keep(id, token)) {
      var job = store.get(id);
      // 创建时保存的计划决定版本；未知供应商版本会在出站前被拒绝。
      var plan = store.plan(id);
      if (!plan.modelVersion().equals(job.modelVersion())
          || !plan.promptVersion().equals(job.promptVersion())
          || !plan.taxonomyVersion().equals(job.taxonomyVersion()))
        throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
      var snapshot = imports.snapshot(job.snapshotId());
      var input = json.readValue(snapshot.documentJson(), ImportDocument.class);
      if ("DISCUSSION".equals(job.kind())) {
        if (requests == null || discussions == null) {
          throw new IllegalStateException("Discussion services unavailable");
        }
        runDiscussion(job, token, lease, input, plan);
      } else {
        runFeedback(job, token, lease, input, plan);
      }
    } catch (JobStore.LostLease ignored) {
      // 已失去租约的旧执行者，既不能提交结果，也不能把新执行者标记为失败。
    } catch (Exception e) {
      log.warn("Analysis job {} failed with {}", id, e.getClass().getSimpleName());
      try {
        store.fail(
            id,
            token,
            e instanceof ModelContract.Failure failure ? failure.code() : "PIPELINE_ERROR");
      } catch (JobStore.LostLease ignored) {
      }
    }
  }

  /**
   * 执行既有反馈分类与报告路径，保持历史接口和报告格式不变。
   *
   * <p>1、复用已成功分类；2、否则分批调用分类网关；3、续租并由程序计算报告；4、原子发布旧版报告。
   */
  private void runFeedback(
      JobStore.Job job,
      String token,
      LeaseKeeper.Handle lease,
      ImportDocument input,
      ModelContract.Plan plan)
      throws Exception {
    var step = store.steps(job.id()).getFirst();
    List<Classification> result;
    if ("SUCCEEDED".equals(step.status()))
      result = json.readValue(step.resultJson(), new TypeReference<List<Classification>>() {});
    else {
      store.startStep(job.id(), token, "CLASSIFY");
      result = classifier.classify(job, token, input, lease::check);
      store.saveClassification(job.id(), token, json.writeValueAsString(result));
    }
    lease.check();
    store.renew(job.id(), token);
    store.startStep(job.id(), token, "REPORT");
    var report = reports.build(job, input, result, plan);
    store.publish(
        job.id(), token, report.reportId(), job.snapshotId(), json.writeValueAsString(report));
  }

  /**
   * 执行自然语言讨论任务；任务计划决定使用结构演示还是受控真实模型。
   *
   * <p>1、读取持久请求而非临时会话；2、复用已完成讨论草稿，否则按固化计划分析；3、续租并进入产物步骤；4、把请求、任务和不可变产物原子标记成功。
   */
  private void runDiscussion(
      JobStore.Job job,
      String token,
      LeaseKeeper.Handle lease,
      ImportDocument input,
      ModelContract.Plan plan)
      throws Exception {
    var request = requests.byJob(job.id());
    var step = store.steps(job.id()).getFirst();
    DiscussionArtifact artifact;
    if ("SUCCEEDED".equals(step.status())) {
      artifact = json.readValue(step.resultJson(), DiscussionArtifact.class);
    } else {
      store.startStep(job.id(), token, "DISCUSSION");
      artifact =
          discussions.analyze(
              job,
              token,
              plan,
              request,
              input,
              UUID.randomUUID().toString(),
              lease::check,
              requests.context(request.projectId(), request.id()),
              requests.specification(request.projectId(), request.id()),
              requests.scope(request.projectId(), request.id()));
      store.saveDiscussion(job.id(), token, json.writeValueAsString(artifact));
    }
    lease.check();
    store.renew(job.id(), token);
    store.startStep(job.id(), token, "ARTIFACT");
    store.publishArtifact(
        job.id(),
        token,
        artifact.artifactId(),
        request.id(),
        job.snapshotId(),
        artifact.schemaVersion(),
        json.writeValueAsString(artifact));
  }
}
