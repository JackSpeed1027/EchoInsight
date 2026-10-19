/** 文件职责：解释来源计数与未知项，不把条数对齐转换为平台总体支持率。 */
export type SourceCoverage = {
  savedComments: number
  sourceReportedComments: number | null
  declaredDifference: number | null
  missingParentLinks: number
  visibleTraversalReconciled: boolean
  completeness: string | null
  notes: string[]
}
export type CoverageView = { frozen: boolean; coverage: SourceCoverage }
/** 平台计数未知与明确为零严格分开，超出声明数时不显示负“缺失条数”。 */
export function coverageSummary(value: SourceCoverage): string {
  const declared = value.sourceReportedComments
  if (declared === null) return `已保存 ${value.savedComments} 条，平台总数未知。`
  if (declared !== value.savedComments)
    return `已保存 ${value.savedComments} 条，平台声明 ${declared} 条，数量未对齐，原因未知。`
  return value.visibleTraversalReconciled
    ? `已保存 ${value.savedComments} 条，当前可见分页与数量已核对；隐藏或删除内容仍未知。`
    : `已保存 ${value.savedComments} 条，与平台声明相等；缺少完整分页证据，不能证明全部。`
}
