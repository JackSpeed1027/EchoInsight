/* 文件职责：把链接/已存资料与用户原话规范成固定入口要求；只做确定性识别，不调用模型或网络。 */
package io.echoinsight.intake;

import io.echoinsight.collection.*;
import io.echoinsight.intelligence.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 仅受支持的单作品评论链；UP目录、小红书在线、跨作品及视频理解不被一句话静默扩权。 */
@Component
public class AnalysisIntakeProtocol {
  public static final String VERSION = "analysis-intake-v1";
  private final BilibiliUrlParser urls;
  private final IntentPlanner intents;

  /** 复用现有URL白名单与讨论/命题规则，不在入口建立另一套语义路由。 */
  public AnalysisIntakeProtocol(BilibiliUrlParser urls, IntentPlanner intents) {
    this.urls = urls;
    this.intents = intents;
  }

  /** 用户只提交业务原话及资料身份；内部工具、供应商和预算不从此处接收。 */
  public record Draft(
      String url,
      String snapshotId,
      String instruction,
      String proposition,
      String analysisScope) {}

  /** URL查询参数不保留；原问题/澄清/范围固定，原始资料版本仍需要明确绑定。 */
  public record Input(
      String version,
      String canonicalUrl,
      String sourceId,
      String requestedSnapshotId,
      String instruction,
      String proposition,
      String analysisScope,
      AnalysisSpecification.Resolution goal,
      boolean includeAssessment) {}

  /** 采集确认与分析确认分开，采集完成不能被当成同意模型调用。 */
  public record Collect(Integer rootLimit, boolean collectAll, boolean confirmed) {}

  /** 分析接受必须绑定本次预检指纹，不能把旧页面确认用于新配置/范围。 */
  public record Accept(String quoteSha256, boolean allowPartialInput, boolean confirmFullScope) {}

  /** 1、检查原话；2、规范URL和可选本地身份；3、澄清明确命题；4、固定支持的讨论配方。 */
  public Input normalize(Draft draft) {
    if (draft == null) throw bad("请填写视频链接或选择已保存资料，并写明分析问题");
    var goal = AnalysisSpecification.resolve(draft.instruction(), draft.proposition());
    String snapshot = blank(draft.snapshotId()), url = blank(draft.url());
    if (snapshot != null
        && !snapshot.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
      throw bad("分析入口资料身份无效");
    if (snapshot == null && url == null) throw bad("请填写视频链接或选择已保存资料，并写明分析问题");
    BilibiliUrlParser.Target target = url == null ? null : urls.parse(url);
    String scope = blank(draft.analysisScope());
    if (scope == null) scope = "FULL";
    AnalysisScopeStore.full(draft.instruction(), scope);
    boolean assessment =
        !goal.stanceRequested() && intents.plan(draft.instruction()).includeAssessment();
    return new Input(
        VERSION,
        target == null ? null : target.canonicalUrl(),
        target == null ? null : target.sourceId(),
        snapshot,
        draft.instruction().strip(),
        blank(draft.proposition()),
        scope,
        goal,
        assessment);
  }

  /** 合并采集同义默认值，让重发比较业务意义；不接受“全部”同时另设根限量。 */
  public Collect collect(Collect body) {
    if (body == null || !body.confirmed()) throw bad("请先确认本次采集范围，采集不会自动开始模型分析");
    if (body.collectAll() && body.rootLimit() != null) throw bad("采齐公开评论时不要同时指定根评论限量");
    int limit = body.rootLimit() == null ? 50 : body.rootLimit();
    if (!body.collectAll() && (limit < 1 || limit > BilibiliCollectionSettings.MAX_ROOT_LIMIT))
      throw bad("根评论数量需要在 1～200 之间");
    return new Collect(body.collectAll() ? null : limit, body.collectAll(), true);
  }

  /** 对外只输出固定业务错误，不反射用户原文、命令或URL碎片。 */
  public static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }

  /** 空白可选字段统一为null，不改写用户问题内部的词语或换行。 */
  private String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
