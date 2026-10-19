/**
 * 文件职责：保存 EchoInsight 快照的任务入口，兼容两次改名以前的浏览器记录。
 * 只存任务 UUID，不存评论、密钥或报告。读取及写入都不删除旧键。
 */
const CURRENT_PREFIX = 'echoinsight.job:'
const LEGACY_PREFIXES = ['echoagent.job:', 'insightflow.job:']

/** 优先读取当前名称；没有映射时依次回看历史键，不创建新任务。 */
export function loadTask(snapshotId: string): string | null {
  const current = localStorage.getItem(CURRENT_PREFIX + snapshotId)
  if (current !== null) return current
  for (const prefix of LEGACY_PREFIXES) {
    const previous = localStorage.getItem(prefix + snapshotId)
    if (previous !== null) return previous
  }
  return null
}

/** 只保存当前名称下的入口，保留全部历史值，供刷新恢复与排查。 */
export function saveTask(snapshotId: string, jobId: string): void {
  localStorage.setItem(CURRENT_PREFIX + snapshotId, jobId)
}
