// 文件职责：验证命题状态不会降级成中立，以及历史父语境的缺口与循环保护。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { stanceName, parentContext } from '../src/stanceView.ts'
test('命题状态独立且未知值不当作中立', () => {
  assert.equal(stanceName('QUALIFIED'), '有限定条件')
  assert.equal(stanceName('QUESTION'), '仅提问')
  assert.equal(stanceName('UNKNOWN'), '无法判断')
  assert.equal(stanceName('NEW_STATE'), '未识别')
})
test('父语境按祖先顺序恢复且不编造缺父', () => {
  const rows = [
    { id: 'a', parentCommentId: null },
    { id: 'b', parentCommentId: 'a' },
    { id: 'c', parentCommentId: 'b' },
    { id: 'gap', parentCommentId: 'absent' },
  ]
  assert.deepEqual(
    parentContext('c', rows).map((x) => x.id),
    ['a', 'b'],
  )
  assert.deepEqual(parentContext('gap', rows), [])
  assert.deepEqual(parentContext('none', rows), [])
})
test('异常历史循环不会卡住页面', () => {
  assert.equal(
    parentContext('a', [
      { id: 'a', parentCommentId: 'b' },
      { id: 'b', parentCommentId: 'a' },
    ]).length,
    1,
  )
})
