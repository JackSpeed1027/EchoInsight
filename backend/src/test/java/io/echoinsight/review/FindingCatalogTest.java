/* 文件职责：验证旧报告核验身份稳定，读取不会回写原始 JSON。 */
package io.echoinsight.review;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 身份不能依赖数组下标，避免结论或引用显示顺序变化后错绑。 */
class FindingCatalogTest {
  private final ObjectMapper json = new ObjectMapper();
  private final FindingCatalog catalog = new FindingCatalog(json);

  /** 交换结论、引用顺序仍匹配同一身份，报告对象保持原样。 */
  @Test
  void identitySurvivesReorderingWithoutMutatingReport() throws Exception {
    var first =
        json.readTree(
            "{\"findings\":[{\"claim\":\"甲\",\"evidenceIds\":[\"b\",\"a\"]},{\"claim\":\"乙\",\"evidenceIds\":[\"c\"]}]}");
    String before = first.toString();
    var second =
        json.readTree(
            "{\"findings\":[{\"claim\":\"乙\",\"evidenceIds\":[\"c\"]},{\"claim\":\"甲\",\"evidenceIds\":[\"a\",\"b\"]}]}");
    var original = catalog.list("report", first);
    var reordered = catalog.list("report", second);
    assertThat(original.getFirst().findingId()).isEqualTo(reordered.getLast().findingId());
    assertThat(original.getLast().findingId()).isEqualTo(reordered.getFirst().findingId());
    assertThat(first.toString()).isEqualTo(before);
  }

  /** 不同报告或不同主张不能共享核验身份。 */
  @Test
  void differentReportsAndClaimsHaveDifferentIdentities() throws Exception {
    var report = json.readTree("{\"findings\":[{\"claim\":\"甲\",\"evidenceIds\":[\"a\"]}]}");
    assertThat(catalog.list("one", report).getFirst().findingId())
        .isNotEqualTo(catalog.list("two", report).getFirst().findingId());
    var changed = json.readTree("{\"findings\":[{\"claim\":\"乙\",\"evidenceIds\":[\"a\"]}]}");
    assertThat(catalog.list("one", report).getFirst().findingId())
        .isNotEqualTo(catalog.list("one", changed).getFirst().findingId());
  }
}
