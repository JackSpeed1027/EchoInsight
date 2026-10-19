/* 文件职责：在原CONTENT步骤内按冻结清单逐片执行和汇总，检查点/预算/取消全部复用原基础设施。 */
package io.echoinsight.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.model.*;
import io.echoinsight.orchestration.JobStore;
import java.util.*;

/** 确定性MAP→REDUCE属于工作流语义节点，不是第二调度器或自主Agent。 */
public final class ContentFullAnalysis {
  private final ContentAnalysisStore inputs;
  private final JobStore jobs;
  private final ModelGateway gateway;
  private final ObjectMapper json;

  /** 由原内容执行分支组合，不注册额外队列、线程或租约。 */
  public ContentFullAnalysis(
      ContentAnalysisStore inputs, JobStore jobs, ModelGateway gateway, ObjectMapper json) {
    this.inputs = inputs;
    this.jobs = jobs;
    this.gateway = gateway;
    this.json = json;
  }

  /** 完成数来自已校验批次；结构演示只计划输入，不虚报已完成语义处理。 */
  public record Processing(
      String strategy,
      int plannedChunks,
      int completedChunks,
      String synthesisStatus,
      List<ContentFullContract.ChunkSummary> chunks) {}

  /** v2内容产物保留逐片摘要与全局结论；evidence始终是原始文本而非中间摘要。 */
  public record Artifact(
      String schemaVersion,
      String artifactId,
      String jobId,
      String sourceMode,
      ContentMaterialStore.Summary material,
      ContentAnalysisService.Scope scope,
      List<ContentModelContract.Finding> findings,
      List<String> uncertainties,
      List<ContentMaterialParser.Segment> evidence,
      List<String> limitations,
      Processing processing) {}

  /** 1、校验冻结全覆盖；2、复用/执行所有MAP；3、只汇总全部成功片；4、原租约下保存草稿和原子发布。 */
  public void run(JobStore.Job job, String token, ModelContract.Plan plan, Runnable check)
      throws Exception {
    var input = inputs.byJob(job.id());
    var full = input.frozen().fullPlan();
    if (full == null
        || !ContentFullContract.PROMPT.equals(plan.promptVersion())
        || !ContentFullContract.TAXONOMY.equals(plan.taxonomyVersion()))
      throw new IllegalStateException("Missing frozen full content plan");
    full.verify(input.frozen().selection());
    Artifact artifact;
    var step = jobs.steps(job.id()).getFirst();
    if ("SUCCEEDED".equals(step.status()))
      artifact = json.readValue(step.resultJson(), Artifact.class);
    else {
      jobs.startStep(job.id(), token, "CONTENT");
      check.run();
      List<ContentFullContract.ChunkSummary> summaries = new ArrayList<>();
      List<ContentModelContract.Finding> findings = List.of();
      List<String> notes = List.of("结构演示未执行分片语义分析或汇总，请勿当作内容结论。");
      boolean demo = "MOCK".equals(plan.reportMode());
      if (!demo) {
        for (var chunk : full.chunks()) {
          check.run();
          jobs.renew(job.id(), token);
          var request =
              request(
                  input,
                  plan,
                  "MAP",
                  chunk.index(),
                  full.select(input.frozen().selection(), chunk),
                  null);
          var response = gateway.analyzeContentFull(job.id(), token, plan, request);
          summaries.add(
              new ContentFullContract.ChunkSummary(
                  chunk.index(), response.findings(), response.uncertainties()));
        }
        // 所有MAP响应已在网关结构校验并结算，原批次缓存的请求指纹包含原文及计划。
        check.run();
        jobs.renew(job.id(), token);
        var reduce =
            request(input, plan, "REDUCE", full.chunks().size(), null, List.copyOf(summaries));
        var response = gateway.analyzeContentFull(job.id(), token, plan, reduce);
        findings = response.findings();
        notes = response.uncertainties();
      }
      var selected = input.frozen().selection();
      var scope =
          new ContentAnalysisService.Scope(
              selected.totalSegments(),
              selected.eligibleSegments(),
              selected.segments().size(),
              selected.omittedSegments(),
              selected.outsideRange(),
              selected.selectedChars(),
              selected.fromMs(),
              selected.toMs());
      artifact =
          new Artifact(
              "content-artifact-v2",
              UUID.randomUUID().toString(),
              job.id(),
              demo ? "STRUCTURAL_DEMO" : plan.reportMode(),
              input.frozen().material(),
              scope,
              List.copyOf(findings),
              List.copyOf(notes),
              selected.segments(),
              List.of(
                  "全部覆盖只指所选范围的原文已纳入计划；结构演示不代表已执行语义处理。",
                  "真实执行逐片压缩再汇总，摘要可能遗漏细节；可回看每片摘要及原文，不能承诺穷尽论点或语义准确。",
                  "汇总引用限于成功片已经引用的原段；有引用不证明结论正确，也不是独立质量评测。",
                  "没有评论、画面或音频，不能从角色台词认证作者意图；字幕齐全不证明原视频/平台资料齐全。"),
              new Processing(
                  ContentChunkPlan.VERSION,
                  full.chunks().size(),
                  demo ? 0 : summaries.size(),
                  demo ? "NOT_EXECUTED" : "SUCCEEDED",
                  List.copyOf(summaries)));
      jobs.saveContent(job.id(), token, json.writeValueAsString(artifact));
    }
    check.run();
    jobs.renew(job.id(), token);
    jobs.startStep(job.id(), token, "ARTIFACT");
    jobs.publishContent(
        job.id(),
        token,
        artifact.artifactId(),
        json.writeValueAsString(artifact),
        "content-artifact-v2");
  }

  /** 固定材料元数据/业务问题/阶段身份，不读取项目最新版本；REDUCE不附带原文伪装再次阅读。 */
  private ContentFullContract.Request request(
      ContentAnalysisStore.Input input,
      ModelContract.Plan plan,
      String phase,
      int index,
      ContentSelection selection,
      List<ContentFullContract.ChunkSummary> summaries) {
    var material = input.frozen().material();
    return new ContentFullContract.Request(
        ContentFullContract.PROMPT,
        input.materialId(),
        material.title(),
        material.format(),
        material.sourceDescription(),
        input.instruction(),
        phase,
        index,
        selection,
        summaries,
        plan.inputTokenCeiling(),
        plan.maxOutputTokens());
  }
}
