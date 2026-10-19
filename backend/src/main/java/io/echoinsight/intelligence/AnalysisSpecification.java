/* 文件职责：明确命题立场的业务边界，不把情绪、隐含目标或创作者意图当作命题。 */
package io.echoinsight.intelligence;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 不可变的命题规格；只有用户明写的目标才进入立场分析，普通讨论无需填写。 */
public record AnalysisSpecification(String version, String proposition, String origin) {
  public static final String VERSION = "proposition-spec-v1";
  private static final Pattern QUOTED = Pattern.compile("命题\\s*「([^「」]+)」");

  /** 只读识别结果：需要澄清时不创建任务，也不猜测一个命题。 */
  public record Resolution(
      boolean stanceRequested,
      boolean needsProposition,
      AnalysisSpecification specification,
      String explanation) {}

  /** 1、校验原话长度；2、只提取明确的命题「…」语法或用户澄清；3、冲突时拒绝；4、含糊目标返回澄清要求。 任意引号中的台词不会被当成目标，澄清原文会与请求一同冻结。 */
  public static Resolution resolve(String instruction, String clarifiedProposition) {
    if (instruction == null || instruction.isBlank() || instruction.strip().length() > 1000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分析要求不能为空且最多1000字");
    String explicit = clarifiedProposition == null ? null : clarifiedProposition.strip();
    if (explicit != null && explicit.isEmpty()) explicit = null;
    if (explicit != null && explicit.length() > 300)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "待判断命题最多300字");
    var matcher = QUOTED.matcher(instruction);
    String quoted = null;
    while (matcher.find()) {
      if (quoted != null)
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "本次请只明确一个命题");
      quoted = matcher.group(1).strip();
    }
    if (quoted != null && (quoted.isEmpty() || quoted.length() > 300))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "待判断命题不能为空且最多300字");
    if (quoted != null && explicit != null && !Objects.equals(quoted, explicit))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "原话中的命题与补充命题不一致");
    String target = explicit != null ? explicit : quoted;
    boolean requested =
        target != null
            || instruction.contains("支持")
            || instruction.contains("反对")
            || instruction.contains("立场")
            || instruction.contains("赞成");
    var spec =
        target == null
            ? null
            : new AnalysisSpecification(
                VERSION, target, quoted != null ? "USER_INSTRUCTION" : "USER_CLARIFICATION");
    return new Resolution(
        requested,
        requested && spec == null,
        spec,
        requested && spec == null
            ? "请补充一条具体、可判断的命题；支持和反对必须针对同一个标准。"
            : spec == null ? "整理讨论和理由，不自动划分正负面。" : "只判断评论对这一命题的表达，不证明命题真实，也不推断作者意图。");
  }
}
