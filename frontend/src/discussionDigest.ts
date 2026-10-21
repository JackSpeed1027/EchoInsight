// 文件职责：完整讨论的只读页面协议、范围转换和进度用语，复用数不冒充新调用数。
import { contentRange } from './contentTypes.ts'
export type ContentChoice = {
  materialId: string
  from: string | number
  to: string | number
  format?: string
}
export type DigestProgress = {
  phase: string
  plannedMaps: number
  completedMaps: number
  plannedReductions: number
  completedReductions: number
  reusedMaps: number
}
export type DigestReceipt = {
  baselineRequestId: string | null
  reusedChunks: number
  origins: {
    index: number
    sourceRequestId: string
    sourceJobId: string
    sourceBatchIndex: number
    responseHash: string
  }[]
}
export type DigestProcessing = {
  strategy: string
  mapCount: number
  reductionCount: number
  completedMaps: number
  completedReductions: number
  targetComments: number
  contextOccurrences: number
  reuse?: DigestReceipt | null
}
export type ContentBinding = {
  materialId: string
  title: string
  fromMs: number | null
  toMs: number | null
  strategy: string
  totalSegments: number
  eligibleSegments: number
  matchedSegments: number
  selectedSegments: number
  selectedChars: number
  notes: string[]
}
/** 只提交明确材料和业务时间；毫秒/单边窗口校验复用原范围转换，空选择不携带残留参数。 */
export function contentChoiceBody(choice: ContentChoice): {
  contentMaterialId?: string
  contentFromMs?: number
  contentToMs?: number
} {
  if (!choice.materialId) return {}
  const range = contentRange(choice.from, choice.to)
  return {
    contentMaterialId: choice.materialId,
    ...(range.fromMs === undefined ? {} : { contentFromMs: range.fromMs, contentToMs: range.toMs }),
  }
}
/** 免费语义完成数保持0；分片输入、复用和新归并分别说明。 */
export function digestProgressText(progress: DigestProgress): string {
  if (progress.phase === 'STRUCTURAL_DEMO')
    return `已规划${progress.plannedMaps}片和${progress.plannedReductions}次汇总，免费演示未执行语义分析。`
  return `分片${progress.completedMaps}/${progress.plannedMaps}（复用${progress.reusedMaps}片） · 汇总${progress.completedReductions}/${progress.plannedReductions}。${progress.phase === 'COMPLETE' ? '输入处理已完成，语义仍需核对。' : '全部节点成功后发布最终报告。'}`
}
/** 技术阶段在页面表达为业务步骤。 */
export function digestNodeName(phase: string): string {
  return (
    ({ MAP: '分片归纳', REDUCE: '分组汇总', FINAL: '最终汇总' } as Record<string, string>)[phase] ||
    '分析步骤'
  )
}
