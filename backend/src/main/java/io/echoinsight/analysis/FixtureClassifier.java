/* 文件职责：仅对合成演示文本查表，普通用户文本一律返回无法判断。 */
package io.echoinsight.analysis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
/** 仅对合成演示文本查表，普通用户文本一律返回无法判断。 */
public class FixtureClassifier {
  /** 演示分类的四种结果。 */
  public enum Label {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    UNKNOWN
  }

  /** 评论标识与分类结果，不包含模型编造的统计。 */
  public record Classification(String commentId, Label label) {}

  private final Map<String, Label> fixtures;

  /** 注入本类需要的依赖，避免在业务方法中自行创建连接或组件。 */
  public FixtureClassifier(ObjectMapper json) throws IOException {
    try (var input = FixtureClassifier.class.getResourceAsStream("/demo-classifier-v1.json")) {
      fixtures = Map.copyOf(json.readValue(input, new TypeReference<Map<String, Label>>() {}));
    }
  }

  /** 1、逐条匹配演示文本；2、定期续租；3、未知文本保留 UNKNOWN。 */
  public List<Classification> classify(ImportDocument document, Runnable heartbeat) {
    List<Classification> result = new ArrayList<>();
    for (int i = 0; i < document.comments().size(); i++) {
      if (i % 100 == 0) heartbeat.run();
      var comment = document.comments().get(i);
      var label = label(document.content().sourceType(), comment.text());
      result.add(new Classification(comment.id(), label));
    }
    return List.copyOf(result);
  }

  /** 单条演示判断供统一模型协议复用，真实用户文本仍返回 UNKNOWN。 */
  public Label label(String sourceType, String text) {
    return "SYNTHETIC".equals(sourceType)
        ? fixtures.getOrDefault(text, Label.UNKNOWN)
        : Label.UNKNOWN;
  }
}
