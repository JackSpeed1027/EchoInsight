/** 文件职责：链接与原话入口的响应协议、精确金额和只读轮询规则。 */
import type { Page, ProjectSnapshot } from './projectApi'
import type { SourceCoverage } from './sourceCoverage'
export type IntakeDraft = {
  url: string
  snapshotId: string
  instruction: string
  proposition: string
  analysisScope: 'FULL' | 'PREVIEW'
}
export type IntakeInput = {
  canonicalUrl: string | null
  requestedSnapshotId: string | null
  instruction: string
  proposition: string | null
  analysisScope: 'FULL' | 'PREVIEW'
  goal: { stanceRequested: boolean; needsProposition: boolean; explanation: string }
}
export type IntakePlan = {
  input: IntakeInput
  candidates: Page<ProjectSnapshot>
  collectionEnabled: boolean
  collectionWorkerRunning: boolean
  notes: string[]
}
export type IntakeView = {
  id: string
  input: IntakeInput
  phase: string
  source: ProjectSnapshot | null
  collection: {
    id: string
    status: string
    snapshotId: string | null
    collectedCommentCount: number
    lastErrorCode: string | null
  } | null
  analysis: { id: string; snapshotId: string; status: string } | null
  collectionEnabled: boolean
  collectionWorkerRunning: boolean
  notes: string[]
}
export type IntakeSummary = {
  id: string
  instruction: string
  canonicalUrl: string | null
  createdAt: string
}
export type IntakeQuote = {
  sha256: string
  available: boolean
  cost: {
    mode: string
    unit: string
    maximumUnits: string
    budgetLimitUnits: string
    plannedCalls: number
  }
  preview: {
    mode: string
    sourceCoverage: SourceCoverage
    savedComments: number
    selectedComments: number
    omittedComments: number
    requiresPartialConsent: boolean
    fullAnalysisAvailable: boolean
    notes: string[]
    fullScope: { reasonCode: string | null }
  }
}
/** 有URL时清除旧下拉选择，避免切换输入方式后悄悄绑定之前的资料。 */
export function intakeBody(draft: IntakeDraft) {
  const url = draft.url.trim()
  return {
    url: url || null,
    snapshotId: url ? null : draft.snapshotId || null,
    instruction: draft.instruction.trim(),
    proposition: draft.proposition.trim() || null,
    analysisScope: draft.analysisScope,
  }
}
/** 使用整数纳美元展示金额，超过JS安全整数范围也不丢精度。 */
export function intakeCost(units: string, unit: string): string {
  if (!/^[0-9]+$/.test(units)) return '费用暂不可用'
  const value = BigInt(units)
  if (unit !== 'USD_NANOS') return value === 0n ? '不产生模型费用' : units + ' ' + unit
  return (
    '$' +
    (value / 1000000000n).toString() +
    (value % 1000000000n === 0n
      ? ''
      : '.' + (value % 1000000000n).toString().padStart(9, '0').replace(/0+$/, ''))
  )
}
/** 只轮询采集中阶段，成功后等待用户确认资料，绝不在前端自动分析。 */
export function intakeShouldPoll(view: Pick<IntakeView, 'phase'>): boolean {
  return view.phase === 'COLLECTING'
}
/** 阶段由后端原任务推导，未知值保持可见，不推测为成功。 */
export function intakePhase(value: string): string {
  return (
    (
      {
        NEEDS_SOURCE: '等待选择或采集资料',
        COLLECTING: '采集任务已受理',
        READY_TO_BIND: '采集完成，等待确认资料',
        COLLECTION_FAILED: '采集失败，请查看原因',
        COLLECTION_CANCELLED: '采集已取消',
        READY_FOR_ANALYSIS: '资料已固定，等待分析确认',
        ANALYSIS_ACCEPTED: '已关联分析任务',
      } as Record<string, string>
    )[value] || value
  )
}
