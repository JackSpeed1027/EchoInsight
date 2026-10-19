// 文件职责：解析服务器明确的安全错误契约，拒绝把HTML、500异常或任意响应正文展示给用户。
type Issue = { path: string; message: string }
/** ApiError仅带安全字段；原响应正文不保存到异常对象或日志。 */
export class ApiError extends Error {
  status: number
  code: string
  issues: Issue[]
  constructor(status: number, code: string, message: string, issues: Issue[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.issues = issues
  }
}
const SAFE_CODES = new Set([
  'REQUEST_REJECTED',
  'INVALID_FIELDS',
  'INVALID_JSON',
  'INVALID_IMPORT',
  'FILE_TOO_LARGE',
  'INVALID_UPLOAD',
  'MISSING_REQUEST_KEY',
])
/** 约束消息必须是有界纯文本；不读取非JSON错误或返回的HTML页面。 */
function safeText(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    value.length > 0 &&
    value.length <= 300 &&
    !/[<>\u0000-\u001f]/.test(value)
  )
}
/** 统一HTTP请求：成功只取JSON，失败保留状态/安全字段和下一动作，不解释底层私有错误。 */
export async function readApi<T>(url: string, options?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, options)
  } catch {
    throw new ApiError(
      0,
      'CONNECTION_FAILED',
      '无法连接后端，请检查服务是否运行；已提交的任务不一定停止，请先恢复查询。',
    )
  }
  if (response.ok) return response.json()
  const defaults: Record<number, string> = {
    400: '填写内容或查询条件有误，请检查字段和数量边界。',
    404: '项目、资料或任务不存在，或不在当前项目范围。',
    409: '当前状态或请求键与已保存内容冲突，请核对原记录后再提交。',
    413: '文件超过上传限制，请缩小文件或按明确范围导入。',
    422: '当前不能可靠执行这类要求，请明确讨论目标或补充材料。',
    503: '本机尚未启用所需能力，请检查服务端配置。',
  }
  let message = defaults[response.status] || '服务暂时无法完成操作，请保留任务记录并重试查询。'
  let code = 'HTTP_ERROR'
  let issues: Issue[] = []
  // 只有明确的应用契约可显示；5xx始终不回显服务器异常正文。
  if (response.status < 500 && response.headers.get('content-type')?.includes('application/json')) {
    try {
      const body = await response.json()
      if (body && typeof body.code === 'string' && SAFE_CODES.has(body.code)) {
        code = body.code
        if (safeText(body.message)) message = body.message
        if (Array.isArray(body.issues))
          issues = body.issues
            .slice(0, 10)
            .filter((i: Issue) => i && safeText(i.path) && safeText(i.message))
      }
    } catch {
      /* 解析失败使用固定提示，不将正文泄漏到错误对象。 */
    }
  }
  if (issues.length) message += ' ' + issues.map((i) => i.path + '：' + i.message).join('；')
  throw new ApiError(response.status, code, message, issues)
}
