/* 文件职责：执行独立内容业务步骤，复用原任务租约、模型账本、成功检查点和原子发布。 */
package io.echoinsight.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.ModelContract;
import io.echoinsight.model.ModelGateway;
import io.echoinsight.orchestration.JobStore;
import java.util.*;
import org.springframework.stereotype.Service;

/** 这是原Worker的一个业务分支，没有自己的队列、状态机、预算或线程池。 */
@Service
public class ContentAnalysisService {
  private final ContentAnalysisStore inputs;
  private final JobStore jobs;
  private final ModelGateway gateway;
  private final ObjectMapper json;

  /** 注入原有基础能力，模型关闭时不调用网关，也不创建模拟费用账本。 */
  public ContentAnalysisService(
      ContentAnalysisStore inputs, JobStore jobs, ModelGateway gateway, ObjectMapper json) {
    this.inputs = inputs;
    this.jobs = jobs;
    this.gateway = gateway;
    this.json = json;
  }

  /** 范围统计只表示实际保存/选入材料，不表示视频理解率或语义准确率。 */
  public record Scope(
      int totalSegments,
      int eligibleSegments,
      int selectedSegments,
      int omittedSegments,
      int outsideRange,
      int selectedChars,
      Long fromMs,
      Long toMs) {}

  /** 不可变内容报告；引用对象是材料段，不能作为评论立场产物读取。 */
  public record Artifact(
      String schemaVersion,
      String artifactId,
      String jobId,
      String sourceMode,
      ContentMaterialStore.Summary material,
      Scope scope,
      List<ContentModelContract.Finding> findings,
      List<String> uncertainties,
      List<ContentMaterialParser.Segment> evidence,
      List<String> limitations) {}

  /** 1、只读创建时冻结输入；2、复用成功CONTENT步骤，否则调用一次受控模型； 3、在租约保护下保存业务草稿；4、原子发布产物与任务成功，取消后的旧执行者不能写入。 */
  public void run(JobStore.Job job, String token, ModelContract.Plan plan, Runnable check)
      throws Exception {
    if (ContentFullContract.supports(plan)) {
      new ContentFullAnalysis(inputs, jobs, gateway, json).run(job, token, plan, check);
      return;
    }
    var input = inputs.byJob(job.id());
    var selection = input.frozen().selection();
    Artifact artifact;
    var step = jobs.steps(job.id()).getFirst();
    if ("SUCCEEDED".equals(step.status()))
      artifact = json.readValue(step.resultJson(), Artifact.class);
    else {
      jobs.startStep(job.id(), token, "CONTENT");
      check.run();
      List<ContentModelContract.Finding> findings = List.of();
      List<String> uncertainties = List.of("结构演示尚未执行语义分析，请勿将材料展示当作分析结论。");
      String sourceMode = "STRUCTURAL_DEMO";
      if (!"MOCK".equals(plan.reportMode())) {
        var request =
            new ContentModelContract.Request(
                plan.promptVersion(),
                input.materialId(),
                input.frozen().material().title(),
                input.frozen().material().format(),
                input.frozen().material().sourceDescription(),
                input.instruction(),
                selection,
                plan.inputTokenCeiling(),
                plan.maxOutputTokens());
        var result = gateway.analyzeContent(job.id(), token, plan, request);
        findings = result.findings();
        uncertainties = result.uncertainties();
        sourceMode = plan.reportMode();
      }
      var scope =
          new Scope(
              selection.totalSegments(),
              selection.eligibleSegments(),
              selection.segments().size(),
              selection.omittedSegments(),
              selection.outsideRange(),
              selection.selectedChars(),
              selection.fromMs(),
              selection.toMs());
      artifact =
          new Artifact(
              "content-artifact-v1",
              UUID.randomUUID().toString(),
              job.id(),
              sourceMode,
              input.frozen().material(),
              scope,
              List.copyOf(findings),
              List.copyOf(uncertainties),
              selection.segments(),
              List.of(
                  "仅依据本次选入材料，引用存在不等于语义或外部事实已验证；来源说明属于用户声明。",
                  "没有评论、音频或画面，不能据角色台词认定创作者意图，未执行ASR或视频理解。",
                  selection.truncated()
                      ? "本次为有限输入，仍有材料段未选入；不能称全篇总结。"
                      : "本次已选入所选范围的全部材料段，不能据此证明原视频/平台资料齐全。"));
      jobs.saveContent(job.id(), token, json.writeValueAsString(artifact));
    }
    check.run();
    jobs.renew(job.id(), token);
    jobs.startStep(job.id(), token, "ARTIFACT");
    jobs.publishContent(job.id(), token, artifact.artifactId(), json.writeValueAsString(artifact));
  }
}
