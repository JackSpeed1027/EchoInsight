/* 文件职责：将现有合成查表分类器接到统一协议，默认不进行网络调用。 */
package io.echoinsight.model;

import io.echoinsight.analysis.FixtureClassifier;
import io.echoinsight.analysis.FixtureClassifier.Classification;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Mock 和未来真实供应商共用输入输出协议，但始终明确区分演示能力。 */
@Component
public class FixtureProvider implements ModelContract.Provider {
  private final FixtureClassifier fixture;

  /** 注入既有演示标签，不读取评测数据。 */
  public FixtureProvider(FixtureClassifier fixture) {
    this.fixture = fixture;
  }

  /** 供应商白名单名称。 */
  @Override
  public String id() {
    return "MOCK";
  }

  /** 保持历史任务记录的模型版本。 */
  @Override
  public String modelVersion() {
    return "synthetic-fixture-v1";
  }

  /** 返回演示分类与零 Token 用量，不能当作真实模型效果。 */
  @Override
  public ModelContract.Response classify(ModelContract.Request request, Duration timeout) {
    return new ModelContract.Response(
        request.comments().stream()
            .map(
                c ->
                    new Classification(
                        c.commentId(), fixture.label(request.sourceType(), c.text())))
            .toList(),
        0,
        0,
        null);
  }
}
