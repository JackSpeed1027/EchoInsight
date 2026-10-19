package io.echoinsight.api;

import io.echoinsight.ingestion.ImportFailure;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ImportErrorHandler {
  @ExceptionHandler(ImportFailure.class)
  public ResponseEntity<ErrorBody> invalid(ImportFailure e) {
    return ResponseEntity.unprocessableEntity()
        .body(new ErrorBody("INVALID_IMPORT", "文件未导入，请修正以下问题", e.issues()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorBody> oversized() {
    return ResponseEntity.status(413)
        .body(new ErrorBody("FILE_TOO_LARGE", "JSON 文件最大 2 MiB", List.of()));
  }

  @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
  public ResponseEntity<ErrorBody> missing() {
    return ResponseEntity.badRequest()
        .body(new ErrorBody("INVALID_UPLOAD", "请使用 file 字段上传 JSON 文件", List.of()));
  }

  public record ErrorBody(String code, String message, List<ImportFailure.Issue> issues) {}
}
