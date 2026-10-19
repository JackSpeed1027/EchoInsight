/* 文件职责：把HTTP参数、范围和解析失败映射为有界安全错误，不将底层异常或用户正文反射到页面。 */
package io.echoinsight.api;

import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 只处理已知输入失败；未知服务器异常仍保持500，不伪装为用户参数错误。 */
@RestControllerAdvice
public class ApiProblemHandler {
  /** 固定消息可安全显示；不回显任意ResponseStatusException.reason。 */
  private static final Set<String> REASONS =
      Set.of(
          "评论身份与追问背景冲突，请重新导入为无冲突资料后新建分析",
          "追问需要一个背景位置：原参考最多2份，参考与历史合计12000字，请缩小背景后新建分析",
          "只有已完成的报告可以继续追问",
          "追问不能为空且最多1000字",
          "追问用于解释或复核所选评论；重新指定命题或要求全量分类请新建分析",
          "请选择原报告中的一条评论作为追问对象",
          "所选评论不在当前有界讨论输入中，请缩小资料后新建分析",
          "连续追问最多8层，请整理问题后新建分析",
          "追问背景超过4000字或总背景20000字，请新建独立分析并缩小资料范围",
          "该请求键已经用于其他追问关系",
          "页码1～10000，每页1～50条",
          "比较筛选无效：页码1～10000、每页1～50条",
          "只能比较同一作品的报告，请核对新旧资料",
          "评测候选需要dev或test划分，数量1～120",
          "只有已发布的逐评论命题判断可导出评测候选",
          "原判断缺少可独立评测的父语境，请核对报告范围",
          "字幕格式无效：需要UTF8的SRT或WebVTT，每段时间递增且正文非空",
          "字幕最多2MiB、5000段、每段10000字、时间范围不超过24小时",
          "字幕需要有效请求键、1～120字标题和最多200字来源说明",
          "字幕与本次作品不匹配，请选择该作品的字幕版本",
          "独立字幕与快照自带字幕不能同时选择",
          "该请求键已用于其他字幕资料",
          "结果筛选无效：页码1～10000、每页1～50条、关键词最多80字",
          "当前结果没有逐评论命题判断",
          "报告格式只能是json或markdown",
          "分析要求不能为空且最多1000字",
          "分析范围只能是FULL或PREVIEW",
          "完整分片当前仅支持明确命题的逐评论分析",
          "该请求键已经用于其他分析范围",
          "请先确认完整范围及费用估算上界",
          "完整命题分析需要启用模型，免费演示不会生成完整立场",
          "本任务预算不足以覆盖完整范围的保守上界，请缩小资料范围",
          "资料存在缺失父评论，无法按完整范围分析，请补齐后创建新版本",
          "部分评论的完整父语境超过处理边界，当前无法承诺完整分析",
          "命题分析暂仅支持单份资料，请在智能分析页明确命题",
          "待判断命题最多300字",
          "待判断命题不能为空且最多300字",
          "本次请只明确一个命题",
          "原话中的命题与补充命题不一致",
          "请先明确需要判断支持或反对的命题",
          "该请求键已经用于其他命题",
          "最多选择3份不重复参考资料",
          "本次参考文本合计最多12000字，请缩小范围",
          "标题1～120字、正文1～12000字、来源说明最多200字",
          "该请求键已经用于其他分析背景",
          "该请求键已经用于其他资料或分析要求",
          "分析结果尚未发布",
          "这个项目有多份资料，请明确选择一份",
          "项目还没有资料，请先添加资料",
          "项目有多份资料，请先选择本次分析对象",
          "需要有效的分析请求键",
          "当前先支持总结讨论、整理观点和比较理由，请把目标写得更明确",
          "需要确认有限输入范围后再提交分析",
          "当前没有父上下文完整且符合大小边界的评论可供分析");

  /** 不含拒绝值、请求正文、SQL、路径或原始供应商消息。 */
  public record Issue(String path, String message) {}

  /** code/status/message/issues是前端唯一可展示的错误契约。 */
  public record Problem(String code, int status, String message, List<Issue> issues) {}

  /** Bean Validation只返回字段名和约束消息，不返回用户提交的值。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Problem> validation(MethodArgumentNotValidException error) {
    var issues =
        error.getBindingResult().getFieldErrors().stream()
            .limit(10)
            .map(
                field ->
                    new Issue(
                        field.getField(),
                        field.getDefaultMessage() == null ? "字段不符合要求" : field.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(new Problem("INVALID_FIELDS", 400, "请按字段提示修正后再提交。", issues));
  }

  /** 解析器消息可能带正文片段，只显示固定说明。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Problem> unreadable() {
    return ResponseEntity.badRequest()
        .body(new Problem("INVALID_JSON", 400, "请求JSON格式错误或字段类型不正确。", List.of()));
  }

  /** 请求键缺失是可修正入参错误，不输出反射方法签名。 */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<Problem> missingHeader() {
    return ResponseEntity.badRequest()
        .body(new Problem("MISSING_REQUEST_KEY", 400, "缺少必需请求头，请刷新页面后重新提交。", List.of()));
  }

  /** 业务状态保留原HTTP语义；仅允许白名单中文原因进入响应。 */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Problem> status(ResponseStatusException error) {
    int status = error.getStatusCode().value();
    String fallback =
        switch (status) {
          case 400 -> "请求条件不符合当前边界，请检查数量、资料范围和字段。";
          case 404 -> "项目、资料或任务不存在，或不在当前范围。";
          case 409 -> "当前状态或请求键与已有内容冲突，请核对原记录。";
          case 413 -> "文件超过上传大小限制。";
          case 422 -> "当前无法按这份要求可靠执行，请明确讨论目标或补充材料。";
          case 503 -> "本机尚未启用所需能力，请检查服务端配置。";
          default -> "服务暂时无法完成请求，请保留任务记录后重试查询。";
        };
    String reason = error.getReason();
    return ResponseEntity.status(status)
        .body(
            new Problem(
                "REQUEST_REJECTED",
                status,
                REASONS.contains(reason == null ? "" : reason) ? reason : fallback,
                List.of()));
  }
}
