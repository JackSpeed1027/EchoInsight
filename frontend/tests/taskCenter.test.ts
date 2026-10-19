// 文件职责：验证任务中心对已取消、未知费用和原预算单位的解释，不把控制状态当退款。
import test from 'node:test'
import assert from 'node:assert/strict'
import { taskState, actionCopy, budgetText } from '../src/taskCenter.ts'
test('取消独立于失败，不显示为仍在运行', () => assert.equal(taskState('CANCELLED'), '已取消'))
test('分析取消说明已发送仍可能计费', () =>
  assert.match(actionCopy('ANALYSIS', 'cancel'), /仍可能计费/))
test('采集继续区分页恢复与旧限量工具', () =>
  assert.match(actionCopy('COLLECTION', 'resume'), /成功页.*旧限量工具/))
test('模型继续使用原预算且拒绝未知费用', () =>
  assert.match(actionCopy('ANALYSIS', 'resume'), /原剩余预算.*费用未决/))
test('模拟单位与纳美元不能混用', () => {
  assert.match(budgetText(100, 'SIMULATED_UNITS'), /模拟单位/)
  assert.equal(budgetText(1000000000, 'USD_NANOS'), '1.000000000 美元（账本）')
})
