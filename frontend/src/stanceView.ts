/** 文件职责：定义命题结果只读视图及中文名称；数量只展示后端统计，不在浏览器猜测立场。 */
export type StanceResult = {
  specification: { version: string; proposition: string; origin: string }
  completion?: {
    mode: string
    selectionVersion: string
    targetComments: number
    completedComments: number
    batchCount: number
    contextOccurrences: number
    coverageVerified: boolean
  } | null
  analyzed: boolean
  statistics: {
    selectedComments: number
    coveredComments: number
    claimComments: number
    questionComments: number
    irrelevantComments: number
    unknownComments: number
    totalClaims: number
    supportClaims: number
    opposeClaims: number
    qualifiedClaims: number
    unknownClaims: number
    supportComments: number
    opposeComments: number
    qualifiedComments: number
    mixedComments: number
  } | null
  decisions: Array<{
    commentId: string
    coverage: string
    reason: string
    claims: Array<{
      statement: string
      position: string
      condition: string
      reason: string
      evidenceIds: string[]
    }>
  }>
}

/** 未识别状态只显示“未识别”，不降级成中立或无关，避免旧客户端误解新协议。 */
export function stanceName(value: string): string {
  const labels: Record<string, string> = {
    SUPPORT: '支持命题',
    OPPOSE: '反对命题',
    QUALIFIED: '有限定条件',
    UNKNOWN: '无法判断',
    CLAIMS: '已提取主张',
    QUESTION: '仅提问',
    IRRELEVANT: '与命题无关',
  }
  return labels[value] ?? '未识别'
}

/** 从不可变产物追溯父语境；防止异常历史数据循环，不将未提供的父评论编造出来。 */
export function parentContext<T extends { id: string; parentCommentId: string | null }>(
  commentId: string,
  evidence: T[],
): T[] {
  const byId = new Map(evidence.map((item) => [item.id, item]))
  const result: T[] = []
  const seen = new Set([commentId])
  let parent = byId.get(commentId)?.parentCommentId
  while (parent && !seen.has(parent)) {
    seen.add(parent)
    const item = byId.get(parent)
    if (!item) break
    result.unshift(item)
    parent = item.parentCommentId
  }
  return result
}

/** 只分页展示当前已发布结果；不把未展示的页当作后台未处理，也不改变统计分母。 */
export function decisionPage<T>(
  items: T[],
  page: number,
  size = 20,
): { items: T[]; page: number; pages: number } {
  const limit = Number.isSafeInteger(size) && size > 0 && size <= 50 ? size : 20
  const pages = Math.max(1, Math.ceil(items.length / limit))
  const current = Math.min(pages, Math.max(1, Number.isSafeInteger(page) ? page : 1))
  return { items: items.slice((current - 1) * limit, current * limit), page: current, pages }
}
