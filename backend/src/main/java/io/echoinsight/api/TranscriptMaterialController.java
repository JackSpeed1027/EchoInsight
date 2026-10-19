/* 文件职责：接收字幕上传、同作品列表、原文段详情和只读检索；不下载媒体或运行模型。 */
package io.echoinsight.api;

import io.echoinsight.material.*;
import io.echoinsight.project.ProjectStore;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 字幕上传只追加版本，来源声明不认证；URI、文件路径、模型和计费参数不从客户端接收。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/transcripts")
public class TranscriptMaterialController {
  private final TranscriptMaterialStore store;
  private final TranscriptRetriever retriever;

  /** 解析/资格/事务均由材料服务处理。 */
  public TranscriptMaterialController(
      TranscriptMaterialStore store, TranscriptRetriever retriever) {
    this.store = store;
    this.retriever = retriever;
  }

  /** 文件扩展名只决定解析器，不用上传文件名构建服务器路径。 */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<TranscriptMaterialStore.Material> upload(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam String snapshotId,
      @RequestParam String title,
      @RequestParam(defaultValue = "") String sourceDescription,
      @RequestPart("file") MultipartFile file)
      throws java.io.IOException {
    if (file.getSize() > SubtitleParser.MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SubtitleParser.LIMIT);
    String name =
        file.getOriginalFilename() == null
            ? ""
            : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
    String format = name.endsWith(".srt") ? "SRT" : name.endsWith(".vtt") ? "VTT" : "";
    return ResponseEntity.status(201)
        .body(
            store.create(
                projectId, key, snapshotId, title, sourceDescription, format, file.getBytes()));
  }

  /** 只列当前作品的独立字幕版本，不返回全文。 */
  @GetMapping
  public ProjectStore.Page<TranscriptMaterialStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam String snapshotId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return store.list(projectId, snapshotId, page, size);
  }

  /** 详情可核对全部来源段和时间，不能跨项目读取。 */
  @GetMapping("/{materialId}")
  public TranscriptMaterialStore.Material get(
      @PathVariable String projectId, @PathVariable String materialId) {
    return store.get(projectId, materialId);
  }

  /** 检索只返回匹配/邻域段，不修改资料，不创建分析。 */
  @GetMapping("/{materialId}/search")
  public TranscriptRetriever.Result search(
      @PathVariable String projectId,
      @PathVariable String materialId,
      @RequestParam String snapshotId,
      @RequestParam String query) {
    return retriever.select(store.forSnapshot(projectId, materialId, snapshotId).segments(), query);
  }
}
