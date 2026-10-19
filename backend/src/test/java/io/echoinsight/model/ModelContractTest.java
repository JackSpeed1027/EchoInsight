/* 文件职责：验证本地实验能力不能访问外部地址，旧任务计划仍为免费 Mock。 */
package io.echoinsight.model;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;

/** 配置边界不依赖模型密钥或网络权限。 */
class ModelContractTest {
  /** 只允许 IP 回环且不跟随重定向，拒绝远程、域名和文件协议。 */
  @Test
  void stubCannotBeConfiguredToExfiltrateData() {
    for (String url :
        new String[] {
          "https://example.com/classify",
          "http://localhost/classify",
          "http://127.0.0.1.example.com/classify",
          "file:///tmp/data",
          "http://user@127.0.0.1/test"
        })
      assertThatThrownBy(() -> new LocalStubHttpProvider(URI.create(url)))
          .isInstanceOf(IllegalArgumentException.class);
  }

  /** 缺少计划的历史任务不能被新默认价格或模型悄悄改变。 */
  @Test
  void legacyPlanIsFreeAndRoundTripsWithoutChangingVersions() {
    var plan = ModelContract.plan(null);
    assertThat(plan.providerId()).isEqualTo("MOCK");
    assertThat(plan.budgetLimit()).isZero();
    assertThat(plan.inputUnitRate() + plan.outputUnitRate()).isZero();
    assertThat(ModelContract.plan(ModelContract.encode(plan))).isEqualTo(plan);
  }
}
