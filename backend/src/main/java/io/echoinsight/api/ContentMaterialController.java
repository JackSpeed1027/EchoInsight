/* 文件职责：提供无需评论的内容文件、不可变版本及有界原文浏览接口，不读取任意服务器路径。 */
package io.echoinsight.api;

import io.echoinsight.content.ContentMaterialParser;
import io.echoinsight.content.ContentMaterialStore;
import io.echoinsight.project.ProjectStore;
import java.util.Locale;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** TXT/MD/SRT/VTT全部为用户上传资料；不触发采集、模型、ASR或视频下载。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/content-materials")
public class ContentMaterialController {
  private final ContentMaterialStore store;

  /** 控制器只负责文件类型和HTTP转换，存储/版本/哈希由业务服务维护。 */
  public ContentMaterialController(ContentMaterialStore store) {
    this.store = store;
  }

  /** 明确前版本可选，修订另存；同键重发返回原材料，不覆盖旧版。 */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ContentMaterialStore.Material> upload(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestPart("file") MultipartFile file,
      @RequestParam String title,
      @RequestParam(required = false) String sourceDescription,
      @RequestParam(required = false) String previousMaterialId)
      throws java.io.IOException {
    if (file.getSize() > 2 * 1024 * 1024)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件最多2MiB");
    String name = file.getOriginalFilename();
    int dot = name == null ? -1 : name.lastIndexOf('.');
    String format = dot < 0 ? "" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    return ResponseEntity.status(201)
        .body(
            store.create(
                projectId,
                key,
                title,
                sourceDescription,
                previousMaterialId == null || previousMaterialId.isBlank()
                    ? null
                    : previousMaterialId,
                format,
                file.getBytes()));
  }

  /** 只读摘要分页，不把不存在的评论数显示成零条讨论。 */
  @GetMapping
  public ProjectStore.Page<ContentMaterialStore.Summary> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int size) {
    return store.list(projectId, page, size);
  }

  /** 读取明确版本的原始段及定位，越项目404；不追随新版本。 */
  @GetMapping("/{materialId}")
  public ContentMaterialStore.Material get(
      @PathVariable String projectId, @PathVariable String materialId) {
    return store.get(projectId, materialId);
  }

  /** 原文子串检索/时间窗口仅便于回看，不代表语义召回或分析全篇。 */
  @GetMapping("/{materialId}/segments")
  public ProjectStore.Page<ContentMaterialParser.Segment> segments(
      @PathVariable String projectId,
      @PathVariable String materialId,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) Long fromMs,
      @RequestParam(required = false) Long toMs,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return store.segments(projectId, materialId, query, fromMs, toMs, page, size);
  }
}
