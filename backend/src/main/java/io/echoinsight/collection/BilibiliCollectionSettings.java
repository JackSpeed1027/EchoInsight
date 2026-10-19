/* 文件职责：集中读取 B站采集器的服务器侧边界，HTTP 请求不能改变二进制、目录、超时或速率。 */
package io.echoinsight.collection;

import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 采集能力默认关闭；仅本机配置明确开启并提供固定工具路径时才允许创建任务。 */
@Component
public class BilibiliCollectionSettings {
  public static final int MAX_ROOT_LIMIT = 200;
  private final boolean enabled;
  private final Path binary;
  private final Path storage;
  private final int timeoutSeconds;
  private final int leaseSeconds;

  /** 1、读取服务器配置；2、规范化绝对路径；3、限制时长；4、拒绝危险或无界设置。 */
  public BilibiliCollectionSettings(Environment environment) {
    enabled =
        environment.getProperty("echoinsight.collection.bilibili.enabled", Boolean.class, false);
    binary = path(environment.getProperty("echoinsight.collection.bilibili.binary", ""));
    storage =
        path(
            environment.getProperty(
                "echoinsight.collection.bilibili.storage-dir", "runtime/collections"));
    timeoutSeconds =
        environment.getProperty(
            "echoinsight.collection.bilibili.timeout-seconds", Integer.class, 60);
    leaseSeconds =
        environment.getProperty(
            "echoinsight.collection.bilibili.lease-seconds", Integer.class, 180);
    if (timeoutSeconds < 10 || timeoutSeconds > 120)
      throw new IllegalArgumentException("Invalid Bilibili collection timeout");
    if (leaseSeconds <= timeoutSeconds * 2 || leaseSeconds > 600)
      throw new IllegalArgumentException(
          "Bilibili collection lease must exceed two command timeouts");
  }

  /** 空二进制路径保留为当前目录占位，仅在 enabled=true 时由执行器给出安全失败。 */
  private Path path(String value) {
    return Path.of(value == null || value.isBlank() ? "." : value).toAbsolutePath().normalize();
  }

  /** 创建批次和调度器共用开关，未配置时不对外采集。 */
  public boolean enabled() {
    return enabled;
  }

  /** 返回服务器固定的绝对工具路径，HTTP不能覆盖它。 */
  public Path binary() {
    return binary;
  }

  /** 原始存档的唯一根目录，工作者只在其下追加按尝试分隔的文件。 */
  public Path storage() {
    return storage;
  }

  /** 单个CLI命令的总等待上限，不等同工具内部一次HTTP的15秒上限。 */
  public int timeoutSeconds() {
    return timeoutSeconds;
  }

  /** 租约必须覆盖两次有界命令，防止正常长请求被过早接管。 */
  public int leaseSeconds() {
    return leaseSeconds;
  }
}
