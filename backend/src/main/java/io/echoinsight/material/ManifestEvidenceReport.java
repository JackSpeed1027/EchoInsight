/* 文件职责：从终态集合运行构造可复核的跨作品议题对照，保持每个结论与自己的产物及原文绑定。 */
package io.echoinsight.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.intelligence.AnalysisRequestStore;
import io.echoinsight.intelligence.DiscussionArtifact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 对照是确定性读模型，不进行语义合并或新胜负判断；GET和导出均不会创建任务或调用模型。 */
@Service
public class ManifestEvidenceReport {
  public static final String VERSION = "manifest-evidence-v1";
  private final ManifestAnalysisStore runs;
  private final InputManifestStore manifests;
  private final AnalysisRequestStore requests;
  private final ObjectMapper json;

  /** 报告依赖既有冻结清单和不可变产物，不维护重复的业务状态。 */
  public ManifestEvidenceReport(
      ManifestAnalysisStore runs,
      InputManifestStore manifests,
      AnalysisRequestStore requests,
      ObjectMapper json) {
    this.runs = runs;
    this.manifests = manifests;
    this.requests = requests;
    this.json = json;
  }

  /** 一个议题切片内的claim、assessment及evidence均来自同一个产物，引用绝不跨作品借用。 */
  public record Slice(
      String requestId,
      String artifactId,
      String snapshotId,
      String title,
      String sourceMode,
      String sourceCompleteness,
      DiscussionArtifact.Scope scope,
      DiscussionArtifact.Issue issue,
      List<DiscussionArtifact.Claim> claims,
      List<DiscussionArtifact.Assessment> assessments,
      List<DiscussionArtifact.Evidence> evidence) {}

  /** 精确规范化问题相同才分组；workCount按不同快照计数，不把一个作品重复议题当多作品共识。 */
  public record Topic(String question, int workCount, List<Slice> sources) {}

  /** 所有子作品及失败状态保留，避免把可用子集写成集合全量。 */
  public record Report(
      String schemaVersion,
      String dependencySha256,
      String runId,
      String manifestId,
      String instruction,
      String status,
      int succeeded,
      int failed,
      List<ManifestAnalysisStore.Item> works,
      List<Topic> topics,
      List<String> limitations) {}

  /** 1、检查终态；2、核对冻结成员和产物身份；3、按稳定规则形成对照；4、绑定完整输入指纹。 */
  @Transactional(readOnly = true)
  public Report get(String projectId, String runId) {
    var run = runs.get(projectId, runId);
    if (run.pending() > 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待所有作品分析结束后生成稳定对照");
    var manifest = manifests.get(projectId, run.manifestId());
    Map<String, InputManifestStore.Item> material = new HashMap<>();
    manifest.items().forEach(i -> material.put(i.snapshotId(), i));
    Map<String, List<Slice>> grouped = new LinkedHashMap<>();
    List<String> dependencies = new ArrayList<>();
    dependencies.add(VERSION);
    dependencies.add(run.id());
    dependencies.add(run.instruction());
    dependencies.add(manifest.requestFingerprint());
    for (var item : run.items()) {
      var frozen = material.get(item.snapshotId());
      if (frozen == null) throw new IllegalStateException("Analysis member not in frozen manifest");
      dependencies.add(frozen.documentSha256());
      dependencies.add(json.valueToTree(frozen).toString());
      dependencies.add(json.valueToTree(item).toString());
      dependencies.add(item.status());
      dependencies.add(Objects.toString(item.artifactId(), ""));
      if (!"SUCCESS".equals(item.status())) continue;
      DiscussionArtifact artifact;
      try {
        var node = requests.artifact(projectId, item.requestId());
        dependencies.add(node.toString());
        artifact = json.treeToValue(node, DiscussionArtifact.class);
      } catch (Exception e) {
        throw new IllegalStateException("Invalid discussion artifact", e);
      }
      if (!item.snapshotId().equals(artifact.snapshotId())
          || !item.requestId().equals(artifact.requestId())
          || !item.artifactId().equals(artifact.artifactId()))
        throw new IllegalStateException("Artifact identity mismatch");
      Map<String, DiscussionArtifact.Evidence> evidence = new LinkedHashMap<>();
      artifact.evidence().forEach(e -> evidence.put(e.id(), e));
      for (var issue : artifact.issues()) {
        var claims =
            artifact.claims().stream().filter(c -> issue.id().equals(c.issueId())).toList();
        var assessments =
            artifact.assessments().stream().filter(a -> issue.id().equals(a.issueId())).toList();
        Set<String> cited = new LinkedHashSet<>(issue.evidenceIds());
        claims.forEach(c -> cited.addAll(c.evidenceIds()));
        assessments.forEach(a -> cited.addAll(a.evidenceIds()));
        if (!evidence.keySet().containsAll(cited))
          throw new IllegalStateException("Dangling artifact evidence");
        var slice =
            new Slice(
                item.requestId(),
                item.artifactId(),
                item.snapshotId(),
                item.title(),
                artifact.sourceMode(),
                frozen.sourceCompleteness(),
                artifact.scope(),
                issue,
                claims,
                assessments,
                cited.stream().map(evidence::get).toList());
        grouped.computeIfAbsent(normalize(issue.question()), key -> new ArrayList<>()).add(slice);
      }
    }
    var topics =
        grouped.values().stream()
            .map(
                slices ->
                    new Topic(
                        slices.getFirst().issue().question(),
                        (int) slices.stream().map(Slice::snapshotId).distinct().count(),
                        List.copyOf(slices)))
            .toList();
    return new Report(
        VERSION,
        hash(dependencies),
        run.id(),
        run.manifestId(),
        run.instruction(),
        run.status(),
        run.succeeded(),
        run.failed(),
        run.items(),
        topics,
        List.of(
            "按NFKC、首尾/连续空白及大小写规范化后的问题精确匹配，不进行模型语义归并，不认定跨作品共识或胜负。",
            "每条观点和评议保留各自产物、快照与原文；STRUCTURAL_DEMO仍只是结构演示。",
            "失败作品保留在works内；平台完整性与模型裁剪范围分别展示，不能据此声称分析了所有评论。",
            "核验意见追加保存在单请求下，不修改本报告指纹，也不自动成为独立金标准。"));
  }

  /** 保留标点和否定词，避免把不同命题强行归并；规则版本参与依赖身份。 */
  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .strip()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  /** 使用JSON数组明确分隔各输入，避免字符串拼接产生指纹歧义。 */
  private String hash(List<String> values) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(json.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
