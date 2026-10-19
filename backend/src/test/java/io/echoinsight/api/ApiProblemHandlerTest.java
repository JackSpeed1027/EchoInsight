/* 文件职责：验证可读错误不会反射用户正文、供应商密钥或底层私有原因。 */
package io.echoinsight.api;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 直接覆盖业务白名单与未知原因的安全边界，而不是复制消息实现。 */
class ApiProblemHandlerTest {
  @Test
  void displaysKnownBusinessReasonButSuppressesPrivateUnknownText() {
    var handler = new ApiProblemHandler();
    assertThat(
            handler
                .status(
                    new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "需要确认有限输入范围后再提交分析"))
                .getBody()
                .message())
        .contains("有限输入");
    var unknown =
        handler.status(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "sk-private-value SQL secret"));
    assertThat(unknown.getBody().message()).doesNotContain("sk-private", "SQL", "secret");
    assertThat(unknown.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void malformedBodyAndMissingHeaderReturnOnlyFixedMessages() {
    assertThat(new ApiProblemHandler().unreadable().getBody().code()).isEqualTo("INVALID_JSON");
    assertThat(new ApiProblemHandler().missingHeader().getBody().message())
        .doesNotContain("Exception", "java.");
  }

  /** 内容容量和费用上界都是固定业务原因，应能直接指导用户缩小范围。 */
  @Test
  void displaysSafeContentCapacityAndBudgetReasons() {
    var handler = new ApiProblemHandler();
    for (String message :
        java.util.List.of(
            "完整分析最多24片，每片100完整段/12000字符；请缩小字幕时间范围或拆分材料，不能静默截取",
            "原任务预算不足以覆盖一次保守调用上界",
            "本任务预算不足以覆盖全部计划调用的保守上界，请缩小范围",
            "处理方式仅支持FULL或PREFIX"))
      assertThat(
              handler
                  .status(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message))
                  .getBody()
                  .message())
          .isEqualTo(message);
  }

  /** 上传编码错误明确展示，任意带密钥/路径的近似文本仍不能进入白名单。 */
  @Test
  void explainsContentEncodingWithoutReflectingPrivateReason() {
    var handler = new ApiProblemHandler();
    assertThat(
            handler
                .status(new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件必须是有效UTF8编码"))
                .getBody()
                .message())
        .contains("UTF8");
    assertThat(
            handler
                .status(
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "文件必须是有效UTF8编码 secret=D:/private/key"))
                .getBody()
                .message())
        .doesNotContain("secret", "private");
  }
}
