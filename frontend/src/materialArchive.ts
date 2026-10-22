/* 文件职责：资料归档页面的最小文件识别和稳定恢复请求键，不在浏览器替代服务端完整校验。 */
export const archiveMaxBytes = 14 * 1024 * 1024
export type MaterialArchive = {
  sha256: string
  data: { version: string; project: { name: string; analysisGoal: string } }
}
export type ArchiveOverview = {
  snapshots: number
  contents: number
  references: number
  subtitles: number
  rawBytes: number
  withinBounds: boolean
  notes: string[]
}
export type ArchivePreview = {
  archiveSha256: string
  sourceName: string
  snapshots: number
  comments: number
  contents: number
  references: number
  subtitles: number
  encodedBytes: number
  notes: string[]
}
export type ArchiveReceipt = {
  id: string
  projectId: string
  archiveSha256: string
  snapshots: Record<string, string>
  contents: Record<string, string>
  references: Record<string, string>
  subtitles: Record<string, string>
  notes: string[]
}
/** 只识别本产品版本与基本摘要；哈希、内容、父链、绑定和全部边界仍交给服务端。 */
export function archiveCandidate(value: unknown): MaterialArchive {
  if (!value || typeof value !== 'object') throw new Error('请选择本系统导出的资料JSON。')
  const v = value as Partial<MaterialArchive>
  if (
    !v.data ||
    v.data.version !== 'echoinsight-materials-v1' ||
    !v.data.project ||
    typeof v.data.project.name !== 'string' ||
    typeof v.data.project.analysisGoal !== 'string' ||
    typeof v.sha256 !== 'string' ||
    !/^[a-f0-9]{64}$/.test(v.sha256)
  )
    throw new Error('归档版本或项目摘要不符合格式，请勿选择报告、源码或配置文件。')
  return value as MaterialArchive
}
/** 同一归档和目的名称/目标始终复用一个恢复意图；刷新、重发或双标签页不重复建项目。 */
export async function materialRestoreKey(
  sha256: string,
  name: string,
  goal: string,
): Promise<string> {
  const bytes = new TextEncoder().encode(JSON.stringify([sha256, name.trim(), goal.trim()]))
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return (
    'materials:' +
    Array.from(new Uint8Array(digest), (v) => v.toString(16).padStart(2, '0')).join('')
  )
}
