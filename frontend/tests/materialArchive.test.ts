/* 文件职责：检验归档文件最小识别与恢复意图的稳定键，不能代替服务端哈希/内容校验。 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { archiveCandidate, archiveMaxBytes, materialRestoreKey } from '../src/materialArchive.ts'
const archive = {
  sha256: 'a'.repeat(64),
  data: {
    version: 'echoinsight-materials-v1',
    project: { name: '合成', analysisGoal: '验证恢复' },
  },
}
test('只识别定义版本与哈希格式，完整包必须由服务端校验', () => {
  assert.equal(archiveCandidate(archive), archive)
  for (const value of [
    null,
    {},
    [],
    { ...archive, sha256: 'bad' },
    { ...archive, data: { ...archive.data, version: 'source-zip' } },
  ])
    assert.throws(() => archiveCandidate(value))
})
test('目的项目摘要必须是文本，不能把报告或配置当归档', () => {
  assert.throws(() =>
    archiveCandidate({
      ...archive,
      data: { ...archive.data, project: { name: 1, analysisGoal: '目标' } },
    }),
  )
  assert.equal(archiveMaxBytes, 14 * 1024 * 1024)
})
test('刷新与双标签页对同一恢复意图使用稳定键', async () => {
  const a = await materialRestoreKey(archive.sha256, ' 恢复项目 ', ' 目标 ')
  assert.equal(a, await materialRestoreKey(archive.sha256, '恢复项目', '目标'))
  assert.match(a, /^materials:[a-f0-9]{64}$/)
})
test('明确另存名称、目标或文件改变恢复意图', async () => {
  const a = await materialRestoreKey(archive.sha256, '项目', '目标')
  for (const key of [
    await materialRestoreKey(archive.sha256, '另存', '目标'),
    await materialRestoreKey(archive.sha256, '项目', '另一目标'),
    await materialRestoreKey('b'.repeat(64), '项目', '目标'),
  ])
    assert.notEqual(a, key)
})
test('稳定键不含原文或目的名称，Unicode边界保持明确', async () => {
  const a = await materialRestoreKey(archive.sha256, '😀中文项目', '保留\n研究问题')
  assert.ok(!a.includes('中文'))
  assert.notEqual(a, await materialRestoreKey(archive.sha256, '😀中文项目', '保留研究问题'))
})
