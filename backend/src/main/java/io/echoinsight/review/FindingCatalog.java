/* 文件职责：为新旧不可变报告计算稳定的核验标识，不回写或重排报告。 */
package io.echoinsight.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

/** 核验标识只依赖报告、结论文字和引用集合，不依赖页面数组位置。 */
@Component
public class FindingCatalog {
  private final ObjectMapper json;

  /** 注入 JSON 编码器，固定身份输入的转义方式。 */
  public FindingCatalog(ObjectMapper json) {
    this.json = json;
  }

  /** 返回原始结论及其稳定核验标识。 */
  public record Finding(String findingId, String claim, List<String> evidenceIds) {}

  /** 1、读取原始结论；2、排序引用后计算指纹；3、保持展示顺序且拒绝重复身份。 */
  public List<Finding> list(String reportId, JsonNode report) {
    List<Finding> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (var finding : report.path("findings")) {
      String claim = finding.path("claim").asText();
      List<String> references = new ArrayList<>();
      finding.path("evidenceIds").forEach(id -> references.add(id.asText()));
      var identity = json.createArrayNode().add("finding-sha256-v1").add(reportId).add(claim);
      var sorted = identity.addArray();
      new TreeSet<>(references).forEach(sorted::add);
      String id = "f_" + hash(identity.toString());
      if (!seen.add(id)) throw new IllegalStateException("Duplicate finding identity");
      result.add(new Finding(id, claim, List.copyOf(references)));
    }
    return List.copyOf(result);
  }

  /** 对规范化身份和请求内容计算 SHA-256，不使用平台默认字符集。 */
  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
