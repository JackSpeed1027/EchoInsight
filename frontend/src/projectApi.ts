// 文件职责：统一项目页面的接口类型与安全错误提示，不把数据库身份当主要交互内容。
export type Project = {
  id: string
  name: string
  analysisGoal: string
  createdAt: string
  workCount: number
  snapshotCount: number
  jobCount: number
}
export type ProjectSnapshot = {
  snapshotId: string
  contentId: string
  sourceType: string
  sourceContentId: string
  title: string
  commentCount: number
  snapshotCreatedAt: string
  addedAt: string
  latestJobId: string | null
  latestJobStatus: string | null
  reportId: string | null
  collectionId: string | null
  sourceCompleteness: string | null
  sourceReportedComments: number | null
}

/** 平台采集完整性与模型输入裁剪分开展示，工具成功不能代表平台全部评论。 */
export function coverageName(status: string | null) {
  return (
    (
      {
        VISIBLE_EXHAUSTED: '当前可见遍历及数量核对完成',
        NOT_PROVEN: '未证明平台全量',
        TRUNCATED: '达到采集上限',
        PARTIAL_FAILED: '部分回复未取全',
      } as Record<string, string>
    )[status || ''] || '来源完整性未知'
  )
}
export type Page<T> = {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

/** 项目及历史页面共用同一安全HTTP契约。 */
export { readApi } from './apiClient'
/** 展示平台名称，具体来源身份按需在资料信息中查看。 */
export function sourceName(source: string) {
  return (
    (
      {
        BILIBILI: 'B站',
        XHS: '小红书',
        SYNTHETIC: '合成演示',
        LOCAL: '本地上传',
      } as Record<string, string>
    )[source] || source
  )
}
/** 状态来自服务器，不在浏览器估算完成时间或掩盖失败。 */
export function jobStatus(status: string | null) {
  return (
    (
      {
        PENDING: '等待分析',
        RUNNING: '正在分析',
        SUCCESS: '分析完成',
        FAILED: '分析失败',
        CANCELLED: '已取消',
      } as Record<string, string>
    )[status || ''] || '尚未分析'
  )
}

/** 背景引用必须显示角色；历史无sourceRole的证据继续按评论处理。 */
export function evidenceSource(item: {
  sourceRole?: string | null
  title?: string | null
  startMs?: number | null
  endMs?: number | null
}) {
  if (item.sourceRole === 'REFERENCE_TEXT')
    return '参考资料 · ' + (item.title || '用户提供文本') + '（来源未认证）'
  if (item.sourceRole === 'TRANSCRIPT') {
    const time = (value: number | null | undefined) =>
      value == null ? '未知' : (value / 1000).toFixed(1) + '秒'
    return (
      '字幕 · ' + (item.title || '当前作品') + ' · ' + time(item.startMs) + '—' + time(item.endMs)
    )
  }
  return '观众评论'
}
