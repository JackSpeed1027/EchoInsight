/* 文件职责：接收两份本地平台导出文件，经字段适配后复用现有导入事务。 */
package io.echoinsight.api;

import io.echoinsight.ingestion.ImportFailure;
import io.echoinsight.ingestion.ImportReceipt;
import io.echoinsight.ingestion.platform.MediaCrawlerAdapter;
import io.echoinsight.project.ProjectImportService;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 文件适配不获取 URL、不运行采集器、不创建分析任务；导入成功后仍由用户决定何时分析。 */
@RestController
public class PlatformImportController {
  private final MediaCrawlerAdapter adapter;
  private final ProjectImportService imports;

  /** 依赖标准导入服务，继续共享去重、版本与快照规则。 */
  public PlatformImportController(MediaCrawlerAdapter adapter, ProjectImportService imports) {
    this.adapter = adapter;
    this.imports = imports;
  }

  /** 本次转换诊断与标准回执分开，诊断不是持久采集档案；sourceTotalComments 仍未知。 */
  public record Result(
      ImportReceipt receipt,
      String adapterVersion,
      String platform,
      String sourceContentId,
      int contentRows,
      int commentRows,
      int selectedCommentRows,
      int excludedContentRows,
      int excludedCommentRows,
      String contentFileSha256,
      String commentsFileSha256,
      Long sourceTotalComments,
      List<String> warnings) {}

  /** 1、检查文件边界；2、按平台映射；3、标准事务保存；4、返回排除数及来源限制。 只有第三步写库，任何格式、父关系或冲突失败都不会留下半份快照。 */
  @PostMapping(
      value = "/api/v1/imports/mediacrawler",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Result> upload(
      @RequestParam String platform,
      @RequestParam String sourceContentId,
      @RequestParam(required = false) String projectId,
      @RequestPart MultipartFile contentsFile,
      @RequestPart MultipartFile commentsFile)
      throws IOException {
    validateFile(contentsFile, "contentsFile");
    validateFile(commentsFile, "commentsFile");
    var converted =
        adapter.convert(
            platform, sourceContentId, contentsFile.getBytes(), commentsFile.getBytes());
    var receipt = imports.ingest(projectId, converted.document());
    var result =
        new Result(
            receipt,
            MediaCrawlerAdapter.VERSION,
            converted.platform(),
            converted.sourceContentId(),
            converted.contentRows(),
            converted.commentRows(),
            converted.selectedCommentRows(),
            converted.excludedContentRows(),
            converted.excludedCommentRows(),
            converted.contentFileSha256(),
            converted.commentsFileSha256(),
            null,
            List.of(
                "仅导入所选作品在当前文件中的评论，不代表平台全量。",
                "本次不导入弹幕、视频文件或字幕；没有执行在线采集。",
                "导入不调用模型；免费 Mock 对真实平台文本显示 UNKNOWN。",
                "原始附加字段未保存；文件指纹和转换诊断仅在本次响应提供，请按需保留回执。"));
    return ResponseEntity.created(URI.create("/api/v1/imports/" + receipt.id())).body(result);
  }

  /** 文件名只用于格式校验，不能指定服务器路径；单文件仍限制 2 MiB。 */
  private void validateFile(MultipartFile file, String field) {
    if (file.getSize() > MediaCrawlerAdapter.MAX_FILE_BYTES) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单文件不能超过 2 MiB");
    }
    if (file.isEmpty()
        || file.getOriginalFilename() == null
        || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".json")) {
      throw new ImportFailure(field, "请选择非空 .json 导出文件");
    }
  }
}
