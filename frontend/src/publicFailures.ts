// 文件职责：在全部业务页面统一解释受控错误码；未知值不当成可展示的异常正文。
export type Failure = { title: string; message: string; action: string }
const MODEL_ACTION =
  '结果未通过发布校验；查看执行记录定位原因，修正后再决定新任务，不自动付费重试。'
const messages: Record<string, [string, string]> = {
  FULL_MODEL_REQUIRED: [
    '完整命题分析需要模型能力',
    '当前仅免费结构演示；可先做有限预览，启用模型后再确认完整范围。',
  ],
  FULL_BUDGET_INSUFFICIENT: [
    '当前任务预算不足以覆盖完整范围上界',
    '缩小资料范围或先做有限预览；系统不会自动增加额度。',
  ],
  FULL_CONTEXT_MISSING: [
    '缺少部分父评论，不能保证完整语境',
    '补齐缺失父评论后导入新版本；有限预览仍明确排除缺口与后代。',
  ],
  FULL_CONTEXT_TOO_LARGE: [
    '部分评论及祖先语境超出单片边界',
    '缩小或整理资料后重建版本；不会裁掉父语境后宣称完整。',
  ],
  FULL_SCOPE_CHANGED: [
    '冻结范围与当前执行输入不一致',
    '保留原任务，核对快照和分片规则，不换清单直接重试。',
  ],
  FULL_COVERAGE_INVALID: [
    '分片结果没有通过完整覆盖对账',
    '已有成功检查点保留；未发布部分不提供总体结论。',
  ],
  MODEL_OUTCOME_UNKNOWN: [
    '调用已发送，但未取得可发布的可信结果',
    '费用可能已产生，暂不能直接重试；先核对调用记录。',
  ],
  MODEL_RESPONSE_JSON: ['模型正文不是完整、严格的JSON', MODEL_ACTION],
  MODEL_RESPONSE_SCHEMA: ['模型返回字段、枚举或数量不符合协议', MODEL_ACTION],
  MODEL_RESPONSE_ENVELOPE: ['模型返回的结果外壳或工具调用不符合协议', MODEL_ACTION],
  MODEL_RESPONSE_EMPTY: ['模型没有返回可用正文', MODEL_ACTION],
  MODEL_RESPONSE_TOO_LARGE: ['模型响应超过安全体积限制', MODEL_ACTION],
  MODEL_EVIDENCE_INVALID: ['模型引用不存在、重复或缺少评论依据', MODEL_ACTION],
  MODEL_ISSUE_INVALID: ['模型议题的身份、文字或字段未通过校验', MODEL_ACTION],
  MODEL_CONTENT_INVALID: ['内容结果的字段、用量或材料引用未通过校验，未发布报告', MODEL_ACTION],
  MODEL_STANCE_INVALID: ['命题结果缺少逐条覆盖、条件或原评论证据', MODEL_ACTION],
  MODEL_CLAIM_INVALID: ['模型观点的议题归属或字段未通过校验', MODEL_ACTION],
  MODEL_RELATION_INVALID: ['模型观点关系越界、自引用或跨议题', MODEL_ACTION],
  MODEL_ASSESSMENT_INVALID: ['模型评议缺失、重复或不符合本次要求', MODEL_ACTION],
  MODEL_INVALID_RESPONSE: ['模型返回内容未通过校验；旧记录没有更细的原因', MODEL_ACTION],
  MODEL_INCOMPLETE_RESPONSE: ['模型输出截断、拒答或未正常结束', MODEL_ACTION],
  MODEL_INVALID_USAGE: ['模型返回用量不合法或超过预留边界', MODEL_ACTION],
  MODEL_USAGE_MISSING: ['供应商没有返回协议要求的原始用量', MODEL_ACTION],
  MODEL_ID_MISMATCH: [
    '响应模型身份与固定计划不一致',
    '检查服务器的模型适配和响应身份，不替换历史计划。',
  ],
  MODEL_UNSUPPORTED_VERSION: [
    '当前适配器不支持这份历史执行计划',
    '保留原记录，核对提示/模型版本后再明确创建新任务。',
  ],
  MODEL_TIMEOUT: ['等待模型响应超时', '超时不能证明未计费，请先查看调用状态。'],
  MODEL_BUDGET_EXCEEDED: ['本任务剩余预算不足', '检查固定预算与预留，不自动追加额度或换模型。'],
  MODEL_CAPACITY: ['模型出站资源暂满', '等待已有任务结束，再恢复查询或安全重试。'],
  MODEL_EMPTY_INPUT: [
    '没有符合大小及父上下文边界的可用评论',
    '补充父评论或缩小超长正文后创建新资料版本。',
  ],
  MODEL_INVALID_INPUT_TREE: [
    '评论上下文关系不满足分析要求',
    '检查缺失父评论、循环或重复身份；不要把缺失父评论伪造成根。',
  ],
  INVALID_COMMENT_TREE: [
    '采集回复树结构不符合契约',
    '保留本次存档，使用适配回放定位；此错误不等于评论数量超限。',
  ],
  COLLECTION_STORAGE_LIMIT: [
    '成功页存储已达到单批16MiB边界',
    '此前成功页保留；本次尚未发布完整资料，请保留批次供排查。',
  ],
  COLLECTION_PAGE_LIMIT: [
    '已达到本次512页容量边界',
    '成功页已保留，尚未发布完整资料；不要把达到容量当成平台全部。',
  ],
  INVALID_PAGE_CURSOR: [
    '平台分页未正常推进或返回异常空页',
    '已保存成功页；稍后重试从断点继续，不循环请求同一页。',
  ],
  PAGE_CHECKPOINT_INVALID: [
    '采集检查点未通过一致性校验',
    '保留批次供排查，不删除成功页或从头覆盖。',
  ],
  COLLECTION_SOURCE_CHANGED: [
    '分页期间同一评论的正文或关系发生变化',
    '成功页保留；可新建采集批次形成新版本，旧批次不混入冲突内容。',
  ],
  COMMENT_COUNT_LIMIT: [
    '采集总评论超过单批5000条限制',
    '根评论与回复数量分开计算；缩小本次采集范围或使用分批导入。',
  ],
  COMMENT_DEPTH_LIMIT: ['评论树超过16层安全深度', '保留存档并检查结构，不强行裁掉父上下文。'],
  TOOL_SCOPE_EXCEEDED: [
    '工具返回根评论超过本次约定数量',
    '检查采集器范围契约，不能把额外内容悄悄纳入。',
  ],
  TOOL_OUTPUT_LIMIT: [
    '采集工具输出超过5MiB限制',
    '缩小采集范围；输出大小与根评论数量不是同一个限制。',
  ],
  DUPLICATE_COMMENT_ID: ['工具返回重复评论身份', '检查存档中重复记录，禁止静默合并冲突正文。'],
  SOURCE_ID_MISMATCH: ['采集内容的作品身份不一致', '核对来源链接和适配器，不能将其他作品混入。'],
  INVALID_IMPORTED_DATA: ['采集结果没有通过统一导入校验', '查看存档与字段边界，旧资料保持不变。'],
  TOOL_TIMEOUT: ['采集命令超过服务器等待上限', '已安全结束本次执行，可稍后重试或改用导出文件。'],
  PLATFORM_RISK_CONTROL: ['平台限制了本次访问', '稍后重试或改用已有导出文件。'],
  PLATFORM_SILENT_REFUSAL: ['平台未返回可确认的数据', '不能据此断言没有内容，可稍后再试。'],
  RATE_LIMITED: ['平台请求频率受限', '等待后重试，不提高平台并发。'],
  NETWORK_FAILURE: ['本次网络访问失败', '检查网络后重试，已保存资料不会丢失。'],
  SOURCE_EMPTY: ['平台本次没有返回可导入数据', '核对作品公开状态或提供导出文件。'],
  NO_COMMENTS: ['该作品没有可导入的公开评论', '当前讨论分析需要评论；独立视频内容分析尚未实现。'],
  SOURCE_NOT_FOUND: ['未找到可访问的作品', '核对官方链接和作品公开状态。'],
  COLLECTOR_UNAVAILABLE: ['本机采集工具不可用', '检查服务器固定工具配置，或使用导出文件。'],
  PIPELINE_ERROR: ['后台处理发生未分类错误', '保留任务ID供排查，不把它推断为评论超限。'],
  RECOVERY_LIMIT: ['后台恢复次数已达上限', '检查失败记录后人工处理，不无限重试。'],
  LEASE_LOST: ['本次执行资格已失效', '刷新任务状态，由持久任务恢复流程处理。'],
  WORKER_INTERRUPTED: ['执行过程被中断', '刷新任务状态，费用以调用账本状态为准。'],
}
/** 返回有界业务解释；调用者仍需单独呈现费用未知，不能据原因推断零收费。 */
export function describeFailure(value: string | null | undefined): Failure {
  const selected = messages[value || '']
  return {
    title: '本次未能完成',
    message: selected?.[0] || '出现尚未分类的执行错误。',
    action: selected?.[1] || '保留任务记录并查看诊断信息，不要反复提交相同任务。',
  }
}
/** 排障只显示安全错误码，不回显任意数据库或响应文本。 */
export function safeFailureCode(value: string | null | undefined) {
  return value && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : 'UNRECOGNIZED_FAILURE'
}
