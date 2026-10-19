// 文件职责：核对内容范围和原文定位，避免空值、非法秒数或字符索引冒充媒体时间。
import test from 'node:test'
import assert from 'node:assert/strict'
import { contentRange, contentLocation, contentProgressText } from '../src/contentTypes.ts'
test('同时留空表示材料范围，不造默认时间', () => assert.deepEqual(contentRange('', ''), {}))
test('秒转毫秒保留整数并拒绝半个范围', () => {
  assert.deepEqual(contentRange('1.25', '3.501'), { fromMs: 1250, toMs: 3501 })
  for (const [a, b] of [
    ['1', ''],
    ['', '2'],
    ['NaN', '3'],
    ['0', 'Infinity'],
    ['-1', '2'],
    ['2', '1'],
    ['0', '86401'],
    ['1', '1.0001'],
  ])
    assert.throws(() => contentRange(a, b))
})
test('文本定位不冒充字幕时间', () =>
  assert.equal(
    contentLocation({
      id: 'x',
      text: '原文',
      startChar: 2,
      endChar: 4,
      startMs: null,
      endMs: null,
    }),
    '原文字符[2, 4)',
  ))
test('字幕保留毫秒精度', () =>
  assert.equal(
    contentLocation({
      id: 'x',
      text: '字幕',
      startChar: null,
      endChar: null,
      startMs: 1250,
      endMs: 3501,
    }),
    '1.250～3.501秒',
  ))

/** 浏览器数字框会隐式转换v-model，覆盖实际页面出现的trim类型错误。 */
test('数字框支持数字、0起点与清空后的混合值', () => {
  assert.deepEqual(contentRange(2, 4), { fromMs: 2000, toMs: 4000 })
  assert.deepEqual(contentRange(0, 1.25), { fromMs: 0, toMs: 1250 })
  assert.throws(() => contentRange(2, ''))
  assert.throws(() => contentRange(NaN, 4))
})

/** 免费任务即便状态成功也不能显示语义处理完成。 */
test('完整结构演示区分规划与语义执行', () => {
  assert.match(
    contentProgressText({
      plannedChunks: 17,
      completedChunks: 0,
      synthesisStatus: 'NOT_EXECUTED',
      plannedCalls: 18,
      successfulCalls: 0,
      phase: 'STRUCTURAL_DEMO',
    }),
    /已规划17片.*尚未执行/,
  )
})
/** 批次完成不代表质量通过，汇总未决不显示成功。 */
test('完整内容进度保留汇总未决和质量边界', () => {
  const progress = {
    plannedChunks: 2,
    completedChunks: 2,
    synthesisStatus: 'OUTCOME_UNKNOWN',
    plannedCalls: 3,
    successfulCalls: 2,
    phase: 'REDUCE',
  }
  assert.match(contentProgressText(progress), /2\/2片.*费用未决/)
  assert.match(
    contentProgressText({ ...progress, phase: 'COMPLETE', synthesisStatus: 'SUCCEEDED' }),
    /汇总已完成.*不等于语义已验证/,
  )
  assert.equal(contentProgressText(null), '')
})
