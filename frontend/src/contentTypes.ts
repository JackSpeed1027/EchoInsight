// 文件职责：独立内容协议和范围转换，原文定位不是评论ID，毫秒时间不是评论发布时间。
export type ContentSegment = {
  id: string
  text: string
  startChar: number | null
  endChar: number | null
  startMs: number | null
  endMs: number | null
}
export type ContentMaterial = {
  id: string
  projectId: string
  previousId: string | null
  versionDepth: number
  title: string
  sourceDescription: string
  format: string
  segmentCount: number
  textChars: number
  rawSha256: string
  segmentsSha256: string
  createdAt: string
}
export type ContentSelection = {
  version: string
  materialId: string
  materialSha256: string
  segmentsSha256: string
  fromMs: number | null
  toMs: number | null
  totalSegments: number
  eligibleSegments: number
  outsideRange: number
  omittedSegments: number
  selectedChars: number
  segments: ContentSegment[]
}
export type ContentPreview = {
  input: { material: ContentMaterial; selection: ContentSelection }
  sourceMode: string
  reservationUnits: number
  budgetUnits: number
  budgetUnit: string
  budgetSufficient: boolean
  requiresPartialConsent: boolean
  notes: string[]
}
export type ContentArtifact = {
  schemaVersion: string
  sourceMode: string
  material: ContentMaterial
  scope: {
    totalSegments: number
    eligibleSegments: number
    selectedSegments: number
    omittedSegments: number
    outsideRange: number
    selectedChars: number
    fromMs: number | null
    toMs: number | null
  }
  findings: { title: string; kind: string; explanation: string; evidenceIds: string[] }[]
  uncertainties: string[]
  evidence: ContentSegment[]
  limitations: string[]
}
export type ContentAnalysis = {
  jobId: string
  projectId: string
  instruction: string
  input: { material: ContentMaterial; selection: ContentSelection }
  status: string
  errorCode: string | null
  createdAt: string
  artifact: ContentArtifact | null
}
/** 两端同时提供，严格拒绝NaN/无穷/负值/半个范围；服务端仍再次校验。 */
export function contentRange(
  from: string | number,
  to: string | number,
): { fromMs?: number; toMs?: number } {
  // type=number的v-model会自动产生number；先统一空值判断，保留合法的0秒。
  const fromText = String(from).trim(),
    toText = String(to).trim()
  if (!fromText && !toText) return {}
  if (!fromText || !toText) throw new Error('请同时填写起止秒数，或同时留空。')
  const start = Number(from),
    end = Number(to)
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || start >= end || end > 86400)
    throw new Error('时间范围需满足0≤起点<终点≤86400秒。')
  const fromMs = Math.round(start * 1000),
    toMs = Math.round(end * 1000)
  if (fromMs >= toMs) throw new Error('时间范围至少相差1毫秒。')
  return { fromMs, toMs }
}
/** 原字符偏移为UTF16半开区间；字幕保留完整相交段，不伪装逐字音视频对齐。 */
export function contentLocation(segment: ContentSegment): string {
  return segment.startMs != null && segment.endMs != null
    ? `${(segment.startMs / 1000).toFixed(3)}～${(segment.endMs / 1000).toFixed(3)}秒`
    : `原文字符[${segment.startChar}, ${segment.endChar})`
}
