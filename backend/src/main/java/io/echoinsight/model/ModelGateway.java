/* 文件职责：限制供应商调用并发与超时，严格校验输出，在租约保护下保存批次产物。 */
package io.echoinsight.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.echoinsight.orchestration.JobStore;
import io.echoinsight.review.FindingCatalog;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 不让超时后仍在运行的供应商请求无限占用新线程，最多两个实际出站线程。 */
@Service
public class ModelGateway implements AutoCloseable {
  private final ModelLedger ledger;
  private final ObjectMapper json;
  private final Map<String, ModelContract.Provider> providers;
  private final Map<String, DiscussionModelContract.Provider> discussionProviders;
  private final ThreadPoolExecutor calls =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.MILLISECONDS,
          new SynchronousQueue<>(),
          r -> {
            var t = new Thread(r, "echoinsight-model-call");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  /** 只注册应用显式声明的分类与讨论供应商；收费供应商必须通过启动配置校验。 */
  @Autowired
  public ModelGateway(
      ModelLedger ledger,
      ObjectMapper json,
      List<ModelContract.Provider> providers,
      List<DiscussionModelContract.Provider> discussionProviders) {
    this.ledger = ledger;
    this.json = json;
    var registered = new HashMap<String, ModelContract.Provider>();
    for (var provider : providers)
      if (registered.put(provider.id(), provider) != null)
        throw new IllegalArgumentException("Duplicate model provider");
    this.providers = Map.copyOf(registered);
    var discussions = new HashMap<String, DiscussionModelContract.Provider>();
    for (var provider : discussionProviders)
      if (discussions.put(provider.id(), provider) != null)
        throw new IllegalArgumentException("Duplicate discussion model provider");
    this.discussionProviders = Map.copyOf(discussions);
  }

  /** 兼容只测试分类网关的既有调用；生产容器使用完整构造器。 */
  public ModelGateway(
      ModelLedger ledger, ObjectMapper json, List<ModelContract.Provider> providers) {
    this(ledger, json, providers, List.of());
  }

  /**
   * 执行或复用一个批次。网关负责外部调用边界，不负责整份报告的统计。
   *
   * <p>1、比对供应商与版本，按输入/输出上界计算预留，并在短事务中取得检查点。 2、成功检查点直接复用；否则交给最多两个出站线程，发送前再次检查租约。
   * 3、事务外等待，在超时以内校验完整标签与用量，再提交结果和结算。 4、失败时由账本区分未发送与结果未知；取消本地 Future 不能证明外部未计费。
   *
   * @param jobId 已保存任务身份，不接受供应商自行提供的任务范围
   * @param token 本次有效执行资格，失效后不得提交
   * @param plan 创建任务时固定的版本、超时与价格估算口径，重试时不得换计划
   * @param request 当前批次的有限文本和用量上界
   * @return 已通过校验并持久化的分类结果，或确认成功的历史检查点
   * @throws JobStore.LostLease 执行资格失效，调用者不得把新执行者标记失败
   * @throws ModelContract.Failure 预算不足、容量不足、输出无效或结果未知等受控错误
   */
  public ModelContract.Response classify(
      String jobId, String token, ModelContract.Plan plan, ModelContract.Request request) {
    var provider = providers.get(plan.providerId());
    if (provider == null || !provider.modelVersion().equals(plan.modelVersion()))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
    provider.validatePlan(plan);
    if (plan.inputTokenCeiling() != null
        && (request.maxInputTokens() != plan.inputTokenCeiling()
            || request.maxOutputTokens() != plan.maxOutputTokens())) {
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
    return execute(
        jobId,
        token,
        plan,
        request.batchIndex(),
        request,
        request.maxInputTokens(),
        request.maxOutputTokens(),
        ModelContract.Response.class,
        () -> provider.classify(request, Duration.ofMillis(plan.timeoutMs())),
        result -> validate(request, result),
        ModelContract.Response::inputTokens,
        ModelContract.Response::outputTokens,
        ModelContract.Response::requestId);
  }

  /**
   * 执行一次讨论语义分析，继续复用分类网关的线程上限、租约、预算和未知结果处理。
   *
   * <p>讨论任务只有一个有界批次，批次号固定为0；恢复时输入指纹一致才允许复用检查点。
   */
  public DiscussionModelContract.Response discuss(
      String jobId,
      String token,
      ModelContract.Plan plan,
      DiscussionModelContract.Request request) {
    if (request == null
        || request.comments() == null
        || request.comments().isEmpty()
        || request.comments().size() > 120) {
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
    var provider = discussionProviders.get(plan.providerId());
    if (provider == null || !provider.modelVersion().equals(plan.modelVersion()))
      throw new ModelContract.Failure("MODEL_UNSUPPORTED_VERSION");
    provider.validatePlan(plan);
    if (plan.inputTokenCeiling() == null
        || request.maxInputTokens() != plan.inputTokenCeiling()
        || request.maxOutputTokens() != plan.maxOutputTokens()) {
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
    validateContextBoundary(plan, request);
    return execute(
        jobId,
        token,
        plan,
        io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(request.promptVersion())
            ? request.batchIndex()
            : 0,
        request,
        request.maxInputTokens(),
        request.maxOutputTokens(),
        DiscussionModelContract.Response.class,
        () -> provider.analyze(request, Duration.ofMillis(plan.timeoutMs())),
        result -> validate(request, result),
        DiscussionModelContract.Response::inputTokens,
        DiscussionModelContract.Response::outputTokens,
        DiscussionModelContract.Response::requestId);
  }

  /** 在费用预留前检查v3/v4对应、背景角色和输入总量，禁止背景碰撞评论身份或扩大调用范围。 */
  private void validateContextBoundary(
      ModelContract.Plan plan, DiscussionModelContract.Request request) {
    String version =
        request.promptVersion() == null
            ? io.echoinsight.model.springai.DiscussionPrompt.VERSION
            : request.promptVersion();
    boolean partitioned = io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(version);
    boolean stance = StanceProtocol.VERSION.equals(version) || partitioned;
    if (partitioned) {
      if (request.batchIndex() == null
          || request.batchIndex() < 0
          || request.batchIndex() >= 5000
          || request.targetCommentIds() == null
          || request.targetCommentIds().isEmpty()
          || request.targetCommentIds().size() > StanceProtocol.MAX_COMMENTS
          || new HashSet<>(request.targetCommentIds()).size() != request.targetCommentIds().size())
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    } else if (request.batchIndex() != null || request.targetCommentIds() != null) {
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
    if (stance) {
      var spec = request.specification();
      if (spec == null
          || !io.echoinsight.intelligence.AnalysisSpecification.VERSION.equals(spec.version())
          || !validText(spec.proposition(), 300)
          || !Set.of("USER_INSTRUCTION", "USER_CLARIFICATION")
              .contains(Objects.toString(spec.origin(), ""))
          || (!partitioned && request.comments().size() > StanceProtocol.MAX_COMMENTS)
          || request.comments().stream().anyMatch(c -> c == null || c.text() == null)
          || (!partitioned
              && request.comments().stream().mapToInt(c -> c.text().length()).sum()
                  > StanceProtocol.MAX_CHARS))
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    } else if (request.specification() != null) {
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
    if (request.promptVersion() == null) {
      if (request.context() != null
          || io.echoinsight.model.springai.DiscussionPrompt.CONTEXT_VERSION.equals(
              plan.promptVersion())
          || StanceProtocol.VERSION.equals(plan.promptVersion())
          || io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(plan.promptVersion()))
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
      return;
    }
    if (!version.equals(plan.promptVersion())
        || !(stance
            || io.echoinsight.model.springai.DiscussionPrompt.CONTEXT_VERSION.equals(version))
        || request.context() == null
        || request.context().size() > 43)
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    Set<String> ids = new HashSet<>();
    for (var comment : request.comments())
      if (comment == null
          || !validId(comment.commentId())
          || !ids.add(comment.commentId())
          || !validText(comment.text(), 10000))
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    if (partitioned && !ids.containsAll(request.targetCommentIds()))
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    int commentChars =
        request.comments().stream().mapToInt(comment -> comment.text().length()).sum();
    if (commentChars > 80000
        || request.totalComments() < request.comments().size()
        || request.totalComments() > 5000)
      throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    for (var comment : request.comments())
      if (comment.parentCommentId() != null && !ids.contains(comment.parentCommentId()))
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    int referenceCount = 0, transcriptCount = 0, referenceChars = 0, transcriptChars = 0;
    int textChars = 0;
    for (var source : request.context()) {
      if (source == null
          || !validId(source.evidenceId())
          || !ids.add(source.evidenceId())
          || !Set.of("REFERENCE_TEXT", "TRANSCRIPT").contains(Objects.toString(source.role(), ""))
          || !validText(source.text(), 12000)
          || !validText(source.title(), 200)
          || source.sourceDescription() == null
          || source.sourceDescription().length() > 200)
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
      if ("REFERENCE_TEXT".equals(source.role())) {
        referenceCount++;
        referenceChars += source.text().length();
        if (source.startMs() != null || source.endMs() != null)
          throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
      } else {
        transcriptCount++;
        transcriptChars += source.text().length();
        if (source.startMs() == null
            || source.endMs() == null
            || source.startMs() < 0
            || source.endMs() <= source.startMs())
          throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
      }
      if (referenceCount > 3
          || transcriptCount > 40
          || referenceChars > 12000
          || transcriptChars > 8000)
        throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
      textChars += source.text().length();
      if (textChars > 20000) throw new ModelContract.Failure("MODEL_INVALID_REQUEST_BOUNDARY");
    }
  }

  /** 允许受控调用抛出供应商异常，统一交给网关转换为公开错误码。 */
  @FunctionalInterface
  private interface ExternalCall<T> {
    T call() throws Exception;
  }

  /** 读取不同响应中的供应商用量，避免分类与讨论复制账本生命周期。 */
  @FunctionalInterface
  private interface IntValue<T> {
    int get(T value);
  }

  /** 读取可空请求身份。 */
  @FunctionalInterface
  private interface StringValue<T> {
    String get(T value);
  }

  /**
   * 统一执行一个已验证业务请求。
   *
   * <p>1、按计划上界预留并读取缓存；2、在线程池内标记已发送后调用一次；3、校验业务结果再结算；4、失败时保留已发送请求的未知费用事实。
   */
  private <T> T execute(
      String jobId,
      String token,
      ModelContract.Plan plan,
      int batchIndex,
      Object request,
      int maxInputTokens,
      int maxOutputTokens,
      Class<T> responseType,
      ExternalCall<T> external,
      Consumer<T> validator,
      IntValue<T> inputTokens,
      IntValue<T> outputTokens,
      StringValue<T> requestId) {
    long reservation = cost(plan, maxInputTokens, maxOutputTokens);
    var prepared =
        ledger.prepare(
            jobId,
            token,
            batchIndex,
            FindingCatalog.hash(ModelContract.encode(plan) + ModelContract.encode(request)),
            reservation,
            plan);
    if (prepared.failure() != null) throw new ModelContract.Failure(prepared.failure());
    if (prepared.cached() != null) {
      try {
        var cached = json.readValue(prepared.cached(), responseType);
        validator.accept(cached);
        return cached;
      } catch (Exception ignored) {
        throw new ModelContract.Failure("MODEL_INVALID_CHECKPOINT");
      }
    }
    Future<T> future = null;
    try {
      future =
          calls.submit(
              () -> {
                ledger.sent(jobId, token, prepared.callId());
                return external.call();
              });
      var result = future.get(plan.timeoutMs(), TimeUnit.MILLISECONDS);
      validator.accept(result);
      int usedInput = inputTokens.get(result), usedOutput = outputTokens.get(result);
      ledger.completeJson(
          jobId,
          token,
          batchIndex,
          prepared.callId(),
          ModelContract.encode(result),
          usedInput,
          usedOutput,
          requestId.get(result),
          cost(plan, usedInput, usedOutput));
      return result;
    } catch (JobStore.LostLease e) {
      ledger.abort(prepared.callId(), "LEASE_LOST");
      throw e;
    } catch (Exception e) {
      if (future != null) future.cancel(true);
      Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
      if (cause instanceof JobStore.LostLease lost) {
        ledger.abort(prepared.callId(), "LEASE_LOST");
        throw lost;
      }
      String error =
          e instanceof RejectedExecutionException
              ? "MODEL_CAPACITY"
              : e instanceof TimeoutException
                  ? "MODEL_TIMEOUT"
                  : cause instanceof ModelContract.Failure failure
                      ? failure.code()
                      : "MODEL_CALL_FAILED";
      String state = ledger.abort(prepared.callId(), error);
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ModelContract.Failure(
          "OUTCOME_UNKNOWN".equals(state) ? "MODEL_OUTCOME_UNKNOWN" : error);
    }
  }

  /** 用整数模拟单位计算费用，避免浮点误差；当前不代表供应商真实价格。 */
  private long cost(ModelContract.Plan plan, int input, int output) {
    return Math.addExact(
        Math.multiplyExact(plan.inputUnitRate(), input),
        Math.multiplyExact(plan.outputUnitRate(), output));
  }

  /** 每个输入必须有且只有一个合法标签，用量不得超出已预留契约上界。 */
  private void validate(ModelContract.Request request, ModelContract.Response response) {
    if (response == null
        || response.classifications() == null
        || response.inputTokens() < 0
        || response.outputTokens() < 0
        || response.inputTokens() > request.maxInputTokens()
        || response.outputTokens() > request.maxOutputTokens()
        || (response.requestId() != null && response.requestId().length() > 128))
      throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    Set<String> expected = new HashSet<>(), seen = new HashSet<>();
    request.comments().forEach(c -> expected.add(c.commentId()));
    for (var result : response.classifications())
      if (result == null
          || result.label() == null
          || !expected.contains(result.commentId())
          || !seen.add(result.commentId()))
        throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
    if (!seen.equals(expected)) throw new ModelContract.Failure("MODEL_INVALID_RESPONSE");
  }

  /** 校验讨论结果的引用闭包、数量和字段长度，阻止幻觉证据或跨议题关系进入产物。 */
  private void validate(
      DiscussionModelContract.Request request, DiscussionModelContract.Response response) {
    if (StanceProtocol.VERSION.equals(request.promptVersion())
        || io.echoinsight.intelligence.StanceChunkPlanner.PROMPT.equals(request.promptVersion())) {
      if (response == null
          || response.inputTokens() < 0
          || response.outputTokens() < 0
          || response.inputTokens() > request.maxInputTokens()
          || response.outputTokens() > request.maxOutputTokens()
          || (response.requestId() != null && response.requestId().length() > 128)
          || response.issues() == null
          || !response.issues().isEmpty()
          || response.claims() == null
          || !response.claims().isEmpty()
          || response.relations() == null
          || !response.relations().isEmpty()
          || response.assessments() == null
          || !response.assessments().isEmpty()) invalid("MODEL_RESPONSE_SCHEMA");
      StanceProtocol.validate(request, response.decisions());
      return;
    }
    if (response == null
        || response.decisions() != null
        || response.issues() == null
        || response.claims() == null
        || response.relations() == null
        || response.assessments() == null
        || response.issues().isEmpty()
        || response.issues().size() > 10
        || response.claims().size() > 40
        || response.relations().size() > 60
        || response.inputTokens() < 0
        || response.outputTokens() < 0
        || response.inputTokens() > request.maxInputTokens()
        || response.outputTokens() > request.maxOutputTokens()
        || (response.requestId() != null && response.requestId().length() > 128)) {
      invalid("MODEL_RESPONSE_SCHEMA");
    }
    Set<String> evidence = new HashSet<>();
    for (var comment : request.comments()) {
      if (comment == null
          || !validId(comment.commentId())
          || !validText(comment.text(), 10000)
          || !evidence.add(comment.commentId())
          || (comment.parentCommentId() != null && !validId(comment.parentCommentId())))
        invalid("MODEL_INVALID_INPUT_TREE");
    }
    Set<String> commentEvidence = new HashSet<>(evidence);
    if (request.context() != null)
      request.context().forEach(source -> evidence.add(source.evidenceId()));
    Set<String> issueIds = new HashSet<>(), claimIds = new HashSet<>();
    for (var issue : response.issues()) {
      if (issue != null
          && (!validEvidence(issue.evidenceIds(), evidence)
              || !hasComment(issue.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (issue == null
          || !validId(issue.id())
          || !issueIds.add(issue.id())
          || !validText(issue.question(), 300)
          || !validText(issue.summary(), 1200)
          || !validEvidence(issue.evidenceIds(), evidence)
          || !hasComment(issue.evidenceIds(), commentEvidence)) invalid("MODEL_ISSUE_INVALID");
    }
    Map<String, String> claimIssues = new HashMap<>();
    for (var claim : response.claims()) {
      if (claim != null
          && (!validEvidence(claim.evidenceIds(), evidence)
              || !hasComment(claim.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (claim == null
          || !validId(claim.id())
          || !claimIds.add(claim.id())
          || !issueIds.contains(claim.issueId())
          || claim.reasoningStatus() == null
          || !validText(claim.statement(), 1000)
          || !validEvidence(claim.evidenceIds(), evidence)
          || !hasComment(claim.evidenceIds(), commentEvidence)) invalid("MODEL_CLAIM_INVALID");
      claimIssues.put(claim.id(), claim.issueId());
    }
    for (var relation : response.relations()) {
      if (relation != null
          && (!validEvidence(relation.evidenceIds(), evidence)
              || !hasComment(relation.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (relation == null
          || relation.fromClaimId() == null
          || relation.toClaimId() == null
          || relation.fromClaimId().equals(relation.toClaimId())
          || !claimIds.contains(relation.fromClaimId())
          || !claimIds.contains(relation.toClaimId())
          || !Objects.equals(
              claimIssues.get(relation.fromClaimId()), claimIssues.get(relation.toClaimId()))
          || relation.type() == null
          || relation.semanticStatus() == null
          || !validEvidence(relation.evidenceIds(), evidence)
          || !hasComment(relation.evidenceIds(), commentEvidence))
        invalid("MODEL_RELATION_INVALID");
    }
    Set<String> assessed = new HashSet<>();
    for (var assessment : response.assessments()) {
      if (assessment != null
          && (!validEvidence(assessment.evidenceIds(), evidence)
              || !hasComment(assessment.evidenceIds(), commentEvidence)))
        invalid("MODEL_EVIDENCE_INVALID");
      if (assessment == null
          || !issueIds.contains(assessment.issueId())
          || !assessed.add(assessment.issueId())
          || assessment.verdict() == null
          || !validText(assessment.conclusion(), 1000)
          || !validText(assessment.reason(), 1200)
          || !validEvidence(assessment.evidenceIds(), evidence)
          || !hasComment(assessment.evidenceIds(), commentEvidence))
        invalid("MODEL_ASSESSMENT_INVALID");
    }
    if (request.includeAssessment()
        ? !assessed.equals(issueIds)
        : !response.assessments().isEmpty()) invalid("MODEL_ASSESSMENT_INVALID");
  }

  /** 背景可以补充解释，但每个观众议题/观点/关系/评议仍须绑定至少一条真实评论。 */
  private boolean hasComment(List<String> ids, Set<String> comments) {
    return ids != null && ids.stream().anyMatch(comments::contains);
  }

  /** 标识符只允许短 ASCII，避免把长文本或控制字符混入关系字段。 */
  private boolean validId(String value) {
    return value != null && value.matches("[A-Za-z0-9._:-]{1,64}");
  }

  /** 所有可展示结论都必须非空且有界。 */
  private boolean validText(String value, int max) {
    return value != null && !value.isBlank() && value.length() <= max;
  }

  /** 每个结论至少绑定一个输入证据，且不允许重复或越界编号。 */
  private boolean validEvidence(List<String> ids, Set<String> allowed) {
    return ids != null
        && !ids.isEmpty()
        && ids.size() <= 20
        && new HashSet<>(ids).size() == ids.size()
        && allowed.containsAll(ids);
  }

  /** 统一抛出公开错误码，不把模型正文写入异常和日志。 */
  private void invalid(String code) {
    throw new ModelContract.Failure(code);
  }

  /** 应用退出时中断出站线程；未确定用量仍由账本与恢复流程保留。 */
  @Override
  @PreDestroy
  public void close() {
    calls.shutdownNow();
  }
}
