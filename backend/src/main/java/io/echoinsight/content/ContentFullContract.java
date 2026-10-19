/* 文件职责：定义完整内容的逐片归纳与全局汇总协议，严格区分原文和模型生成的中间摘要。 */
package io.echoinsight.content;

import io.echoinsight.model.ModelContract;
import java.util.*;

/** 新协议独立于content-json-v1，避免改变旧请求编码/哈希和已成功检查点。 */
public final class ContentFullContract {
  public static final String PROMPT = "content-full-json-v2", TAXONOMY = "content-full-evidence-v2";

  private ContentFullContract() {}

  /** 摘要来自成功的原模型批次，不是新来源；引用仍为原材料段ID。 */
  public record ChunkSummary(
      int index, List<ContentModelContract.Finding> findings, List<String> uncertainties) {}

  /** MAP只带当前片原文；REDUCE只带全部已校验摘要，不重发整篇，也不允许调用工具。 */
  public record Request(
      String promptVersion,
      String materialId,
      String title,
      String format,
      String sourceDescription,
      String instruction,
      String phase,
      int batchIndex,
      ContentSelection selection,
      List<ChunkSummary> summaries,
      int maxInputTokens,
      int maxOutputTokens) {}

  /** 发送前严格检查阶段、版本、输入边界和摘要闭包，不以供应商输出代替业务范围。 */
  public static void validateRequest(ModelContract.Plan plan, Request r) {
    if (r == null
        || !PROMPT.equals(plan.promptVersion())
        || !TAXONOMY.equals(plan.taxonomyVersion())
        || !PROMPT.equals(r.promptVersion())
        || plan.inputTokenCeiling() == null
        || r.maxInputTokens() != plan.inputTokenCeiling()
        || r.maxOutputTokens() != plan.maxOutputTokens()
        || !text(r.materialId(), 64)
        || !text(r.title(), 200)
        || !text(r.instruction(), 1000)
        || r.sourceDescription() == null
        || r.sourceDescription().length() > 200) failRequest();
    if ("MAP".equals(r.phase())) {
      if (r.batchIndex() < 0
          || r.batchIndex() >= ContentChunkPlan.MAX_CHUNKS
          || r.summaries() != null
          || r.selection() == null
          || !r.materialId().equals(r.selection().materialId())
          || r.selection().segments() == null
          || r.selection().segments().isEmpty()
          || r.selection().segments().size() > ContentChunkPlan.MAX_SEGMENTS) failRequest();
      Set<String> ids = new HashSet<>();
      int chars = 0;
      for (var segment : r.selection().segments()) {
        if (segment == null
            || !text(segment.id(), 64)
            || !ids.add(segment.id())
            || !text(segment.text(), 10000)) failRequest();
        chars += segment.text().length();
      }
      if (chars > 12000 || chars != r.selection().selectedChars()) failRequest();
    } else if ("REDUCE".equals(r.phase())) {
      if (r.selection() != null
          || r.summaries() == null
          || r.summaries().isEmpty()
          || r.summaries().size() > ContentChunkPlan.MAX_CHUNKS
          || r.batchIndex() != r.summaries().size()) failRequest();
      int index = 0;
      for (var summary : r.summaries()) {
        if (summary == null || summary.index() != index++) failRequest();
        validateFields(
            summary.findings(), summary.uncertainties(), known(summary.findings()), true);
      }
      // 静态字段上限之外再核对序列化大小，不能在汇总时静默裁剪中间结果。
      if (ModelContract.encode(r).length() > 100000) failRequest();
    } else failRequest();
  }

  /** 验证用量与来源闭包；MAP收紧摘要容量，使所有成功分片都能进入有界汇总。 */
  public static void validate(Request r, ContentModelContract.Response response) {
    if (response == null
        || response.inputTokens() < 0
        || response.outputTokens() < 0
        || response.inputTokens() > r.maxInputTokens()
        || response.outputTokens() > r.maxOutputTokens()
        || (response.requestId() != null && response.requestId().length() > 128)) fail();
    Set<String> allowed = new HashSet<>();
    if ("MAP".equals(r.phase())) r.selection().segments().forEach(x -> allowed.add(x.id()));
    else r.summaries().forEach(x -> allowed.addAll(known(x.findings())));
    validateFields(response.findings(), response.uncertainties(), allowed, "MAP".equals(r.phase()));
  }

  /** 生成内容只允许在已有引用间汇总，不能产生新材料身份；重复/越界引用均拒绝。 */
  private static void validateFields(
      List<ContentModelContract.Finding> findings,
      List<String> notes,
      Set<String> allowed,
      boolean compact) {
    if (findings == null
        || findings.isEmpty()
        || findings.size() > (compact ? 3 : 12)
        || notes == null
        || notes.size() > (compact ? 2 : 12)) fail();
    for (var f : findings) {
      if (f == null
          || !text(f.title(), compact ? 80 : 120)
          || f.kind() == null
          || !text(f.explanation(), compact ? 400 : 2000)
          || f.evidenceIds() == null
          || f.evidenceIds().isEmpty()
          || f.evidenceIds().size() > (compact ? 4 : 8)) fail();
      Set<String> seen = new HashSet<>();
      for (var id : f.evidenceIds())
        if (!text(id, 64) || !allowed.contains(id) || !seen.add(id)) fail();
    }
    for (var note : notes) if (!text(note, compact ? 200 : 1000)) fail();
  }

  /** 仅抽取已存在引用，不以中间摘要文字创造新证据。 */
  private static Set<String> known(List<ContentModelContract.Finding> findings) {
    Set<String> ids = new HashSet<>();
    if (findings != null)
      for (var f : findings) if (f != null && f.evidenceIds() != null) ids.addAll(f.evidenceIds());
    return ids;
  }

  /** 固定UTF16字段边界；页面与网关仍不能认证语义正确性。 */
  private static boolean text(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  /** 输出错误在发送后仍进入原账本的未知费用保护。 */
  private static void fail() {
    throw new ModelContract.Failure("MODEL_CONTENT_INVALID");
  }

  /** 输入错误必须在预留和出站之前拒绝。 */
  private static void failRequest() {
    throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
  }
}
