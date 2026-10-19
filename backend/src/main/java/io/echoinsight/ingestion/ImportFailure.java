package io.echoinsight.ingestion;

import java.util.List;

public class ImportFailure extends RuntimeException {
  private final List<Issue> issues;

  public ImportFailure(String path, String message) {
    this(List.of(new Issue(path, message)));
  }

  public ImportFailure(List<Issue> issues) {
    super("导入数据校验失败");
    this.issues = List.copyOf(issues);
  }

  public List<Issue> issues() {
    return issues;
  }

  public record Issue(String path, String message) {}
}
