/* 文件职责：用本地ChatModel验证v5严格JSON解析及版本配对，永不联网。 */
package io.echoinsight.model.springai;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.intelligence.AnalysisSpecification;
import io.echoinsight.model.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.openai.api.OpenAiApi;

/** 供应商元数据是合成用量，只验证适配边界，不代表DeepSeek语义质量。 */
class StanceProviderTest {
  private DiscussionModelContract.Request input() {
    return new DiscussionModelContract.Request(
        "s",
        "分析立场",
        "标题",
        "SYNTHETIC",
        false,
        1,
        List.of(new DiscussionModelContract.Text("c", null, "合成文本")),
        128000,
        4096,
        List.of(),
        StanceProtocol.VERSION,
        new AnalysisSpecification(AnalysisSpecification.VERSION, "合成命题", "USER_CLARIFICATION"));
  }

  private ChatResponse response(String text) {
    return new ChatResponse(
        List.of(
            new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())),
        ChatResponseMetadata.builder()
            .id("local")
            .model("local")
            .usage(new DefaultUsage(10, 2, 12, new OpenAiApi.Usage(2, 10, 12)))
            .build());
  }

  /** 无主张仍可返回UNKNOWN，但必须逐条覆盖，原始用量保留。 */
  @Test
  void strictV5PayloadUsesSeparateShape() {
    var provider =
        new SpringAiDiscussionProvider(
            prompt ->
                response(
                    """
                    {"decisions":[{"commentId":"c","coverage":"UNKNOWN","reason":"语境不足","claims":[]}]}
                    """),
            "local");
    var result = provider.analyze(input(), Duration.ofSeconds(2));
    assertThat(result.issues()).isEmpty();
    assertThat(result.decisions()).hasSize(1);
    assertThat(result.inputTokens()).isEqualTo(10);
  }

  /** 未知字段、旧版外壳、缺字段和数字枚举全部拒绝，不能靠JSON模式代替本地校验。 */
  @Test
  void malformedV5SchemaCannotMasqueradeAsValid() {
    for (String text :
        List.of(
            "{\"issues\":[]}",
            "{\"decisions\":[],\"extra\":1}",
            "{\"decisions\":[{\"commentId\":\"c\",\"coverage\":1,\"reason\":\"x\",\"claims\":[]}]}",
            "{\"decisions\":[{\"commentId\":\"c\",\"claims\":[]}]}")) {
      var provider = new SpringAiDiscussionProvider(prompt -> response(text), "local");
      assertThatThrownBy(() -> provider.analyze(input(), Duration.ofSeconds(2)))
          .hasMessage("MODEL_RESPONSE_SCHEMA");
    }
  }
}
