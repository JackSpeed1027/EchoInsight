/* 文件职责：确认演示分类不会冒充对普通用户文本的语义分析。 */
package io.echoinsight.analysis;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.ingestion.ImportDocument;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 固定文本查表只对合成来源生效。 */
class FixtureClassifierTest {
  /** 相同文本在用户来源中仍为 UNKNOWN；合成来源才允许匹配内置标签。 */
  @Test
  void onlySyntheticFixturesReceiveDemoLabels() throws Exception {
    var time = OffsetDateTime.parse("2026-10-15T12:00:00+08:00");
    var comments =
        List.of(new ImportDocument.Comment("a", "1", null, "线程池与拒绝策略的例子很清楚，帮助我理解了。", time));
    var classifier = new FixtureClassifier(new ObjectMapper());
    var user =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("a", "USER_IMPORT", "s", "c", "标题", time),
            comments,
            List.of());
    var demo =
        new ImportDocument(
            "1.0",
            new ImportDocument.Content("a", "SYNTHETIC", "s", "c", "标题", time),
            comments,
            List.of());
    assertThat(classifier.classify(user, () -> {}).getFirst().label())
        .isEqualTo(FixtureClassifier.Label.UNKNOWN);
    assertThat(classifier.classify(demo, () -> {}).getFirst().label())
        .isEqualTo(FixtureClassifier.Label.POSITIVE);
  }
}
