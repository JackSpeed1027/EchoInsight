/* 文件职责：启动 EchoInsight Java 业务服务，装配持久任务、数据导入和报告能力。 */
package io.echoinsight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Java 业务服务入口。LangGraph 是独立的 Python 编排模块，通过只读 API 协作， 不改变本应用对快照、任务状态、报告、人工核验及模型预算的管理职责。 */
@SpringBootApplication
@EnableScheduling
public class EchoInsightApplication {
  /** 使用当前 IDEA/终端环境启动服务；数据库凭据从忽略的本地配置读取。 */
  public static void main(String[] args) {
    SpringApplication.run(EchoInsightApplication.class, args);
  }
}
