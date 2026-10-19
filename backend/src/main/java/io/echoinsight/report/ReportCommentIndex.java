/* 文件职责：用来源评论ID连接报告证据别名和快照内部ID，只转换新读投影，不改写历史产物。 */
package io.echoinsight.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.echoinsight.ingestion.ImportDocument;
import java.util.HashMap;
import java.util.Map;

/** v6使用稳定报告别名，v3/v4/v5可用内部ID；业务关联必须经sourceCommentId，不能假设两者相同。 */
public final class ReportCommentIndex {
  /** 无实例状态，也不维护第二份身份数据库。 */
  private ReportCommentIndex() {}

  /** 1、核对来源身份；2、建立一一映射；3、深拷贝产物；4、只转换评论引用字段，背景ID保持原样。 */
  public static JsonNode normalize(JsonNode artifact, ImportDocument input) {
    Map<String, String> aliases = mapping(artifact, input);
    JsonNode result = artifact.deepCopy();
    convertReferences(result, aliases);
    for (var evidence : result.path("evidence")) {
      String id = evidence.path("id").asText();
      if (aliases.containsKey(id)) {
        ((ObjectNode) evidence).put("id", aliases.get(id));
      }
    }
    return result;
  }

  /** 映射可随导出保存，评审能从内部候选回到原报告别名，不把映射冒称重新判断。 */
  public static Map<String, String> mapping(JsonNode artifact, ImportDocument input) {
    Map<String, String> bySource = new HashMap<>();
    input.comments().forEach(c -> bySource.put(c.sourceCommentId(), c.id()));
    Map<String, String> aliases = new HashMap<>();
    for (var evidence : artifact.path("evidence")) {
      if (!evidence.path("sourceRole").asText("COMMENT").equals("COMMENT")) {
        continue;
      }
      String internal = bySource.get(evidence.path("sourceCommentId").asText());
      if (internal == null || aliases.put(evidence.path("id").asText(), internal) != null) {
        throw new IllegalStateException("Published comment evidence identity mismatch");
      }
    }
    return Map.copyOf(aliases);
  }

  /** 树深固定为协议JSON深度，不沿评论父链递归；statement/reason等原文不得做字符串替换。 */
  private static void convertReferences(JsonNode node, Map<String, String> aliases) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      for (String field : java.util.List.of("commentId", "parentCommentId")) {
        JsonNode id = object.get(field);
        if (id != null && id.isTextual() && aliases.containsKey(id.asText())) {
          object.put(field, aliases.get(id.asText()));
        }
      }
      JsonNode ids = object.get("evidenceIds");
      if (ids instanceof ArrayNode array) {
        for (int i = 0; i < array.size(); i++) {
          String id = array.get(i).asText();
          if (aliases.containsKey(id)) {
            array.set(i, object.textNode(aliases.get(id)));
          }
        }
      }
      object.elements().forEachRemaining(value -> convertReferences(value, aliases));
    } else if (node.isArray()) {
      node.elements().forEachRemaining(value -> convertReferences(value, aliases));
    }
  }
}
