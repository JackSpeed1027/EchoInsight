/* 文件职责：验证集合扇出复用原任务系统、计划冻结、原子创建和部分失败状态。 */
package io.echoinsight.material;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.orchestration.AnalysisWorker;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.project.ProjectImportService;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 合成材料与免费结构计划，自动验收不向真实供应商发送任何请求。 */
@SpringBootTest(properties = "echoinsight.projects.workspace=manifest-analysis-it")
class ManifestAnalysisIT {
  @Autowired InputManifestStore manifests;
  @Autowired ManifestAnalysisStore runs;
  @Autowired ProjectStore projects;
  @Autowired ProjectImportService imports;
  @Autowired AnalysisWorker worker;
  @Autowired JobStore jobs;
  @Autowired AnalysisRequestStore requests;
  @Autowired JdbcTemplate db;

  /** 两个作品同事务接受，重复请求无新子任务，完成后每份产物可回到自己的快照。 */
  @Test
  void fansOutFrozenInputsWithOnePlanAndIdempotentChildren() {
    String project = project();
    var manifest = manifest(project);
    String key = key();
    var first = runs.create(project, key, manifest.summary().id(), "总结讨论观点");
    var repeat = runs.create(project, key, manifest.summary().id(), "总结讨论观点");
    assertThat(first.id()).isEqualTo(repeat.id());
    assertThat(first.items()).hasSize(2);
    assertThat(first.aggregateBudgetUsd()).isEqualTo("0");
    assertThat(jobs.plan(first.items().get(0).jobId()))
        .isEqualTo(jobs.plan(first.items().get(1).jobId()));
    for (var item : first.items()) worker.runOne(item.jobId());
    var done = runs.get(project, first.id());
    assertThat(done.status()).isEqualTo("SUCCESS");
    assertThat(done.succeeded()).isEqualTo(2);
    assertThat(done.items())
        .allSatisfy(
            item -> {
              var artifact = requests.artifact(project, item.requestId());
              assertThat(artifact.path("snapshotId").asText()).isEqualTo(item.snapshotId());
              assertThat(artifact.path("sourceMode").asText()).isEqualTo("STRUCTURAL_DEMO");
            });
  }

  /** 一部分失败时保留成功结果，不把部分结果显示成全集合成功。 */
  @Test
  void derivesPartialFailureWithoutDuplicatingChildStateMachine() {
    String project = project();
    var run = runs.create(project, key(), manifest(project).summary().id(), "总结主要观点");
    worker.runOne(run.items().get(0).jobId());
    var failed = run.items().get(1);
    String token = jobs.claim(failed.jobId());
    jobs.fail(failed.jobId(), token, "SYNTHETIC_FAILURE");
    var state = runs.get(project, run.id());
    assertThat(state.status()).isEqualTo("PARTIAL_FAILED");
    assertThat(state.succeeded()).isEqualTo(1);
    assertThat(state.failed()).isEqualTo(1);
    assertThatThrownBy(() -> runs.get(project(), run.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 不支持的要求导致整个组合回滚，不留下空运行或一半子请求。 */
  @Test
  void invalidIntentRollsBackWholeRun() {
    String project = project();
    var manifest = manifest(project);
    assertThatThrownBy(() -> runs.create(project, key(), manifest.summary().id(), "明天北京天气如何"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM manifest_analysis_run WHERE project_id=?",
                Integer.class,
                project))
        .isZero();
    assertThat(requests.list(project, 1, 10).total()).isZero();
  }

  /** 每个作品使用独立来源，避免后一个输入被去重成同一个快照。 */
  private InputManifestStore.Manifest manifest(String project) {
    String first = imports.ingest(project, InputManifestIT.document(key(), "问题一")).snapshotId();
    String second = imports.ingest(project, InputManifestIT.document(key(), "问题二")).snapshotId();
    return manifests.create(project, key(), "两份材料", List.of(first, second));
  }

  private String project() {
    return projects.create(key(), "分作品验收", "检查组合边界").project().id();
  }

  private String key() {
    return UUID.randomUUID().toString();
  }
}
