/* 文件职责：用稳定错误码表达采集失败，避免把工具 stderr、原始内容或本机路径返回给用户。 */
package io.echoinsight.collection;

/** 任务失败后只持久化 code；cause 供服务端诊断链使用，API 不直接序列化异常。 */
public class CollectionFailure extends RuntimeException {
  private final String code;

  public CollectionFailure(String code) {
    super(code);
    this.code = code;
  }

  public CollectionFailure(String code, Throwable cause) {
    super(code, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
