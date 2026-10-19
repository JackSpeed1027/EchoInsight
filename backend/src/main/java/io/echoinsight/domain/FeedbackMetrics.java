/* 文件职责：保存分类数量并计算可复核的分母与正面比例，不调用模型。 */
package io.echoinsight.domain;

/** 单个快照的确定性计数。UNKNOWN 代表无法分类，不属于正面、负面或中立， 因此不能进入已分类评论分母；结果也不代表平台全部观众。 */
public record FeedbackMetrics(int positive, int negative, int neutral, int unknown) {
  /** 拒绝负数，防止异常分类计数进入报告；合法的全零输入允许存在。 */
  public FeedbackMetrics {
    if (positive < 0 || negative < 0 || neutral < 0 || unknown < 0) {
      throw new IllegalArgumentException("Counts must be non-negative");
    }
  }

  /** 将三类已确认标签相加；先提升为 long，避免多个 int 计数求和时溢出。 */
  public long classifiedComments() {
    return (long) positive + negative + neutral;
  }

  /** 返回当前已分类样本中的正面比例。分母为零时返回 null， 用“无可用比例”表达未分类，不能把缺少信息误写成 0% 正面。 */
  public Double positiveShareAmongClassified() {
    long denominator = classifiedComments();
    return denominator == 0 ? null : (double) positive / denominator;
  }
}
