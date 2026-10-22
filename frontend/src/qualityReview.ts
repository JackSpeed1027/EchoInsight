// 文件职责：定义人工核对类型及完成条件，区分原文输入、人工初审和提交后的模型对照。
export type ReviewLabel = {
  commentId: string
  coverage: string
  positions: string[]
  statement: string
  condition: string
  rationale: string
  evidenceIds: string[]
}
export type ReviewInput = {
  id: string
  sha256: string
  createdAt: string
  input: {
    requestId: string
    instruction: string
    title: string
    sourceType: string
    sourceContentId: string
    mode: string
    proposition: string | null
    requestedTargets: number
    eligibleTargets: number
    targetIds: string[]
    comments: {
      id: string
      sourceCommentId: string
      parentId: string | null
      text: string
      target: boolean
    }[]
    background: {
      id: string
      role: string
      title: string
      text: string
      startMs?: number | null
      endMs?: number | null
      startChar?: number | null
      endChar?: number | null
    }[]
    notes: string[]
  }
}
export type ReviewSummary = {
  id: string
  requestId: string
  instruction: string
  title: string
  targetCount: number
  createdAt: string
}
export type ReviewRecord = {
  id: string
  inputId: string
  inputSha256: string
  sha256: string
  createdAt: string
  body: {
    reviewer: string
    exposure: string
    kind: string
    previousId: string | null
    labels: ReviewLabel[]
  }
}
export type ReviewRecordSummary = {
  id: string
  reviewer: string
  kind: string
  labeledTargets: number
  createdAt: string
}
export type ReviewComparison = {
  state: string
  sourceMode: string
  sampledTargets: number
  coverageDisagreements: number
  positionDisagreements: number
  items: {
    commentId: string
    human: ReviewLabel
    modelDecision: {
      coverage: string
      claims: { position: string; statement: string; reason: string; condition?: string }[]
    } | null
    citedClaims: { statement: string }[]
    coverageComparison: string
    positionComparison: string
    note: string
  }[]
  notes: string[]
  independentQualityProven: false
}

/** 空判断保持空，不将未填写自动归入未知；本条原文作为固定可回看的依据，不是预填观点。 */
export function blankReviewLabels(input: ReviewInput): ReviewLabel[] {
  return input.input.targetIds.map((id) => ({
    commentId: id,
    coverage: '',
    positions: [],
    statement: '',
    condition: '',
    rationale: '',
    evidenceIds: [id],
  }))
}
/** 前端只帮助提示完成度；服务器仍重新校验所有目标、原文身份、字段与版本。 */
export function reviewLabelComplete(label: ReviewLabel, mode: string): boolean {
  if (
    !['CLAIMS', 'QUESTION', 'IRRELEVANT', 'UNKNOWN'].includes(label.coverage) ||
    !label.rationale.trim() ||
    !label.evidenceIds.includes(label.commentId)
  )
    return false
  if (
    label.coverage === 'CLAIMS' &&
    (!label.statement.trim() || (mode === 'STANCE' && !label.positions.length))
  )
    return false
  if (label.coverage !== 'CLAIMS' && label.positions.length) return false
  if (mode !== 'STANCE' && label.positions.length) return false
  return !label.positions.includes('QUALIFIED') || !!label.condition.trim()
}
/** 只翻译受控判断，保留未知边界，未填写与UNKNOWN不能合并。 */
export function reviewCoverageName(value: string) {
  return (
    (
      {
        CLAIMS: '包含主张',
        QUESTION: '仅提问',
        IRRELEVANT: '与本问题无关',
        UNKNOWN: '语境不足，无法判断',
      } as Record<string, string>
    )[value] || '尚未判断'
  )
}
/** 所有立场仅相对于输入中明确冻结的命题。 */
export function reviewPositionName(value: string) {
  return (
    (
      {
        SUPPORT: '支持',
        OPPOSE: '反对',
        QUALIFIED: '有条件成立',
        UNKNOWN: '立场无法判断',
      } as Record<string, string>
    )[value] || value
  )
}
/** 不把工程替身或原报告未完成写成真实质量对照。 */
export function reviewComparisonName(value: string) {
  return (
    (
      {
        REPORT_NOT_READY: '原报告尚未完成，已保留人工提交',
        NO_SEMANTIC_PREDICTION: '当前只有结构演示，没有语义预测可对照',
        ENGINEERING_ONLY: '工程替身对照，不是真实质量成绩',
        HUMAN_FIRST_REVIEW: '人工初审对照，分歧仍需复查',
      } as Record<string, string>
    )[value] || '对照状态待确认'
  )
}
