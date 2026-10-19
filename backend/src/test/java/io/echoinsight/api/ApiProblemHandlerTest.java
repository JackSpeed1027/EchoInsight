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
}
