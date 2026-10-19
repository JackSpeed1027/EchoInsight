/* 文件职责：以固定参数运行本机 bilibili-cli，并对进程、时间、输出和退出语义建立硬边界。 */
package io.echoinsight.collection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** 不使用 shell，不接收任意命令参数，也不继承 Cookie/代理等用户环境变量。 */
@Component
public class BilibiliCliCollector {
  private static final int STDERR_LIMIT = 64 * 1024;
  private final BilibiliCollectionSettings settings;

  public record Payload(byte[] video, byte[] comments) {}

  /** 服务器配置是唯一执行来源，客户端只能提供经过白名单解析的作品身份和数量上限。 */
  public BilibiliCliCollector(BilibiliCollectionSettings settings) {
    this.settings = settings;
  }

  /** 1、取视频元数据；2、取有界根评论及回复；3、按退出码分类；4、返回内存中受限字节。 */
  public Payload collect(BilibiliUrlParser.Target target, int rootLimit) {
    if (!settings.enabled()) throw new CollectionFailure("COLLECTOR_DISABLED");
    if (!Files.isRegularFile(settings.binary()))
      throw new CollectionFailure("COLLECTOR_UNAVAILABLE");
    byte[] video = run(List.of("video", target.sourceId(), "-o", "json"));
    byte[] comments =
        run(
            List.of(
                "comments",
                target.sourceId(),
                "--replies",
                "-n",
                Integer.toString(rootLimit),
                "-o",
                "json",
                "-j",
                "1"));
    return new Payload(video, comments);
  }

  /** 固定第一页、页大小30且最多20个候选；关键词作为单独的值参数，不能注入新选项。 */
  public byte[] discover(String mid, String keyword, int limit) {
    if (!settings.enabled()) throw new CollectionFailure("COLLECTOR_DISABLED");
    if (!Files.isRegularFile(settings.binary()))
      throw new CollectionFailure("COLLECTOR_UNAVAILABLE");
    if (!mid.matches("[1-9][0-9]{0,18}") || limit < 1 || limit > 20 || keyword.length() > 80)
      throw new CollectionFailure("INVALID_DISCOVERY_SCOPE");
    return run(
        List.of(
            "user",
            mid,
            "--videos",
            "--keyword=" + keyword,
            "--page",
            "1",
            "--page-size",
            "30",
            "--order",
            "pubdate",
            "-n",
            Integer.toString(limit),
            "-o",
            "json",
            "-j",
            "1"));
  }

  /** 公共参数固定关闭缓存与自动重试，并限制单工具请求频率，避免隐藏额外请求。 */
  byte[] run(List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add(settings.binary().toString());
    command.addAll(arguments);
    command.addAll(
        List.of(
            "--no-cache",
            "--timeout",
            "15s",
            "--retries",
            "0",
            "--rate",
            "1s",
            "--color",
            "never",
            "--quiet"));
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.environment().clear();
      String systemRoot = System.getenv("SystemRoot");
      if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
      process = builder.start();
      Process child = process;
      var readers = Executors.newVirtualThreadPerTaskExecutor();
      try {
        var stdout =
            readers.submit(
                () ->
                    readBounded(child.getInputStream(), BilibiliCliAdapter.MAX_JSON_BYTES, child));
        var stderr = readers.submit(() -> readBounded(child.getErrorStream(), STDERR_LIMIT, child));
        boolean finished = process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
          process.waitFor(5, TimeUnit.SECONDS);
          throw new CollectionFailure("TOOL_TIMEOUT");
        }
        Captured output = stdout.get(5, TimeUnit.SECONDS);
        Captured errors = stderr.get(5, TimeUnit.SECONDS);
        if (output.exceeded() || errors.exceeded())
          throw new CollectionFailure("TOOL_OUTPUT_LIMIT");
        if (process.exitValue() != 0) throw new CollectionFailure(exitCode(process.exitValue()));
        if (output.bytes().length == 0) throw new CollectionFailure("EMPTY_TOOL_OUTPUT");
        return output.bytes();
      } finally {
        // 先终止子进程再停止读取线程，避免中断时读取管道阻塞整个工作线程退出。
        if (child.isAlive()) child.destroyForcibly();
        readers.shutdownNow();
      }
    } catch (CollectionFailure failure) {
      throw failure;
    } catch (IOException e) {
      throw new CollectionFailure("COLLECTOR_UNAVAILABLE", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CollectionFailure("COLLECTION_INTERRUPTED", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new CollectionFailure("TOOL_STREAM_FAILURE", e);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  /** 超限后继续排空管道，使子进程可以退出；只保留边界内字节，stderr 从不进入接口或数据库。 */
  private Captured readBounded(InputStream stream, int limit, Process child) throws IOException {
    ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
    byte[] buffer = new byte[8192];
    long total = 0;
    boolean exceeded = false;
    for (int read; (read = stream.read(buffer)) != -1; ) {
      if (total <= limit && read <= limit - total) kept.write(buffer, 0, read);
      else {
        exceeded = true;
        child.destroyForcibly();
      }
      total += read;
    }
    return new Captured(kept.toByteArray(), exceeded);
  }

  /** bilibili-cli v0.3.0 的稳定退出码直接映射为可运维、可重试的业务语义。 */
  String exitCode(int code) {
    return switch (code) {
      case 1 -> "TOOL_FAILURE";
      case 2 -> "PLATFORM_RISK_CONTROL";
      case 3 -> "SOURCE_EMPTY";
      case 4 -> "PLATFORM_SILENT_REFUSAL";
      case 5 -> "NETWORK_FAILURE";
      case 6 -> "RATE_LIMITED";
      case 7 -> "SOURCE_NOT_FOUND";
      default -> "UNKNOWN_TOOL_EXIT";
    };
  }

  private record Captured(byte[] bytes, boolean exceeded) {}
}
