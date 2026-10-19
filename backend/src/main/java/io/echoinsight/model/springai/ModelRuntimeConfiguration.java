/* 文件职责：显式注册默认关闭的供应商与新任务计划，不引入自动联网 starter。 */
package io.echoinsight.model.springai;

import io.echoinsight.model.ConfiguredModelPlan;
import io.echoinsight.model.ModelSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用启动只构造配置和客户端；外部调用仍只能经过有账本保护的统一网关。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelSettings.class)
public class ModelRuntimeConfiguration {
  /** 不完整的启用配置立即失败，防止静默降级后误解分析来源。 */
  @Bean
  public ConfiguredModelPlan configuredModelPlan(ModelSettings settings) {
    return new ConfiguredModelPlan(settings);
  }

  /** 只有服务器显式开启并通过计划校验，才注册 DeepSeek；本方法不发送网络请求。 */
  @Bean
  @ConditionalOnProperty(name = "echoinsight.model.enabled", havingValue = "true")
  public SpringAiClassifierProvider deepSeekProvider(
      ModelSettings settings, ConfiguredModelPlan plans) {
    return DeepSeekSpringAiFactory.create(settings.apiKey(), plans.current());
  }

  /** 独立内容适配器仅在服务器显式启用时注册，构造不触发真实调用。 */
  @Bean
  @ConditionalOnProperty(name = "echoinsight.model.enabled", havingValue = "true")
  public SpringAiContentProvider deepSeekContentProvider(
      ModelSettings settings, ConfiguredModelPlan plans) {
    return DeepSeekSpringAiFactory.createContent(settings.apiKey(), plans.content());
  }

  /** 讨论协议与分类协议使用独立提示，但共享任务计划来源、调用网关和持久账本。 */
  @Bean
  @ConditionalOnProperty(name = "echoinsight.model.enabled", havingValue = "true")
  public SpringAiDiscussionProvider deepSeekDiscussionProvider(
      ModelSettings settings, ConfiguredModelPlan plans) {
    return DeepSeekSpringAiFactory.createDiscussion(settings.apiKey(), plans.discussion());
  }
}
