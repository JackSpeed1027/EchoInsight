/* 文件职责：提供项目内 B站 URL 采集的创建、查询、列表与失败重试接口。 */
package io.echoinsight.api;

import io.echoinsight.collection.BilibiliCollectionSettings;
import io.echoinsight.collection.BilibiliUrlParser;
import io.echoinsight.collection.CollectionStore;
import io.echoinsight.project.ProjectStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制器不运行命令、不接收 Cookie/代理/工具参数；所有外部访问由受限后台工作者完成。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/collections")
public class BilibiliCollectionController {
  private final io.echoinsight.collection.PagedBilibiliCollection paged;
  private final BilibiliUrlParser urls;
  private final CollectionStore store;

  public BilibiliCollectionController(
      BilibiliUrlParser urls,
      CollectionStore store,
      io.echoinsight.collection.PagedBilibiliCollection paged) {
    this.paged = paged;
    this.urls = urls;
    this.store = store;
  }

  /** URL 是唯一平台输入，rootLimit 仅控制根评论上限；回复仍由工具在同一评论树内展开。 */
  public record CreateRequest(
      @NotBlank @Size(max = 500) String url,
      @Min(value = 1, message = "根评论上限至少1条")
          @Max(value = BilibiliCollectionSettings.MAX_ROOT_LIMIT, message = "根评论上限最多200条，回复另计")
          Integer rootLimit,
      Boolean collectAll) {}

  /** 范围说明与状态同行返回，前端不能把“采集成功”误写成“平台全量”。 */
  public record Result(
      CollectionStore.Batch batch,
      List<String> scopeNotes,
      io.echoinsight.collection.PagedBilibiliCollection.Progress progress) {}

  /** 1、解析官方 URL；2、按幂等键创建批次；3、后台执行；4、返回可轮询地址。 */
  @PostMapping("/bilibili")
  public ResponseEntity<Result> create(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String requestKey,
      @Valid @RequestBody CreateRequest request) {
    int limit = request.rootLimit() == null ? 50 : request.rootLimit();
    boolean all = Boolean.TRUE.equals(request.collectAll());
    if (all && request.rootLimit() != null)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "采齐公开评论时不要同时指定根评论限量");
    var batch = store.createWithMode(projectId, requestKey, urls.parse(request.url()), limit, all);
    URI location = URI.create("/api/v1/projects/" + projectId + "/collections/" + batch.id());
    return ResponseEntity.accepted().location(location).body(result(batch));
  }

  /** 页面轮询的是持久状态，刷新或后端重启后不会丢失采集进度。 */
  @GetMapping("/{collectionId}")
  public Result get(@PathVariable String projectId, @PathVariable String collectionId) {
    return result(store.get(projectId, collectionId));
  }

  /** 项目内历史支持分页，列表不包含评论正文或本机存档路径。 */
  @GetMapping
  public ProjectStore.Page<Result> list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    var batches = store.list(projectId, page, size);
    return new ProjectStore.Page<>(
        batches.items().stream().map(this::result).toList(),
        batches.total(),
        batches.page(),
        batches.size(),
        batches.totalPages());
  }

  /** 失败批次显式重试，成功、等待或运行中的批次不会被重复执行。 */
  @PostMapping("/{collectionId}/retry")
  public ResponseEntity<Result> retry(
      @PathVariable String projectId, @PathVariable String collectionId) {
    return ResponseEntity.accepted().body(result(store.retry(projectId, collectionId)));
  }

  private Result result(CollectionStore.Batch batch) {
    return new Result(
        batch,
        List.of(
            "采集范围为公开视频元数据、评论及楼中楼，不包含弹幕、字幕或视频文件。",
            "collectAll=true 按页续跑至接口终止；VISIBLE_EXHAUSTED只表示当前可见遍历及数量核对完成，不承诺包含已删除、隐藏或权限不可见评论。",
            "NOT_PROVEN 表示当前结果没有证据证明平台全量；TRUNCATED 表示达到根评论上限。",
            "PARTIAL_FAILED 表示回复、父引用或平台总数尚有缺口，成功导入不等于覆盖全部讨论。"),
        paged.progress(batch));
  }
}
