/* 文件职责：验证默认免费、凭据脱敏、报价边界及历史计划指纹兼容，不访问网络或数据库。 */
package io.echoinsight.model;

import static org.assertj.core.api.Assertions.*;

import io.echoinsight.model.springai.ModelRuntimeConfiguration;
import io.echoinsight.model.springai.SpringAiClassifierProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 这些测试证明工程配置边界，不代表供应商可用、真实费用或模型准确率。 */
class ConfiguredModelPlanTest {
  /** 统一构造明确的测试配置，假密钥仅用于不联网装配，不能用于实际请求。 */
  private ModelSettings settings(
      boolean enabled, boolean allowed, String price, long input, long output, String budget) {
    return new ModelSettings(
        enabled,
        allowed,
        "TEST_SECRET_SENTINEL",
        "deepseek-flash",
        price,
        input,
        output,
        new BigDecimal(budget));
  }

  /** 无配置时使用旧 Mock 计划，不需要任何外部客户端。 */
  @Test
  void disabledRemainsFreeAndDoesNotRegisterExternalProvider() {
    new ApplicationContextRunner()
        .withUserConfiguration(ModelRuntimeConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ConfiguredModelPlan.class);
              assertThat(context).doesNotHaveBean(SpringAiClassifierProvider.class);
              assertThat(context.getBean(ConfiguredModelPlan.class).current())
                  .isEqualTo(ModelContract.Plan.mock());
            });
  }

  /** 只开启总开关不构成收费授权；报价缺失同样拒绝，不静默使用别的执行模式。 */
  @Test
  void enabledRequiresExplicitPermissionAndCompletePricing() {
    assertThatThrownBy(() -> new ConfiguredModelPlan(settings(true, false, "test-v1", 1, 1, "1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConfiguredModelPlan(settings(true, true, "", 1, 1, "1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConfiguredModelPlan(settings(true, true, "test-v1", 0, 1, "1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 精确十进制计价；不允许向下舍入预算，也不接受覆盖不了一批预留的额度。 */
  @Test
  void rejectsRoundingOverflowAndInsufficientReservation() {
    for (String budget : new String[] {"0", "0.0000000001", "1001", "0.000001"}) {
      assertThatThrownBy(
              () -> new ConfiguredModelPlan(settings(true, true, "test-v1", 1, 2, budget)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /** 单价是本地上限而非实际账单，计划中不保存密钥，输入界限不使用字符估计。 */
  @Test
  void pinsExactCurrencyRatesAndSourceWithoutSecrets() {
    var settings = settings(true, true, "test-v1", 1, 2, "1.000000001");
    var plans = new ConfiguredModelPlan(settings);
    assertThat(plans.current().budgetLimit()).isEqualTo(1000000001L);
    assertThat(plans.current().inputTokenCeiling()).isEqualTo(1000000);
    assertThat(plans.current().reportMode()).isEqualTo("MODEL");
    assertThat(plans.discussion().inputTokenCeiling()).isEqualTo(128000);
    assertThat(plans.discussion().promptVersion()).isEqualTo("discussion-json-v3");
    assertThat(plans.discussion().taxonomyVersion()).isEqualTo("argument-map-v1");
    assertThat(plans.status().taskBudgetUsd()).isEqualTo("1.000000001");
    assertThat(
            settings.toString()
                + ModelContract.encode(settings)
                + ModelContract.encode(plans.current())
                + ModelContract.encode(plans.status()))
        .doesNotContain("TEST_SECRET_SENTINEL", "apiKey");
    assertThat(ModelContract.plan(ModelContract.encode(plans.current())))
        .isEqualTo(plans.current());
  }

  /** 固定旧 JSON 字节序列；添加可空字段后仍须完全相同，否则恢复会误判批次输入变化。 */
  @Test
  void legacyPlanSerializationAndFingerprintRemainByteCompatible() {
    String old =
        "{\"providerId\":\"MOCK\",\"modelVersion\":\"synthetic-fixture-v1\","
            + "\"promptVersion\":\"no-prompt-v1\",\"taxonomyVersion\":\"sentiment-v1\","
            + "\"batchSize\":50,\"timeoutMs\":3000,\"maxOutputTokens\":512,"
            + "\"unit\":\"SIMULATED_UNITS\",\"priceVersion\":\"mock-zero-v1\","
            + "\"inputUnitRate\":0,\"outputUnitRate\":0,\"budgetLimit\":0}";
    assertThat(ModelContract.encode(ModelContract.Plan.mock())).isEqualTo(old);
    assertThat(ModelContract.encode(ModelContract.plan(old))).isEqualTo(old);
    assertThat(ModelContract.plan(null)).isEqualTo(ModelContract.Plan.mock());
  }

  /** 收费能力只在完整配置下装配；创建客户端不调用供应商。 */
  @Test
  void explicitConfigurationBuildsClientWithoutNetworkRequest() {
    new ApplicationContextRunner()
        .withUserConfiguration(ModelRuntimeConfiguration.class)
        .withPropertyValues(
            "echoinsight.model.enabled=true",
            "echoinsight.model.allow-paid-calls=true",
            "echoinsight.model.api-key=TEST_SECRET_SENTINEL",
            "echoinsight.model.expected-response-model=deepseek-flash",
            "echoinsight.model.price-version=test-v1",
            "echoinsight.model.input-nanos-per-token=1",
            "echoinsight.model.output-nanos-per-token=2",
            "echoinsight.model.task-budget-usd=1")
        .run(
            context ->
                assertThat(context).hasNotFailed().hasSingleBean(SpringAiClassifierProvider.class));
  }

  /** 旧键仍能读取，新键优先；整个测试只使用占位密钥，并且没有模型调用。 */
  @Test
  void historicalConfigurationAliasesWorkAndNewKeysTakePrecedence() {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(ModelRuntimeConfiguration.class)
            .withInitializer(
                context -> {
                  try {
                    for (var source :
                        new org.springframework.boot.env.YamlPropertySourceLoader()
                            .load(
                                "compatibility",
                                new org.springframework.core.io.ClassPathResource(
                                    "application.yml"))) {
                      context
                          .getEnvironment()
                          .getPropertySources()
                          .addBefore("systemProperties", source);
                    }
                  } catch (java.io.IOException error) {
                    throw new IllegalStateException("Test YAML unavailable");
                  }
                })
            .withPropertyValues(
                "echoagent.model.enabled=true",
                "echoagent.model.allow-paid-calls=true",
                "echoagent.model.api-key=TEST_SECRET_SENTINEL",
                "echoagent.model.expected-response-model=deepseek-flash",
                "echoagent.model.price-version=legacy-test-v1",
                "echoagent.model.input-nanos-per-token=1",
                "echoagent.model.output-nanos-per-token=2",
                "echoagent.model.task-budget-usd=1");
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(SpringAiClassifierProvider.class);
          assertThat(context.getBean(ConfiguredModelPlan.class).current().priceVersion())
              .isEqualTo("legacy-test-v1");
        });
    runner
        .withPropertyValues("echoinsight.model.enabled=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(SpringAiClassifierProvider.class);
              assertThat(context.getBean(ConfiguredModelPlan.class).current())
                  .isEqualTo(ModelContract.Plan.mock());
            });
  }
}
