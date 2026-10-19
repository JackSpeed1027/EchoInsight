// 文件职责：项目任务中心的安全响应类型、只读状态文案与控制说明，不能替代后端行锁校验。
export type TaskKind = 'ANALYSIS' | 'COLLECTION'
export type TaskAction = 'cancel' | 'resume' | 'retry'
export type ProjectTask = {
  kind: TaskKind
  id: string
  status: string
  title: string
  instruction: string | null
  snapshotId: string | null
  requestId: string | null
  reportId: string | null
  createdAt: string
  attempts: number
  errorCode: string | null
  feeUncertain: boolean
  canCancel: boolean
  canResume: boolean
  canRetry: boolean
}
export type TaskDetail = {
  task: ProjectTask
  steps: { key: string; status: string }[]
  model: {
    budget: { unit: string; limitUnits: number; reservedUnits: number; settledUnits: number }
    total: number
    items: { index: number; status: string; attempts: number }[]
  } | null
  completedPages: number
  checkpointBytes: number
  recentEvents: { action: string; previousStatus: string; nextStatus: string; createdAt: string }[]
  eventCount: number
  notes: string[]
}
export type TaskCounts = {
  total: number
  pending: number
  running: number
  success: number
  failed: number
  cancelled: number
  feeUncertain: number
}
/** 统一任务状态，不使用分析专属文案描述采集；未知值如实呈现。 */
export function taskState(value: string): string {
  return (
    (
      {
        PENDING: '等待执行',
        RUNNING: '正在执行',
        SUCCESS: '已完成',
        FAILED: '失败',
        CANCELLED: '已取消',
        READY: '待执行',
        SUCCEEDED: '已完成',
        ABANDONED: '租约失效',
        OUTCOME_UNKNOWN: '结果与费用未决',
      } as Record<string, string>
    )[value] || value
  )
}
/** 说明真正的服务器动作及影响，点击确认才发POST；按钮资格完全读自服务器。 */
export function actionCopy(kind: TaskKind, action: TaskAction): string {
  if (action === 'cancel')
    return kind === 'ANALYSIS'
      ? '取消后停止后续分析与报告发布，保留输入和成功检查点。已经发出的模型请求仍可能计费，不能保证立即停止。'
      : '取消后停止后续采集和资料发布，保留成功页与存档。正在进行的网络或工具可能运行到自身超时。'
  return kind === 'ANALYSIS'
    ? '继续执行原任务，复用固定资料、问题、计划和成功检查点，使用原剩余预算；后续模型调用可能计费。费用未决时后台拒绝继续。'
    : '继续原采集批次。公开分页复用成功页；旧限量工具可能重新采集。仍受平台限制，不保证取到全部评论。'
}
/** 费用单位不能混加；整数纳美元仅按原账本换算，不推断供应商实收。 */
export function budgetText(units: number, unit: string): string {
  return unit === 'USD_NANOS'
    ? `${(units / 1_000_000_000).toFixed(9)} 美元（账本）`
    : `${units} ${unit}（模拟单位）`
}
