/* 文件职责：串联UP主候选检索、显式选择采集和冻结范围；HTTP不能提交命令、Cookie或模型计划。 */
package io.echoinsight.api;

import io.echoinsight.collection.*;
import io.echoinsight.material.InputManifestStore;
import io.echoinsight.project.ProjectStore;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 接口表达业务动作，动态工具选择尚未引入，所有平台调用都有服务器硬边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CreatorWorkflowController {
  private final CreatorDiscoveryStore discoveries;
  private final CollectionGroupStore groups;

  /** 两个组合服务共用原采集执行链，控制器不持有线程或运行外部程序。 */
  public CreatorWorkflowController(CreatorDiscoveryStore discoveries, CollectionGroupStore groups) {
    this.discoveries = discoveries;
    this.groups = groups;
  }

  /** keyword为平台关键词，非自然语言语义分类；空字符串表示最新投稿候选。 */
  public record DiscoverInput(String url, String keyword, Integer candidateLimit) {}

  /** 只接受本次候选的BV号，rootLimit为每作品的根评论上限。 */
  public record CollectInput(String discoveryId, List<String> bvids, Integer rootLimit) {}

  /** collectionIds必须全部属于本组且成功；部分失败时由用户明确确认子集。 */
  public record FreezeInput(String name, List<String> collectionIds) {}

  /** 接受持久发现任务，返回202后由共享工具池执行。 */
  @PostMapping("/creator-discoveries")
  public ResponseEntity<CreatorDiscoveryStore.Run> discover(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody DiscoverInput input) {
    return ResponseEntity.accepted()
        .body(
            discoveries.create(
                projectId,
                key,
                input.url(),
                input.keyword(),
                input.candidateLimit() == null ? 10 : input.candidateLimit()));
  }

  /** 查询原检索的固定输入和结果，不重新访问平台。 */
  @GetMapping("/creator-discoveries/{discoveryId}")
  public CreatorDiscoveryStore.Run discovery(
      @PathVariable String projectId, @PathVariable String discoveryId) {
    return discoveries.get(projectId, discoveryId);
  }

  /** 历史按页返回，可恢复刷新前的候选选择入口。 */
  @GetMapping("/creator-discoveries")
  public ProjectStore.Page<CreatorDiscoveryStore.Run> discoveries(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return discoveries.list(projectId, page, size);
  }

  /** 同事务接受所有选择，不能只留下半组子任务。 */
  @PostMapping("/collection-groups")
  public ResponseEntity<CollectionGroupStore.Group> collect(
      @PathVariable String projectId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody CollectInput input) {
    return ResponseEntity.accepted()
        .body(
            groups.create(
                projectId,
                key,
                input.discoveryId(),
                input.bvids(),
                input.rootLimit() == null ? 20 : input.rootLimit()));
  }

  /** 派生进度支持部分失败，不复制一份可漂移的状态。 */
  @GetMapping("/collection-groups/{groupId}")
  public CollectionGroupStore.Group group(
      @PathVariable String projectId, @PathVariable String groupId) {
    return groups.get(projectId, groupId);
  }

  /** 通过项目内历史恢复运行，不自动重新采集。 */
  @GetMapping("/collection-groups")
  public ProjectStore.Page<CollectionGroupStore.Group> groups(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return groups.list(projectId, page, size);
  }

  /** 显式冻结成功子集，随后仍由用户提交自然语言分析要求。 */
  @PostMapping("/collection-groups/{groupId}/manifest")
  public ResponseEntity<InputManifestStore.Manifest> freeze(
      @PathVariable String projectId,
      @PathVariable String groupId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FreezeInput input) {
    return ResponseEntity.status(201)
        .body(groups.freeze(projectId, groupId, key, input.name(), input.collectionIds()));
  }
}
