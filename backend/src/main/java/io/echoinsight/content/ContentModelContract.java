/* 文件职责：独立内容语义协议，只允许引用本次冻结材料段，不伪造评论、作者意图或外部事实。 */
package io.echoinsight.content;

import io.echoinsight.model.ModelContract;
import java.time.Duration;
import java.util.*;

/** 新协议与旧评论v3/v4/v5/v6隔离版本；费用和物理调用仍经过原ModelGateway/ModelLedger。 */
public final class ContentModelContract {
  public static final String PROMPT = "content-json-v1", TAXONOMY = "content-evidence-v1";

  private ContentModelContract() {}

  /** 服务端注入范围和身份，用户只提交业务问题，不能指定模型、价格或工具。 */
  public record Request(
      String promptVersion,
      String materialId,
      String title,
      String format,
      String sourceDescription,
      String instruction,
      ContentSelection selection,
      int maxInputTokens,
      int maxOutputTokens) {}

  /** OBSERVATION是对给定文本的归纳，仍非外部事实认证；INTERPRETATION必须保留条件限制。 */
  public enum Kind {
    OBSERVATION,
    INTERPRETATION,
    NEEDS_CONTEXT
  }

  /** 每项结论必须引用本次真实存在的材料段，引用存在与语义正确分开验证。 */
  public record Finding(String title, Kind kind, String explanation, List<String> evidenceIds) {}

  /** 模型只输出业务正文；用量与请求身份只能由适配器从原响应元数据取得。 */
  public record Payload(List<Finding> findings, List<String> uncertainties) {}

  /** 本地校验后保存到原批次检查点，恢复不能替换计划或输入。 */
  public record Response(
      List<Finding> findings,
      List<String> uncertainties,
      int inputTokens,
      int outputTokens,
      String requestId) {}

  /** 独立业务适配器只执行单次无工具请求，不持有任务状态或自行重试。 */
  public interface Provider {
    String id();

    String modelVersion();

    void validatePlan(ModelContract.Plan plan);

    Response analyze(Request request, Duration timeout);
  }

  /** 1、校验用量和响应边界；2、验证结论枚举/文字；3、验证唯一、闭合的引用；4、拒绝空/超长结果。 Java验证结构，不声称可以认证结论含义、作者身份或事实真伪。 */
  public static void validate(Request request, Response response) {
    if (response == null
        || response.findings() == null
        || response.findings().isEmpty()
        || response.findings().size() > 12
        || response.uncertainties() == null
        || response.uncertainties().size() > 12
        || response.inputTokens() < 0
        || response.outputTokens() < 0
        || response.inputTokens() > request.maxInputTokens()
        || response.outputTokens() > request.maxOutputTokens()
        || (response.requestId() != null && response.requestId().length() > 128)) fail();
    Set<String> known = new HashSet<>();
    request.selection().segments().forEach(x -> known.add(x.id()));
    for (var finding : response.findings()) {
      if (finding == null
          || !text(finding.title(), 120)
          || finding.kind() == null
          || !text(finding.explanation(), 2000)
          || finding.evidenceIds() == null
          || finding.evidenceIds().isEmpty()
          || finding.evidenceIds().size() > 8) fail();
      Set<String> seen = new HashSet<>();
      for (String id : finding.evidenceIds()) if (!known.contains(id) || !seen.add(id)) fail();
    }
    for (String note : response.uncertainties()) if (!text(note, 1000)) fail();
  }

  /** 展示文本禁止空值和越界，不把异常正文写入错误接口。 */
  private static boolean text(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  /** 统一由原网关在已发送后记为未知费用，不把输出校验失败当免费。 */
  private static void fail() {
    throw new ModelContract.Failure("MODEL_CONTENT_INVALID");
  }
}
