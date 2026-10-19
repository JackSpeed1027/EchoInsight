/* 文件职责：读取本机模型配置，默认关闭收费能力，避免凭据进入字符串诊断。 */
package io.echoinsight.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 配置只来自服务器；页面不接收密钥、端点、单价或额度。字符串输出始终脱敏。 */
@ConfigurationProperties("echoinsight.model")
public record ModelSettings(
    boolean enabled,
    boolean allowPaidCalls,
    @JsonIgnore String apiKey,
    String expectedResponseModel,
    String priceVersion,
    Long inputNanosPerToken,
    Long outputNanosPerToken,
    BigDecimal taskBudgetUsd) {
  /** 只说明开关状态，禁止默认 record.toString 将密钥写入日志或调试结果。 */
  @Override
  public String toString() {
    return "ModelSettings[enabled="
        + enabled
        + ", allowPaidCalls="
        + allowPaidCalls
        + ", credentials=REDACTED]";
  }
}
